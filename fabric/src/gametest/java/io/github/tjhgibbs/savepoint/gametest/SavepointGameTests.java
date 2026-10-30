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
            Manifest m = svc.createUndoPoint(null, "Target");
            if (svc.store().get(m.id()).isEmpty()) h.fail("savepoint missing from store");
            if (svc.store().list().size() != 1) h.fail("expected exactly one savepoint");
            if (!m.entries().containsKey("level.dat")) h.fail("level.dat not captured: " + m.entries().keySet());
            if (!Files.isRegularFile(root.resolve("manifests").resolve(m.id() + ".json"))) h.fail("manifest file missing");
            Manifest again = svc.createUndoPoint(null, "Target");
            if (again.id().equals(m.id())) h.fail("ids must differ");
            if (!m.name().equals("Before revert \u2192 Target")) h.fail("undo point should name its target, got " + m.name());
            // Per-world setting: written to this world's store, not the global config.
            io.github.tjhgibbs.savepoint.core.WorldSettings.set(root, "fullBackupKeep", "7");
            if (svc.settings().fullBackupKeep() != 7) h.fail("per-world fullBackupKeep not in effect");
            var payload = svc.buildListPayload();
            int idx = io.github.tjhgibbs.savepoint.core.Settings.specs().indexOf(io.github.tjhgibbs.savepoint.core.Settings.spec("fullBackupKeep"));
            if (!payload.settingValues().get(idx).equals("7")) h.fail("list payload must carry the per-world value");
            h.succeed();
        } catch (IOException e) {
            h.fail("create failed: " + e);
        }
    }
}
