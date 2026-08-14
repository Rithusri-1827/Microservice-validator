package com.msval.governance.gateway;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * DD-014 — event_id dedup window: {@code ConcurrentHashMap<String, Long>} of first-seen
 * millis; duplicates within the window are ACK-only. Entries older than the window are
 * pruned by scan (piggy-backed here on a 60 s cadence rather than a dedicated thread).
 */
public final class DedupWindow {

    private final long windowMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();
    private volatile long lastPrune;

    public DedupWindow(long windowMs) {
        this(windowMs, System::currentTimeMillis);
    }

    public DedupWindow(long windowMs, LongSupplier clock) {
        this.windowMs = windowMs;
        this.clock = clock;
        this.lastPrune = clock.getAsLong();
    }

    /** true = fresh (recorded now); false = duplicate within the window. */
    public boolean checkAndPut(String eventId) {
        long now = clock.getAsLong();
        maybePrune(now);
        while (true) {
            Long prior = seen.putIfAbsent(eventId, now);
            if (prior == null) {
                return true;
            }
            if (now - prior < windowMs) {
                return false;
            }
            if (seen.replace(eventId, prior, now)) { // expired entry: treat as fresh
                return true;
            }
        }
    }

    private void maybePrune(long now) {
        if (now - lastPrune < 60_000) {
            return;
        }
        lastPrune = now;
        seen.entrySet().removeIf(e -> now - e.getValue() >= windowMs);
    }

    /** Test hook. */
    public int size() {
        return seen.size();
    }

    /** Test hook: force the periodic scan. */
    public void prune() {
        long now = clock.getAsLong();
        seen.entrySet().removeIf(e -> now - e.getValue() >= windowMs);
    }
}
