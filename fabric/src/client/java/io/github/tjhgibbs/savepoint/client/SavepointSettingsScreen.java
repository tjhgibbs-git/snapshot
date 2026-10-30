package io.github.tjhgibbs.savepoint.client;

import io.github.tjhgibbs.savepoint.core.Settings;
import io.github.tjhgibbs.savepoint.core.Settings.FullBackupMode;
import io.github.tjhgibbs.savepoint.mod.SavepointPayloads;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Settings, built from {@link Settings#specs()}: one row per key with a name, the control, a Reset button, a
 * plain-English description and the default. Numbers are stepped sliders (a value table that always contains the
 * current value) except the disk budget, which is a number box. Changes go to the server as ConfigSet payloads
 * (after a short pause while dragging/typing), so the config file / world file and the server stay the single
 * source of truth. The one per-world setting sits under its own heading and says so.
 */
final class SavepointSettingsScreen extends Screen {
    private static final int PAD = 6;
    private static final int MAX_W = 380;
    private static final int RESET_W = 46;
    private static final int SCROLLBAR = 4;
    private static final long SEND_DELAY_MS = 350;

    private static final Map<String, int[]> STOPS = Map.of(
            "keepNewest", new int[] {0, 1, 2, 3, 4, 5, 6, 8, 10, 15, 20, 30, 50, 100},
            "cooldownSeconds", new int[] {0, 1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 120, 300, 600, 1800, 3600},
            "fullBackupKeep", new int[] {1, 2, 3, 4, 5, 6, 8, 10, 15, 20, 50, 100});

    /** A heading or one setting. */
    private sealed interface Item permits Heading, Row {
        int height();
    }

    private record Heading(Component text) implements Item {
        public int height() {
            return 16;
        }
    }

    private final class Row implements Item {
        final Settings.Spec spec;
        AbstractWidget control;
        Button reset;
        Runnable setToDefault;
        List<FormattedCharSequence> desc = List.of();
        int descHeight;

        Row(Settings.Spec spec) {
            this.spec = spec;
        }

        Component title() {
            return Component.translatableWithFallback("savepoint.setting." + spec.key(), spec.key());
        }

        Component description() {
            String key = "savepoint.setting." + spec.key() + ".desc";
            if (spec.key().equals("fullBackupMode")) key += "." + current.fullBackupMode().name().toLowerCase(Locale.ROOT);
            return Component.translatableWithFallback(key, spec.description());
        }

        Component defaultText() {
            return Component.translatable("savepoint.settings.default", format(spec.key(), Settings.DEFAULTS.get(spec.key())));
        }

        public int height() {
            return 12 + 20 + 3 + descHeight + 10 + 8;
        }
    }

    private final Screen parent;
    private Settings current;
    private final List<Item> items = new ArrayList<>();
    private final Map<String, Long> pendingSendAt = new LinkedHashMap<>();
    private int scroll;
    private int left, contentW, viewTop, viewBottom;
    private boolean draggingScrollbar;

    SavepointSettingsScreen(Screen parent) {
        super(Component.translatable("savepoint.settings.title"));
        this.parent = parent;
        this.current = ClientState.settings();
    }

    // ---- building the rows ---------------------------------------------------------------------------------------

    @Override
    protected void init() {
        items.clear();
        contentW = Math.min(width - PAD * 2, MAX_W);
        left = (width - contentW) / 2;
        viewTop = 46;
        viewBottom = height - 30;
        int textW = contentW - SCROLLBAR - 6;

        for (boolean world : new boolean[] {false, true}) {
            if (Settings.specs().stream().noneMatch(sp -> sp.perWorld() == world)) continue;
            items.add(new Heading(world ? Component.translatable("savepoint.settings.heading_world", worldName())
                    : Component.translatable("savepoint.settings.heading_global")));
            for (Settings.Spec spec : Settings.specs()) {
                if (spec.perWorld() != world) continue;
                Row row = new Row(spec);
                buildControls(row, textW);
                items.add(row);
            }
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 75, height - 26, 150, 20).build());
        refreshDescriptions(textW);
        layoutRows();
    }

    private String worldName() {
        var l = ClientState.list();
        return l == null ? "" : l.worldName();
    }

    private void buildControls(Row row, int textW) {
        String key = row.spec.key();
        int cw = contentW - SCROLLBAR - 4 - RESET_W - 4;
        AbstractWidget control;
        if (key.equals("fullBackupMode")) {
            CycleButton<FullBackupMode> cycle = CycleButton.<FullBackupMode>builder(m -> format(key, m.name()), current.fullBackupMode())
                    .withValues(FullBackupMode.values())
                    .displayOnlyValue()
                    .create(0, 0, cw, 20, row.title(), (btn, v) -> {
                        changed(key, v.name());
                        refreshDescriptions(textW);
                        layoutRows();
                    });
            row.setToDefault = () -> cycle.setValue(Settings.DEFAULTS.fullBackupMode());
            control = cycle;
        } else if (STOPS.containsKey(key)) {
            StepSlider slider = new StepSlider(cw, stopsFor(key), Integer.parseInt(current.get(key)), key);
            row.setToDefault = () -> slider.setTo(Integer.parseInt(Settings.DEFAULTS.get(key)));
            control = slider;
        } else {
            EditBox box = new EditBox(font, 0, 0, cw, 20, row.title());
            box.setMaxLength(12);
            box.setValue(current.get(key));
            box.setResponder(text -> {
                try {
                    current.with(key, text);
                    box.setTextColor(0xFFE0E0E0);
                    box.setTooltip(null);
                    changed(key, text);
                } catch (IllegalArgumentException e) {
                    box.setTextColor(0xFFFF5555);
                    box.setTooltip(Tooltip.create(Component.literal(e.getMessage())));
                }
            });
            row.setToDefault = () -> box.setValue(Settings.DEFAULTS.get(key));
            control = box;
        }
        row.control = addRenderableWidget(control);
        row.reset = addRenderableWidget(Button.builder(Component.translatable("savepoint.settings.reset"), b -> {
            row.setToDefault.run();
            changed(key, Settings.DEFAULTS.get(key));
            flushNow(key);
        }).bounds(0, 0, RESET_W, 20).tooltip(Tooltip.create(Component.translatable("savepoint.settings.reset_tip",
                format(key, Settings.DEFAULTS.get(key))))).build());
    }

    private int[] stopsFor(String key) {
        int[] base = STOPS.get(key);
        int cur = Integer.parseInt(current.get(key));
        for (int v : base) if (v == cur) return base;
        int[] out = new int[base.length + 1];
        System.arraycopy(base, 0, out, 0, base.length);
        out[base.length] = cur;
        java.util.Arrays.sort(out);
        return out;
    }

    private void refreshDescriptions(int textW) {
        for (Item it : items) {
            if (it instanceof Row r) {
                r.desc = font.split(r.description(), textW);
                r.descHeight = r.desc.size() * 10;
            }
        }
    }

    // ---- value changes -------------------------------------------------------------------------------------------

    /** Remember the new value now, tell the server shortly (so a slider drag is one message, not fifty). */
    private void changed(String key, String value) {
        try {
            current = current.with(key, value);
        } catch (IllegalArgumentException e) {
            return;
        }
        pendingSendAt.put(key, System.currentTimeMillis() + SEND_DELAY_MS);
    }

    private void flushNow(String key) {
        pendingSendAt.put(key, 0L);
    }

    private void sendDue(boolean all) {
        if (pendingSendAt.isEmpty()) return;
        long now = System.currentTimeMillis();
        var it = pendingSendAt.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (all || e.getValue() <= now) {
                if (ClientPlayNetworking.canSend(SavepointPayloads.ConfigSetPayload.TYPE)) {
                    ClientPlayNetworking.send(new SavepointPayloads.ConfigSetPayload(e.getKey(), current.get(e.getKey())));
                }
                it.remove();
            }
        }
    }

    // ---- display text of values -----------------------------------------------------------------------------------

    /** Human text of a canonical value ("0" budget -> "Unlimited"). */
    static Component format(String key, String value) {
        return switch (key) {
            case "storeBudgetGb" -> value.equals("0") ? Component.translatable("savepoint.value.unlimited") : Component.translatable("savepoint.value.gb", value);
            case "keepNewest" -> Component.translatable("savepoint.value.count", value);
            case "cooldownSeconds" -> value.equals("0") ? Component.translatable("savepoint.value.off") : Component.translatable("savepoint.value.seconds", value);
            case "fullBackupMode" -> Component.translatable("savepoint.value.mode." + value.toLowerCase(Locale.ROOT));
            case "fullBackupKeep" -> Component.translatable("savepoint.value.backups", value);
            default -> Component.literal(value);
        };
    }

    /** Slider over a table of int stops. */
    private final class StepSlider extends AbstractSliderButton {
        private final int[] stops;
        private final String key;

        StepSlider(int w, int[] stops, int currentValue, String key) {
            super(0, 0, w, 20, Component.empty(), 0);
            this.stops = stops;
            this.key = key;
            this.value = indexOf(currentValue) / (double) (stops.length - 1);
            updateMessage();
        }

        private int indexOf(int v) {
            int best = 0;
            for (int i = 0; i < stops.length; i++) if (Math.abs(stops[i] - v) < Math.abs(stops[best] - v)) best = i;
            return best;
        }

        private int index() {
            return (int) Math.round(value * (stops.length - 1));
        }

        void setTo(int v) {
            this.value = indexOf(v) / (double) (stops.length - 1);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(format(key, Integer.toString(stops[index()])));
        }

        @Override
        protected void applyValue() {
            changed(key, Integer.toString(stops[index()]));
        }
    }

    // ---- layout & scrolling -----------------------------------------------------------------------------------------

    private int contentHeight() {
        int h = 0;
        for (Item it : items) h += it.height();
        return h;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - (viewBottom - viewTop));
    }

    private void layoutRows() {
        scroll = Math.clamp(scroll, 0, maxScroll());
        int y = viewTop - scroll;
        for (Item it : items) {
            if (it instanceof Row r) {
                r.control.setX(left);
                r.control.setY(y + 12);
                r.reset.setX(left + contentW - SCROLLBAR - 4 - RESET_W);
                r.reset.setY(y + 12);
                boolean vis = y >= viewTop - 1 && y + 12 + 20 <= viewBottom + 1;
                r.control.visible = vis;
                r.reset.visible = vis;
            }
            y += it.height();
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (y >= viewTop && y < viewBottom && maxScroll() > 0) {
            // The wheel always scrolls the page; it never cycles a button the page scrolls past.
            scroll = Math.clamp(scroll - (int) Math.round(scrollY * 20), 0, maxScroll());
            layoutRows();
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (maxScroll() > 0 && event.x() >= left + contentW - SCROLLBAR - 1 && event.x() < left + contentW + 3
                && event.y() >= viewTop && event.y() < viewBottom) {
            draggingScrollbar = true;
            dragTo(event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void dragTo(double my) {
        int track = viewBottom - viewTop;
        int thumb = Math.max(12, track * track / (track + maxScroll()));
        double f = (my - viewTop - thumb / 2.0) / Math.max(1, track - thumb);
        scroll = (int) Math.round(Math.clamp(f, 0, 1) * maxScroll());
        layoutRows();
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (draggingScrollbar) {
            dragTo(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    @Override
    public void tick() {
        super.tick();
        sendDue(false);
    }

    // ---- rendering ---------------------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        for (Item it : items) {
            if (it instanceof Row r) r.reset.active = !current.get(r.spec.key()).equals(Settings.DEFAULTS.get(r.spec.key()));
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
        // live usage readout (the numbers move as budget / keep-newest changes prune old savepoints)
        UsageBar.draw(g, font, ClientState.list(), left, 22, contentW, contentW);

        g.enableScissor(0, viewTop, width, viewBottom);
        int y = viewTop - scroll;
        for (Item it : items) {
            if (y + it.height() >= viewTop && y <= viewBottom) {
                switch (it) {
                    case Heading h -> {
                        g.text(font, h.text(), left + 1, y + 4, 0xFFFFD866);
                        g.horizontalLine(left + font.width(h.text()) + 6, left + contentW - SCROLLBAR - 3, y + 8, 0x40FFFFFF);
                    }
                    case Row r -> {
                        g.text(font, r.title(), left + 1, y + 1, 0xFFFFFFFF);
                        int dy = y + 12 + 20 + 3;
                        for (FormattedCharSequence l : r.desc) {
                            g.text(font, l, left + 1, dy, 0xFFA8A8A8, false);
                            dy += 10;
                        }
                        g.text(font, r.defaultText(), left + 1, dy, 0xFF707070, false);
                    }
                }
            }
            y += it.height();
        }
        g.disableScissor();
        int max = maxScroll();
        if (max > 0) {
            int track = viewBottom - viewTop;
            int thumb = Math.max(12, track * track / (track + max));
            int ty = viewTop + (track - thumb) * scroll / max;
            int sx = left + contentW - SCROLLBAR;
            g.fill(sx, viewTop, sx + SCROLLBAR, viewBottom, 0x40000000);
            g.fill(sx, ty, sx + SCROLLBAR, ty + thumb, 0xFF808080);
            g.fill(sx, ty, sx + SCROLLBAR - 1, ty + thumb - 1, 0xFFC0C0C0);
        }
    }

    @Override
    public void removed() {
        sendDue(true);
        super.removed();
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
