package com.topstep.trading;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The warmup guard's suppression predicates, extracted from
 * LiveEngineRunner.handleStrategySignal into {@link WarmupGuard} precisely
 * so they can be asserted without booting the runner (per Agent 10 task 5).
 *
 * <p>Semantics under test: a StrategySignalEvent arriving while the
 * symbol's last candle timestamp is more than 5 minutes behind wall-clock
 * is suppressed (no order created); a fresh-candle signal passes; and a
 * signal CREATED during warmup is suppressed even when dequeued after
 * warmup completed (async EventBus).
 */
class WarmupGuardTest {

    private static final long THRESHOLD = 5 * 60; // LiveEngineRunner's value

    @Test
    void signalWithStaleCandleIsSuppressed() {
        Instant now = Instant.parse("2026-07-08T15:00:00Z");
        // Last candle 5m01s old → beyond the 5-minute threshold → suppress.
        Instant stale = now.minusSeconds(THRESHOLD + 1);
        assertTrue(WarmupGuard.isStaleSignal(stale, now, THRESHOLD));
        // A candle from a replayed session hours ago → suppress.
        assertTrue(WarmupGuard.isStaleSignal(now.minusSeconds(7 * 3600), now, THRESHOLD));
    }

    @Test
    void signalWithFreshCandlePasses() {
        Instant now = Instant.parse("2026-07-08T15:00:00Z");
        assertFalse(WarmupGuard.isStaleSignal(now.minusSeconds(30), now, THRESHOLD));
        // Exactly at the threshold is still acceptable (isBefore is strict).
        assertFalse(WarmupGuard.isStaleSignal(now.minusSeconds(THRESHOLD), now, THRESHOLD));
    }

    @Test
    void signalWithNoCandleHistoryIsSuppressed() {
        // No candle ever seen for the symbol → nothing proves freshness →
        // fail-safe suppress.
        assertTrue(WarmupGuard.isStaleSignal(null,
                Instant.parse("2026-07-08T15:00:00Z"), THRESHOLD));
    }

    @Test
    void signalCreatedDuringWarmupIsSuppressedEvenIfDequeuedLater() {
        Instant warmupDone = Instant.parse("2026-07-08T15:00:00Z");
        // Created one second before warmup completed → replay-era → suppress.
        assertTrue(WarmupGuard.createdDuringWarmup(
                warmupDone.minusSeconds(1), warmupDone));
        // Created after warmup completed → passes.
        assertFalse(WarmupGuard.createdDuringWarmup(
                warmupDone.plusSeconds(1), warmupDone));
        // Warmup timestamp not yet set (layer-1 flag handles that phase) or
        // signal without a timestamp → this predicate does not suppress.
        assertFalse(WarmupGuard.createdDuringWarmup(warmupDone, null));
        assertFalse(WarmupGuard.createdDuringWarmup(null, warmupDone));
    }
}
