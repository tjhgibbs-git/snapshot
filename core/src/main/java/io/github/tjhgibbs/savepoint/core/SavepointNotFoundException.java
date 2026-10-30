package io.github.tjhgibbs.savepoint.core;

import java.io.IOException;

public class SavepointNotFoundException extends IOException {
    public SavepointNotFoundException(String id) {
        super("No such savepoint: " + id);
    }
}
