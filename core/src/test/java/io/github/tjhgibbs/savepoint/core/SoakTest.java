package io.github.tjhgibbs.savepoint.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.tjhgibbs.savepoint.core.Manifest.Kind;
import io.github.tjhgibbs.savepoint.core.SavepointStore.Options;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The playtest loop the mod exists for, many times over: play (mutate), revert (undo point + restore), repeat. Each
 * cycle the world must match the savepoint exactly, and replaying the same playtest must not grow the store beyond
 * the small manifest of each new undo point.
 */
class SoakTest {
    static final String REGION = "dimensions/minecraft/overworld/region/r.0.0.mca";
    static final int CYCLES = 20;

    @TempDir Path tmp;

    @Test
    void createMutateRevertCycles() throws Exception {
        AtomicLong clock = new AtomicLong(2_000_000_000_000L);
        Path world = TestWorlds.makeWorld(tmp.resolve("world"), 7);
        SavepointStore store = new SavepointStore(tmp.resolve("store"), Options.defaults().withClock(clock::get));
        clock.addAndGet(10_000);
        Manifest base = store.create(world, SavepointMeta.of(Kind.MANUAL, "base"));
        var expected = TestWorlds.fingerprint(world);

        Random r = new Random(1);
        byte[] playtestChunk = TestWorlds.randomBytes(r, 3000);
        byte[] playtestLevel = TestWorlds.randomBytes(r, 200);
        int chunkIndex = TestWorlds.readRegion(world.resolve(REGION)).get(1).index();

        long afterFirst = -1;
        long manifestGrowth = 0;
        for (int i = 0; i < CYCLES; i++) {
            // "play": the same edits every time, as when retrying one build or fight; the game stamps a new time
            clock.addAndGet(60_000);
            TestWorlds.replaceChunk(world.resolve(REGION), chunkIndex, playtestChunk, (int) (clock.get() / 1000));
            TestWorlds.writeFile(world, "level.dat", playtestLevel);
            TestWorlds.writeFile(world, "playerdata/p.dat", ("cycle " + i).getBytes());
            assertNotEquals(expected, TestWorlds.fingerprint(world));

            // "revert": undo point first, then restore
            clock.addAndGet(10_000);
            long before = store.usage().total();
            store.create(world, SavepointMeta.of(Kind.UNDO, "Before revert"));
            store.restore(world, base.id());
            assertEquals(expected, TestWorlds.fingerprint(world), "cycle " + i + ": world differs from the savepoint");

            if (i == 0) afterFirst = store.usage().total();
            else manifestGrowth = Math.max(manifestGrowth, store.usage().total() - before);
        }
        var usage = store.usage();
        assertEquals(CYCLES + 1, usage.manifestCount());
        // Per later cycle only the manifest and the tiny per-cycle player file are new.
        assertTrue(manifestGrowth < 8 * 1024, "store grew " + manifestGrowth + " bytes in one repeated cycle");
        assertTrue(usage.total() - afterFirst < (CYCLES - 1) * 8 * 1024L, "store grew too much: " + (usage.total() - afterFirst));
        assertTrue(store.verify(base.id(), OperationControl.NONE).ok());

        // A tiny budget prunes everything except the newest savepoint (which is also the newest undo point).
        store.prune(new SavepointStore.PrunePolicy(1, 1));
        assertEquals(1, store.list().size());
        assertEquals(Kind.UNDO, store.list().getFirst().kind(), "newest undo point is always kept");
        assertTrue(store.usage().total() < usage.total(), "pruning freed space");
    }
}
