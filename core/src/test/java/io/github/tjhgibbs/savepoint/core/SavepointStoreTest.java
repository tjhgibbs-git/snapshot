package io.github.tjhgibbs.savepoint.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.tjhgibbs.savepoint.core.Manifest.Kind;
import io.github.tjhgibbs.savepoint.core.SavepointStore.Options;
import io.github.tjhgibbs.savepoint.core.SavepointStore.PrunePolicy;
import io.github.tjhgibbs.savepoint.core.SavepointStore.RestoreReport;
import io.github.tjhgibbs.savepoint.core.TestWorlds.Chunk;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavepointStoreTest {
    @TempDir Path tmp;
    Path world;
    Path storeRoot;
    SavepointStore store;
    final AtomicLong clock = new AtomicLong(2_000_000_000_000L);

    static final String REGION = "dimensions/minecraft/overworld/region/r.0.0.mca";

    @BeforeEach
    void setUp() throws IOException {
        world = TestWorlds.makeWorld(tmp.resolve("world"), 42);
        storeRoot = tmp.resolve("store");
        store = new SavepointStore(storeRoot, Options.defaults().withClock(clock::get));
    }

    Manifest create(Kind kind, String name) throws IOException {
        clock.addAndGet(10_000);
        return store.create(world, SavepointMeta.of(kind, name));
    }

    @Test
    void createMutateRestoreRoundTrip() throws Exception {
        var before = TestWorlds.fingerprint(world);
        Manifest m = create(Kind.MANUAL, "first");
        assertFalse(m.entries().containsKey("session.lock"));
        assertTrue(m.entries().containsKey("dimensions/minecraft/the_nether/region/c.0.0.mcc"));
        assertInstanceOf(Manifest.RegionEntry.class, m.entries().get(REGION));

        // mutate: change a chunk, level.dat, add a file+dir, delete a file, delete a region, touch lock
        TestWorlds.replaceChunk(world.resolve(REGION), TestWorlds.readRegion(world.resolve(REGION)).get(3).index(), new byte[777], 1_999_999_999);
        TestWorlds.writeFile(world, "level.dat", new byte[] {1, 2, 3});
        TestWorlds.writeFile(world, "newdir/sub/new.txt", "hello".getBytes());
        Files.delete(world.resolve("icon.png"));
        Files.delete(world.resolve("dimensions/minecraft/overworld/region/r.0.1.mca"));
        Files.writeString(world.resolve("session.lock"), "changed-lock");
        assertNotEquals(before, TestWorlds.fingerprint(world));

        RestoreReport rep = store.restore(world, m.id());
        assertEquals(before, TestWorlds.fingerprint(world));
        assertEquals("changed-lock", Files.readString(world.resolve("session.lock")), "session.lock must not be touched");
        assertTrue(rep.rewrittenRegions().contains(REGION));
        assertTrue(rep.rewrittenRegions().contains("dimensions/minecraft/overworld/region/r.0.1.mca"));
        assertTrue(rep.restoredFiles().containsAll(List.of("level.dat", "icon.png")));
        assertEquals(List.of("newdir/sub/new.txt"), rep.deletedFiles());
        assertFalse(Files.exists(world.resolve("newdir")), "emptied directories are removed");
        assertTrue(rep.unchangedRegions() >= 2);
        assertEquals(0, Files.list(storeRoot.resolve("tmp")).count(), "staging cleaned up");

        // restoring again is a no-op
        RestoreReport again = store.restore(world, m.id());
        assertTrue(again.rewrittenRegions().isEmpty() && again.restoredFiles().isEmpty() && again.deletedFiles().isEmpty());
    }

    @Test
    void externalChunkStubAndMccPreserved() throws Exception {
        Manifest m = create(Kind.MANUAL, "ext");
        String nether = "dimensions/minecraft/the_nether/region/r.0.0.mca";
        var chunk = ((Manifest.RegionEntry) m.entries().get(nether)).chunk(1000);
        assertEquals(0, chunk.size());
        assertEquals(0x82, chunk.compression());
        byte[] mcc = Files.readAllBytes(world.resolve("dimensions/minecraft/the_nether/region/c.0.0.mcc"));
        Files.delete(world.resolve("dimensions/minecraft/the_nether/region/c.0.0.mcc"));
        TestWorlds.deleteTree(world.resolve("dimensions/minecraft/the_nether"));
        store.restore(world, m.id());
        assertArrayEquals(mcc, Files.readAllBytes(world.resolve("dimensions/minecraft/the_nether/region/c.0.0.mcc")));
        assertEquals(0x82, TestWorlds.readRegion(world.resolve(nether)).stream().filter(c -> c.index() == 1000).findFirst().get().compression());
    }

    @Test
    void secondIdenticalCreateAddsNoBlobBytes() throws Exception {
        create(Kind.MANUAL, "a");
        var u1 = store.usage();
        Manifest b = create(Kind.MANUAL, "b");
        var u2 = store.usage();
        assertEquals(u1.blobBytes(), u2.blobBytes());
        assertEquals(u1.blobCount(), u2.blobCount());
        assertTrue(u2.manifestBytes() > u1.manifestBytes());
        assertEquals(0, store.uniqueBytes(b.id()));
    }

    @Test
    void modifyingOneChunkAddsOnlyThatChunk() throws Exception {
        create(Kind.MANUAL, "a");
        long before = store.usage().blobBytes();
        byte[] payload = TestWorlds.randomBytes(new Random(9), 1234);
        TestWorlds.replaceChunk(world.resolve(REGION), TestWorlds.readRegion(world.resolve(REGION)).get(5).index(), payload, 1_999_999_000);
        Manifest b = create(Kind.MANUAL, "b");
        assertEquals(before + 1234, store.usage().blobBytes());
        assertEquals(1234, store.uniqueBytes(b.id()));
    }

    @Test
    void sameSecondRewriteIsNotMissed() throws Exception {
        // chunk timestamp equals the "second" of the first savepoint; same length, different content
        int ts = (int) (clock.get() / 1000) + 10;
        Path r = world.resolve(REGION);
        Chunk c0 = TestWorlds.readRegion(r).get(0);
        byte[] v1 = new byte[500], v2 = new byte[500];
        v1[0] = 1;
        v2[0] = 2;
        TestWorlds.replaceChunk(r, c0.index(), v1, ts);
        Manifest a = create(Kind.MANUAL, "a"); // created ~10 s after clock, chunk ts within guard window
        TestWorlds.replaceChunk(r, c0.index(), v2, ts);
        Manifest b = create(Kind.MANUAL, "b");
        var ca = ((Manifest.RegionEntry) a.entries().get(REGION)).chunk(c0.index());
        var cb = ((Manifest.RegionEntry) b.entries().get(REGION)).chunk(c0.index());
        assertNotEquals(ca.hash(), cb.hash());
        store.restore(world, a.id());
        assertArrayEquals(v1, TestWorlds.readRegion(r).stream().filter(c -> c.index() == c0.index()).findFirst().get().payload());
    }

    @Test
    void restoreDiffAlsoCatchesSameSecondChange() throws Exception {
        int ts = (int) (clock.get() / 1000) + 10;
        Path r = world.resolve(REGION);
        int idx = TestWorlds.readRegion(r).get(0).index();
        byte[] v1 = new byte[500], v2 = new byte[500];
        v1[0] = 1;
        v2[0] = 2;
        TestWorlds.replaceChunk(r, idx, v1, ts);
        Manifest a = create(Kind.MANUAL, "a");
        TestWorlds.replaceChunk(r, idx, v2, ts); // same ts, same size
        RestoreReport rep = store.restore(world, a.id());
        assertTrue(rep.rewrittenRegions().contains(REGION));
        assertArrayEquals(v1, TestWorlds.readRegion(r).stream().filter(c -> c.index() == idx).findFirst().get().payload());
    }

    @Test
    void stableChunksAreReusedWithoutRehash() throws Exception {
        // Documents the shortcut: an old, unchanged-header chunk is trusted, so content changed behind the
        // header's back (same ts+len, old ts) keeps the old hash.
        Path r = world.resolve(REGION);
        int idx = TestWorlds.readRegion(r).get(0).index();
        Manifest a = create(Kind.MANUAL, "a");
        int len = TestWorlds.readRegion(r).get(0).payload().length;
        TestWorlds.replaceChunk(r, idx, new byte[len], TestWorlds.OLD_TS);
        Manifest b = create(Kind.MANUAL, "b");
        assertEquals(((Manifest.RegionEntry) a.entries().get(REGION)).chunk(idx).hash(), ((Manifest.RegionEntry) b.entries().get(REGION)).chunk(idx).hash());
    }

    @Test
    void corruptBlobDetectedAndWorldUntouched() throws Exception {
        Manifest m = create(Kind.MANUAL, "a");
        var rr = (Manifest.RegionEntry) m.entries().get(REGION);
        String victim = rr.chunks().get(7).hash();
        Path blob = store.blobs().pathOf(victim);
        byte[] bytes = Files.readAllBytes(blob);
        bytes[0] ^= 0x55;
        Files.write(blob, bytes);

        var vr = store.verify(m.id(), OperationControl.NONE);
        assertFalse(vr.ok());
        assertTrue(vr.problems().get(0).contains(victim));

        TestWorlds.writeFile(world, "level.dat", new byte[] {9, 9});
        Files.delete(world.resolve("icon.png"));
        TestWorlds.replaceChunk(world.resolve(REGION), rr.chunks().get(0).index(), new byte[10], 1_999_999_999);
        var mutated = TestWorlds.fingerprint(world);
        assertThrows(CorruptBlobException.class, () -> store.restore(world, m.id()));
        assertEquals(mutated, TestWorlds.fingerprint(world));
        assertEquals(0, Files.list(storeRoot.resolve("tmp")).count());

        // a deleted blob is also reported
        Files.delete(blob);
        assertFalse(store.verify(m.id(), OperationControl.NONE).ok());
        assertThrows(CorruptBlobException.class, () -> store.restore(world, m.id()));
        assertEquals(mutated, TestWorlds.fingerprint(world));
    }

    @Test
    void blobStoreBasics() throws Exception {
        BlobStore bs = store.blobs();
        String h = bs.put("abc".getBytes());
        assertEquals(h, bs.put("abc".getBytes()));
        assertEquals(storeRoot.resolve("blobs").resolve(h.substring(0, 2)).resolve(h), bs.pathOf(h));
        assertArrayEquals("abc".getBytes(), bs.readVerified(h));
        assertEquals(1, bs.scan().size());
        assertThrows(IllegalArgumentException.class, () -> bs.pathOf("../../etc/passwd"));
    }

    @Test
    void gcRemovesOnlyUnreferencedBlobs() throws Exception {
        Manifest a = create(Kind.MANUAL, "a");
        TestWorlds.replaceChunk(world.resolve(REGION), TestWorlds.readRegion(world.resolve(REGION)).get(0).index(), new byte[321], 1_999_999_000);
        Manifest b = create(Kind.MANUAL, "b");
        String orphan = store.blobs().put("orphan".getBytes());
        var res = store.gc();
        assertEquals(1, res.blobsDeleted());
        assertFalse(store.blobs().exists(orphan));
        long freed = store.delete(b.id());
        assertEquals(321, freed);
        assertTrue(store.verify(a.id(), OperationControl.NONE).ok());
        store.delete(a.id());
        assertEquals(0, store.usage().blobCount());
        assertThrows(SavepointNotFoundException.class, () -> store.restore(world, a.id()));
    }

    @Test
    void pruneRules() throws Exception {
        List<Manifest> ms = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Kind k = i == 2 ? Kind.UNDO : (i == 5 ? Kind.UNDO : Kind.MANUAL);
            TestWorlds.writeFile(world, "extra.bin", TestWorlds.randomBytes(new Random(i), 100_000));
            ms.add(create(k, "s" + i));
        }
        store.pin(ms.get(0).id(), true);
        long usage = store.usage().total();
        assertTrue(usage > 800_000);

        // budget 0 / negative = unlimited
        assertTrue(store.prune(new PrunePolicy(0, 1)).isEmpty());

        // tiny budget: only protected survive: pinned s0, newest 2 (s7,s6), newest undo (s5)
        List<Manifest> pruned = store.prune(new PrunePolicy(1, 2));
        List<String> prunedNames = pruned.stream().map(Manifest::name).toList();
        assertEquals(List.of("s1", "s2", "s3", "s4"), prunedNames);
        List<String> left = store.list().stream().map(Manifest::name).toList();
        assertEquals(List.of("s7", "s6", "s5", "s0"), left);
        assertTrue(store.usage().total() < usage);
        for (Manifest m : store.list()) assertTrue(store.verify(m.id(), OperationControl.NONE).ok());
    }

    @Test
    void pruneStopsOnceUnderBudget() throws Exception {
        List<Manifest> ms = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            TestWorlds.writeFile(world, "extra.bin", TestWorlds.randomBytes(new Random(i), 200_000));
            ms.add(create(Kind.MANUAL, "s" + i));
        }
        long total = store.usage().total();
        long budget = total - 350_000; // need to drop at least 2 oldest (each ~200 KB unique)
        List<Manifest> pruned = store.prune(new PrunePolicy(budget, 1));
        assertEquals(List.of("s0", "s1"), pruned.stream().map(Manifest::name).toList());
        assertTrue(store.usage().total() <= budget);
        assertEquals(4, store.list().size());
    }

    @Test
    void metadataPersistsAndQuickSlotReplaces() throws Exception {
        var pos = new Manifest.PlayerPos("minecraft:the_nether", 1.5, -64, 1e3);
        clock.addAndGet(5000);
        Manifest a = store.create(world, SavepointMeta.of(Kind.MANUAL, "Hello \"quoted\" é\n").withNote("n\\t").withPlayer(pos).withThumbnail(new byte[] {1, 2, 3}));
        store.rename(a.id(), "renamed");
        store.pin(a.id(), true);
        store.setNote(a.id(), "note");
        SavepointStore reopened = new SavepointStore(storeRoot);
        Manifest r = reopened.get(a.id()).orElseThrow();
        assertEquals("renamed", r.name());
        assertEquals("note", r.note());
        assertTrue(r.pinned());
        assertEquals(pos, r.player());
        assertArrayEquals(new byte[] {1, 2, 3}, reopened.readThumbnail(a.id()).orElseThrow());
        assertEquals(a.entries(), r.entries());
        Manifest d = Manifest.fromJson(store.create(world, SavepointMeta.of(Kind.MANUAL, "Hello \"quoted\" é\n")).toJson());
        assertEquals("Hello \"quoted\" é\n", d.name());

        Manifest q1 = create(Kind.QUICK, "q1");
        Manifest q2 = create(Kind.QUICK, "q2");
        List<String> ids = store.list().stream().map(Manifest::id).toList();
        assertFalse(ids.contains(q1.id()));
        assertTrue(ids.contains(q2.id()));
    }

    @Test
    void fullBackupZipMatchesOriginalAndRetention() throws Exception {
        var before = TestWorlds.fingerprint(world);
        Manifest m = create(Kind.MANUAL, "a");
        Path zip = tmp.resolve("backups/" + ZipBackup.fileName(m));
        store.buildFullBackup(m.id(), zip, "My World", OperationControl.NONE);
        Path out = tmp.resolve("unzipped");
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path p = out.resolve(e.getName());
                Files.createDirectories(p.getParent());
                Files.copy(zis, p);
            }
        }
        Path w = out.resolve("My World");
        assertEquals(before, TestWorlds.fingerprint(w));
        // the unzipped world is a valid world: every region re-parses
        assertFalse(Files.exists(w.resolve("session.lock")));
        assertEquals(0, Files.list(zip.getParent()).filter(p -> p.toString().endsWith(".part")).count());

        // retention
        Path dir = tmp.resolve("ret");
        Files.createDirectories(dir);
        for (int i = 0; i < 5; i++) {
            Path p = dir.resolve("savepoint-" + i + ".zip");
            Files.write(p, new byte[] {1});
            Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(1_000_000L + i * 1000));
        }
        Files.write(dir.resolve("keep-me.txt"), new byte[] {1});
        List<Path> deleted = ZipBackup.retain(dir, 3);
        assertEquals(2, deleted.size());
        assertFalse(Files.exists(dir.resolve("savepoint-0.zip")));
        assertFalse(Files.exists(dir.resolve("savepoint-1.zip")));
        assertTrue(Files.exists(dir.resolve("savepoint-2.zip")));
        assertTrue(Files.exists(dir.resolve("keep-me.txt")));
    }

    @Test
    void cancellationLeavesNoSavepointAndRestoreUntouched() throws Exception {
        OperationControl cancel = new OperationControl() {
            @Override
            public boolean isCancelled() {
                return true;
            }
        };
        assertThrows(OperationCancelledException.class, () -> store.create(world, SavepointMeta.of(Kind.MANUAL, "x"), cancel));
        assertTrue(store.list().isEmpty());
        Manifest m = create(Kind.MANUAL, "a");
        TestWorlds.writeFile(world, "level.dat", new byte[] {5});
        var mutated = TestWorlds.fingerprint(world);
        assertThrows(OperationCancelledException.class, () -> store.restore(world, m.id(), cancel));
        assertEquals(mutated, TestWorlds.fingerprint(world));
    }

    @Test
    void nestedStoreIsNotCaptured() throws Exception {
        SavepointStore nested = new SavepointStore(world.resolve("savepoints-inside"), Options.defaults().withClock(clock::get));
        clock.addAndGet(10_000);
        Manifest a = nested.create(world, SavepointMeta.of(Kind.MANUAL, "a"));
        assertTrue(a.entries().keySet().stream().noneMatch(k -> k.startsWith("savepoints-inside")));
        clock.addAndGet(10_000);
        Manifest b = nested.create(world, SavepointMeta.of(Kind.MANUAL, "b"));
        assertEquals(a.entries(), b.entries());
        nested.restore(world, a.id());
        assertTrue(Files.isDirectory(world.resolve("savepoints-inside/manifests")));
    }

    @Test
    void malformedMcaFallsBackToWholeFile() throws Exception {
        TestWorlds.writeFile(world, "dimensions/minecraft/overworld/region/r.5.5.mca", new byte[] {1, 2, 3});
        Manifest m = create(Kind.MANUAL, "a");
        assertInstanceOf(Manifest.FileEntry.class, m.entries().get("dimensions/minecraft/overworld/region/r.5.5.mca"));
        Files.delete(world.resolve("dimensions/minecraft/overworld/region/r.5.5.mca"));
        store.restore(world, m.id());
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(world.resolve("dimensions/minecraft/overworld/region/r.5.5.mca")));
    }

    @Test
    void regionWriterProducesCompactValidFiles() throws Exception {
        Path f = tmp.resolve("r.0.0.mca");
        List<Chunk> cs = TestWorlds.randomChunks(1, 300, 123, 10, 12000);
        TestWorlds.writeRegion(f, cs);
        List<Chunk> back = TestWorlds.readRegion(f);
        assertEquals(cs.size(), back.size());
        for (int i = 0; i < cs.size(); i++) {
            assertEquals(cs.get(i).index(), back.get(i).index());
            assertEquals(123, back.get(i).timestamp());
            assertArrayEquals(cs.get(i).payload(), back.get(i).payload());
        }
        assertEquals(0, Files.size(f) % 4096);
        // rewriting what we read gives the identical file (writer is deterministic and compact)
        Path f2 = tmp.resolve("r2.mca");
        TestWorlds.writeRegion(f2, back);
        assertArrayEquals(Files.readAllBytes(f), Files.readAllBytes(f2));
        assertThrows(IllegalArgumentException.class, () -> TestWorlds.writeRegion(tmp.resolve("bad.mca"), List.of(cs.get(1), cs.get(0))));
    }

    @Test
    void restoreRefusesUnsafeManifestPaths() throws Exception {
        Manifest m = create(Kind.MANUAL, "a");
        Path mf = storeRoot.resolve("manifests/" + m.id() + ".json");
        Files.writeString(mf, Files.readString(mf).replace("\"level.dat\"", "\"../evil.dat\""));
        SavepointStore fresh = new SavepointStore(storeRoot);
        assertThrows(IOException.class, () -> fresh.restore(world, m.id()));
        assertFalse(Files.exists(tmp.resolve("evil.dat")));
    }
}
