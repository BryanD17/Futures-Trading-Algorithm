package com.topstep.trading;

import java.time.Instant;

/**
 * Pure predicates for {@link LiveEngineRunner}'s warmup guard, extracted so
 * the suppression logic is unit-testable without booting the runner (which
 * needs a connector, credentials, and a live EventBus).
 *
 * <p>The guard exists because the startup backfill replays days of history
 * through the same candle path as live data: a historical candle inside a
 * past killzone could satisfy every gate and emit a signal for a price from
 * yesterday. These predicates decide when such a signal must be suppressed.
 */
final class WarmupGuard {

    private WarmupGuard() {}

    /**
     * True when the most recent candle seen for the signal's symbol is
     * missing or older than {@code thresholdSeconds} behind wall-clock —
     * meaning the signal was produced by historical/replayed (or stalled)
     * data and must not create an order. Self-healing across reconnects
     * and replays.
     */
    static boolean isStaleSignal(Instant lastCandleTs, Instant now, long thresholdSeconds) {
        return lastCandleTs == null
                || lastCandleTs.isBefore(now.minusSeconds(thresholdSeconds));
    }

    /**
     * True when the signal event was CREATED before warmup completed. The
     * EventBus dispatches asynchronously (queue + worker thread), so a
     * signal emitted during replay can be dequeued after the warmup flag
     * flips true — creation time is the reliable discriminator.
     */
    static boolean createdDuringWarmup(Instant signalCreatedAt, Instant warmupCompletedAt) {
        return warmupCompletedAt != null
                && signalCreatedAt != null
                && signalCreatedAt.isBefore(warmupCompletedAt);
    }
}
