package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.Event;
import com.topstep.trading.event.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIELD BUG regression (2026-07-09 LIVE): the entry-anatomy detectors
 * (displacement/FVG/MSS) were fed raw 1m candles, so a displacement that
 * is obvious on the 5m chart — five directional but individually wicky 1m
 * candles — never registered, and MNQ fired ZERO displacements across an
 * entire LIVE session while the machine sat at SWEEP_DONE.
 *
 * <p>These tests feed the SAME 1m stream both ways: with the new default
 * (5m anatomy) the displacement is detected; pinned back to 1m it is not —
 * proving the granularity, not the detector, was the blocker.
 */
@DisplayName("Detector timeframe: 5m anatomy sees what 1m cannot (field bug 2026-07-09)")
class StdvOteDetectorTimeframeTest {

    static final class NullBus extends EventBus {
        @Override public void publish(Event event) { /* drop */ }
    }

    private static final Instant T0 = Instant.parse("2026-07-08T14:00:00Z");

    @AfterEach
    void cleanup() {
        System.clearProperty("stdvote.detectorTimeframe");
        StdvOteRegistry.unregister("MNQ");
    }

    /**
     * 1m candle that closes {@code drift} above its open but carries wide
     * wicks — body ratio ~0.32, far below the 0.65 strong-candle floor, so
     * the 1m detector can never call it displacement.
     */
    private static Candle wicky(Instant ts, double open, double drift) {
        double close = open + drift;
        double high = Math.max(open, close) + 1.25;
        double low = Math.min(open, close) - 1.25;
        return new Candle("MNQ", ts, open, high, low, close, 100);
    }

    /** Drive the stream and capture the strategy-layer stdout. */
    private static String driveAndCapture() {
        StdvOteRunnerStrategy runner = new StdvOteRunnerStrategy("MNQ", null, new NullBus());
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            int minute = 0;
            double price = 20000.0;
            // Quiet phase: 16 five-minute buckets of flat, wicky 1m candles
            // (5m range 2.5) — establishes a small 5m ATR baseline.
            for (int b = 0; b < 16; b++) {
                for (int i = 0; i < 5; i++) {
                    runner.onCandle(wicky(T0.plus(Duration.ofMinutes(minute++)), price, 0.0), null);
                }
            }
            // Impulse phase: five consecutive 1m candles each +1.2 with wide
            // wicks — individually weak (body ratio 0.32), but the aggregate
            // 5m candle has a 6-point body at ~70% ratio: a textbook 5m
            // displacement.
            for (int i = 0; i < 5; i++) {
                runner.onCandle(wicky(T0.plus(Duration.ofMinutes(minute++)), price, 1.2), null);
                price += 1.2;
            }
            // One more candle to CLOSE the impulse 5m bucket (aggregation
            // completes a bucket when the next one opens).
            runner.onCandle(wicky(T0.plus(Duration.ofMinutes(minute)), price, 0.0), null);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("default (5m): the aggregate displacement IS detected from weak 1m candles")
    void fiveMinuteAnatomyDetectsTheAggregateMove() {
        System.clearProperty("stdvote.detectorTimeframe"); // default = 5m
        String out = driveAndCapture();
        assertThat(out)
                .as("five weak 1m candles form one strong 5m displacement — the 5m detector must see it")
                .contains("[DISPLACEMENT MNQ] BULLISH");
    }

    @Test
    @DisplayName("pinned 1m: the SAME stream produces no displacement (the old blindness)")
    void oneMinuteAnatomyMissesTheSameMove() {
        System.setProperty("stdvote.detectorTimeframe", "1");
        String out = driveAndCapture();
        assertThat(out)
                .as("no single 1m candle clears the strong-candle floor — 1m anatomy stays blind")
                .doesNotContain("[DISPLACEMENT MNQ]");
    }
}
