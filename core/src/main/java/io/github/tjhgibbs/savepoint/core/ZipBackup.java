package io.github.tjhgibbs.savepoint.core;

import io.github.tjhgibbs.savepoint.core.Manifest.ChunkRef;
import io.github.tjhgibbs.savepoint.core.Manifest.FileEntry;
import io.github.tjhgibbs.savepoint.core.Manifest.RegionEntry;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Independent full backups: a plain zip of the whole world, reconstructed from a savepoint manifest (region
 * files are rebuilt from chunk blobs, so the zip is consistent with that savepoint no matter what the live
 * world is doing). Runs entirely on the calling thread. Use {@link SavepointStore#buildFullBackup}.
 */
public final class ZipBackup {
    public static final String PREFIX = "savepoint-";
    public static final String SUFFIX = ".zip";

    private ZipBackup() {}

    public static String fileName(Manifest m) {
        return PREFIX + m.id() + SUFFIX;
    }

    static void write(Manifest m, BlobStore blobs, Path zipFile, String rootName, OperationControl ctl) throws IOException {
        if (rootName.isEmpty() || rootName.contains("/") || rootName.contains("\\"))
            throw new IllegalArgumentException("bad zip root folder name: " + rootName);
        Files.createDirectories(zipFile.toAbsolutePath().getParent());
        Path part = zipFile.resolveSibling(zipFile.getFileName() + ".part");
        long total = m.logicalBytes();
        long done = 0;
        try {
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(part), 1 << 16))) {
                for (Map.Entry<String, Manifest.Entry> e : m.entries().entrySet()) {
                    ctl.checkCancelled();
                    ZipEntry ze = new ZipEntry(rootName + "/" + e.getKey());
                    ze.setTime(m.createdMillis());
                    if (e.getValue() instanceof FileEntry f) {
                        zos.setLevel(6);
                        zos.putNextEntry(ze);
                        blobs.streamVerified(f.hash(), zos);
                        done += f.size();
                    } else {
                        RegionEntry r = (RegionEntry) e.getValue();
                        // payloads are already compressed: only deflate (cheaply) regions that are mostly sector padding
                        long padded = 0, payload = 0;
                        for (ChunkRef c : r.chunks()) {
                            padded += (long) RegionWriter.sectorsFor(c.size()) * RegionFile.SECTOR;
                            payload += c.size();
                        }
                        zos.setLevel(payload * 10 < padded * 6 ? 1 : 0);
                        zos.putNextEntry(ze);
                        List<RegionWriter.ChunkOut> outs = new ArrayList<>(r.chunks().size());
                        for (ChunkRef c : r.chunks()) outs.add(new RegionWriter.ChunkOut(c.index(), c.timestamp(), c.compression(), c.size()));
                        RegionWriter.write(zos, outs, c -> {
                            ctl.checkCancelled();
                            return blobs.readVerified(r.chunk(c.index()).hash());
                        });
                        for (ChunkRef c : r.chunks()) done += c.size();
                    }
                    zos.closeEntry();
                    ctl.progress("Writing backup", done, total);
                }
            }
            try {
                Files.move(part, zipFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(part, zipFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /**
     * Keeps the newest {@code keep} files named {@code savepoint-*.zip} in {@code dir} (by modification time)
     * and deletes the rest. Other files are never touched. Returns the deleted files.
     */
    public static List<Path> retain(Path dir, int keep) throws IOException {
        List<Path> deleted = new ArrayList<>();
        if (!Files.isDirectory(dir)) return deleted;
        List<Path> zips;
        try (Stream<Path> s = Files.list(dir)) {
            zips = s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(PREFIX) && n.endsWith(SUFFIX) && Files.isRegularFile(p);
            }).sorted(Comparator.comparing((Path p) -> mtime(p)).thenComparing(p -> p.getFileName().toString()).reversed()).toList();
        }
        for (int i = Math.max(keep, 0); i < zips.size(); i++) {
            Files.deleteIfExists(zips.get(i));
            deleted.add(zips.get(i));
        }
        return deleted;
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
