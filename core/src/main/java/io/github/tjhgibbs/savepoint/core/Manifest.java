package io.github.tjhgibbs.savepoint.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable description of one savepoint. {@code entries} maps world-relative paths (forward slashes) to
 * either a whole-file blob ({@link FileEntry}) or, for {@code .mca} files, a chunk table ({@link RegionEntry}).
 * Serialised as {@code manifests/<id>.json}.
 */
public record Manifest(
        String id,
        String name,
        String note,
        long createdMillis,
        boolean pinned,
        boolean auto,
        Kind kind,
        PlayerPos player,
        String thumbnail,
        Map<String, Entry> entries) {

    public static final int FORMAT = 1;

    public enum Kind {
        MANUAL,
        QUICK,
        UNDO,
        SESSION;

        public String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static Kind parse(String s) {
            for (Kind k : values()) if (k.json().equals(s)) return k;
            return MANUAL;
        }
    }

    /** Player location when the savepoint was taken (all optional metadata, supplied by the caller). */
    public record PlayerPos(String dimension, double x, double y, double z) {}

    public sealed interface Entry permits FileEntry, RegionEntry {}

    public record FileEntry(String hash, long size) implements Entry {}

    /** One chunk of a region: payload hash/size (excluding length+compression bytes), header timestamp, compression byte. */
    public record ChunkRef(int index, int timestamp, int compression, int size, String hash) {}

    /** Chunk table of a region file, sorted by index. */
    public record RegionEntry(List<ChunkRef> chunks) implements Entry {
        public ChunkRef chunk(int index) {
            int lo = 0, hi = chunks.size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int v = chunks.get(mid).index();
                if (v < index) lo = mid + 1;
                else if (v > index) hi = mid - 1;
                else return chunks.get(mid);
            }
            return null;
        }
    }

    public Manifest withName(String n) {
        return new Manifest(id, n, note, createdMillis, pinned, auto, kind, player, thumbnail, entries);
    }

    public Manifest withNote(String n) {
        return new Manifest(id, name, n, createdMillis, pinned, auto, kind, player, thumbnail, entries);
    }

    public Manifest withPinned(boolean p) {
        return new Manifest(id, name, note, createdMillis, p, auto, kind, player, thumbnail, entries);
    }

    public Manifest withThumbnail(String t) {
        return new Manifest(id, name, note, createdMillis, pinned, auto, kind, player, t, entries);
    }

    /** Every blob hash this savepoint keeps alive (including the thumbnail). */
    public Set<String> referencedBlobs() {
        Set<String> s = new HashSet<>();
        for (Entry e : entries.values()) {
            if (e instanceof FileEntry f) s.add(f.hash());
            else for (ChunkRef c : ((RegionEntry) e).chunks()) s.add(c.hash());
        }
        if (thumbnail != null) s.add(thumbnail);
        return s;
    }

    /** Sum of the sizes of everything in the world at snapshot time (chunk payloads + whole files). */
    public long logicalBytes() {
        long t = 0;
        for (Entry e : entries.values()) {
            if (e instanceof FileEntry f) t += f.size();
            else for (ChunkRef c : ((RegionEntry) e).chunks()) t += c.size();
        }
        return t;
    }

    // ---- JSON ----

    public String toJson() {
        StringBuilder b = new StringBuilder(1 << 12);
        b.append("{\n \"format\": ").append(FORMAT).append(",\n \"id\": ");
        Json.quote(b, id);
        b.append(",\n \"name\": ");
        Json.quote(b, name);
        b.append(",\n \"note\": ");
        Json.quote(b, note);
        b.append(",\n \"createdMillis\": ").append(createdMillis);
        b.append(",\n \"pinned\": ").append(pinned);
        b.append(",\n \"auto\": ").append(auto);
        b.append(",\n \"kind\": \"").append(kind.json()).append('"');
        if (player != null) {
            b.append(",\n \"player\": {\"dimension\": ");
            Json.quote(b, player.dimension());
            b.append(", \"x\": ").append(player.x()).append(", \"y\": ").append(player.y()).append(", \"z\": ")
                    .append(player.z()).append('}');
        }
        if (thumbnail != null) b.append(",\n \"thumbnail\": \"").append(thumbnail).append('"');
        b.append(",\n \"entries\": {");
        boolean first = true;
        for (Map.Entry<String, Entry> me : entries.entrySet()) {
            b.append(first ? "\n  " : ",\n  ");
            first = false;
            Json.quote(b, me.getKey());
            b.append(": ");
            if (me.getValue() instanceof FileEntry f) {
                b.append("{\"hash\": \"").append(f.hash()).append("\", \"size\": ").append(f.size()).append('}');
            } else {
                b.append("{\"chunks\": [");
                boolean fc = true;
                for (ChunkRef c : ((RegionEntry) me.getValue()).chunks()) {
                    if (!fc) b.append(',');
                    fc = false;
                    b.append('[').append(c.index()).append(',').append(c.timestamp()).append(',')
                            .append(c.compression()).append(',').append(c.size()).append(",\"").append(c.hash())
                            .append("\"]");
                }
                b.append("]}");
            }
        }
        b.append("\n }\n}\n");
        return b.toString();
    }

    public static Manifest fromJson(String json) {
        Map<String, Object> o = Json.obj(Json.parse(json));
        long fmt = Json.num(o.get("format"));
        if (fmt != FORMAT) throw new IllegalArgumentException("unsupported manifest format " + fmt);
        PlayerPos pp = null;
        if (o.get("player") != null) {
            Map<String, Object> p = Json.obj(o.get("player"));
            pp = new PlayerPos((String) p.get("dimension"), Json.dbl(p.get("x")), Json.dbl(p.get("y")), Json.dbl(p.get("z")));
        }
        String thumb = (String) o.get("thumbnail");
        if (thumb != null && !Hashes.isHash(thumb)) throw new IllegalArgumentException("bad thumbnail hash");
        Map<String, Entry> entries = new TreeMap<>();
        for (Map.Entry<String, Object> e : Json.obj(o.get("entries")).entrySet()) {
            Map<String, Object> v = Json.obj(e.getValue());
            if (v.containsKey("hash")) {
                String h = (String) v.get("hash");
                if (!Hashes.isHash(h)) throw new IllegalArgumentException("bad hash for " + e.getKey());
                entries.put(e.getKey(), new FileEntry(h, Json.num(v.get("size"))));
            } else {
                List<Object> arr = Json.arr(v.get("chunks"));
                List<ChunkRef> chunks = new ArrayList<>(arr.size());
                int prev = -1;
                for (Object co : arr) {
                    List<Object> c = Json.arr(co);
                    int idx = (int) Json.num(c.get(0));
                    String h = (String) c.get(4);
                    if (idx <= prev || idx >= RegionFile.CHUNKS || !Hashes.isHash(h))
                        throw new IllegalArgumentException("bad chunk table for " + e.getKey());
                    prev = idx;
                    chunks.add(new ChunkRef(idx, (int) Json.num(c.get(1)), (int) Json.num(c.get(2)), (int) Json.num(c.get(3)), h));
                }
                entries.put(e.getKey(), new RegionEntry(chunks));
            }
        }
        return new Manifest(
                (String) o.get("id"),
                (String) o.getOrDefault("name", ""),
                (String) o.getOrDefault("note", ""),
                Json.num(o.get("createdMillis")),
                Boolean.TRUE.equals(o.get("pinned")),
                Boolean.TRUE.equals(o.get("auto")),
                Kind.parse((String) o.get("kind")),
                pp,
                thumb,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
    }
}
