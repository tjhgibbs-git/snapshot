package io.github.tjhgibbs.savepoint.mod;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common (both sides) entrypoint. */
public final class SavepointMod implements ModInitializer {
    public static final String MOD_ID = "savepoint";
    public static final Logger LOG = LoggerFactory.getLogger("Savepoint");

    @Override
    public void onInitialize() {
        LOG.info("Savepoint ready");
    }
}
