package com.topstep.trading.chart;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Determinism is a stated success criterion of this codebase: the same
 * candle sequence must produce identical outputs, always. Two fresh engines
 * fed the identical list must agree on zone state, fib prices, and 30m
 * candle counts after EVERY candle — not just at the end.
 */
class ChartEngineDeterminismTest {

    private static final String SYM = "MNQ";
    private static final Instant T0 = Instant.parse("2026-01-05T10:00:00Z");

    private static Candle ohlc(Instant ts, double o, double h, double l, double c) {
        return new Candle(SYM, ts, o, h, l, c, 1);
    }

    /** The full lifecycle sequence: base, leg, chop, tag, reject, break. */
    private static List<Candle> lifecycleSequence() {
        double[] bars30 = {20000, 19995, 19990, 19995, 20000, 20010, 20025, 20040, 20035, 20030};
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < bars30.length; i++) {
            Instant ts = T0.plus(Duration.ofMinutes(30L * i));
            out.add(ohlc(ts, bars30[i], bars30[i], bars30[i], bars30[i]));
        }
        Instant b10 = T0.plus(Duration.ofMinutes(300));
        out.add(ohlc(b10, 20030, 20030, 20030, 20030));                               // forms zone
        out.add(ohlc(b10.plus(Duration.ofMinutes(1)), 20020, 20020, 20012, 20012));   // approach
        out.add(ohlc(b10.plus(Duration.ofMinutes(2)), 20010, 20010, 20008, 20009.5)); // tag → ARMED
        out.add(ohlc(b10.plus(Duration.ofMinutes(3)), 20015, 20015, 20015, 20015));   // → REACTED
        out.add(ohlc(b10.plus(Duration.ofMinutes(4)), 19985, 19985, 19985, 19985));   // → INVALIDATED
        return out;
    }

    @Test
    void twoEnginesAgreeAfterEveryCandle() {
        ChartEngine a = new ChartEngine();
        ChartEngine b = new ChartEngine();
        a.registerInstrument(SYM, 0.25);
        b.registerInstrument(SYM, 0.25);

        int i = 0;
        for (Candle c : lifecycleSequence()) {
            a.onCandle(c);
            b.onCandle(c);
            i++;

            // 30m candle series identical (count and content).
            List<Candle> barsA = a.get30mCandles(SYM, 1000);
            List<Candle> barsB = b.get30mCandles(SYM, 1000);
            assertEquals(barsA.size(), barsB.size(), "30m count diverged at candle " + i);
            for (int k = 0; k < barsA.size(); k++) {
                assertEquals(barsA.get(k).getTimestamp(), barsB.get(k).getTimestamp());
                assertEquals(barsA.get(k).getOpen(), barsB.get(k).getOpen());
                assertEquals(barsA.get(k).getHigh(), barsB.get(k).getHigh());
                assertEquals(barsA.get(k).getLow(), barsB.get(k).getLow());
                assertEquals(barsA.get(k).getClose(), barsB.get(k).getClose());
            }

            // Zone identical: OteZoneSnapshot is a record → structural equals
            // covers state, direction, origin/extreme (and therefore fibs),
            // times, and taggedAt.
            Optional<OteZoneSnapshot> zoneA = a.getActiveOteZone(SYM);
            Optional<OteZoneSnapshot> zoneB = b.getActiveOteZone(SYM);
            assertEquals(zoneA, zoneB, "zone diverged at candle " + i);
            zoneA.ifPresent(z -> {
                assertEquals(z.oteStart(), zoneB.get().oteStart());
                assertEquals(z.oteSweet(), zoneB.get().oteSweet());
                assertEquals(z.oteEnd(), zoneB.get().oteEnd());
            });

            // Gate answers identical.
            assertEquals(a.hasReactedOte(SYM, true), b.hasReactedOte(SYM, true));
            assertEquals(a.hasReactedOte(SYM, false), b.hasReactedOte(SYM, false));

            // Snapshot bookkeeping identical.
            assertEquals(a.snapshot(SYM, 100).oneMinuteBarsIngested(),
                    b.snapshot(SYM, 100).oneMinuteBarsIngested());
            assertEquals(a.snapshot(SYM, 100).lastCandleTime(),
                    b.snapshot(SYM, 100).lastCandleTime());
        }

        // Sanity: the sequence actually exercised the whole lifecycle.
        assertTrue(i >= 15);
        assertTrue(a.getActiveOteZone(SYM).isEmpty(), "sequence ends INVALIDATED");
    }
}
