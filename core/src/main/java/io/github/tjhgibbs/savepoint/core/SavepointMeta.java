package io.github.tjhgibbs.savepoint.core;

import io.github.tjhgibbs.savepoint.core.Manifest.Kind;
import io.github.tjhgibbs.savepoint.core.Manifest.PlayerPos;

/**
 * Caller-supplied metadata for a new savepoint. {@code player} and {@code thumbnailPng} are optional (null).
 * The store never inspects the world to fill these in.
 */
public record SavepointMeta(
        String name, String note, Kind kind, boolean auto, boolean pinned, PlayerPos player, byte[] thumbnailPng) {

    public static SavepointMeta of(Kind kind, String name) {
        return new SavepointMeta(name, "", kind, kind == Kind.UNDO, false, null, null);
    }

    public SavepointMeta withNote(String n) {
        return new SavepointMeta(name, n, kind, auto, pinned, player, thumbnailPng);
    }

    public SavepointMeta withAuto(boolean a) {
        return new SavepointMeta(name, note, kind, a, pinned, player, thumbnailPng);
    }

    public SavepointMeta withPinned(boolean p) {
        return new SavepointMeta(name, note, kind, auto, p, player, thumbnailPng);
    }

    public SavepointMeta withPlayer(PlayerPos p) {
        return new SavepointMeta(name, note, kind, auto, pinned, p, thumbnailPng);
    }

    public SavepointMeta withThumbnail(byte[] png) {
        return new SavepointMeta(name, note, kind, auto, pinned, player, png);
    }
}
