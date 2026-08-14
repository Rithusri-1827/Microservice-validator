package com.msval.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** TEST-006 (unit slice) — the DD-014 dedup window with an injected clock. */
class DedupWindowTest {

    @Test
    void duplicateWithinWindowIsRejected() {
        AtomicLong clock = new AtomicLong(1_000_000);
        DedupWindow w = new DedupWindow(60_000, clock::get);
        assertTrue(w.checkAndPut("e1"), "first sight is fresh");
        assertFalse(w.checkAndPut("e1"), "second sight within the window is a duplicate");
        clock.addAndGet(59_999);
        assertFalse(w.checkAndPut("e1"), "still inside the window");
    }

    @Test
    void entryExpiresAfterWindow() {
        AtomicLong clock = new AtomicLong(1_000_000);
        DedupWindow w = new DedupWindow(60_000, clock::get);
        assertTrue(w.checkAndPut("e1"));
        clock.addAndGet(60_000);
        assertTrue(w.checkAndPut("e1"), "beyond the window the id is fresh again (TEST-006: "
                + "second evaluation is harmless)");
    }

    @Test
    void distinctIdsAreIndependent() {
        AtomicLong clock = new AtomicLong(0);
        DedupWindow w = new DedupWindow(60_000, clock::get);
        assertTrue(w.checkAndPut("a"));
        assertTrue(w.checkAndPut("b"));
        assertFalse(w.checkAndPut("a"));
        assertFalse(w.checkAndPut("b"));
    }

    @Test
    void pruneEvictsAgedEntries() {
        AtomicLong clock = new AtomicLong(0);
        DedupWindow w = new DedupWindow(60_000, clock::get);
        w.checkAndPut("old");
        clock.set(61_000);
        w.checkAndPut("new");
        w.prune();
        assertEquals(1, w.size(), "only the fresh entry survives the scan");
    }
}
