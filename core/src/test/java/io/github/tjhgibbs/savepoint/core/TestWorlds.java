package io.github.tjhgibbs.savepoint.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Test helpers: synthetic worlds and order-independent world fingerprints. */
final class TestWorlds {
    private TestWorlds() {}

    static final int OLD_TS = 1_000_000;

    record Chunk(int index, int timestamp, int compression, byte[] payload) {}

    static byte[] randomBytes(Random r, int n) {
        byte[] b = new byte[n];
        r.nextBytes(b);
        return b;
    }

    static void writeRegion(Path file, List<Chunk> chunks) throws IOException {
        List<RegionWriter.ChunkOut> outs = new ArrayList<>();
        for (Chunk c : chunks) outs.add(new RegionWriter.ChunkOut(c.index(), c.timestamp(), c.compression(), c.payload().length));
        RegionWriter.writeFile(file, outs, o -> chunks.stream().filter(c -> c.index() == o.index()).findFirst().get().payload());
    }

    static List<Chunk> readRegion(Path file) throws IOException {
        List<Chunk> out = new ArrayList<>();
        try (RegionFile rf = RegionFile.open(file)) {
            for (int i = 0; i < RegionFile.CHUNKS; i++) {
                RegionFile.Slot s = rf.slot(i);
                if (!s.present()) continue;
                RegionFile.Raw raw = rf.readRaw(s);
                out.add(new Chunk(i, s.timestamp(), raw.compression(), raw.payload()));
            }
        }
        return out;
    }

    static List<Chunk> randomChunks(long seed, int count, int ts, int minSize, int maxSize) {
        Random r = new Random(seed);
        List<Chunk> l = new ArrayList<>();
        int step = Math.max(1, RegionFile.CHUNKS / count);
        for (int i = 0; i < count; i++)
            l.add(new Chunk(i * step % RegionFile.CHUNKS, ts, 2, randomBytes(r, minSize + r.nextInt(maxSize - minSize + 1))));
        l.sort(java.util.Comparator.comparingInt(Chunk::index));
        return l;
    }

    static void writeFile(Path root, String rel, byte[] data) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, data);
    }

    /** A small world with regions in 26.x layout, an external chunk stub + .mcc, and assorted files. */
    static Path makeWorld(Path dir, long seed) throws IOException {
        Random r = new Random(seed);
        Files.createDirectories(dir);
        writeFile(dir, "level.dat", randomBytes(r, 900));
        writeFile(dir, "session.lock", "lock".getBytes());
        writeFile(dir, "icon.png", randomBytes(r, 2000));
        writeFile(dir, "players/data/aaaa.dat", randomBytes(r, 300));
        writeFile(dir, "data/minecraft/scoreboard.dat", randomBytes(r, 120));
        String d = "dimensions/minecraft/overworld/";
        writeRegion(dir.resolve(d + "region/r.0.0.mca"), randomChunks(seed + 1, 40, OLD_TS, 100, 9000));
        writeRegion(dir.resolve(d + "region/r.0.1.mca"), randomChunks(seed + 2, 20, OLD_TS, 100, 9000));
        writeRegion(dir.resolve(d + "entities/r.0.0.mca"), randomChunks(seed + 3, 10, OLD_TS, 50, 500));
        writeRegion(dir.resolve(d + "poi/r.0.0.mca"), List.of());
        // external chunk: stub only (comp | 0x80, empty payload) + sibling .mcc
        List<Chunk> ext = new ArrayList<>(randomChunks(seed + 4, 5, OLD_TS, 100, 2000));
        ext.add(new Chunk(1000, OLD_TS, 2 | 0x80, new byte[0]));
        writeRegion(dir.resolve("dimensions/minecraft/the_nether/region/r.0.0.mca"), ext);
        writeFile(dir, "dimensions/minecraft/the_nether/region/c.0.0.mcc", randomBytes(r, 1_500_000));
        return dir;
    }

    /** path -> fingerprint (chunk-level for .mca, byte-level otherwise), excluding session.lock. */
    static TreeMap<String, String> fingerprint(Path world) throws IOException {
        TreeMap<String, String> m = new TreeMap<>();
        for (String rel : WorldFiles.list(world, List.of())) {
            Path p = world.resolve(rel);
            if (rel.endsWith(".mca")) {
                StringBuilder b = new StringBuilder("mca");
                for (Chunk c : readRegion(p))
                    b.append(' ').append(c.index()).append(':').append(c.timestamp()).append(':').append(c.compression()).append(':')
                            .append(Hashes.sha256Hex(c.payload()), 0, 16);
                m.put(rel, b.toString());
            } else {
                m.put(rel, Hashes.sha256Hex(Files.readAllBytes(p)));
            }
        }
        return m;
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(x);
        }
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path src : (Iterable<Path>) s::iterator) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) Files.createDirectories(dst);
                else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst);
                }
            }
        }
    }

    /** Replaces one chunk's payload and timestamp in a region file (keeps the rest). */
    static void replaceChunk(Path mca, int index, byte[] payload, int ts) throws IOException {
        List<Chunk> cs = new ArrayList<>();
        for (Chunk c : readRegion(mca)) cs.add(c.index() == index ? new Chunk(index, ts, c.compression(), payload) : c);
        writeRegion(mca, cs);
    }
}
