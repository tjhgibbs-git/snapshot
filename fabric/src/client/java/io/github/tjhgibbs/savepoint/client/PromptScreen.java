package io.github.tjhgibbs.savepoint.client;

import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** One text field with OK / Cancel (used for renaming). Enter confirms, Esc cancels. */
final class PromptScreen extends Screen {
    private final Screen parent;
    private final String initial;
    private final Consumer<String> onOk;
    private EditBox box;
    private Button ok;

    PromptScreen(Screen parent, Component title, String initial, Consumer<String> onOk) {
        super(title);
        this.parent = parent;
        this.initial = initial;
        this.onOk = onOk;
    }

    @Override
    protected void init() {
        int w = Math.min(240, width - 20), x = (width - w) / 2, y = height / 2 - 20;
        box = new EditBox(font, x, y, w, 20, title);
        box.setMaxLength(100);
        box.setValue(box.getValue().isEmpty() ? initial : box.getValue());
        box.setResponder(s -> ok.active = !s.isBlank());
        addRenderableWidget(box);
        int bw = (w - 4) / 2;
        ok = addRenderableWidget(Button.builder(CommonComponents.GUI_OK, b -> confirm()).bounds(x, y + 28, bw, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(x + bw + 4, y + 28, bw, 20).build());
        ok.active = !box.getValue().isBlank();
        setInitialFocus(box);
    }

    private void confirm() {
        String name = box.getValue().strip();
        if (name.isEmpty()) return;
        minecraft.setScreenAndShow(parent);
        onOk.accept(name);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation()) {
            confirm();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, height / 2 - 38, 0xFFFFFFFF);
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
