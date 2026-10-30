package io.github.tjhgibbs.savepoint.core;

import java.util.function.LongSupplier;

/** Shared cooldown timer for create and revert. Thread-safe. */
public final class Cooldown {
    private final LongSupplier clock;
    private long lastMillis = Long.MIN_VALUE / 2;

    public Cooldown() {
        this(System::currentTimeMillis);
    }

    public Cooldown(LongSupplier clock) {
        this.clock = clock;
    }

    /** Seconds still to wait (rounded up), or 0 if an action may happen now. Does not start the timer. */
    public synchronized int remainingSeconds(int cooldownSeconds) {
        if (cooldownSeconds <= 0) return 0;
        long left = lastMillis + cooldownSeconds * 1000L - clock.getAsLong();
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    /** Marks an action as having just happened. */
    public synchronized void mark() {
        lastMillis = clock.getAsLong();
    }
}
