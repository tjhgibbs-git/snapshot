package io.github.tjhgibbs.savepoint.client;

import io.github.tjhgibbs.savepoint.core.Settings;
import io.github.tjhgibbs.savepoint.mod.SavepointPayloads;
import io.github.tjhgibbs.savepoint.mod.SavepointPayloads.Entry;
import io.github.tjhgibbs.savepoint.mod.SavepointPayloads.ListPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * What the client knows about the current world's savepoints: the newest {@link ListPayload} plus a local cooldown
 * clock. The integrated server (and its service) is replaced on every rejoin, so this is dropped on disconnect and
 * rebuilt by a list request on join. Client thread only.
 */
final class ClientState {
    private static ListPayload list;
    /** Bumped whenever {@link #list} changes; screens compare it to know when to refresh. */
    private static int version;
    private static long cooldownUntilMs;
    /** While set, a create we asked for is in flight (the server blocks while saving). */
    private static long busyUntilMs;

    private static String createdId;

    private ClientState() {}

    /** Id of the savepoint the server just created (the list screen selects it once), or null. */
    static void onCreated(String id) {
        createdId = id;
        busyUntilMs = 0;
    }

    static String takeCreatedId() {
        String id = createdId;
        createdId = null;
        return id;
    }

    static ListPayload list() {
        return list;
    }

    static int version() {
        return version;
    }

    static void onList(ListPayload p) {
        list = p;
        version++;
        cooldownUntilMs = System.currentTimeMillis() + p.cooldownRemaining() * 1000L;
        busyUntilMs = 0;
    }

    static void reset() {
        list = null;
        version++;
        cooldownUntilMs = 0;
        busyUntilMs = 0;
    }

    static void requestList() {
        if (ClientPlayNetworking.canSend(SavepointPayloads.RequestListPayload.TYPE)) {
            ClientPlayNetworking.send(new SavepointPayloads.RequestListPayload());
        }
    }

    /** Whole seconds still to wait before a create/revert is accepted (rounded up). */
    static int cooldownSeconds() {
        long left = cooldownUntilMs - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    /** Called when we send a create so a double click cannot queue two. */
    static void markCreateSent() {
        busyUntilMs = System.currentTimeMillis() + 15_000;
    }

    static boolean createInFlight() {
        return System.currentTimeMillis() < busyUntilMs;
    }

    /** The values in effect for this world (per-world ones applied), or the defaults before the first list. */
    static Settings settings() {
        Settings s = Settings.DEFAULTS;
        ListPayload l = list;
        if (l == null) return s;
        var specs = Settings.specs();
        for (int i = 0; i < specs.size() && i < l.settingValues().size(); i++) {
            try {
                s = s.with(specs.get(i).key(), l.settingValues().get(i));
            } catch (IllegalArgumentException ignored) {
                // keep default
            }
        }
        return s;
    }

    /** Newest entry of a kind ("quick", "undo", ...), or null. */
    static Entry newest(String kind) {
        ListPayload l = list;
        if (l == null) return null;
        for (Entry e : l.entries()) if (e.kind().equals(kind)) return e;
        return null;
    }

    static Entry byId(String id) {
        ListPayload l = list;
        if (l == null || id == null) return null;
        for (Entry e : l.entries()) if (e.id().equals(id)) return e;
        return null;
    }
}
