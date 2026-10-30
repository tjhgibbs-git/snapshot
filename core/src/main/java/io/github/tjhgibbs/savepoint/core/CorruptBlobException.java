package io.github.tjhgibbs.savepoint.core;

import java.io.IOException;

/** A blob is missing from the store or its bytes no longer hash to its name. */
public class CorruptBlobException extends IOException {
    private final String hash;

    public CorruptBlobException(String hash, String message) {
        super(message + " (blob " + hash + ")");
        this.hash = hash;
    }

    public String hash() {
        return hash;
    }
}
