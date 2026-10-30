package io.github.tjhgibbs.savepoint.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.tjhgibbs.savepoint.core.Manifest.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the store against a COPY of a real Minecraft world (the original is only read). Skipped if absent. */
class RealWorldTest {
    static final Path SRC = Path.of("/mnt/c/Users/tjhgi/AppData/Roaming/PrismLauncher/instances/26.3/minecraft/saves/Mods Demo World");

    @TempDir Path tmp;

    @Test
    void realWorldRoundTrip() throws Exception {
        assumeTrue(Files.isDirectory(SRC), "real world not available: " + SRC);
        Path world = tmp.resolve("world");
        TestWorlds.copyTree(SRC, world);
        var before = TestWorlds.fingerprint(world);
        long mcaCount = before.keySet().stream().filter(k -> k.endsWith(".mca")).count();
        SavepointStore store = new SavepointStore(tmp.resolve("store"));

        long t0 = System.nanoTime();
        Manifest a = store.create(world, SavepointMeta.of(Kind.MANUAL, "real"));
        long tCreate = (System.nanoTime() - t0) / 1_000_000;
        long regions = a.entries().values().stream().filter(e -> e instanceof Manifest.RegionEntry).count();
        assertEquals(mcaCount, regions, "every real .mca must parse as a region");
        assertTrue(store.verify(a.id(), OperationControl.NONE).ok());

        t0 = System.nanoTime();
        Manifest b = store.create(world, SavepointMeta.of(Kind.MANUAL, "real2"));
        long tSecond = (System.nanoTime() - t0) / 1_000_000;
        long blobBytes = store.usage().blobBytes();
        assertEquals(0, store.uniqueBytes(b.id()));

        // mutate: damage a few files and a region, then restore
        int n = 0;
        for (String rel : before.keySet()) {
            Path p = world.resolve(rel);
            if (rel.endsWith(".mca") && n++ < 2) {
                List<TestWorlds.Chunk> cs = TestWorlds.readRegion(p);
                if (!cs.isEmpty()) TestWorlds.replaceChunk(p, cs.get(0).index(), new byte[50], 2_000_000_000);
            }
        }
        Files.write(world.resolve("level.dat"), new byte[] {1});
        TestWorlds.deleteTree(world.resolve("players"));
        TestWorlds.writeFile(world, "stray/file.txt", new byte[] {7});
        assertNotEquals(before, TestWorlds.fingerprint(world));

        t0 = System.nanoTime();
        var rep = store.restore(world, a.id());
        long tRestore = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(before, TestWorlds.fingerprint(world));

        Path zip = tmp.resolve("full.zip");
        t0 = System.nanoTime();
        store.buildFullBackup(a.id(), zip, "Mods Demo World", OperationControl.NONE);
        long tZip = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(Files.size(zip) > 0);

        System.out.printf("[real world] %d files, %d MB logical, %d regions: create %d ms, 2nd create %d ms, restore %d ms (%d regions rewritten, %d files), zip %d ms (%d MB), store %d MB%n",
                a.entries().size(), a.logicalBytes() >> 20, regions, tCreate, tSecond, tRestore, rep.rewrittenRegions().size(),
                rep.restoredFiles().size(), tZip, Files.size(zip) >> 20, blobBytes >> 20);
    }
}
