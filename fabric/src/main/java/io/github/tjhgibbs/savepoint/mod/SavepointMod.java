package io.github.tjhgibbs.savepoint.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common (both sides) entrypoint. */
public final class SavepointMod implements ModInitializer {
    public static final String MOD_ID = "savepoint";
    public static final Logger LOG = LoggerFactory.getLogger("Savepoint");

    @Override
    public void onInitialize() {
        SavepointConfig.load();
        SavepointNetworking.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> SavepointCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(SavepointService::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> SavepointService.stop());
        LOG.info("Savepoint ready");
    }
}
