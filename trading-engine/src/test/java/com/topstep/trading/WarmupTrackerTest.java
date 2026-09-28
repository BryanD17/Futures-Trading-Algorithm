package com.topstep.trading;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGENT-05 (V5 RC-15): deterministic, candle-time warmup.
 *
 * <ul>
 *   <li>warmup completes when every REQUIRED feed delivered one LIVE candle;</li>
 *   <li>a dead feed (the MES SMT) no longer holds the trading symbol hostage:
 *       warmup completes at {@code warmup.timeoutSeconds} with a WARN naming
 *       the missing symbol, and a later signal is NOT dropped;</li>
 *   <li>"created before warmup" compares CANDLE times, never wall clock.</li>
 * </ul>
 */
class WarmupTrackerTest {

    private static final Instant T0 = Instant.parse("2026-09-24T12:45:00Z"); // 08:45 ET

    @Test
    void missingSmtCompletesAtTimeoutWithWarnAndLaterSignalIsNotDropped() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        WarmupGuard.Tracker w = new WarmupGuard.Tracker(List.of("MNQ", "MGC", "MES"), 120, clock::get);

        // Backfill replay (before beginLive): does NOT count as live.
        w.onCandle("MNQ", T0.minusSeconds(3600));
        w.onCandle("MES", T0.minusSeconds(3600));
        w.beginLive();
        assertThat(w.isComplete()).isFalse();

        // Live candles for MNQ and MGC only — the MES SMT feed is dead.
        w.onCandle("MNQ", T0);
        w.onCandle("MGC", T0);
        assertThat(w.missing()).containsExactly("MES");

        // 119 s: still warming; a signal is dropped with the missing symbol named.
        clock.addAndGet(119_000L);
        String early = w.dropReason(T0, Instant.now());
        assertThat(early).contains("WARMUP").contains("MES");

        // 120 s: completes with a WARN naming MES.
        PrintStream orig = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true));
        try {
            clock.addAndGet(1_000L);
            assertThat(w.poll()).isTrue();
        } finally {
            System.setOut(orig);
        }
        String log = buf.toString();
        System.out.print(log);
        assertThat(log).contains("WARN warmup timeout").contains("[MES]");
        assertThat(w.completionNote()).contains("MES");
        assertThat(w.completionCandleTime()).isEqualTo(T0);

        // A LATER signal (candle after completion) is NOT dropped.
        assertThat(w.dropReason(T0.plusSeconds(60), Instant.now())).isNull();
        // A signal from a candle BEFORE the completion candle is replay-era.
        assertThat(w.dropReason(T0.minusSeconds(60), Instant.now()))
                .contains("before warmup completion candle");
    }

    @Test
    void allFeedsLiveCompletesImmediatelyOnCandleTime() {
        AtomicLong clock = new AtomicLong(0L);
        WarmupGuard.Tracker w = new WarmupGuard.Tracker(List.of("MNQ", "MES"), 120, clock::get);
        w.beginLive();
        w.onCandle("MNQ", T0);
        assertThat(w.isComplete()).isFalse();
        w.onCandle("MES", T0.plusSeconds(60));
        assertThat(w.isComplete()).isTrue();
        assertThat(w.completionCandleTime()).isEqualTo(T0.plusSeconds(60));
        // Wall clock plays no part: a signal on the completion candle trades.
        assertThat(w.dropReason(T0.plusSeconds(60), Instant.EPOCH)).isNull();
    }

    @Test
    void legacySignalWithoutCandleTimeFallsBackToWallClockCreation() {
        AtomicLong clock = new AtomicLong(5_000L);
        WarmupGuard.Tracker w = new WarmupGuard.Tracker(List.of("MNQ"), 120, clock::get);
        w.beginLive();
        w.onCandle("MNQ", T0);
        assertThat(w.isComplete()).isTrue();
        Instant completedWall = w.completedAtWall();
        assertThat(w.dropReason(null, completedWall.minusMillis(1))).contains("WARMUP");
        assertThat(w.dropReason(null, completedWall.plusMillis(1))).isNull();
    }
}
