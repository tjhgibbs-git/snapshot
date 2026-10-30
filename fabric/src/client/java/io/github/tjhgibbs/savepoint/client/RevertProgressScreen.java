package io.github.tjhgibbs.savepoint.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** "Reverting to X..." with a progress bar and a Cancel button that is only live until files start moving. */
public final class RevertProgressScreen extends Screen {
    private final RevertCoordinator coordinator;
    private Button cancel;

    RevertProgressScreen(RevertCoordinator c) {
        super(Component.translatable("savepoint.revert.title", c.targetName()));
        this.coordinator = c;
    }

    @Override
    protected void init() {
        cancel = addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> {
            coordinator.cancel();
            b.active = false;
        }).bounds(width / 2 - 60, height / 2 + 40, 120, 20).build());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);
        if (cancel != null) cancel.active = coordinator.canCancel();
        int cx = width / 2, cy = height / 2;
        g.centeredText(font, title, cx, cy - 40, 0xFFFFFFFF);
        g.centeredText(font, coordinator.statusLine(), cx, cy - 20, 0xFFAAAAAA);
        int w = 200, x = cx - w / 2, y = cy;
        g.fill(x - 1, y - 1, x + w + 1, y + 9, 0xFF000000);
        g.fill(x, y, x + w, y + 8, 0xFF303030);
        double f = coordinator.fraction();
        g.fill(x, y, x + (int) (w * f), y + 8, f >= 1.0 ? 0xFF55FF55 : 0xFF55AAFF);
    }
}
