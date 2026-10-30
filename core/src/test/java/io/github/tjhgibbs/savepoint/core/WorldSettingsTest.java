package io.github.tjhgibbs.savepoint.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldSettingsTest {
    @TempDir
    Path dir;

    @Test
    void worldValueOverridesGlobalDefaultOnly_forPerWorldKeys() throws IOException {
        Settings global = Settings.DEFAULTS.with("fullBackupKeep", "5").with("keepNewest", "7");
        assertEquals(5, WorldSettings.effective(global, dir).fullBackupKeep());

        WorldSettings.set(dir, "fullBackupKeep", "2");
        Settings eff = WorldSettings.effective(global, dir);
        assertEquals(2, eff.fullBackupKeep());
        assertEquals(7, eff.keepNewest(), "other settings stay global");
        assertEquals(5, global.fullBackupKeep(), "global object untouched");
        assertTrue(Files.isRegularFile(dir.resolve(WorldSettings.FILE)));

        // a different world has its own value
        Path other = dir.resolve("other");
        assertEquals(5, WorldSettings.effective(global, other).fullBackupKeep());

        WorldSettings.clear(dir, "fullBackupKeep");
        assertEquals(5, WorldSettings.effective(global, dir).fullBackupKeep());
    }

    @Test
    void rejectsBadInputAndGlobalKeys() {
        assertThrows(IllegalArgumentException.class, () -> WorldSettings.set(dir, "fullBackupKeep", "0"));
        assertThrows(IllegalArgumentException.class, () -> WorldSettings.set(dir, "fullBackupKeep", "many"));
        assertThrows(IllegalArgumentException.class, () -> WorldSettings.set(dir, "keepNewest", "4"));
        assertThrows(IllegalArgumentException.class, () -> WorldSettings.set(dir, "nope", "4"));
        assertFalse(Files.exists(dir.resolve(WorldSettings.FILE)));
    }

    @Test
    void garbageFileFallsBackToGlobal() throws IOException {
        Files.writeString(dir.resolve(WorldSettings.FILE), "fullBackupKeep=banana\nkeepNewest=99\n");
        Settings eff = WorldSettings.effective(Settings.DEFAULTS, dir);
        assertEquals(3, eff.fullBackupKeep());
        assertEquals(3, eff.keepNewest(), "global-only keys are never read from the world file");
    }

    @Test
    void onlyFullBackupKeepIsPerWorld() {
        for (Settings.Spec sp : Settings.specs()) assertEquals(sp.key().equals("fullBackupKeep"), sp.perWorld(), sp.key());
    }
}
