package io.github.tjhgibbs.savepoint.core;

import io.github.tjhgibbs.savepoint.core.Manifest.ChunkRef;
import io.github.tjhgibbs.savepoint.core.Manifest.Entry;
import io.github.tjhgibbs.savepoint.core.Manifest.FileEntry;
import io.github.tjhgibbs.savepoint.core.Manifest.Kind;
import io.github.tjhgibbs.savepoint.core.Manifest.RegionEntry;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * The savepoint store for one world: {@code <root>/blobs/ab/<sha256>}, {@code <root>/manifests/<id>.json},
 * {@code <root>/tmp/} (scratch, wiped on open). Pure Java, no Minecraft types.
 *
 * <h2>What a savepoint is</h2>
 * Every regular file under the world directory except the top-level {@code session.lock} (and except the
 * store itself if it is nested inside the world). {@code .mca} files are split into per-chunk blobs
 * (payload bytes opaque, never decompressed); everything else, including {@code .mcc} overflow files, is a
 * whole-file blob. Identical content is stored once across all savepoints.
 *
 * <h2>Thread-safety</h2>
 * Mutating and heavy operations (create, restore, verify, gc, prune, delete, setThumbnail,
 * buildFullBackup) are serialised by one internal lock and run on the <b>calling</b> thread (they may use
 * short-lived worker threads internally for hashing/IO). Cheap metadata operations (rename, setNote, pin)
 * use a separate short lock, and {@link #list()}, {@link #get}, {@link #usage()}, {@link #uniqueBytes()} are lock-free reads of
 * immutable manifests, so a UI thread can call them while a background create runs. Callers must make sure
 * the world files are quiescent (saved, saving disabled) while create runs and closed while restore runs.
 * {@link OperationControl} callbacks are invoked on the calling thread only.
 *
 * <h2>Chunk reuse shortcut</h2>
 * During create, a chunk is not read or hashed if the newest previous savepoint has the same chunk with the
 * same header timestamp, payload length and compression byte, <i>and</i> that timestamp was more than
 * {@link Options#recentGuardMillis()} older than the previous savepoint's creation time (so a later
 * same-second rewrite could not have been missed) and the blob still exists. Restore uses the same rule
 * when diffing, relative to the target's creation time.
 */
public final class SavepointStore {

    /** Tunables; tests inject a clock. */
    public record Options(int ioThreads, LongSupplier clock, long recentGuardMillis) {
        public static Options defaults() {
            return new Options(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), System::currentTimeMillis, 2000);
        }

        public Options withClock(LongSupplier c) {
            return new Options(ioThreads, c, recentGuardMillis);
        }

        public Options withIoThreads(int n) {
            return new Options(n, clock, recentGuardMillis);
        }
    }

    /** What a restore did. Lists are world-relative paths. */
    public record RestoreReport(
            String id,
            List<String> rewrittenRegions,
            List<String> restoredFiles,
            List<String> deletedFiles,
            int unchangedRegions,
            int unchangedFiles,
            long bytesWritten,
            long millis) {}

    /** Result of {@link #verify}. */
    public record VerifyReport(String id, int blobsChecked, long bytesChecked, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /** Disk accounting. {@code total()} is what the store occupies. */
    public record StoreUsage(long blobBytes, long manifestBytes, int blobCount, int manifestCount) {
        public long total() {
            return blobBytes + manifestBytes;
        }
    }

    public record GcResult(int blobsDeleted, long bytesFreed) {}

    /**
     * Pruning rules. {@code maxBytes <= 0} means no size limit. Never pruned: pinned savepoints, the
     * {@code keepNewest} newest savepoints, and the newest {@link Kind#UNDO} savepoint.
     */
    public record PrunePolicy(long maxBytes, int keepNewest) {}

    private final Path root;
    private final Path manifestsDir;
    private final Path tmpDir;
    private final BlobStore blobs;
    private final Options opts;
    private final java.util.concurrent.locks.ReentrantLock opLock = new java.util.concurrent.locks.ReentrantLock();
    private final Object manifestLock = new Object();
    private final Map<String, Manifest> cache = new ConcurrentHashMap<>();
    private volatile List<String> unreadable = List.of();

    public SavepointStore(Path root) throws IOException {
        this(root, Options.defaults());
    }

    public SavepointStore(Path root, Options opts) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.opts = opts;
        this.manifestsDir = this.root.resolve("manifests");
        this.tmpDir = this.root.resolve("tmp");
        Files.createDirectories(manifestsDir);
        deleteTree(tmpDir);
        Files.createDirectories(tmpDir);
        this.blobs = new BlobStore(this.root.resolve("blobs"), tmpDir);
    }

    public Path root() {
        return root;
    }

    /** Package-private access for tests and the zip builder. */
    BlobStore blobs() {
        return blobs;
    }

    // ------------------------------------------------------------------ create

    public Manifest create(Path worldDir, SavepointMeta meta) throws IOException {
        return create(worldDir, meta, OperationControl.NONE);
    }

    /** Captures the world as a new savepoint. On cancel/failure no manifest is written (stray blobs are GC'd later). */
    public Manifest create(Path worldDir, SavepointMeta meta, OperationControl ctl) throws IOException {
        opLock.lock();
        try {
            long now = opts.clock().getAsLong();
            Path world = worldDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(world)) throw new IOException("World folder not found: " + world);
            Manifest prev = newest(list());
            List<String> files = WorldFiles.list(world, excludedDirs(world));
            List<Task<Map.Entry<String, Entry>>> tasks = new ArrayList<>();
            for (String rel : files) {
                Entry pe = prev == null ? null : prev.entries().get(rel);
                long prevCreated = prev == null ? 0 : prev.createdMillis();
                tasks.add(new Task<>(sizeOf(world.resolve(rel)), () -> Map.entry(rel, captureFile(world, rel, pe, prevCreated, ctl))));
            }
            List<Map.Entry<String, Entry>> done = runParallel("Saving world", tasks, ctl);
            Map<String, Entry> entries = new TreeMap<>();
            for (Map.Entry<String, Entry> e : done) entries.put(e.getKey(), e.getValue());
            String thumb = meta.thumbnailPng() == null ? null : blobs.put(meta.thumbnailPng());
            Manifest m = new Manifest(newId(now), meta.name() == null ? "" : meta.name(), meta.note() == null ? "" : meta.note(),
                    now, meta.pinned(), meta.auto(), meta.kind(), meta.player(), thumb, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
            ctl.checkCancelled();
            writeManifest(m);
            if (meta.kind() == Kind.QUICK) {
                // single quick slot: replace older unpinned quick savepoints
                for (Manifest o : list()) if (o.kind() == Kind.QUICK && !o.pinned() && !o.id().equals(m.id())) deleteManifestFile(o.id());
                try {
                    gc();
                } catch (IOException ignored) {
                    // old blobs just stay until the next gc
                }
            }
            return m;
        } finally {
            opLock.unlock();
        }
    }

    private Entry captureFile(Path world, String rel, Entry prev, long prevCreated, OperationControl ctl) throws IOException {
        Path file = world.resolve(rel);
        if (rel.endsWith(".mca")) {
            try {
                return captureRegion(file, prev instanceof RegionEntry r ? r : null, prevCreated, ctl);
            } catch (MalformedRegionException e) {
                // not a sane region: keep exact bytes as an opaque file
            }
        }
        ctl.checkCancelled();
        BlobStore.Stored s = blobs.putFile(file);
        return new FileEntry(s.hash(), s.size());
    }

    private Entry captureRegion(Path file, RegionEntry prev, long prevCreated, OperationControl ctl) throws IOException {
        try (RegionFile rf = RegionFile.open(file)) {
            List<ChunkRef> out = new ArrayList<>(rf.presentCount());
            for (int i = 0; i < RegionFile.CHUNKS; i++) {
                RegionFile.Slot s = rf.slot(i);
                if (!s.present()) continue;
                ctl.checkCancelled();
                RegionFile.Head h = rf.head(s);
                ChunkRef pc = prev == null ? null : prev.chunk(i);
                if (pc != null && pc.timestamp() == s.timestamp() && pc.size() == h.length() && pc.compression() == h.compression()
                        && stable(pc.timestamp(), prevCreated) && blobs.exists(pc.hash())) {
                    out.add(pc);
                    continue;
                }
                RegionFile.Raw raw = rf.readRaw(s);
                String hash = Hashes.sha256Hex(raw.payload());
                blobs.put(hash, raw.payload());
                out.add(new ChunkRef(i, s.timestamp(), raw.compression(), raw.payload().length, hash));
            }
            return new RegionEntry(java.util.Collections.unmodifiableList(out));
        }
    }

    /** True if a chunk timestamp is old enough, relative to when a snapshot was read, that a rewrite could not share its second. */
    private boolean stable(int timestampSeconds, long snapshotMillis) {
        return Integer.toUnsignedLong(timestampSeconds) * 1000L < snapshotMillis - opts.recentGuardMillis();
    }

    // ------------------------------------------------------------------ listing / metadata

    /** All readable savepoints, newest first. Lock-free. Unreadable manifests are skipped (see {@link #unreadableManifests()}). */
    public List<Manifest> list() {
        List<Manifest> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (Stream<Path> s = Files.list(manifestsDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".json")) continue;
                String id = n.substring(0, n.length() - 5);
                seen.add(id);
                try {
                    out.add(loadManifest(id));
                } catch (IOException | RuntimeException e) {
                    bad.add(id + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            bad.add("manifests dir: " + e.getMessage());
        }
        cache.keySet().retainAll(seen);
        unreadable = bad;
        out.sort(NEWEST_FIRST);
        return out;
    }

    private static final Comparator<Manifest> NEWEST_FIRST =
            Comparator.comparingLong(Manifest::createdMillis).thenComparing(Manifest::id).reversed();

    /** Problems found by the last {@link #list()}. */
    public List<String> unreadableManifests() {
        return unreadable;
    }

    public Optional<Manifest> get(String id) {
        try {
            return Optional.of(loadManifest(id));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private Manifest require(String id) throws IOException {
        try {
            return loadManifest(id);
        } catch (java.nio.file.NoSuchFileException e) {
            throw new SavepointNotFoundException(id);
        }
    }

    private Manifest loadManifest(String id) throws IOException {
        checkId(id);
        Manifest c = cache.get(id);
        if (c != null) return c;
        Path p = manifestsDir.resolve(id + ".json");
        Manifest m;
        try {
            m = Manifest.fromJson(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IOException("Unreadable manifest " + id + ": " + e.getMessage(), e);
        }
        if (!id.equals(m.id())) throw new IOException("Manifest id mismatch in " + p);
        cache.put(id, m);
        return m;
    }

    private List<Manifest> listStrict() throws IOException {
        List<Manifest> out = list();
        if (!unreadable.isEmpty()) throw new IOException("Savepoint store has unreadable manifests, refusing to continue: " + unreadable);
        return out;
    }

    private static void checkId(String id) {
        if (id == null || id.isEmpty() || !id.matches("[A-Za-z0-9._-]+") || id.equals(".") || id.equals(".."))
            throw new IllegalArgumentException("bad savepoint id: " + id);
    }

    private static Manifest newest(List<Manifest> l) {
        return l.isEmpty() ? null : l.get(0);
    }

    private String newId(long now) {
        String base = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(now));
        while (true) {
            String id = base + "-" + String.format("%04x", java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000));
            if (!Files.exists(manifestsDir.resolve(id + ".json"))) return id;
        }
    }

    private void writeManifest(Manifest m) throws IOException {
        synchronized (manifestLock) {
            Path t = Files.createTempFile(tmpDir, "manifest", ".part");
            try {
                Files.writeString(t, m.toJson(), StandardCharsets.UTF_8);
                moveReplace(t, manifestsDir.resolve(m.id() + ".json"));
                cache.put(m.id(), m);
            } finally {
                Files.deleteIfExists(t);
            }
        }
    }

    private Manifest update(String id, UnaryOperator<Manifest> f) throws IOException {
        synchronized (manifestLock) {
            Manifest n = f.apply(require(id));
            writeManifest(n);
            return n;
        }
    }

    public Manifest rename(String id, String newName) throws IOException {
        return update(id, m -> m.withName(newName));
    }

    public Manifest setNote(String id, String note) throws IOException {
        return update(id, m -> m.withNote(note));
    }

    public Manifest pin(String id, boolean pinned) throws IOException {
        return update(id, m -> m.withPinned(pinned));
    }

    /** Attaches (or replaces) the PNG thumbnail of a savepoint. */
    public Manifest setThumbnail(String id, byte[] png) throws IOException {
        opLock.lock();
        try {
            String h = blobs.put(png);
            return update(id, m -> m.withThumbnail(h));
        } finally {
            opLock.unlock();
        }
    }

    public Optional<byte[]> readThumbnail(String id) throws IOException {
        Manifest m = require(id);
        return m.thumbnail() == null ? Optional.empty() : Optional.of(blobs.readVerified(m.thumbnail()));
    }

    // ------------------------------------------------------------------ delete / gc / prune / usage

    /** Deletes a savepoint (pinned or not) and garbage-collects blobs nothing else references. Returns bytes freed. */
    public long delete(String id) throws IOException {
        opLock.lock();
        try {
            require(id);
            deleteManifestFile(id);
            return gc().bytesFreed();
        } finally {
            opLock.unlock();
        }
    }

    private void deleteManifestFile(String id) throws IOException {
        synchronized (manifestLock) {
            Files.deleteIfExists(manifestsDir.resolve(id + ".json"));
            cache.remove(id);
        }
    }

    /** Deletes blobs not referenced by any manifest (and stale scratch files). Refuses to run if any manifest is unreadable. */
    public GcResult gc() throws IOException {
        opLock.lock();
        try {
            Set<String> live = new HashSet<>();
            for (Manifest m : listStrict()) live.addAll(m.referencedBlobs());
            int n = 0;
            long freed = 0;
            for (Map.Entry<String, Long> e : blobs.scan().entrySet()) {
                if (live.contains(e.getKey())) continue;
                blobs.delete(e.getKey());
                n++;
                freed += e.getValue();
            }
            return new GcResult(n, freed);
        } finally {
            opLock.unlock();
        }
    }

    /** Current disk use of the store. Lock-free, safe from a UI thread. */
    public StoreUsage usage() throws IOException {
        Map<String, Long> sizes = blobs.scan();
        long b = 0;
        for (long v : sizes.values()) b += v;
        long mb = 0;
        int mc = 0;
        try (Stream<Path> s = Files.list(manifestsDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (p.getFileName().toString().endsWith(".json")) {
                    mb += Files.size(p);
                    mc++;
                }
            }
        }
        return new StoreUsage(b, mb, sizes.size(), mc);
    }

    /**
     * Per-savepoint "unique bytes": blobs that only that savepoint references, i.e. what deleting it would free.
     * Lock-free like {@link #usage()}, so a UI never waits behind a long zip; while another operation is mid-way the
     * numbers may be momentarily off by that operation's blobs.
     */
    public Map<String, Long> uniqueBytes() throws IOException {
        List<Manifest> all = list();
        Map<String, Long> sizes = blobs.scan();
        Map<String, Integer> refs = new HashMap<>();
        Map<String, Set<String>> per = new HashMap<>();
        for (Manifest m : all) {
            Set<String> r = m.referencedBlobs();
            per.put(m.id(), r);
            for (String h : r) refs.merge(h, 1, Integer::sum);
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (Manifest m : all) {
            long t = 0;
            for (String h : per.get(m.id())) if (refs.get(h) == 1) t += sizes.getOrDefault(h, 0L);
            out.put(m.id(), t);
        }
        return out;
    }

    public long uniqueBytes(String id) throws IOException {
        Long v = uniqueBytes().get(id);
        if (v == null) throw new SavepointNotFoundException(id);
        return v;
    }

    /**
     * Deletes oldest-first until the store fits {@code policy.maxBytes()}, skipping protected savepoints
     * (pinned, newest {@code keepNewest}, newest UNDO). May leave the store over budget if only protected
     * savepoints remain. Returns the pruned manifests (oldest first).
     */
    public List<Manifest> prune(PrunePolicy policy) throws IOException {
        opLock.lock();
        try {
            gc();
            List<Manifest> all = listStrict(); // newest first
            if (policy.maxBytes() <= 0 || all.isEmpty()) return List.of();
            Set<String> protectedIds = new HashSet<>();
            for (int i = 0; i < all.size(); i++) {
                Manifest m = all.get(i);
                if (m.pinned() || i < policy.keepNewest()) protectedIds.add(m.id());
            }
            for (Manifest m : all)
                if (m.kind() == Kind.UNDO) {
                    protectedIds.add(m.id());
                    break;
                }
            Map<String, Long> sizes = blobs.scan();
            Map<String, Integer> refs = new HashMap<>();
            long total = 0;
            for (long v : sizes.values()) total += v;
            for (Manifest m : all) {
                for (String h : m.referencedBlobs()) refs.merge(h, 1, Integer::sum);
                total += Files.size(manifestsDir.resolve(m.id() + ".json"));
            }
            List<Manifest> pruned = new ArrayList<>();
            for (int i = all.size() - 1; i >= 0 && total > policy.maxBytes(); i--) {
                Manifest m = all.get(i);
                if (protectedIds.contains(m.id())) continue;
                total -= Files.size(manifestsDir.resolve(m.id() + ".json"));
                for (String h : m.referencedBlobs()) {
                    if (refs.merge(h, -1, Integer::sum) == 0) total -= sizes.getOrDefault(h, 0L);
                }
                pruned.add(m);
            }
            for (Manifest m : pruned) deleteManifestFile(m.id());
            if (!pruned.isEmpty()) gc();
            return pruned;
        } finally {
            opLock.unlock();
        }
    }

    // ------------------------------------------------------------------ verify

    /** Re-hashes every blob the savepoint references. */
    public VerifyReport verify(String id, OperationControl ctl) throws IOException {
        opLock.lock();
        try {
            Manifest m = require(id);
            Map<String, String> firstPath = new LinkedHashMap<>(); // hash -> path using it
            Map<String, Long> sizes = new HashMap<>();
            for (Map.Entry<String, Entry> e : m.entries().entrySet()) {
                if (e.getValue() instanceof FileEntry f) {
                    firstPath.putIfAbsent(f.hash(), e.getKey());
                    sizes.put(f.hash(), f.size());
                } else {
                    for (ChunkRef c : ((RegionEntry) e.getValue()).chunks()) {
                        firstPath.putIfAbsent(c.hash(), e.getKey() + " chunk " + c.index());
                        sizes.put(c.hash(), (long) c.size());
                    }
                }
            }
            if (m.thumbnail() != null) firstPath.putIfAbsent(m.thumbnail(), "thumbnail");
            List<Task<String>> tasks = new ArrayList<>();
            for (Map.Entry<String, String> e : firstPath.entrySet()) {
                String h = e.getKey();
                tasks.add(new Task<>(sizes.getOrDefault(h, 1L), () -> {
                    ctl.checkCancelled();
                    if (!blobs.exists(h)) return e.getValue() + ": blob " + h + " is missing";
                    return blobs.isIntact(h) ? null : e.getValue() + ": blob " + h + " is corrupt";
                }));
            }
            List<String> res = runParallel("Verifying", tasks, ctl);
            List<String> problems = new ArrayList<>();
            for (String r : res) if (r != null) problems.add(r);
            long bytes = 0;
            for (long v : sizes.values()) bytes += v;
            return new VerifyReport(id, firstPath.size(), bytes, problems);
        } finally {
            opLock.unlock();
        }
    }

    // ------------------------------------------------------------------ restore

    public RestoreReport restore(Path worldDir, String id) throws IOException {
        return restore(worldDir, id, OperationControl.NONE);
    }

    /**
     * Makes {@code worldDir} match the savepoint. Only differing files/regions are rewritten; files that are not
     * part of the savepoint are deleted (never {@code session.lock}). The new content is first staged and
     * hash-verified in {@code <store>/tmp}; only then are files moved into place, with the replaced files kept
     * aside so a failure part-way rolls everything back. If a blob is missing/corrupt, or the operation is
     * cancelled before the move phase, the world is left exactly as it was. The world must not be in use.
     */
    public RestoreReport restore(Path worldDir, String id, OperationControl ctl) throws IOException {
        opLock.lock();
        try {
            long t0 = System.nanoTime();
            Manifest m = require(id);
            Path world = worldDir.toAbsolutePath().normalize();
            for (String rel : m.entries().keySet()) WorldFiles.resolveSafe(world, rel);
            Files.createDirectories(world);
            List<String> current = WorldFiles.list(world, excludedDirs(world));

            // 1. diff
            List<Task<Boolean>> diffTasks = new ArrayList<>();
            List<String> keys = new ArrayList<>(m.entries().keySet());
            for (String rel : keys) {
                Entry e = m.entries().get(rel);
                diffTasks.add(new Task<>(Math.max(1, sizeOf(world.resolve(rel))), () -> !matches(world.resolve(rel), e, m.createdMillis(), ctl)));
            }
            List<Boolean> changed = runParallel("Comparing", diffTasks, ctl);
            List<String> toWrite = new ArrayList<>();
            int unchangedRegions = 0, unchangedFiles = 0;
            for (int i = 0; i < keys.size(); i++) {
                if (changed.get(i)) toWrite.add(keys.get(i));
                else if (m.entries().get(keys.get(i)) instanceof RegionEntry) unchangedRegions++;
                else unchangedFiles++;
            }
            Set<String> wanted = m.entries().keySet();
            List<String> toDelete = new ArrayList<>();
            for (String c : current) if (!wanted.contains(c)) toDelete.add(c);

            // 2. stage (verifies every blob)
            Path stage = tmpDir.resolve("restore-" + UUID.randomUUID());
            Path newDir = stage.resolve("new");
            Path oldDir = stage.resolve("old");
            long written = 0;
            try {
                Files.createDirectories(newDir);
                List<Task<Long>> stageTasks = new ArrayList<>();
                for (String rel : toWrite) {
                    Entry e = m.entries().get(rel);
                    stageTasks.add(new Task<>(Math.max(1, entrySize(e)), () -> stageEntry(newDir.resolve(rel), e, ctl)));
                }
                for (long w : runParallel("Restoring", stageTasks, ctl)) written += w;
                ctl.checkCancelled();

                // 3. commit (no cancellation from here on)
                commit(world, newDir, oldDir, toWrite, toDelete);
            } catch (IOException | RuntimeException e) {
                deleteTree(stage);
                throw e;
            }
            deleteTree(stage);
            Set<Path> dirs = new HashSet<>();
            for (String rel : toDelete) dirs.add(world.resolve(rel).getParent());
            for (Path d : dirs) pruneEmptyDirs(d, world);

            List<String> rewrittenRegions = new ArrayList<>(), restoredFiles = new ArrayList<>();
            for (String rel : toWrite) (m.entries().get(rel) instanceof RegionEntry ? rewrittenRegions : restoredFiles).add(rel);
            return new RestoreReport(id, rewrittenRegions, restoredFiles, toDelete, unchangedRegions, unchangedFiles, written,
                    (System.nanoTime() - t0) / 1_000_000);
        } finally {
            opLock.unlock();
        }
    }

    private boolean matches(Path file, Entry e, long snapshotMillis, OperationControl ctl) {
        try {
            if (!Files.isRegularFile(file)) return false;
            if (e instanceof FileEntry f) {
                if (Files.size(file) != f.size()) return false;
                return hashFile(file).equals(f.hash());
            }
            RegionEntry r = (RegionEntry) e;
            try (RegionFile rf = RegionFile.open(file)) {
                if (rf.presentCount() != r.chunks().size()) return false;
                for (ChunkRef c : r.chunks()) {
                    ctl.checkCancelled();
                    RegionFile.Slot s = rf.slot(c.index());
                    if (!s.present() || s.timestamp() != c.timestamp()) return false;
                    RegionFile.Head h = rf.head(s);
                    if (h.length() != c.size() || h.compression() != c.compression()) return false;
                    if (!stable(c.timestamp(), snapshotMillis) && !Hashes.sha256Hex(rf.readRaw(s).payload()).equals(c.hash()))
                        return false;
                }
                return true;
            }
        } catch (IOException ex) {
            return false; // unreadable / malformed: just rewrite it
        }
    }

    private long stageEntry(Path target, Entry e, OperationControl ctl) throws IOException {
        Files.createDirectories(target.getParent());
        if (e instanceof FileEntry f) {
            try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(target), 1 << 16)) {
                blobs.streamVerified(f.hash(), o);
            }
            if (Files.size(target) != f.size()) throw new CorruptBlobException(f.hash(), "blob size differs from manifest");
            return f.size();
        }
        RegionEntry r = (RegionEntry) e;
        List<RegionWriter.ChunkOut> outs = new ArrayList<>(r.chunks().size());
        for (ChunkRef c : r.chunks()) outs.add(new RegionWriter.ChunkOut(c.index(), c.timestamp(), c.compression(), c.size()));
        RegionWriter.writeFile(target, outs, c -> {
            ctl.checkCancelled();
            return blobs.readVerified(r.chunk(c.index()).hash());
        });
        return Files.size(target);
    }

    private void commit(Path world, Path newDir, Path oldDir, List<String> toWrite, List<String> toDelete) throws IOException {
        List<String> backedUp = new ArrayList<>();
        List<String> installed = new ArrayList<>();
        try {
            for (String rel : toWrite) {
                Path target = world.resolve(rel);
                Files.createDirectories(target.getParent());
                if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Path bak = oldDir.resolve(rel);
                    Files.createDirectories(bak.getParent());
                    move(target, bak);
                    backedUp.add(rel);
                }
                move(newDir.resolve(rel), target);
                installed.add(rel);
            }
            for (String rel : toDelete) {
                Path bak = oldDir.resolve(rel);
                Files.createDirectories(bak.getParent());
                move(world.resolve(rel), bak);
                backedUp.add(rel);
            }
        } catch (IOException | RuntimeException e) {
            IOException failure = new IOException("Restore failed while moving files; rolled back: " + e.getMessage(), e);
            for (int i = installed.size() - 1; i >= 0; i--) {
                try {
                    Files.deleteIfExists(world.resolve(installed.get(i)));
                } catch (IOException x) {
                    failure.addSuppressed(x);
                }
            }
            for (int i = backedUp.size() - 1; i >= 0; i--) {
                try {
                    Path t = world.resolve(backedUp.get(i));
                    Files.createDirectories(t.getParent());
                    move(oldDir.resolve(backedUp.get(i)), t);
                } catch (IOException x) {
                    failure.addSuppressed(x);
                }
            }
            throw failure;
        }
    }

    private static void pruneEmptyDirs(Path dir, Path stopAt) {
        while (dir != null && !dir.equals(stopAt) && dir.startsWith(stopAt)) {
            try {
                Files.delete(dir);
            } catch (DirectoryNotEmptyException e) {
                return;
            } catch (IOException e) {
                return;
            }
            dir = dir.getParent();
        }
    }

    // ------------------------------------------------------------------ full backup

    /**
     * Writes {@code zipFile}: a zip containing {@code <rootFolderName>/...} with the whole world as of the
     * savepoint, rebuilt from blobs (every blob hash-verified). Runs on the calling thread; atomic (temp + rename).
     */
    public void buildFullBackup(String id, Path zipFile, String rootFolderName, OperationControl ctl) throws IOException {
        opLock.lock();
        try {
            ZipBackup.write(require(id), blobs, zipFile, rootFolderName, ctl);
        } finally {
            opLock.unlock();
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<Path> excludedDirs(Path world) {
        return root.startsWith(world) ? List.of(root) : List.of();
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long entrySize(Entry e) {
        if (e instanceof FileEntry f) return f.size();
        long t = 0;
        for (ChunkRef c : ((RegionEntry) e).chunks()) t += c.size();
        return t;
    }

    private static String hashFile(Path f) throws IOException {
        MessageDigest md = Hashes.sha256();
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        }
        return Hashes.hex(md.digest());
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(x);
        }
    }

    private record Task<T>(long weight, Callable<T> body) {}

    private <T> List<T> runParallel(String phase, List<Task<T>> tasks, OperationControl ctl) throws IOException {
        long total = 0;
        for (Task<T> t : tasks) total += t.weight();
        ExecutorService ex = Executors.newFixedThreadPool(Math.max(1, opts.ioThreads()), r -> {
            Thread t = new Thread(r, "savepoint-io");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<T>> fs = new ArrayList<>(tasks.size());
            for (Task<T> t : tasks) fs.add(ex.submit(t.body()));
            List<T> out = new ArrayList<>(tasks.size());
            long done = 0;
            ctl.progress(phase, 0, total);
            for (int i = 0; i < fs.size(); i++) {
                ctl.checkCancelled();
                out.add(fs.get(i).get());
                done += tasks.get(i).weight();
                ctl.progress(phase, done, total);
            }
            return out;
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException io) {
                IOException n = io instanceof CorruptBlobException cb ? new CorruptBlobException(cb.hash(), "Savepoint data is damaged: " + stripBlob(cb)) : io;
                throw n;
            }
            if (c instanceof RuntimeException re) throw re;
            if (c instanceof Error er) throw er;
            throw new IOException(c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } finally {
            ex.shutdownNow();
            try {
                ex.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String stripBlob(CorruptBlobException e) {
        String m = e.getMessage();
        int i = m.lastIndexOf(" (blob ");
        return i < 0 ? m : m.substring(0, i);
    }
}
