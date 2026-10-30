package io.github.tjhgibbs.savepoint.mod;

import io.github.tjhgibbs.savepoint.core.IdResolver;
import io.github.tjhgibbs.savepoint.core.Manifest;
import java.io.IOException;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server half of a revert (runs on the server thread). Checks, takes the "before revert" undo point, starts the
 * cooldown and hands over to the client's {@code RevertCoordinator}, which stops this server, restores the files
 * with the world closed and reopens it. After the payload is sent this server is on its way out.
 */
final class RevertFlow {
    private RevertFlow() {}

    static void begin(SavepointService svc, ServerPlayer p, Manifest target) {
        if (!svc.server().isSingleplayerOwner(p.nameAndId())) {
            p.sendSystemMessage(Component.translatable("savepoint.msg.revert_owner_only"));
            return;
        }
        if (!ServerPlayNetworking.canSend(p, SavepointPayloads.BeginRevertPayload.TYPE)) {
            p.sendSystemMessage(Component.translatable("savepoint.msg.revert_no_client"));
            return;
        }
        if (!svc.checkCooldown(p)) return;
        Manifest undo;
        try {
            undo = svc.createUndoPoint(p, target.name());
        } catch (IOException | RuntimeException e) {
            SavepointMod.LOG.error("Revert aborted: undo point failed", e);
            p.sendSystemMessage(Component.translatable("savepoint.msg.revert_undo_failed", String.valueOf(e.getMessage())).withStyle(ChatFormatting.RED));
            return;
        }
        if (svc.store().get(target.id()).isEmpty()) {
            // Pruning to make room for the undo point removed the very savepoint we wanted.
            p.sendSystemMessage(Component.translatable("savepoint.msg.revert_target_pruned", target.name()).withStyle(ChatFormatting.RED));
            return;
        }
        svc.markCooldown();
        SavepointMod.LOG.info("Revert to {} ({}): undo point {} taken, handing over to the client", target.id(), target.name(), undo.id());
        p.sendSystemMessage(Component.translatable("savepoint.msg.revert_starting", target.name(), IdResolver.shortId(undo)));
        ServerPlayNetworking.send(p, new SavepointPayloads.BeginRevertPayload(
                svc.worldDir().getFileName().toString(), svc.worldDir().toString(), svc.storeRoot().toString(),
                target.id(), target.name(), undo.id()));
    }
}
