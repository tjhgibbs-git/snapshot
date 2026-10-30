package io.github.tjhgibbs.savepoint.mod;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Registers payload types and the server-side receivers (they run on the server thread). */
final class SavepointNetworking {
    private SavepointNetworking() {}

    static void register() {
        PayloadTypeRegistry.clientboundPlay().register(SavepointPayloads.ListPayload.TYPE, SavepointPayloads.ListPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SavepointPayloads.BeginRevertPayload.TYPE, SavepointPayloads.BeginRevertPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SavepointPayloads.CreatedPayload.TYPE, SavepointPayloads.CreatedPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SavepointPayloads.ThumbnailPayload.TYPE, SavepointPayloads.ThumbnailPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SavepointPayloads.RevertPayload.TYPE, SavepointPayloads.RevertPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SavepointPayloads.ThumbnailUploadPayload.TYPE, SavepointPayloads.ThumbnailUploadPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SavepointPayloads.RequestThumbnailPayload.TYPE, SavepointPayloads.RequestThumbnailPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SavepointPayloads.RequestListPayload.TYPE, SavepointPayloads.RequestListPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SavepointPayloads.CreatePayload.TYPE, SavepointPayloads.CreatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SavepointPayloads.DeletePayload.TYPE, SavepointPayloads.DeletePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SavepointPayloads.PinPayload.TYPE, SavepointPayloads.PinPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SavepointPayloads.RenamePayload.TYPE, SavepointPayloads.RenamePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SavepointPayloads.BackupPayload.TYPE, SavepointPayloads.BackupPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SavepointPayloads.ConfigSetPayload.TYPE, SavepointPayloads.ConfigSetPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(SavepointPayloads.RequestListPayload.TYPE,
                (p, ctx) -> withService(ctx.player(), svc -> svc.sendList(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(SavepointPayloads.CreatePayload.TYPE, (p, ctx) -> withService(ctx.player(), svc -> {
            if (p.quick()) svc.createQuick(ctx.player());
            else svc.createManual(ctx.player(), p.name());
        }));
        ServerPlayNetworking.registerGlobalReceiver(SavepointPayloads.DeletePayload.TYPE,
                (p, ctx) -> withService(ctx.player(), svc -> svc.delete(ctx.player(), p.id())));
        ServerPlayNetworking.registerGlobalReceiver(SavepointPayloads.PinPayload.TYPE,
                (p, ctx) -> withService(ctx.player(), svc -> svc.pin(ctx.player(), p.id(), p.pinned())));
        ServerPlayNetworking.registerGlobalReceiver(SavepointPayloads.RenamePayload.TYPE,
                (p, ctx) -> withService(ctx.player(), svc -> svc.rename(ctx.player(), p.id(), p.name())));
        ServerPlayNetworking.registerGlobalReceiver(SavepointPayloads.RevertPayload.TYPE,
                (p, ctx) -> withService(ctx.player(), svc -> svc.revert(ctx.player(), p.id())));
        ServerPlayNetworking.registerGlobalReceiver(SavepointPayloads.ThumbnailUploadPayload.TYPE,
                (p, ctx) -> withService(ctx.player(), svc -> svc.uploadThumbnail(ctx.player(), p.id(), p.png())));
        ServerPlayNetworking.registerGlobalReceiver(SavepointPayloads.RequestThumbnailPayload.TYPE,
                (p, ctx) -> withService(ctx.player(), svc -> svc.sendThumbnail(ctx.player(), p.id())));
        ServerPlayNetworking.registerGlobalReceiver(SavepointPayloads.BackupPayload.TYPE,
                (p, ctx) -> withService(ctx.player(), svc -> svc.backup(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(SavepointPayloads.ConfigSetPayload.TYPE,
                (p, ctx) -> withService(ctx.player(), svc -> svc.setConfig(ctx.player(), p.key(), p.value(), false)));
    }

    private static void withService(ServerPlayer player, java.util.function.Consumer<SavepointService> action) {
        SavepointService svc = SavepointService.require(player);
        if (svc != null) action.accept(svc);
    }
}
