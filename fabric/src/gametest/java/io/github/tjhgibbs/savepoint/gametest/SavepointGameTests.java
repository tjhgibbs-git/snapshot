package io.github.tjhgibbs.savepoint.gametest;

import io.github.tjhgibbs.savepoint.core.Manifest;
import io.github.tjhgibbs.savepoint.mod.SavepointService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Creates a real savepoint of the gametest server's world and checks the store holds it. */
public class SavepointGameTests {
    @GameTest(structure = "savepoint-gametest:empty64", maxTicks = 200)
    public void createSavepointOnTestWorld(GameTestHelper h) {
        try {
            Path root = Files.createTempDirectory("savepoint-gametest");
            SavepointService svc = SavepointService.startForTesting(h.getLevel().getServer(), root);
            Manifest m = svc.createUndoPoint(null);
            if (svc.store().get(m.id()).isEmpty()) h.fail("savepoint missing from store");
            if (svc.store().list().size() != 1) h.fail("expected exactly one savepoint");
            if (!m.entries().containsKey("level.dat")) h.fail("level.dat not captured: " + m.entries().keySet());
            if (!Files.isRegularFile(root.resolve("manifests").resolve(m.id() + ".json"))) h.fail("manifest file missing");
            Manifest again = svc.createUndoPoint(null);
            if (again.id().equals(m.id())) h.fail("ids must differ");
            h.succeed();
        } catch (IOException e) {
            h.fail("create failed: " + e);
        }
    }
}
