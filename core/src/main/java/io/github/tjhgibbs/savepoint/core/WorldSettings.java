package io.github.tjhgibbs.savepoint.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Per-world overrides of the {@link Settings} flagged {@code perWorld}, stored as {@code <store>/settings.properties}.
 * A world without the file (or without a key) uses the global value. Everything else in {@link Settings} stays global.
 */
public final class WorldSettings {
    public static final String FILE = "settings.properties";

    private WorldSettings() {}

    /** The global settings with this world's overrides applied. Missing or invalid entries are ignored. */
    public static Settings effective(Settings global, Path storeRoot) {
        Properties p = read(storeRoot);
        Settings s = global;
        for (Settings.Spec sp : Settings.specs()) {
            String v = sp.perWorld() ? p.getProperty(sp.key()) : null;
            if (v == null) continue;
            try {
                s = sp.set().apply(s, v.strip());
            } catch (IllegalArgumentException ignored) {
                // hand-edited garbage: fall back to the global value
            }
        }
        return s;
    }

    /**
     * Validates and stores one per-world value.
     * @throws IllegalArgumentException unknown key, not a per-world key, or invalid value (message is user-presentable)
     */
    public static void set(Path storeRoot, String key, String value) throws IOException {
        Settings.Spec sp = Settings.spec(key);
        if (sp == null) throw new IllegalArgumentException("Unknown setting: " + key);
        if (!sp.perWorld()) throw new IllegalArgumentException(key + " is not a per-world setting");
        String canonical = sp.get().apply(Settings.DEFAULTS.with(key, value));
        Properties p = read(storeRoot);
        p.setProperty(sp.key(), canonical);
        write(storeRoot, p);
    }

    /** Drops a per-world value so the world follows the global one again. */
    public static void clear(Path storeRoot, String key) throws IOException {
        Settings.Spec sp = Settings.spec(key);
        Properties p = read(storeRoot);
        if (p.remove(sp != null ? sp.key() : key) != null) write(storeRoot, p);
    }

    private static Properties read(Path storeRoot) {
        Properties p = new Properties();
        Path f = storeRoot.resolve(FILE);
        if (!Files.isRegularFile(f)) return p;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return new Properties();
        }
        return p;
    }

    private static void write(Path storeRoot, Properties p) throws IOException {
        Files.createDirectories(storeRoot);
        Path tmp = storeRoot.resolve(FILE + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "Savepoint settings for this world (override the global defaults)");
        }
        Files.move(tmp, storeRoot.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
    }
}
