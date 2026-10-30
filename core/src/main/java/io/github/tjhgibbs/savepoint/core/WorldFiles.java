package io.github.tjhgibbs.savepoint.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Enumerates everything under a world directory that a savepoint captures: all regular files except the
 * top-level {@code session.lock}, and except any excluded directory (the savepoint store, if nested).
 * Symlinks are not followed. Paths are world-relative with forward slashes, sorted.
 */
public final class WorldFiles {
    public static final String SESSION_LOCK = "session.lock";

    private WorldFiles() {}

    public static List<String> list(Path worldDir, Collection<Path> excludedDirs) throws IOException {
        Path root = worldDir.toAbsolutePath().normalize();
        List<Path> ex = new ArrayList<>();
        for (Path p : excludedDirs) ex.add(p.toAbsolutePath().normalize());
        List<String> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                return ex.contains(dir.toAbsolutePath().normalize()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                if (!a.isRegularFile()) return FileVisitResult.CONTINUE;
                String rel = relative(root, f);
                if (!rel.equals(SESSION_LOCK)) out.add(rel);
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(out);
        return out;
    }

    public static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** Resolves a manifest path under {@code worldDir}, refusing anything that would escape it. */
    public static Path resolveSafe(Path worldDir, String rel) throws IOException {
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains("\\") || rel.contains("\0"))
            throw new IOException("unsafe path in manifest: " + rel);
        for (String part : rel.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IOException("unsafe path in manifest: " + rel);
        if (rel.equals(SESSION_LOCK)) throw new IOException("manifest must not contain session.lock");
        return worldDir.resolve(rel);
    }
}
