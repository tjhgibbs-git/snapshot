package io.github.tjhgibbs.savepoint.client;

import io.github.tjhgibbs.savepoint.mod.SavepointPayloads;
import io.github.tjhgibbs.savepoint.mod.SavepointPayloads.Entry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * "Revert to X?" dialog: says what is lost and that a safety savepoint is taken first. When opened by the quick-revert
 * key, pressing that key again confirms, so the fast path is two presses (or Shift+key for none).
 */
final class RevertConfirmScreen extends ConfirmScreen {
    private final boolean viaQuickKey;

    private RevertConfirmScreen(net.minecraft.client.gui.screens.Screen parent, Entry e, boolean viaQuickKey, Component title, Component body) {
        super(yes -> {
            Minecraft mc = Minecraft.getInstance();
            if (yes) {
                ClientPlayNetworking.send(new SavepointPayloads.RevertPayload(e.id()));
                mc.setScreenAndShow(null);
            } else {
                mc.setScreenAndShow(parent);
            }
        }, title, body, Component.translatable("savepoint.button.revert"), CommonComponents.GUI_CANCEL);
        this.viaQuickKey = viaQuickKey;
    }

    static Screen create(Screen parent, Entry e, boolean viaQuickKey) {
        Component age = ClientFmt.age(System.currentTimeMillis() - e.createdMillis());
        Component body = Component.translatable("savepoint.revert.confirm.body", age);
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null && server.isPublished()) {
            body = Component.empty().append(body).append("\n\n").append(Component.translatable("savepoint.revert.confirm.lan").withStyle(ChatFormatting.YELLOW));
        }
        if (viaQuickKey) {
            body = Component.empty().append(body).append("\n\n").append(
                    Component.translatable("savepoint.revert.confirm.key_hint", SavepointClient.quickRevertKey.getTranslatedKeyMessage()).withStyle(ChatFormatting.GRAY));
        }
        Component title = Component.translatable("savepoint.revert.confirm.title", Names.shorten(e.name(), 36));
        return new RevertConfirmScreen(parent, e, viaQuickKey, title, body);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (viaQuickKey && SavepointClient.quickRevertKey.matches(event)) {
            callback.accept(true);
            return true;
        }
        return super.keyPressed(event);
    }
}
