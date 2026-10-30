package io.github.tjhgibbs.savepoint.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Read-only view of an Anvil region file ({@code .mca}). Layout: 4 KiB sectors; sectors 0-1 hold 1024
 * location entries ({@code offsetInSectors<<8 | sectorCount}) and 1024 big-endian timestamps (seconds);
 * each chunk is {@code int length, byte compression, payload} where length counts the compression byte.
 * <p>Payloads are opaque: this class never decompresses. A compression byte with bit 0x80 set means the
 * payload lives in a sibling {@code c.X.Z.mcc} file; the stub stored here is kept verbatim (and the .mcc is
 * captured as an ordinary file by the store).
 * <p>Not thread-safe; one instance per thread.
 */
public final class RegionFile implements AutoCloseable {
    public static final int SECTOR = 4096;
    public static final int CHUNKS = 1024;
    public static final int HEADER = 2 * SECTOR;
    public static final int EXTERNAL_FLAG = 0x80;

    /** A chunk slot from the header. {@code sectorCount == 0} means the chunk is absent. */
    public record Slot(int index, int sectorOffset, int sectorCount, int timestamp) {
        public boolean present() {
            return sectorCount != 0;
        }
    }

    /** Chunk prefix: payload length (excluding the compression byte) and the compression byte (0-255). */
    public record Head(int length, int compression) {}

    /** A raw chunk. {@code payload} excludes the length and compression bytes. */
    public record Raw(int compression, byte[] payload) {}

    private final FileChannel ch;
    private final long fileSize;
    private final Slot[] slots = new Slot[CHUNKS];
    private int present;

    private RegionFile(FileChannel ch, long size) {
        this.ch = ch;
        this.fileSize = size;
    }

    /** Opens and validates the header. Throws {@link MalformedRegionException} if it is not a sane region. */
    public static RegionFile open(Path file) throws IOException {
        FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
        try {
            long size = ch.size();
            if (size < HEADER) throw new MalformedRegionException("region file shorter than header: " + file);
            ByteBuffer hb = ByteBuffer.allocate(HEADER);
            readFully(ch, hb, 0);
            hb.flip();
            RegionFile r = new RegionFile(ch, size);
            for (int i = 0; i < CHUNKS; i++) {
                int loc = hb.getInt(i * 4);
                int ts = hb.getInt(SECTOR + i * 4);
                int off = loc >>> 8;
                int cnt = loc & 0xFF;
                if (loc == 0 || cnt == 0) {
                    r.slots[i] = new Slot(i, 0, 0, ts);
                    continue;
                }
                if (off < 2 || (long) (off + cnt) * SECTOR > size)
                    throw new MalformedRegionException("bad location for chunk " + i + " in " + file);
                r.slots[i] = new Slot(i, off, cnt, ts);
                r.present++;
            }
            return r;
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    public Slot slot(int index) {
        return slots[index];
    }

    public int presentCount() {
        return present;
    }

    public Head head(Slot s) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(5);
        readFully(ch, b, (long) s.sectorOffset() * SECTOR);
        b.flip();
        int len = b.getInt();
        int comp = b.get() & 0xFF;
        if (len < 1 || len > s.sectorCount() * SECTOR - 4)
            throw new MalformedRegionException("bad chunk length " + len + " for chunk " + s.index());
        return new Head(len - 1, comp);
    }

    public Raw readRaw(Slot s) throws IOException {
        Head h = head(s);
        byte[] payload = new byte[h.length()];
        readFully(ch, ByteBuffer.wrap(payload), (long) s.sectorOffset() * SECTOR + 5);
        return new Raw(h.compression(), payload);
    }

    public long fileSize() {
        return fileSize;
    }

    private static void readFully(FileChannel ch, ByteBuffer b, long pos) throws IOException {
        while (b.hasRemaining()) {
            int n = ch.read(b, pos);
            if (n < 0) throw new MalformedRegionException("unexpected end of region file");
            pos += n;
        }
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
