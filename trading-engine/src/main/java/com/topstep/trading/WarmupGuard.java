package com.topstep.trading;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Pure predicates + a deterministic readiness tracker for the runners'
 * warmup guard (V5 RC-15), extracted so the suppression logic is
 * unit-testable without booting a runner (which needs a connector,
 * credentials, and a live EventBus).
 *
 * <p>The guard exists because the startup backfill replays days of history
 * through the same candle path as live data: a historical candle inside a
 * past killzone could satisfy every gate and emit a signal for a price from
 * yesterday. Such a signal must never become an order.
 *
 * <h2>V5 rule (deterministic, candle-time based)</h2>
 * <ol>
 *   <li>{@link Tracker#beginLive()} is called once every initial
 *       subscription (and its synchronous backfill) has returned. Candles
 *       delivered after that are LIVE candles.</li>
 *   <li>Warmup COMPLETES when every REQUIRED symbol has delivered at least
 *       one live candle, OR after {@code warmup.timeoutSeconds} (default
 *       120) with a WARN naming the missing symbol(s) — a dead SMT feed can
 *       no longer hold the trading symbol hostage.</li>
 *   <li>The completion CANDLE time is recorded; a signal is "created before
 *       warmup" when its {@code candleTime} is before that candle time —
 *       candle time vs candle time, never wall clock.</li>
 * </ol>
 */
final class WarmupGuard {

    /** Signals dropped by any warmup layer, process-wide (telemetry). */
    private static final AtomicLong DROPPED = new AtomicLong();

    private WarmupGuard() {}

    /** Process-wide count of warmup-dropped signals (for /api/status). */
    static long droppedSignals() {
        return DROPPED.get();
    }

    static void recordDrop() {
        DROPPED.incrementAndGet();
    }

    /**
     * True when the reference candle time is missing or older than
     * {@code thresholdSeconds} behind wall-clock — meaning the signal was
     * produced by historical/replayed (or stalled) data and must not create
     * an order. LIVE only (SIM's virtual clock is not wall clock).
     */
    static boolean isStaleSignal(Instant lastCandleTs, Instant now, long thresholdSeconds) {
        return lastCandleTs == null
                || lastCandleTs.isBefore(now.minusSeconds(thresholdSeconds));
    }

    /**
     * True when the signal was produced BEFORE warmup completed. Compares
     * the signal's CANDLE time with the warmup-completion CANDLE time when
     * both are known; falls back to the wall-clock creation stamps only for
     * legacy signals that carry no candle time.
     */
    static boolean createdDuringWarmup(Instant signalCreatedAt, Instant warmupCompletedAt) {
        return warmupCompletedAt != null
                && signalCreatedAt != null
                && signalCreatedAt.isBefore(warmupCompletedAt);
    }

    /**
     * Per-symbol readiness + timeout. Thread-safe; the clock is injectable
     * for tests ({@code nowMillis}).
     */
    static final class Tracker {
        private final Set<String> required;
        private final long timeoutMillis;
        private final LongSupplier nowMillis;
        private final Map<String, Instant> firstLiveCandle = new ConcurrentHashMap<>();
        private final Map<String, Instant> lastCandle = new ConcurrentHashMap<>();
        private volatile long liveSinceMillis = -1L;
        private volatile boolean complete = false;
        private volatile Instant completionCandleTime;
        private volatile Instant completedAtWall;
        private volatile String completionNote = "";

        Tracker(Collection<String> requiredSymbols, long timeoutSeconds, LongSupplier nowMillis) {
            this.required = new LinkedHashSet<>(requiredSymbols);
            this.timeoutMillis = Math.max(1L, timeoutSeconds) * 1000L;
            this.nowMillis = nowMillis;
        }

        Tracker(Collection<String> requiredSymbols, long timeoutSeconds) {
            this(requiredSymbols, timeoutSeconds, System::currentTimeMillis);
        }

        /** Subscriptions + synchronous backfill returned: later candles are live. */
        synchronized void beginLive() {
            if (liveSinceMillis < 0) {
                liveSinceMillis = nowMillis.getAsLong();
            }
            maybeComplete();
        }

        /** Record a candle (replay or live) for a symbol. */
        synchronized void onCandle(String symbol, Instant candleTime) {
            if (symbol == null || candleTime == null) return;
            lastCandle.merge(symbol, candleTime, (a, b) -> b.isAfter(a) ? b : a);
            if (liveSinceMillis >= 0 && !complete) {
                firstLiveCandle.putIfAbsent(symbol, candleTime);
                maybeComplete();
            }
        }

        /** Re-evaluate the timeout (call periodically and on every signal). */
        synchronized boolean poll() {
            maybeComplete();
            return complete;
        }

        private void maybeComplete() {
            if (complete || liveSinceMillis < 0) return;
            Set<String> missing = missing();
            if (missing.isEmpty()) {
                finish(maxCandleTime(firstLiveCandle), "all required feeds delivered a live candle");
                return;
            }
            long waited = nowMillis.getAsLong() - liveSinceMillis;
            if (waited >= timeoutMillis) {
                String note = "WARN warmup timeout after " + (waited / 1000) + "s — missing live candle from "
                        + missing + "; completing without it";
                System.out.println("[Warmup] " + note);
                // Completion candle time = the newest candle any feed has
                // delivered so far (the trading symbol's clock).
                finish(maxCandleTime(lastCandle), note);
            }
        }

        private void finish(Instant candleTime, String note) {
            complete = true;
            completionCandleTime = candleTime;
            completedAtWall = Instant.ofEpochMilli(nowMillis.getAsLong());
            completionNote = note;
            System.out.println("[Warmup] complete at candle " + candleTime + " — " + note);
        }

        private static Instant maxCandleTime(Map<String, Instant> m) {
            Instant max = null;
            for (Instant t : m.values()) {
                if (max == null || t.isAfter(max)) max = t;
            }
            return max;
        }

        Set<String> missing() {
            Set<String> out = new LinkedHashSet<>();
            for (String s : required) {
                if (!firstLiveCandle.containsKey(s)) out.add(s);
            }
            return out;
        }

        boolean isComplete() { return complete; }
        Instant completionCandleTime() { return completionCandleTime; }
        Instant completedAtWall() { return completedAtWall; }
        String completionNote() { return completionNote; }
        Instant lastCandle(String symbol) { return lastCandle.get(symbol); }

        /**
         * Why a signal must be dropped, or null when it may trade.
         *
         * @param signalCandleTime  the signal's candle time (may be null for legacy signals)
         * @param signalCreatedWall the signal's wall-clock creation time (fallback only)
         */
        String dropReason(Instant signalCandleTime, Instant signalCreatedWall) {
            poll();
            if (!complete) {
                return "WARMUP: not complete (missing live candle from " + missing() + ")";
            }
            if (signalCandleTime != null && completionCandleTime != null) {
                if (signalCandleTime.isBefore(completionCandleTime)) {
                    return "WARMUP: signal candle " + signalCandleTime
                            + " is before warmup completion candle " + completionCandleTime;
                }
                return null;
            }
            if (signalCandleTime == null && createdDuringWarmup(signalCreatedWall, completedAtWall)) {
                return "WARMUP: signal created (wall) " + signalCreatedWall
                        + " before warmup completed " + completedAtWall;
            }
            return null;
        }
    }
}
