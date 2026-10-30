package io.github.tjhgibbs.savepoint.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Immutable user settings plus the key table (name, plain-English description, validation) the commands and
 * the future settings screen are both driven from. Persisting is a plain {@code .properties} file.
 */
public record Settings(
        double storeBudgetGb, int keepNewest, int cooldownSeconds, FullBackupMode fullBackupMode, int fullBackupKeep) {

    public enum FullBackupMode {
        OFF,
        SESSION,
        EVERY
    }

    public static final Settings DEFAULTS = new Settings(10, 3, 10, FullBackupMode.SESSION, 3);

    /** One configurable key. {@code get} renders the value, {@code set} returns a new Settings or throws IllegalArgumentException. */
    public record Spec(String key, String description, String valueHint, Function<Settings, String> get,
                       BiFunction<Settings, String, Settings> set) {}

    private static final List<Spec> SPECS = List.of(
            new Spec("storeBudgetGb",
                    "Disk space savepoints may use, in GB. Oldest unpinned savepoints are deleted past this. 0 = unlimited.",
                    "0 - 100000",
                    s -> trim(s.storeBudgetGb),
                    (s, v) -> new Settings(parseDouble(v, 0, 100000), s.keepNewest, s.cooldownSeconds, s.fullBackupMode, s.fullBackupKeep)),
            new Spec("keepNewest",
                    "How many of the newest savepoints are never deleted automatically.",
                    "0 - 1000",
                    s -> Integer.toString(s.keepNewest),
                    (s, v) -> new Settings(s.storeBudgetGb, parseInt(v, 0, 1000), s.cooldownSeconds, s.fullBackupMode, s.fullBackupKeep)),
            new Spec("cooldownSeconds",
                    "Minimum seconds between taking savepoints (and between reverts). 0 = no limit.",
                    "0 - 3600",
                    s -> Integer.toString(s.cooldownSeconds),
                    (s, v) -> new Settings(s.storeBudgetGb, s.keepNewest, parseInt(v, 0, 3600), s.fullBackupMode, s.fullBackupKeep)),
            new Spec("fullBackupMode",
                    "Full world backup zips: OFF = never offered, SESSION = offered once per play session, EVERY = automatic after every savepoint.",
                    "OFF | SESSION | EVERY",
                    s -> s.fullBackupMode.name(),
                    (s, v) -> new Settings(s.storeBudgetGb, s.keepNewest, s.cooldownSeconds, parseMode(v), s.fullBackupKeep)),
            new Spec("fullBackupKeep",
                    "How many full backup zips to keep; older ones are deleted.",
                    "1 - 100",
                    s -> Integer.toString(s.fullBackupKeep),
                    (s, v) -> new Settings(s.storeBudgetGb, s.keepNewest, s.cooldownSeconds, s.fullBackupMode, parseInt(v, 1, 100))));

    public static List<Spec> specs() {
        return SPECS;
    }

    public static Spec spec(String key) {
        for (Spec sp : SPECS) if (sp.key().equalsIgnoreCase(key)) return sp;
        return null;
    }

    /** @throws IllegalArgumentException unknown key or invalid value (message is user-presentable) */
    public Settings with(String key, String value) {
        Spec sp = spec(key);
        if (sp == null) throw new IllegalArgumentException("Unknown setting: " + key);
        return sp.set().apply(this, value == null ? "" : value.strip());
    }

    public String get(String key) {
        Spec sp = spec(key);
        if (sp == null) throw new IllegalArgumentException("Unknown setting: " + key);
        return sp.get().apply(this);
    }

    /** Budget in bytes; 0 means unlimited. */
    public long storeBudgetBytes() {
        return (long) (storeBudgetGb * 1024.0 * 1024.0 * 1024.0);
    }

    /** Loads leniently: missing or invalid entries fall back to defaults. */
    public static Settings load(Reader in) throws IOException {
        Properties p = new Properties();
        p.load(in);
        Settings s = DEFAULTS;
        for (Spec sp : SPECS) {
            String v = p.getProperty(sp.key());
            if (v == null) continue;
            try {
                s = sp.set().apply(s, v.strip());
            } catch (IllegalArgumentException ignored) {
                // keep default
            }
        }
        return s;
    }

    public void store(Writer out) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Savepoint settings. Edit here or in the in-game settings screen.");
        for (Spec sp : SPECS) {
            lines.add("");
            lines.add("# " + sp.description() + " (" + sp.valueHint() + ")");
            lines.add(sp.key() + "=" + sp.get().apply(this));
        }
        out.write(String.join("\n", lines) + "\n");
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private static int parseInt(String v, int min, int max) {
        try {
            int i = Integer.parseInt(v);
            if (i < min || i > max) throw new IllegalArgumentException("Must be between " + min + " and " + max);
            return i;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a whole number: " + v);
        }
    }

    private static double parseDouble(String v, double min, double max) {
        try {
            double d = Double.parseDouble(v);
            if (Double.isNaN(d) || d < min || d > max) throw new IllegalArgumentException("Must be between " + trim(min) + " and " + trim(max));
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number: " + v);
        }
    }

    private static FullBackupMode parseMode(String v) {
        try {
            return FullBackupMode.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Must be OFF, SESSION or EVERY");
        }
    }
}
