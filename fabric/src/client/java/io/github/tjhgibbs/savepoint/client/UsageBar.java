package io.github.tjhgibbs.savepoint.client;

import io.github.tjhgibbs.savepoint.mod.SavepointPayloads.ListPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** "Using 1.2 GB of 10 GB" with a bar that turns yellow, then red, as the disk budget fills up. */
final class UsageBar {
    static final int HEIGHT = 5;

    private UsageBar() {}

    static Component text(ListPayload l) {
        if (l == null) return Component.translatable("savepoint.usage.unknown");
        if (l.budgetBytes() <= 0) return Component.translatable("savepoint.usage.unlimited", ClientFmt.bytes(l.usedBytes()));
        int pct = (int) Math.min(999, l.usedBytes() * 100 / l.budgetBytes());
        return Component.translatable("savepoint.usage", ClientFmt.bytes(l.usedBytes()), ClientFmt.bytes(l.budgetBytes()), pct);
    }

    static int textColor(ListPayload l) {
        return l != null && l.budgetBytes() > 0 && l.usedBytes() * 10 >= l.budgetBytes() * 9 ? 0xFFFF7070 : 0xFFD0D0D0;
    }

    /** Draws the text on the left of the row and, under it, the bar. Needs {@link #HEIGHT} + 12 pixels. */
    static void draw(GuiGraphicsExtractor g, Font font, ListPayload l, int x, int y, int w, int maxTextWidth) {
        Component t = text(l);
        String s = font.width(t) > maxTextWidth ? font.plainSubstrByWidth(t.getString(), maxTextWidth - font.width("…")) + "…" : t.getString();
        g.text(font, s, x, y, textColor(l));
        int by = y + 11;
        g.fill(x, by, x + w, by + HEIGHT, 0xFF000000);
        g.fill(x + 1, by + 1, x + w - 1, by + HEIGHT - 1, 0xFF303030);
        if (l != null && l.budgetBytes() > 0) {
            double f = Math.min(1.0, (double) l.usedBytes() / l.budgetBytes());
            int color = f >= 0.9 ? 0xFFE05050 : f >= 0.7 ? 0xFFE0C040 : 0xFF55BB55;
            g.fill(x + 1, by + 1, x + 1 + (int) ((w - 2) * f), by + HEIGHT - 1, color);
        }
    }
}
