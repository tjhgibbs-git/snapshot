package io.github.tjhgibbs.savepoint.core;

import java.io.IOException;

/** A .mca file does not parse as a region file. Callers fall back to storing it as an opaque file. */
public class MalformedRegionException extends IOException {
    public MalformedRegionException(String message) {
        super(message);
    }
}
