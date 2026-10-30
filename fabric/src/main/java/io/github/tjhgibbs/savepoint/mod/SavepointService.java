package io.github.tjhgibbs.savepoint.mod;

import io.github.tjhgibbs.savepoint.core.Cooldown;
import io.github.tjhgibbs.savepoint.core.IdResolver;
import io.github.tjhgibbs.savepoint.core.Manifest;
import io.github.tjhgibbs.savepoint.core.Manifest.Kind;
import io.github.tjhgibbs.savepoint.core.OperationControl;
import io.github.tjhgibbs.savepoint.core.SavepointMeta;
import io.github.tjhgibbs.savepoint.core.SavepointStore;
import io.github.tjhgibbs.savepoint.core.Settings;
import io.github.tjhgibbs.savepoint.core.ZipBackup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Per integrated-server savepoint service. One instance lives from server start to stop (see {@link #current()}).
 *
 * <p>Threading: every public method that takes a {@link ServerPlayer} must be called on the server thread (commands
 * and payload receivers already are). Creating a savepoint runs ON the server thread, deliberately: after
 * {@code saveEverything(flush)} nothing else writes the world, and keeping the server thread inside
 * {@code store.create} guarantees no chunk unload or autosave can tear a region file while it is read. For an
 * unchanged-ish world this is ~0.1-0.5 s; the first create of a huge world can take seconds (progress goes to the
 * action bar). The full-backup zip, which can be long, runs on a background thread from stored blobs only.
 */
public final class SavepointService {
    private static volatile SavepointService current;

    /** Chunk 3 plugs the revert flow in here. */
    public interface RevertHandler {
        void revert(SavepointService service, ServerPlayer player, Manifest target);
    }

    private static volatile RevertHandler revertHandler = (svc, p, m) -> p.sendSystemMessage(Component.translatable("savepoint.msg.revert_unavailable"));

    private final MinecraftServer server;
    private final SavepointStore store;
    private final Path worldDir;
    private final Path backupDir;
    private final Path storeRoot;
    /** Static so the revert cooldown survives the server restart a revert causes. */
    private static final Cooldown cooldown = new Cooldown();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Savepoint-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean backupRunning = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile boolean backupDoneThisSession;
    private long lastProgressNanos;

    private SavepointService(MinecraftServer server, Path worldDir, Path storeRoot) throws IOException {
        this.server = server;
        this.worldDir = worldDir;
        this.storeRoot = storeRoot;
        this.store = new SavepointStore(storeRoot);
        this.backupDir = storeRoot.resolve("backups");
        Files.createDirectories(backupDir);
    }

    // ------------------------------------------------------------------ lifecycle

    /** The service for the running integrated server, or null (not started yet, or dedicated server). */
    public static SavepointService current() {
        return current;
    }

    static void start(MinecraftServer server) {
        if (!server.isSingleplayer() || server.isDedicatedServer()) {
            SavepointMod.LOG.info("Dedicated server: Savepoint is singleplayer-only and stays inactive");
            return;
        }
        try {
            Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path root = FabricLoader.getInstance().getGameDir().resolve("savepoints").resolve(world.getFileName().toString());
            current = new SavepointService(server, world, root);
            SavepointMod.LOG.info("Savepoint store: {}", root);
        } catch (IOException | RuntimeException e) {
            SavepointMod.LOG.error("Savepoint could not open its store", e);
        }
    }

    /** Test hook (GameTests run on a dedicated server): installs a service with an explicit store root. */
    public static SavepointService startForTesting(MinecraftServer server, Path storeRoot) throws IOException {
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        current = new SavepointService(server, world, storeRoot);
        return current;
    }

    static void stop() {
        SavepointService s = current;
        current = null;
        if (s == null) return;
        s.stopping.set(true);
        s.worker.shutdown();
        try {
            if (!s.worker.awaitTermination(10, TimeUnit.SECONDS)) s.worker.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Service for this player's server if the player may use Savepoint, else tells them why and returns null. */
    static SavepointService require(ServerPlayer player) {
        SavepointService s = current;
        if (s == null) {
            player.sendSystemMessage(Component.translatable("savepoint.msg.only_singleplayer"));
            return null;
        }
        if (!s.mayUse(player)) {
            player.sendSystemMessage(Component.translatable("savepoint.msg.no_permission"));
            return null;
        }
        return s;
    }

    /** Singleplayer owner, or anyone with game-master permission. */
    public boolean mayUse(ServerPlayer p) {
        return server.isSingleplayerOwner(p.nameAndId()) || p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public static void setRevertHandler(RevertHandler h) {
        revertHandler = h;
    }

    // ------------------------------------------------------------------ accessors for later chunks

    public SavepointStore store() {
        return store;
    }

    public Path storeRoot() {
        return storeRoot;
    }

    public Path worldDir() {
        return worldDir;
    }

    public Path backupDir() {
        return backupDir;
    }

    public MinecraftServer server() {
        return server;
    }

    public Settings settings() {
        return SavepointConfig.get();
    }

    /** Seconds the player must still wait before create/revert; 0 = go. Does not start the timer. */
    public int cooldownRemaining() {
        return cooldown.remainingSeconds(settings().cooldownSeconds());
    }

    /** Call when a create/revert actually happened. */
    public void markCooldown() {
        cooldown.mark();
    }

    /** If on cooldown, tells the player ("Wait 7s") and returns false. For chunk 3's revert too. */
    public boolean checkCooldown(ServerPlayer p) {
        int left = cooldownRemaining();
        if (left > 0) {
            p.sendSystemMessage(Component.translatable("savepoint.msg.cooldown", left));
            return false;
        }
        return true;
    }

    public boolean isBackupRunning() {
        return backupRunning.get();
    }

    public boolean backupDoneThisSession() {
        return backupDoneThisSession;
    }

    /** Chunk 3 entry point: validates id, then hands over to the revert handler. */
    public void revert(ServerPlayer p, String token) {
        Manifest m = resolve(p, token);
        if (m != null) revertHandler.revert(this, p, m);
    }

    // ------------------------------------------------------------------ create

    /** Command/GUI "create": a named savepoint (blank name = "Savepoint N"). */
    public Manifest createManual(ServerPlayer p, String name) {
        String n = name == null || name.isBlank() ? nextDefaultName() : name.strip();
        return createAnnounced(p, SavepointMeta.of(Kind.MANUAL, n));
    }

    /** Single replaceable quick slot. */
    public Manifest createQuick(ServerPlayer p) {
        return createAnnounced(p, SavepointMeta.of(Kind.QUICK, "Quick savepoint"));
    }

    /**
     * Automatic "before revert" point for chunk 3: no cooldown, no chat, flagged auto. Server thread.
     * @throws IOException if saving or capturing failed (caller must then abort the revert)
     */
    public Manifest createUndoPoint(ServerPlayer p) throws IOException {
        Manifest m = capture(p, SavepointMeta.of(Kind.UNDO, "Before revert"), false);
        pruneQuietly();
        return m;
    }

    /** Runs the save + store.create; no cooldown, no chat. */
    private Manifest capture(ServerPlayer p, SavepointMeta meta, boolean showProgress) throws IOException {
        if (backupRunning.get()) throw new IOException("A full backup is running; try again when it finishes");
        // Players, level data and all dirty chunks to disk, flushed to the region files.
        if (!server.saveEverything(true, true, true)) throw new IOException("The game could not save the world");
        if (p != null) {
            meta = meta.withPlayer(new Manifest.PlayerPos(p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ()));
        }
        OperationControl ctl = !showProgress || p == null ? OperationControl.NONE : new OperationControl() {
            @Override
            public void progress(String phase, long done, long total) {
                long now = System.nanoTime();
                if (now - lastProgressNanos < 250_000_000L) return;
                lastProgressNanos = now;
                p.sendSystemMessage(Component.translatable("savepoint.msg.progress", Fmt.percent(done, total)), true);
            }
        };
        return store.create(worldDir, meta, ctl);
    }

    private Manifest createAnnounced(ServerPlayer p, SavepointMeta meta) {
        if (!checkCooldown(p)) return null;
        if (backupRunning.get()) {
            p.sendSystemMessage(Component.translatable("savepoint.msg.busy_backup"));
            return null;
        }
        p.sendSystemMessage(Component.translatable("savepoint.msg.saving"), true);
        Manifest m;
        long t0 = System.nanoTime();
        try {
            m = capture(p, meta, true);
        } catch (IOException | RuntimeException e) {
            SavepointMod.LOG.error("Savepoint create failed", e);
            p.sendSystemMessage(Component.translatable("savepoint.msg.create_failed", String.valueOf(e.getMessage())).withStyle(ChatFormatting.RED));
            return null;
        }
        markCooldown();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        SavepointMod.LOG.info("Savepoint {} created in {} ms", m.id(), ms);
        p.sendSystemMessage(createdLine(m));
        int pruned = pruneQuietly();
        if (pruned > 0) p.sendSystemMessage(Component.translatable("savepoint.msg.pruned", pruned));
        afterCreate(p);
        sendList(p);
        return m;
    }

    private int pruneQuietly() {
        Settings s = settings();
        try {
            return store.prune(new SavepointStore.PrunePolicy(s.storeBudgetBytes(), s.keepNewest())).size();
        } catch (IOException e) {
            SavepointMod.LOG.warn("Prune failed", e);
            return 0;
        }
    }

    private void afterCreate(ServerPlayer p) {
        switch (settings().fullBackupMode()) {
            case EVERY -> startBackup(p, true);
            case SESSION -> {
                if (!backupDoneThisSession && !backupRunning.get()) {
                    p.sendSystemMessage(Component.translatable("savepoint.msg.backup_offer",
                            action(Component.translatable("savepoint.msg.backup_button"), "/savepoint backup",
                                    Component.translatable("savepoint.msg.backup_hover"), ChatFormatting.AQUA)));
                }
            }
            case OFF -> {}
        }
    }

    private String nextDefaultName() {
        int max = 0;
        for (Manifest m : store.list()) {
            if (m.kind() != Kind.MANUAL || !m.name().startsWith("Savepoint ")) continue;
            try {
                max = Math.max(max, Integer.parseInt(m.name().substring("Savepoint ".length()).strip()));
            } catch (NumberFormatException ignored) {
                // custom name
            }
        }
        return "Savepoint " + (max + 1);
    }

    // ------------------------------------------------------------------ list / info / edit

    /** Resolves what the user typed, telling them if it fails. */
    public Manifest resolve(ServerPlayer p, String token) {
        IdResolver.Result r = IdResolver.resolve(store.list(), token);
        if (r instanceof IdResolver.Found f) return f.manifest();
        if (r instanceof IdResolver.Ambiguous a) {
            p.sendSystemMessage(Component.translatable("savepoint.msg.ambiguous", a.token()));
            for (Manifest m : a.candidates()) p.sendSystemMessage(Component.literal("  [" + IdResolver.shortId(m) + "] " + m.name()));
        } else {
            p.sendSystemMessage(Component.translatable("savepoint.msg.not_found", token));
        }
        return null;
    }

    /** Names and short ids for tab completion. */
    public List<String> suggestions() {
        List<String> out = new ArrayList<>();
        for (Manifest m : store.list()) {
            out.add(IdResolver.shortId(m));
            if (!m.name().isBlank()) out.add(m.name().indexOf(' ') >= 0 ? "\"" + m.name().replace("\"", "") + "\"" : m.name());
        }
        return out;
    }

    public void list(ServerPlayer p) {
        List<Manifest> all = store.list();
        if (all.isEmpty()) {
            p.sendSystemMessage(Component.translatable("savepoint.msg.list_empty"));
            return;
        }
        p.sendSystemMessage(Component.translatable("savepoint.msg.list_header", all.size()));
        long now = System.currentTimeMillis();
        for (Manifest m : all) {
            MutableComponent id = action(Component.literal(IdResolver.shortId(m)), "/savepoint info " + m.id(),
                    Component.translatable("savepoint.msg.info_hover"), ChatFormatting.YELLOW);
            p.sendSystemMessage(Component.translatable("savepoint.msg.list_line", id, m.name(), Fmt.age(now - m.createdMillis()),
                    Fmt.bytes(m.logicalBytes()), flags(m)));
        }
    }

    public void info(ServerPlayer p, String token) {
        Manifest m = resolve(p, token);
        if (m == null) return;
        long unique = 0;
        try {
            unique = store.uniqueBytes(m.id());
        } catch (IOException ignored) {
            // shown as 0
        }
        p.sendSystemMessage(Component.translatable("savepoint.msg.info_title", m.name(), IdResolver.shortId(m)));
        p.sendSystemMessage(Component.translatable("savepoint.msg.info_when", Fmt.age(System.currentTimeMillis() - m.createdMillis()), m.kind().json()));
        p.sendSystemMessage(Component.translatable("savepoint.msg.info_size", Fmt.bytes(m.logicalBytes()), Fmt.bytes(unique)));
        if (m.player() != null) {
            p.sendSystemMessage(Component.translatable("savepoint.msg.info_where", m.player().dimension(),
                    Math.round(m.player().x()), Math.round(m.player().y()), Math.round(m.player().z())));
        }
        p.sendSystemMessage(Component.translatable("savepoint.msg.info_flags", flags(m)));
        p.sendSystemMessage(Component.empty()
                .append(action(Component.translatable("savepoint.msg.revert_button"), "/savepoint revert " + m.id(), Component.translatable("savepoint.msg.revert_hover"), ChatFormatting.GREEN))
                .append(" ")
                .append(action(Component.translatable(m.pinned() ? "savepoint.msg.unpin_button" : "savepoint.msg.pin_button"),
                        "/savepoint " + (m.pinned() ? "unpin " : "pin ") + m.id(), Component.empty(), ChatFormatting.GOLD)));
    }

    public void rename(ServerPlayer p, String token, String name) {
        if (!editable(p)) return;
        Manifest m = resolve(p, token);
        if (m == null) return;
        if (name == null || name.isBlank()) {
            p.sendSystemMessage(Component.translatable("savepoint.msg.name_blank"));
            return;
        }
        try {
            Manifest r = store.rename(m.id(), name.strip());
            p.sendSystemMessage(Component.translatable("savepoint.msg.renamed", IdResolver.shortId(r), r.name()));
            sendList(p);
        } catch (IOException e) {
            fail(p, e);
        }
    }

    public void pin(ServerPlayer p, String token, boolean pinned) {
        if (!editable(p)) return;
        Manifest m = resolve(p, token);
        if (m == null) return;
        try {
            Manifest r = store.pin(m.id(), pinned);
            p.sendSystemMessage(Component.translatable(pinned ? "savepoint.msg.pinned" : "savepoint.msg.unpinned", r.name()));
            sendList(p);
        } catch (IOException e) {
            fail(p, e);
        }
    }

    public void delete(ServerPlayer p, String token) {
        if (!editable(p)) return;
        Manifest m = resolve(p, token);
        if (m == null) return;
        try {
            long freed = store.delete(m.id());
            p.sendSystemMessage(Component.translatable("savepoint.msg.deleted", m.name(), Fmt.bytes(freed)));
            sendList(p);
        } catch (IOException e) {
            fail(p, e);
        }
    }

    /** Store-mutating calls would wait on the store lock while a zip is being built; refuse instead of freezing the game. */
    private boolean editable(ServerPlayer p) {
        if (backupRunning.get()) {
            p.sendSystemMessage(Component.translatable("savepoint.msg.busy_backup"));
            return false;
        }
        return true;
    }

    private void fail(ServerPlayer p, IOException e) {
        SavepointMod.LOG.error("Savepoint operation failed", e);
        p.sendSystemMessage(Component.translatable("savepoint.msg.failed", String.valueOf(e.getMessage())).withStyle(ChatFormatting.RED));
    }

    // ------------------------------------------------------------------ status / config

    public void status(ServerPlayer p) {
        try {
            long used = store.usage().total();
            long budget = settings().storeBudgetBytes();
            p.sendSystemMessage(Component.translatable(budget > 0 ? "savepoint.msg.status" : "savepoint.msg.status_unlimited",
                    Fmt.bytes(used), Fmt.bytes(budget), budget > 0 ? Fmt.percent(used, budget) : "", store.list().size()));
        } catch (IOException e) {
            fail(p, e);
        }
    }

    public void showConfig(ServerPlayer p) {
        p.sendSystemMessage(Component.translatable("savepoint.msg.config_header"));
        Settings s = settings();
        for (Settings.Spec sp : Settings.specs()) {
            p.sendSystemMessage(Component.translatable("savepoint.msg.config_line", sp.key(), sp.get().apply(s), sp.description()));
        }
    }

    public void setConfig(ServerPlayer p, String key, String value) {
        try {
            Settings n = settings().with(key, value);
            SavepointConfig.set(n);
            p.sendSystemMessage(Component.translatable("savepoint.msg.config_set", key, n.get(key)));
            sendList(p);
        } catch (IllegalArgumentException e) {
            p.sendSystemMessage(Component.translatable("savepoint.msg.config_bad", key, e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    // ------------------------------------------------------------------ full backup

    /** Builds a zip of the latest savepoint on a background thread. */
    public void backup(ServerPlayer p) {
        startBackup(p, false);
    }

    private void startBackup(ServerPlayer p, boolean automatic) {
        Manifest latest = store.list().stream().filter(m -> m.kind() != Kind.UNDO).findFirst().orElse(null);
        if (latest == null) latest = store.list().stream().findFirst().orElse(null);
        if (latest == null) {
            p.sendSystemMessage(Component.translatable("savepoint.msg.backup_none"));
            return;
        }
        if (!backupRunning.compareAndSet(false, true)) {
            p.sendSystemMessage(Component.translatable("savepoint.msg.backup_running"));
            return;
        }
        final Manifest source = latest;
        p.sendSystemMessage(Component.translatable("savepoint.msg.backup_started", source.name()));
        final String root = worldDir.getFileName().toString();
        try {
            worker.execute(() -> {
                Path zip = backupDir.resolve(ZipBackup.fileName(source));
                Path part = backupDir.resolve(zip.getFileName() + ".part");
                Component result;
                try {
                    store.buildFullBackup(source.id(), part, root, new OperationControl() {
                        @Override
                        public boolean isCancelled() {
                            return stopping.get();
                        }
                    });
                    Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING);
                    ZipBackup.retain(backupDir, settings().fullBackupKeep());
                    backupDoneThisSession = true;
                    result = Component.translatable("savepoint.msg.backup_done", zip.toString(), Fmt.bytes(Files.size(zip)));
                } catch (Exception e) {
                    try {
                        Files.deleteIfExists(part);
                    } catch (IOException ignored) {
                        // leftover .part is ignored by retention
                    }
                    SavepointMod.LOG.error("Full backup failed", e);
                    result = Component.translatable("savepoint.msg.backup_failed", String.valueOf(e.getMessage())).withStyle(ChatFormatting.RED);
                } finally {
                    backupRunning.set(false);
                }
                Component msg = result;
                if (!stopping.get()) server.execute(() -> {
                    p.sendSystemMessage(msg);
                    sendList(p);
                });
            });
        } catch (RuntimeException e) {
            backupRunning.set(false);
            fail(p, new IOException(e.getMessage(), e));
        }
    }

    // ------------------------------------------------------------------ client list

    /** Builds a {@link SavepointPayloads.ListPayload} off-thread and sends it. Safe to call often. */
    public void sendList(ServerPlayer p) {
        if (stopping.get()) return;
        try {
            worker.execute(() -> {
                SavepointPayloads.ListPayload payload;
                try {
                    payload = buildListPayload();
                } catch (IOException | RuntimeException e) {
                    SavepointMod.LOG.warn("Could not build savepoint list", e);
                    return;
                }
                server.execute(() -> {
                    if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, SavepointPayloads.ListPayload.TYPE)) {
                        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, payload);
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // shutting down
        }
    }

    /** Any thread. */
    public SavepointPayloads.ListPayload buildListPayload() throws IOException {
        List<Manifest> all = store.list();
        Map<String, Long> unique = store.uniqueBytes();
        List<SavepointPayloads.Entry> es = new ArrayList<>();
        for (Manifest m : all) {
            if (es.size() >= SavepointPayloads.MAX_ENTRIES) break;
            es.add(SavepointPayloads.Entry.of(m, unique.getOrDefault(m.id(), 0L)));
        }
        Settings s = settings();
        List<String> vals = new ArrayList<>();
        for (Settings.Spec sp : Settings.specs()) vals.add(sp.get().apply(s));
        return new SavepointPayloads.ListPayload(es, store.usage().total(), s.storeBudgetBytes(), cooldownRemaining(),
                backupRunning.get(), backupDoneThisSession, vals);
    }

    // ------------------------------------------------------------------ chat helpers

    private Component createdLine(Manifest m) {
        MutableComponent line = Component.translatable(m.kind() == Kind.QUICK ? "savepoint.msg.quick_created" : "savepoint.msg.created",
                m.name(), IdResolver.shortId(m), Fmt.bytes(m.logicalBytes()));
        line.append(" ").append(action(Component.translatable("savepoint.msg.revert_button"), "/savepoint revert " + m.id(),
                Component.translatable("savepoint.msg.revert_hover"), ChatFormatting.GREEN));
        if (m.kind() == Kind.MANUAL) {
            line.append(" ").append(action(Component.translatable("savepoint.msg.pin_button"), "/savepoint pin " + m.id(),
                    Component.translatable("savepoint.msg.pin_hover"), ChatFormatting.GOLD));
        }
        return line;
    }

    private static Component flags(Manifest m) {
        List<String> f = new ArrayList<>();
        if (m.pinned()) f.add("pinned");
        if (m.auto()) f.add("auto");
        if (m.kind() == Kind.QUICK) f.add("quick");
        return Component.literal(String.join(", ", f));
    }

    static MutableComponent action(Component label, String command, Component hover, ChatFormatting color) {
        return Component.literal("[").append(label).append("]").withStyle(s -> s
                .withColor(color)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hover)));
    }
}
