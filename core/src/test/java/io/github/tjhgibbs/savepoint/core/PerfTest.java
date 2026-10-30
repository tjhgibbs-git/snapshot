package io.github.tjhgibbs.savepoint.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.tjhgibbs.savepoint.core.Manifest.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Only runs with {@code -Pperf}: builds a ~1 GB synthetic world and prints timings. */
@Tag("perf")
class PerfTest {
    @TempDir Path tmp;

    @Test
    void oneGigabyteWorld() throws Exception {
        Path world = tmp.resolve("world");
        Random r = new Random(1);
        long t0 = System.nanoTime();
        int regions = 48;
        for (int i = 0; i < regions; i++) {
            Path f = world.resolve("dimensions/minecraft/overworld/region/r." + (i % 8) + "." + (i / 8) + ".mca");
            // 1024 chunks of ~20 KB random (incompressible) bytes = ~20 MB per region
            List<TestWorlds.Chunk> cs = new java.util.ArrayList<>();
            for (int c = 0; c < 1024; c++) cs.add(new TestWorlds.Chunk(c, TestWorlds.OLD_TS, 2, TestWorlds.randomBytes(r, 16_000 + r.nextInt(8000))));
            TestWorlds.writeRegion(f, cs);
        }
        TestWorlds.writeFile(world, "level.dat", TestWorlds.randomBytes(r, 5000));
        long gen = (System.nanoTime() - t0) / 1_000_000;
        SavepointStore store = new SavepointStore(tmp.resolve("store"));

        t0 = System.nanoTime();
        Manifest a = store.create(world, SavepointMeta.of(Kind.MANUAL, "a"));
        long tCreate = (System.nanoTime() - t0) / 1_000_000;
        t0 = System.nanoTime();
        Manifest b = store.create(world, SavepointMeta.of(Kind.MANUAL, "b"));
        long tSecond = (System.nanoTime() - t0) / 1_000_000;

        Path f0 = world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca");
        for (int i = 0; i < 20; i++) TestWorlds.replaceChunk(f0, i, TestWorlds.randomBytes(r, 5000), 2_000_000_000);
        Files.write(world.resolve("level.dat"), new byte[] {1});
        t0 = System.nanoTime();
        var rep = store.restore(world, a.id());
        long tRestore = (System.nanoTime() - t0) / 1_000_000;

        t0 = System.nanoTime();
        store.buildFullBackup(a.id(), tmp.resolve("full.zip"), "world", OperationControl.NONE);
        long tZip = (System.nanoTime() - t0) / 1_000_000;
        t0 = System.nanoTime();
        assertTrue(store.verify(a.id(), OperationControl.NONE).ok());
        long tVerify = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(0, store.uniqueBytes(b.id()));
        System.out.printf("[perf] %d MB world (gen %d ms): create %d ms, unchanged 2nd create %d ms, restore(1 region+file) %d ms [%d regions rewritten], zip %d ms, verify %d ms, store %d MB%n",
                a.logicalBytes() >> 20, gen, tCreate, tSecond, tRestore, rep.rewrittenRegions().size(), tZip, tVerify, store.usage().total() >> 20);
    }
}
