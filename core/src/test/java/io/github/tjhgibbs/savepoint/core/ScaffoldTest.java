package io.github.tjhgibbs.savepoint.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScaffoldTest {
    @Test
    void coreIsWired() {
        assertEquals("Savepoint", Savepoint.NAME);
    }
}
