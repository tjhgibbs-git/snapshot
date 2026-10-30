package io.github.tjhgibbs.savepoint.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.tjhgibbs.savepoint.core.Settings;
import java.util.function.BiConsumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /savepoint ...}. The root name is deliberate: WorldEdit owns /snapshot, Axiom and 14erEdit use other
 * roots. Allowed for the singleplayer owner and for ops (game-master level).
 */
final class SavepointCommands {
    private SavepointCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("savepoint").requires(SavepointCommands::allowed);
        root.then(Commands.literal("create")
                .executes(c -> run(c, (s, p) -> s.createManual(p, null)))
                .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> run(c, (s, p) -> s.createManual(p, StringArgumentType.getString(c, "name"))))));
        root.then(Commands.literal("quick").executes(c -> run(c, SavepointService::createQuick)));
        root.then(Commands.literal("list").executes(c -> run(c, SavepointService::list)));
        root.then(Commands.literal("status").executes(c -> run(c, SavepointService::status)));
        root.then(Commands.literal("backup").executes(c -> run(c, SavepointService::backup)));
        root.then(idCommand("info", (s, p, id) -> s.info(p, id)));
        root.then(idCommand("revert", (s, p, id) -> s.revert(p, id)));
        root.then(idCommand("pin", (s, p, id) -> s.pin(p, id, true)));
        root.then(idCommand("unpin", (s, p, id) -> s.pin(p, id, false)));
        root.then(idCommand("delete", (s, p, id) -> s.delete(p, id)));
        root.then(Commands.literal("rename").then(idArg()
                .then(Commands.argument("name", StringArgumentType.greedyString()).executes(
                        c -> run(c, (s, p) -> s.rename(p, StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "name")))))));
        root.then(Commands.literal("config")
                .executes(c -> run(c, SavepointService::showConfig))
                .then(Commands.argument("key", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Settings.specs().stream().map(Settings.Spec::key), b))
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .suggests((c, b) -> {
                                    Settings.Spec sp = Settings.spec(StringArgumentType.getString(c, "key"));
                                    if (sp != null && sp.key().equals("fullBackupMode")) return SharedSuggestionProvider.suggest(new String[] {"OFF", "SESSION", "EVERY"}, b);
                                    return b.buildFuture();
                                })
                                .executes(c -> run(c, (s, p) -> s.setConfig(p, StringArgumentType.getString(c, "key"), StringArgumentType.getString(c, "value")))))));
        d.register(root);
    }

    private interface IdAction {
        void run(SavepointService s, ServerPlayer p, String id);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> idCommand(String name, IdAction a) {
        return Commands.literal(name).then(idArg().executes(c -> run(c, (s, p) -> a.run(s, p, StringArgumentType.getString(c, "id")))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> idArg() {
        return Commands.argument("id", StringArgumentType.string()).suggests((c, b) -> {
            SavepointService s = SavepointService.current();
            return SharedSuggestionProvider.suggest(s == null ? java.util.List.<String>of() : s.suggestions(), b);
        });
    }

    private static boolean allowed(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer p)) return false;
        SavepointService s = SavepointService.current();
        // No service (dedicated server): still show the command so the player gets the polite refusal.
        return s == null ? src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER) : s.mayUse(p);
    }

    private static int run(CommandContext<CommandSourceStack> c, BiConsumer<SavepointService, ServerPlayer> action) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        SavepointService s = SavepointService.require(p);
        if (s == null) return 0;
        action.accept(s, p);
        return 1;
    }
}
