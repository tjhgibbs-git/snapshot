package io.github.tjhgibbs.savepoint.mod;

import io.github.tjhgibbs.savepoint.core.IdResolver;
import io.github.tjhgibbs.savepoint.core.Manifest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Networking payloads between the (future) client screen and the integrated server. Small on purpose:
 * thumbnails are not carried. All C2S payloads are handled by {@link SavepointNetworking} on the server
 * thread and call the same {@link SavepointService} methods the commands use.
 */
public final class SavepointPayloads {
    private SavepointPayloads() {}

    static final int MAX_STR = 256;
    static final int MAX_ENTRIES = 1000;

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SavepointMod.MOD_ID, path));
    }

    /** One row of the list. {@code uniqueBytes} = bytes freed if this savepoint alone were deleted. */
    public record Entry(String id, String shortId, String name, String kind, long createdMillis, boolean pinned,
                        boolean auto, long logicalBytes, long uniqueBytes, String dimension) {
        public static Entry of(Manifest m, long uniqueBytes) {
            return new Entry(m.id(), IdResolver.shortId(m), m.name(), m.kind().json(), m.createdMillis(), m.pinned(), m.auto(),
                    m.logicalBytes(), uniqueBytes, m.player() == null ? "" : m.player().dimension());
        }

        void write(FriendlyByteBuf b) {
            b.writeUtf(id, MAX_STR);
            b.writeUtf(shortId, MAX_STR);
            b.writeUtf(name, MAX_STR);
            b.writeUtf(kind, 16);
            b.writeLong(createdMillis);
            b.writeBoolean(pinned);
            b.writeBoolean(auto);
            b.writeVarLong(logicalBytes);
            b.writeVarLong(uniqueBytes);
            b.writeUtf(dimension, MAX_STR);
        }

        static Entry read(FriendlyByteBuf b) {
            return new Entry(b.readUtf(MAX_STR), b.readUtf(MAX_STR), b.readUtf(MAX_STR), b.readUtf(16), b.readLong(),
                    b.readBoolean(), b.readBoolean(), b.readVarLong(), b.readVarLong(), b.readUtf(MAX_STR));
        }
    }

    /**
     * S2C: everything the screen needs. {@code settingValues} is in {@code Settings.specs()} order (use
     * {@code Settings.load}-style {@code with(key, value)} to rebuild a Settings). {@code cooldownRemaining} in seconds.
     */
    public record ListPayload(List<Entry> entries, long usedBytes, long budgetBytes, int cooldownRemaining,
                              boolean backupRunning, boolean backupDoneThisSession, List<String> settingValues)
            implements CustomPacketPayload {
        public static final Type<ListPayload> TYPE = typeOf("list");
        public static final StreamCodec<FriendlyByteBuf, ListPayload> CODEC = StreamCodec.of(ListPayload::write, ListPayload::read);

        private static void write(FriendlyByteBuf b, ListPayload p) {
            b.writeVarInt(p.entries.size());
            for (Entry e : p.entries) e.write(b);
            b.writeVarLong(p.usedBytes);
            b.writeVarLong(p.budgetBytes);
            b.writeVarInt(p.cooldownRemaining);
            b.writeBoolean(p.backupRunning);
            b.writeBoolean(p.backupDoneThisSession);
            b.writeVarInt(p.settingValues.size());
            for (String s : p.settingValues) b.writeUtf(s, MAX_STR);
        }

        private static ListPayload read(FriendlyByteBuf b) {
            int n = b.readVarInt();
            if (n < 0 || n > MAX_ENTRIES) throw new IllegalArgumentException("too many entries: " + n);
            List<Entry> es = new ArrayList<>(n);
            for (int i = 0; i < n; i++) es.add(Entry.read(b));
            long used = b.readVarLong();
            long budget = b.readVarLong();
            int cd = b.readVarInt();
            boolean br = b.readBoolean();
            boolean bd = b.readBoolean();
            int k = b.readVarInt();
            if (k < 0 || k > 64) throw new IllegalArgumentException("too many settings: " + k);
            List<String> vals = new ArrayList<>(k);
            for (int i = 0; i < k; i++) vals.add(b.readUtf(MAX_STR));
            return new ListPayload(List.copyOf(es), used, budget, cd, br, bd, List.copyOf(vals));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: send a fresh {@link ListPayload}. */
    public record RequestListPayload() implements CustomPacketPayload {
        public static final Type<RequestListPayload> TYPE = typeOf("request_list");
        public static final StreamCodec<FriendlyByteBuf, RequestListPayload> CODEC =
                StreamCodec.of((b, p) -> {}, b -> new RequestListPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: create a savepoint. {@code quick} true = overwrite the quick slot (name ignored); empty name = auto name. */
    public record CreatePayload(String name, boolean quick) implements CustomPacketPayload {
        public static final Type<CreatePayload> TYPE = typeOf("create");
        public static final StreamCodec<FriendlyByteBuf, CreatePayload> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.name, MAX_STR);
                    b.writeBoolean(p.quick);
                },
                b -> new CreatePayload(b.readUtf(MAX_STR), b.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: delete a savepoint by id (the full id from {@link Entry#id()}). */
    public record DeletePayload(String id) implements CustomPacketPayload {
        public static final Type<DeletePayload> TYPE = typeOf("delete");
        public static final StreamCodec<FriendlyByteBuf, DeletePayload> CODEC =
                StreamCodec.of((b, p) -> b.writeUtf(p.id, MAX_STR), b -> new DeletePayload(b.readUtf(MAX_STR)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: pin or unpin. */
    public record PinPayload(String id, boolean pinned) implements CustomPacketPayload {
        public static final Type<PinPayload> TYPE = typeOf("pin");
        public static final StreamCodec<FriendlyByteBuf, PinPayload> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.id, MAX_STR);
                    b.writeBoolean(p.pinned);
                },
                b -> new PinPayload(b.readUtf(MAX_STR), b.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: rename. */
    public record RenamePayload(String id, String name) implements CustomPacketPayload {
        public static final Type<RenamePayload> TYPE = typeOf("rename");
        public static final StreamCodec<FriendlyByteBuf, RenamePayload> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.id, MAX_STR);
                    b.writeUtf(p.name, MAX_STR);
                },
                b -> new RenamePayload(b.readUtf(MAX_STR), b.readUtf(MAX_STR)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: build a full backup zip from the latest savepoint. */
    public record BackupPayload() implements CustomPacketPayload {
        public static final Type<BackupPayload> TYPE = typeOf("backup");
        public static final StreamCodec<FriendlyByteBuf, BackupPayload> CODEC =
                StreamCodec.of((b, p) -> {}, b -> new BackupPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: change one setting (validated server side; the server answers with a fresh {@link ListPayload}). */
    public record ConfigSetPayload(String key, String value) implements CustomPacketPayload {
        public static final Type<ConfigSetPayload> TYPE = typeOf("config_set");
        public static final StreamCodec<FriendlyByteBuf, ConfigSetPayload> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.key, MAX_STR);
                    b.writeUtf(p.value, MAX_STR);
                },
                b -> new ConfigSetPayload(b.readUtf(MAX_STR), b.readUtf(MAX_STR)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
