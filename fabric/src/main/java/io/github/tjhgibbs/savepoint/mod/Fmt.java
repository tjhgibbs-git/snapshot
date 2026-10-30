package io.github.tjhgibbs.savepoint.mod;

import java.util.Locale;

/** Small text formatting helpers. */
final class Fmt {
    private Fmt() {}

    static String bytes(long b) {
        if (b < 1024) return b + " B";
        double v = b;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    static String age(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 60) return s + "s ago";
        if (s < 3600) return s / 60 + "m ago";
        if (s < 86400) return s / 3600 + "h ago";
        return s / 86400 + "d ago";
    }

    static String percent(long done, long total) {
        return total <= 0 ? "0%" : Math.min(100, done * 100 / total) + "%";
    }
}
