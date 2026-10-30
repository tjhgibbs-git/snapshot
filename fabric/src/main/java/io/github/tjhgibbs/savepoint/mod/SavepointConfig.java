package io.github.tjhgibbs.savepoint.mod;

import io.github.tjhgibbs.savepoint.core.Settings;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Holder for the current {@link Settings}, backed by {@code config/savepoint.properties}. Safe to use from the
 * client (settings screen) and the server. {@link Settings#specs()} has the keys and one-line descriptions.
 */
public final class SavepointConfig {
    private static volatile Settings current = Settings.DEFAULTS;

    private SavepointConfig() {}

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("savepoint.properties");
    }

    public static Settings get() {
        return current;
    }

    /** Replaces the settings in memory only (e.g. a client mirror of what the server reported). */
    public static void setInMemory(Settings s) {
        current = s;
    }

    /** Replaces and saves. */
    public static void set(Settings s) {
        current = s;
        save();
    }

    public static synchronized void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) {
            save();
            return;
        }
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            current = Settings.load(r);
        } catch (IOException e) {
            SavepointMod.LOG.warn("Could not read {}: {}", f, e.toString());
        }
    }

    public static synchronized void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                current.store(w);
            }
        } catch (IOException e) {
            SavepointMod.LOG.warn("Could not write {}: {}", f, e.toString());
        }
    }
}
