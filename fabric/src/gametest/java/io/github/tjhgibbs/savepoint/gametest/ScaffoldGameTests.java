package io.github.tjhgibbs.savepoint.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Placeholder so the gametest source set has an entrypoint. */
public class ScaffoldGameTests {
    @GameTest(structure = "savepoint-gametest:empty64", maxTicks = 20)
    public void scaffoldLoads(GameTestHelper h) {
        h.succeed();
    }
}
