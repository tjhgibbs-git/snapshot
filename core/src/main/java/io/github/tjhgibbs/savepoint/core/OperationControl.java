package io.github.tjhgibbs.savepoint.core;

/**
 * Progress and cancellation hook for long operations. Callbacks may arrive from the calling thread only
 * (workers never call it), but {@link #isCancelled()} may be polled from worker threads, so keep it
 * thread-safe and cheap. Cancelling makes the operation throw {@link OperationCancelledException} and leave
 * the world / store logically unchanged (restore can no longer be cancelled once it starts moving files).
 */
public interface OperationControl {
    OperationControl NONE = new OperationControl() {};

    /** @param phase short human-readable phase name; done/total are in bytes (or items where noted). */
    default void progress(String phase, long done, long total) {}

    default boolean isCancelled() {
        return false;
    }

    default void checkCancelled() {
        if (isCancelled()) throw new OperationCancelledException();
    }
}
