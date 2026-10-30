package io.github.tjhgibbs.savepoint.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.tjhgibbs.savepoint.core.Manifest.Kind;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ServiceLogicTest {
    @Test
    void cooldown() {
        AtomicLong now = new AtomicLong(1_000_000);
        Cooldown c = new Cooldown(now::get);
        assertEquals(0, c.remainingSeconds(10));
        c.mark();
        assertEquals(10, c.remainingSeconds(10));
        now.addAndGet(2_500);
        assertEquals(8, c.remainingSeconds(10));
        assertEquals(0, c.remainingSeconds(0));
        now.addAndGet(8_000);
        assertEquals(0, c.remainingSeconds(10));
    }

    private static Manifest m(String id, String name) {
        return new Manifest(id, name, "", 0, false, false, Kind.MANUAL, null, null, Map.of());
    }

    @Test
    void idResolution() {
        List<Manifest> all = List.of(m("20261030-120000-a1b2", "Before boss"), m("20261030-110000-c3d4", "Before bridge"),
                m("20261030-100000-a1ff", "Start"));
        assertInstanceOf(IdResolver.Found.class, IdResolver.resolve(all, "20261030-110000-c3d4"));
        assertEquals("c3d4", ((IdResolver.Found) IdResolver.resolve(all, "c3d4")).manifest().id().substring(16));
        assertEquals("Before boss", ((IdResolver.Found) IdResolver.resolve(all, "latest")).manifest().name());
        assertEquals("Start", ((IdResolver.Found) IdResolver.resolve(all, "start")).manifest().name());
        assertEquals("Before boss", ((IdResolver.Found) IdResolver.resolve(all, "before bo")).manifest().name());
        assertInstanceOf(IdResolver.Ambiguous.class, IdResolver.resolve(all, "before"));
        assertInstanceOf(IdResolver.NotFound.class, IdResolver.resolve(all, "zzzz"));
        assertInstanceOf(IdResolver.NotFound.class, IdResolver.resolve(List.of(), "latest"));
        assertEquals("a1ff", IdResolver.shortId(all.get(2)));
    }

    @Test
    void settingsValidationAndRoundTrip() throws IOException {
        Settings s = Settings.DEFAULTS.with("storeBudgetGb", "2.5").with("fullBackupMode", "every").with("cooldownSeconds", "0");
        assertEquals(2.5, s.storeBudgetGb());
        assertEquals(Settings.FullBackupMode.EVERY, s.fullBackupMode());
        assertEquals(0, s.cooldownSeconds());
        assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULTS.with("keepNewest", "-1"));
        assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULTS.with("keepNewest", "abc"));
        assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULTS.with("fullBackupMode", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULTS.with("nope", "1"));
        StringWriter w = new StringWriter();
        s.store(w);
        assertEquals(s, Settings.load(new StringReader(w.toString())));
        // lenient load: garbage falls back to defaults
        Settings l = Settings.load(new StringReader("keepNewest=banana\ncooldownSeconds=4\n"));
        assertEquals(3, l.keepNewest());
        assertEquals(4, l.cooldownSeconds());
        assertEquals(10L * 1024 * 1024 * 1024, Settings.DEFAULTS.storeBudgetBytes());
        for (Settings.Spec sp : Settings.specs()) assertFalse(sp.description().isBlank());
    }
}
