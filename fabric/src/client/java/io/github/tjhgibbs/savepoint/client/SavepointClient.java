package io.github.tjhgibbs.savepoint.client;

import net.fabricmc.api.ClientModInitializer;

/** Client entrypoint. Screens and keybinds arrive in chunk 4. */
public final class SavepointClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        RevertCoordinator.register();
    }
}
