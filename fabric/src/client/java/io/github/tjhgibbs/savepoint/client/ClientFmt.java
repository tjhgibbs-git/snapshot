package io.github.tjhgibbs.savepoint.client;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/** Text formatting for the UI (sizes, relative times). */
final class ClientFmt {
    private ClientFmt() {}

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

    /** "just now", "3 min ago", "2 h ago", "5 d ago". */
    static Component age(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 10) return Component.translatable("savepoint.age.now");
        if (s < 60) return Component.translatable("savepoint.age.seconds", s);
        if (s < 3600) return Component.translatable("savepoint.age.minutes", s / 60);
        if (s < 86400) return Component.translatable("savepoint.age.hours", s / 3600);
        return Component.translatable("savepoint.age.days", s / 86400);
    }

    /** Seconds with one decimal under 10 s ("0.4 s"), else whole ("12 s"). */
    static String seconds(long millis) {
        return millis < 10_000 ? String.format(Locale.ROOT, "%.1f s", millis / 1000.0) : (millis / 1000) + " s";
    }
}
