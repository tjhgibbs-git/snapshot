package io.github.tjhgibbs.savepoint.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Content-addressed blob storage: {@code <root>/ab/abcdef...} where the name is the lowercase SHA-256 of the
 * bytes. Writes go to a temp file and are atomically renamed, so a blob either exists whole or not at all.
 * Identical content is stored once. Every read re-hashes and throws {@link CorruptBlobException} on mismatch.
 * Methods are thread-safe (no shared mutable state; concurrent identical puts are harmless).
 */
public final class BlobStore {
    private static final int IN_MEMORY_LIMIT = 64 << 20;

    private final Path root;
    private final Path tmp;

    public BlobStore(Path root, Path tmpDir) {
        this.root = root;
        this.tmp = tmpDir;
    }

    public Path pathOf(String hash) {
        if (!Hashes.isHash(hash)) throw new IllegalArgumentException("bad hash: " + hash);
        return root.resolve(hash.substring(0, 2)).resolve(hash);
    }

    public boolean exists(String hash) {
        return Files.isRegularFile(pathOf(hash));
    }

    public long size(String hash) throws IOException {
        return Files.size(pathOf(hash));
    }

    /** Stores bytes; returns their hash. Does not rewrite an existing blob. */
    public String put(byte[] data) throws IOException {
        String h = Hashes.sha256Hex(data);
        put(h, data);
        return h;
    }

    /** Stores bytes whose hash the caller already computed (not re-checked here). */
    public void put(String hash, byte[] data) throws IOException {
        Path dest = pathOf(hash);
        if (Files.isRegularFile(dest)) return;
        Files.createDirectories(tmp);
        Path t = Files.createTempFile(tmp, "blob", ".part");
        try {
            Files.write(t, data);
            publish(t, dest);
        } finally {
            Files.deleteIfExists(t);
        }
    }

    /** Result of {@link #putFile}. */
    public record Stored(String hash, long size) {}

    /** Hashes and stores a whole file (streamed if large). */
    public Stored putFile(Path file) throws IOException {
        long size = Files.size(file);
        if (size <= IN_MEMORY_LIMIT) {
            byte[] data = Files.readAllBytes(file);
            return new Stored(put(data), data.length);
        }
        Files.createDirectories(tmp);
        Path t = Files.createTempFile(tmp, "blob", ".part");
        try {
            MessageDigest md = Hashes.sha256();
            long n = 0;
            try (InputStream in = Files.newInputStream(file); OutputStream out = Files.newOutputStream(t)) {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) > 0) {
                    md.update(buf, 0, r);
                    out.write(buf, 0, r);
                    n += r;
                }
            }
            String h = Hashes.hex(md.digest());
            Path dest = pathOf(h);
            if (!Files.isRegularFile(dest)) publish(t, dest);
            return new Stored(h, n);
        } finally {
            Files.deleteIfExists(t);
        }
    }

    private void publish(Path t, Path dest) throws IOException {
        Files.createDirectories(dest.getParent());
        try {
            Files.move(t, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(t, dest);
        } catch (IOException e) {
            if (!Files.isRegularFile(dest)) throw e; // lost a race with an identical put: fine
        }
    }

    /** Reads a blob fully, verifying its hash. */
    public byte[] readVerified(String hash) throws IOException {
        byte[] data;
        try {
            data = Files.readAllBytes(pathOf(hash));
        } catch (NoSuchFileException e) {
            throw new CorruptBlobException(hash, "blob is missing");
        }
        if (!Hashes.sha256Hex(data).equals(hash)) throw new CorruptBlobException(hash, "blob content does not match its hash");
        return data;
    }

    /**
     * Streams a blob to {@code out}, throwing {@link CorruptBlobException} after the last byte if the hash
     * does not match. Callers must discard what they wrote on failure.
     */
    public void streamVerified(String hash, OutputStream out) throws IOException {
        MessageDigest md = Hashes.sha256();
        try (InputStream in = Files.newInputStream(pathOf(hash))) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) {
                md.update(buf, 0, r);
                out.write(buf, 0, r);
            }
        } catch (NoSuchFileException e) {
            throw new CorruptBlobException(hash, "blob is missing");
        }
        if (!Hashes.hex(md.digest()).equals(hash)) throw new CorruptBlobException(hash, "blob content does not match its hash");
    }

    /** Hashes the stored file without keeping it; true if it is present and intact. */
    public boolean isIntact(String hash) {
        try {
            MessageDigest md = Hashes.sha256();
            try (InputStream in = Files.newInputStream(pathOf(hash))) {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            }
            return Hashes.hex(md.digest()).equals(hash);
        } catch (IOException e) {
            return false;
        }
    }

    public void delete(String hash) throws IOException {
        Files.deleteIfExists(pathOf(hash));
    }

    /** Scans the store: hash -> size in bytes. */
    public Map<String, Long> scan() throws IOException {
        Map<String, Long> m = new HashMap<>();
        if (!Files.isDirectory(root)) return m;
        try (Stream<Path> shards = Files.list(root)) {
            for (Path shard : (Iterable<Path>) shards::iterator) {
                if (!Files.isDirectory(shard)) continue;
                try (Stream<Path> files = Files.list(shard)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        String n = f.getFileName().toString();
                        if (Hashes.isHash(n)) m.put(n, Files.size(f));
                    }
                }
            }
        }
        return m;
    }
}
