package io.github.tjhgibbs.savepoint.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.tjhgibbs.savepoint.mod.SavepointMod;
import io.github.tjhgibbs.savepoint.mod.SavepointPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Client entrypoint: payload receivers, the three keys, and the "Savepoints" button on the pause menu.
 *
 * <p>Default keys were picked after checking vanilla's options and every mod in the pack (Axiom, WorldEdit, Sylvan,
 * Capella, Stacksmith): K opens the screen, F7 quick-saves, F8 quick-reverts. G, H, J, R, C, X are taken.
 */
public final class SavepointClient implements ClientModInitializer {
    static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(SavepointMod.MOD_ID, "main"));
    static KeyMapping openKey;
    static KeyMapping quickSaveKey;
    static KeyMapping quickRevertKey;

    private Screen lastScreen;
    /** The "how to use" chat line is shown at most once per game session. */
    private static boolean hintShown;

    @Override
    public void onInitializeClient() {
        RevertCoordinator.register();
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.savepoint.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_K, CATEGORY));
        quickSaveKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.savepoint.quick_save", InputConstants.Type.KEYBOARD, InputConstants.KEY_F7, CATEGORY));
        quickRevertKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.savepoint.quick_revert", InputConstants.Type.KEYBOARD, InputConstants.KEY_F8, CATEGORY));

        ClientPlayNetworking.registerGlobalReceiver(SavepointPayloads.ListPayload.TYPE, (p, ctx) -> {
            ClientState.onList(p);
            maybeShowKeyHint(ctx.client(), p);
        });
        ClientPlayNetworking.registerGlobalReceiver(SavepointPayloads.ThumbnailPayload.TYPE, (p, ctx) -> Thumbs.onReceived(p));
        ClientPlayNetworking.registerGlobalReceiver(SavepointPayloads.CreatedPayload.TYPE, (p, ctx) -> {
            ClientState.onCreated(p.id());
            Minecraft mc = Minecraft.getInstance();
            SceneShots.attachTo(p.id());
            SystemToast.addOrUpdate(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable(p.kind().equals("quick") ? "savepoint.toast.quick_saved" : "savepoint.toast.saved"),
                    Component.translatable("savepoint.toast.saved_body", p.name(), ClientFmt.seconds(p.millis())));
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> ClientState.requestList());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            ClientState.reset();
            Thumbs.reset();
            SceneShots.clear();
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof PauseScreen && mc.level != null && mc.hasSingleplayerServer()) {
                Button b = Button.builder(Component.translatable("savepoint.pause_button"), x -> mc.setScreenAndShow(new SavepointScreen(screen)))
                        .bounds(6, 6, 88, 20).build();
                Screens.getWidgets(screen).add(b);
            }
        });
    }

    private void tick(Minecraft mc) {
        Screen now = mc.gui.screen();
        // A screen just opened over the world: the last frame is still the clean scene, keep it for thumbnails.
        if (now != null && lastScreen == null && mc.level != null) SceneShots.capture(mc);
        if (now == null && mc.level != null) {
            // Key mappings only fire with no screen open, so these never trigger while typing in a text box.
            while (openKey.consumeClick()) {
                SceneShots.capture(mc);
                mc.setScreenAndShow(new SavepointScreen(null));
            }
            while (quickSaveKey.consumeClick()) quickSave(mc);
            while (quickRevertKey.consumeClick()) quickRevert(mc);
        }
        lastScreen = mc.gui.screen();
    }

    private static boolean usable(Minecraft mc) {
        if (RevertCoordinator.isActive()) return false;
        if (!mc.hasSingleplayerServer() || !ClientPlayNetworking.canSend(SavepointPayloads.CreatePayload.TYPE)) {
            toast(mc, "savepoint.toast.unavailable", "savepoint.toast.unavailable_body");
            return false;
        }
        return true;
    }

    private void quickSave(Minecraft mc) {
        if (!usable(mc)) return;
        int wait = ClientState.cooldownSeconds();
        if (wait > 0) {
            SystemToast.addOrUpdate(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable("savepoint.toast.cooldown"), Component.translatable("savepoint.toast.cooldown_body", wait));
            return;
        }
        if (ClientState.createInFlight()) return;
        ClientState.markCreateSent();
        SceneShots.capture(mc);
        ClientPlayNetworking.send(new SavepointPayloads.CreatePayload("", true));
    }

    /** Asks first (press again, or click Revert) unless Shift is held. Reverting loses progress; saving never does. */
    private void quickRevert(Minecraft mc) {
        if (!usable(mc)) return;
        SavepointPayloads.Entry quick = ClientState.newest("quick");
        if (quick == null) {
            toast(mc, "savepoint.toast.no_quick", "savepoint.toast.no_quick_body");
            return;
        }
        int wait = ClientState.cooldownSeconds();
        if (wait > 0) {
            SystemToast.addOrUpdate(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable("savepoint.toast.cooldown"), Component.translatable("savepoint.toast.cooldown_body", wait));
            return;
        }
        if (mc.hasShiftDown()) {
            ClientPlayNetworking.send(new SavepointPayloads.RevertPayload(quick.id()));
        } else {
            mc.setScreenAndShow(RevertConfirmScreen.create(null, quick, true));
        }
    }

    /** First world without savepoints this session: one chat line naming the keys, so players know the mod is there. */
    private static void maybeShowKeyHint(Minecraft mc, SavepointPayloads.ListPayload p) {
        if (hintShown || !p.entries().isEmpty() || RevertCoordinator.isActive()) return;
        hintShown = true;
        mc.gui.chatListener().handleSystemMessage(Component.translatable("savepoint.hint.keys", openKey.getTranslatedKeyMessage(),
                quickSaveKey.getTranslatedKeyMessage(), quickRevertKey.getTranslatedKeyMessage()).withStyle(net.minecraft.ChatFormatting.GRAY), false);
    }

    static void toast(Minecraft mc, String title, String body) {
        SystemToast.addOrUpdate(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.translatable(title), Component.translatable(body));
    }
}
