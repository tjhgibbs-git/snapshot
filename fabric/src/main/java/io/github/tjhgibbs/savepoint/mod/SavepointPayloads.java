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
 * Networking payloads between the client screens and the integrated server. Small on purpose:
 * the list carries no images; thumbnails travel one at a time, on request. All C2S payloads are handled by {@link SavepointNetworking} on the server
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
                        boolean auto, long logicalBytes, long uniqueBytes, String dimension, boolean hasThumbnail) {
        public static Entry of(Manifest m, long uniqueBytes) {
            return new Entry(m.id(), IdResolver.shortId(m), m.name(), m.kind().json(), m.createdMillis(), m.pinned(), m.auto(),
                    m.logicalBytes(), uniqueBytes, m.player() == null ? "" : m.player().dimension(), m.thumbnail() != null);
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
            b.writeBoolean(hasThumbnail);
        }

        static Entry read(FriendlyByteBuf b) {
            return new Entry(b.readUtf(MAX_STR), b.readUtf(MAX_STR), b.readUtf(MAX_STR), b.readUtf(16), b.readLong(),
                    b.readBoolean(), b.readBoolean(), b.readVarLong(), b.readVarLong(), b.readUtf(MAX_STR), b.readBoolean());
        }
    }

    /**
     * S2C: everything the screen needs. {@code settingValues} is in {@code Settings.specs()} order and holds the
     * values in effect for THIS world (per-world settings already applied); rebuild a Settings with {@code with(key, value)}.
     * {@code cooldownRemaining} in seconds. {@code worldName} is the world's folder name.
     */
    public record ListPayload(List<Entry> entries, long usedBytes, long budgetBytes, int cooldownRemaining,
                              boolean backupRunning, boolean backupDoneThisSession, List<String> settingValues, String worldName)
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
            b.writeUtf(p.worldName, MAX_STR);
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
            return new ListPayload(List.copyOf(es), used, budget, cd, br, bd, List.copyOf(vals), b.readUtf(MAX_STR));
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

    /** C2S: revert to this savepoint (same checks and flow as {@code /savepoint revert <id>}). */
    public record RevertPayload(String id) implements CustomPacketPayload {
        public static final Type<RevertPayload> TYPE = typeOf("revert");
        public static final StreamCodec<FriendlyByteBuf, RevertPayload> CODEC =
                StreamCodec.of((b, p) -> b.writeUtf(p.id, MAX_STR), b -> new RevertPayload(b.readUtf(MAX_STR)));

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

    /**
     * S2C: the server has taken its undo point and is about to be stopped; the client must now disconnect, restore
     * {@code targetId} from the store at {@code storeRoot} into {@code worldDir} and reopen {@code levelId}.
     * Paths are absolute (client and integrated server share a machine).
     */
    public record BeginRevertPayload(String levelId, String worldDir, String storeRoot, String targetId, String targetName,
                                     String undoId) implements CustomPacketPayload {
        public static final Type<BeginRevertPayload> TYPE = typeOf("begin_revert");
        public static final StreamCodec<FriendlyByteBuf, BeginRevertPayload> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.levelId, MAX_STR);
                    b.writeUtf(p.worldDir, 4096);
                    b.writeUtf(p.storeRoot, 4096);
                    b.writeUtf(p.targetId, MAX_STR);
                    b.writeUtf(p.targetName, MAX_STR);
                    b.writeUtf(p.undoId, MAX_STR);
                },
                b -> new BeginRevertPayload(b.readUtf(MAX_STR), b.readUtf(4096), b.readUtf(4096), b.readUtf(MAX_STR),
                        b.readUtf(MAX_STR), b.readUtf(MAX_STR)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Largest PNG accepted in either direction. Serverbound custom payloads are capped at 32767 bytes by the game. */
    public static final int MAX_THUMBNAIL = 30_000;

    /** S2C: a savepoint was just created (by any means). Carries what the HUD toast and the thumbnail upload need. */
    public record CreatedPayload(String id, String name, String kind, long millis) implements CustomPacketPayload {
        public static final Type<CreatedPayload> TYPE = typeOf("created");
        public static final StreamCodec<FriendlyByteBuf, CreatedPayload> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.id, MAX_STR);
                    b.writeUtf(p.name, MAX_STR);
                    b.writeUtf(p.kind, 16);
                    b.writeVarLong(p.millis);
                },
                b -> new CreatedPayload(b.readUtf(MAX_STR), b.readUtf(MAX_STR), b.readUtf(16), b.readVarLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: attach a client screenshot (PNG, at most {@link #MAX_THUMBNAIL} bytes) to a savepoint that has none yet. */
    public record ThumbnailUploadPayload(String id, byte[] png) implements CustomPacketPayload {
        public static final Type<ThumbnailUploadPayload> TYPE = typeOf("thumbnail_upload");
        public static final StreamCodec<FriendlyByteBuf, ThumbnailUploadPayload> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.id, MAX_STR);
                    b.writeByteArray(p.png);
                },
                b -> new ThumbnailUploadPayload(b.readUtf(MAX_STR), b.readByteArray(MAX_THUMBNAIL)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: ask for the thumbnail of a savepoint (answered with {@link ThumbnailPayload}, nothing if it has none). */
    public record RequestThumbnailPayload(String id) implements CustomPacketPayload {
        public static final Type<RequestThumbnailPayload> TYPE = typeOf("request_thumbnail");
        public static final StreamCodec<FriendlyByteBuf, RequestThumbnailPayload> CODEC =
                StreamCodec.of((b, p) -> b.writeUtf(p.id, MAX_STR), b -> new RequestThumbnailPayload(b.readUtf(MAX_STR)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: one thumbnail PNG. */
    public record ThumbnailPayload(String id, byte[] png) implements CustomPacketPayload {
        public static final Type<ThumbnailPayload> TYPE = typeOf("thumbnail");
        public static final StreamCodec<FriendlyByteBuf, ThumbnailPayload> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.id, MAX_STR);
                    b.writeByteArray(p.png);
                },
                b -> new ThumbnailPayload(b.readUtf(MAX_STR), b.readByteArray(MAX_THUMBNAIL)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
