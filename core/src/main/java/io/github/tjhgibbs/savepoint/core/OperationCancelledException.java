package io.github.tjhgibbs.savepoint.core;

/** Thrown when an {@link OperationControl} reports cancellation. */
public class OperationCancelledException extends RuntimeException {
    public OperationCancelledException() {
        super("Operation cancelled");
    }
}
