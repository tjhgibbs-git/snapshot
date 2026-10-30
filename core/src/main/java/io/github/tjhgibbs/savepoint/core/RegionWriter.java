package io.github.tjhgibbs.savepoint.core;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes compact, valid region files: header, then chunks packed back to back in index order, each padded to
 * a 4 KiB sector. Payload bytes are written exactly as given (never recompressed).
 */
public final class RegionWriter {
    private RegionWriter() {}

    /** Description of one chunk to write. {@code payloadSize} excludes the compression byte. */
    public record ChunkOut(int index, int timestamp, int compression, int payloadSize) {}

    /** Supplies payload bytes on demand so a region never needs to be fully in memory. */
    @FunctionalInterface
    public interface PayloadLoader {
        byte[] load(ChunkOut chunk) throws IOException;
    }

    /** Chunks must be sorted by ascending unique index. Does not close {@code out}. */
    public static void write(OutputStream out, List<ChunkOut> chunks, PayloadLoader loader) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(RegionFile.HEADER);
        int sector = 2;
        int prev = -1;
        for (ChunkOut c : chunks) {
            if (c.index() <= prev || c.index() >= RegionFile.CHUNKS)
                throw new IllegalArgumentException("chunk indices must be unique, sorted, < 1024");
            prev = c.index();
            int count = sectorsFor(c.payloadSize());
            if (count > 255) throw new IOException("chunk " + c.index() + " too large for a region file");
            header.putInt(c.index() * 4, (sector << 8) | count);
            header.putInt(RegionFile.SECTOR + c.index() * 4, c.timestamp());
            sector += count;
        }
        out.write(header.array());
        byte[] pad = new byte[RegionFile.SECTOR];
        for (ChunkOut c : chunks) {
            byte[] payload = loader.load(c);
            if (payload.length != c.payloadSize())
                throw new IOException("chunk " + c.index() + " payload size mismatch");
            int len = payload.length + 1;
            out.write(new byte[] {(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len,
                (byte) c.compression()});
            out.write(payload);
            int used = 5 + payload.length;
            out.write(pad, 0, sectorsFor(payload.length) * RegionFile.SECTOR - used);
        }
    }

    public static void writeFile(Path file, List<ChunkOut> chunks, PayloadLoader loader) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            write(o, chunks, loader);
        }
    }

    static int sectorsFor(int payloadSize) {
        return (5 + payloadSize + RegionFile.SECTOR - 1) / RegionFile.SECTOR;
    }
}
