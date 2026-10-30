package io.github.tjhgibbs.savepoint.client;

import io.github.tjhgibbs.savepoint.core.OperationCancelledException;
import io.github.tjhgibbs.savepoint.core.OperationControl;
import io.github.tjhgibbs.savepoint.core.SavepointStore;
import io.github.tjhgibbs.savepoint.mod.SavepointMod;
import io.github.tjhgibbs.savepoint.mod.SavepointPayloads;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/**
 * Client-side owner of a revert. The integrated server and its {@code SavepointService} do not survive a revert,
 * so everything that must outlive them lives here: the server tells us (BeginRevertPayload) after taking its undo
 * point, then this class runs the sequence
 * <ol>
 *   <li>DISCONNECT: leave the world and stop the integrated server (blocks until the world is saved and closed),</li>
 *   <li>RESTORE: {@code store.restore(worldDir, id)} on a worker thread behind {@link RevertProgressScreen},</li>
 *   <li>REOPEN: {@code WorldOpenFlows.openWorld(levelId)} (also after a failed or cancelled restore, the core
 *       guarantees the world is untouched then),</li>
 *   <li>MESSAGE: once the player is back in, chat line + toast with the outcome.</li>
 * </ol>
 * All state changes happen on the client thread (driven from the tick event); the worker only publishes volatiles.
 * Chunk 4's UI starts a revert the normal way: send the {@code /savepoint revert <id>} command (the server does the
 * checks and undo point); never call {@link #begin} directly.
 */
public final class RevertCoordinator {
    private enum Phase { IDLE, DISCONNECT, RESTORE, REOPEN, AWAIT_JOIN }

    private static RevertCoordinator active;
    /** Dev only: -Dsavepoint.debugRestoreDelayMs=N slows every progress callback so the progress screen can be inspected. */
    private static final long DEBUG_DELAY_MS = Long.getLong("savepoint.debugRestoreDelayMs", 0L);

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final SavepointPayloads.BeginRevertPayload req;
    private Phase phase = Phase.DISCONNECT;
    private volatile String phaseLabel = "";
    private volatile long done, total;
    private volatile boolean cancelRequested;
    private volatile boolean pastPointOfNoReturn;
    private volatile boolean workerFinished;
    private volatile Throwable failure;
    private volatile boolean cancelled;
    private boolean ok;
    private int waitTicks;
    private int titleTicks;
    private long t0, tRestoreStart, tRestoreEnd, tReopen;
    private byte[] undoThumbnail;

    private RevertCoordinator(SavepointPayloads.BeginRevertPayload req) {
        this.req = req;
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(SavepointPayloads.BeginRevertPayload.TYPE, (payload, ctx) -> {
            if (active != null) {
                SavepointMod.LOG.warn("Ignoring revert request: one is already running");
                return;
            }
            active = new RevertCoordinator(payload);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            RevertCoordinator c = active;
            if (c != null) c.tick(mc);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            RevertCoordinator c = active;
            if (c != null && c.phase == Phase.AWAIT_JOIN) c.waitTicks = 0;
        });
    }

    /** True while a revert owns the client (UI should disable its buttons). */
    public static boolean isActive() {
        return active != null;
    }

    // ---------------------------------------------------------------- for the progress screen

    String targetName() {
        return req.targetName();
    }

    double fraction() {
        long t = total;
        return t <= 0 ? 0 : Math.min(1.0, (double) done / t);
    }

    Component statusLine() {
        return switch (phase) {
            case DISCONNECT -> Component.translatable("savepoint.revert.phase.saving");
            case RESTORE -> cancelRequested ? Component.translatable("savepoint.revert.phase.cancelling")
                    : Component.translatable("savepoint.revert.phase.restoring");
            default -> Component.translatable("savepoint.revert.phase.reopening");
        };
    }

    boolean canCancel() {
        return phase == Phase.RESTORE && !cancelRequested && !pastPointOfNoReturn;
    }

    void cancel() {
        if (canCancel()) cancelRequested = true;
    }

    // ---------------------------------------------------------------- state machine

    private void tick(Minecraft mc) {
        switch (phase) {
            case DISCONNECT -> disconnectAndStartRestore(mc);
            case RESTORE -> {
                if (workerFinished) reopen(mc);
            }
            case REOPEN -> { /* openWorld is running; it moves us to AWAIT_JOIN itself */ }
            case AWAIT_JOIN -> awaitJoin(mc);
            case IDLE -> active = null;
        }
    }

    private void disconnectAndStartRestore(Minecraft mc) {
        t0 = System.nanoTime();
        undoThumbnail = SceneShots.fresh(); // before the disconnect, which clears the shots
        phase = Phase.RESTORE; // set first: the disconnect pumps frames and re-enters ticks? no, but keep state consistent
        phaseLabel = "";
        try {
            ClientLevel level = mc.level;
            if (level != null) level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
            // Blocks (rendering the vanilla "saving" screen) until the integrated server has saved and fully stopped.
            mc.disconnect(new RevertProgressScreen(this), false);
        } catch (RuntimeException e) {
            SavepointMod.LOG.error("Revert: disconnect failed", e);
            failure = e;
            workerFinished = true;
            return;
        }
        long tStopped = System.nanoTime();
        SavepointMod.LOG.info("Revert: server stopped after {} ms", (tStopped - t0) / 1_000_000);
        tRestoreStart = tStopped;
        Thread t = new Thread(this::restoreWorker, "Savepoint-revert");
        t.setDaemon(true);
        t.start();
    }

    private void restoreWorker() {
        try {
            SavepointStore store = new SavepointStore(Path.of(req.storeRoot()));
            attachUndoThumbnail(store);
            store.restore(Path.of(req.worldDir()), req.targetId(), new OperationControl() {
                @Override
                public void progress(String phase, long d, long t) {
                    if (DEBUG_DELAY_MS > 0) sleepQuietly(DEBUG_DELAY_MS);
                    phaseLabel = phase;
                    total = t;
                    done = d;
                    if (t > 0 && d >= t && "Restoring".equals(phase)) pastPointOfNoReturn = true;
                }

                @Override
                public boolean isCancelled() {
                    return cancelRequested;
                }
            });
        } catch (OperationCancelledException e) {
            cancelled = true;
        } catch (Throwable e) {
            SavepointMod.LOG.error("Revert: restore failed (world left as it was)", e);
            failure = e;
        } finally {
            tRestoreEnd = System.nanoTime();
            workerFinished = true;
        }
    }

    /**
     * The undo point was taken by a server that is now gone, so the usual thumbnail upload cannot reach it; write the
     * last scene shot straight into the store instead. Cosmetic: failures are ignored.
     */
    private void attachUndoThumbnail(SavepointStore store) {
        byte[] png = undoThumbnail;
        if (png == null || req.undoId() == null || req.undoId().isEmpty()) return;
        try {
            store.setThumbnail(req.undoId(), png);
        } catch (Exception e) {
            SavepointMod.LOG.debug("Revert: could not attach the undo thumbnail", e);
        }
    }

    private void reopen(Minecraft mc) {
        ok = failure == null && !cancelled;
        SavepointMod.LOG.info("Revert: restore {} in {} ms", ok ? "done" : cancelled ? "cancelled" : "FAILED",
                (tRestoreEnd - tRestoreStart) / 1_000_000);
        done = total = 1;
        phase = Phase.REOPEN;
        tReopen = System.nanoTime();
        try {
            mc.createWorldOpenFlows().openWorld(req.levelId(), this::reopenAborted);
            phase = Phase.AWAIT_JOIN;
            waitTicks = 0;
        } catch (RuntimeException e) {
            SavepointMod.LOG.error("Revert: could not reopen the world", e);
            if (failure == null) failure = e;
            stranded(mc, e);
        }
    }

    /** The world-open flow was cancelled by the user (e.g. at a vanilla warning screen) or bounced back. */
    private void reopenAborted() {
        Minecraft mc = Minecraft.getInstance();
        stranded(mc, failure != null ? failure : new IllegalStateException("opening the world was cancelled"));
    }

    private void awaitJoin(Minecraft mc) {
        if (mc.level != null && mc.player != null) {
            // Give the join a moment so the chat is no longer being reset.
            if (++waitTicks < 20) return;
            long ms = (System.nanoTime() - tReopen) / 1_000_000;
            SavepointMod.LOG.info("Revert: rejoined {} ms after reopen; total {} ms", ms, (System.nanoTime() - t0) / 1_000_000);
            announce(mc);
            finish();
            return;
        }
        // Watchdog: never leave the user on the title screen without saying why.
        if (mc.gui.screen() instanceof TitleScreen && ++titleTicks > 40) {
            stranded(mc, failure != null ? failure : new IllegalStateException("the world did not open"));
        }
    }

    private void announce(Minecraft mc) {
        Component msg;
        Component toastTitle;
        if (ok) {
            Component undo = Component.literal("[").append(Component.translatable("savepoint.revert.undo_button")).append("]").withStyle(s -> s
                    .withColor(ChatFormatting.GREEN)
                    .withClickEvent(new ClickEvent.RunCommand("/savepoint revert undo"))
                    .withHoverEvent(new HoverEvent.ShowText(Component.translatable("savepoint.revert.undo_hover"))));
            msg = Component.translatable("savepoint.revert.done", req.targetName()).append(" ").append(undo);
            toastTitle = Component.translatable("savepoint.revert.toast_done");
            SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId.WORLD_BACKUP, toastTitle,
                    Component.translatable("savepoint.revert.toast_done_body", req.targetName()));
        } else if (cancelled) {
            msg = Component.translatable("savepoint.revert.cancelled").withStyle(ChatFormatting.YELLOW);
            SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId.WORLD_BACKUP,
                    Component.translatable("savepoint.revert.toast_cancelled"), Component.translatable("savepoint.revert.cancelled"));
        } else {
            msg = Component.translatable("savepoint.revert.failed", errorText(failure)).withStyle(ChatFormatting.RED);
            SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId.WORLD_ACCESS_FAILURE,
                    Component.translatable("savepoint.revert.toast_failed"), Component.translatable("savepoint.revert.failed_short"));
        }
        mc.gui.hud.getChat().addClientSystemMessage(msg);
    }

    /** The world could not be opened again: show a blocking message over the title screen. */
    private void stranded(Minecraft mc, Throwable why) {
        String text = errorText(why);
        mc.gui.setScreen(new AlertScreen(RevertCoordinator::clear,
                Component.translatable("savepoint.revert.stranded_title").withStyle(ChatFormatting.RED),
                Component.translatable(ok ? "savepoint.revert.stranded_after_ok" : "savepoint.revert.stranded_body", text)));
        finish();
    }

    private static String errorText(Throwable t) {
        if (t == null) return "unknown error";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + m;
    }

    private void finish() {
        phase = Phase.IDLE;
        active = null;
    }

    private static void clear() {
        Minecraft.getInstance().gui.setScreen(new TitleScreen());
    }
}
