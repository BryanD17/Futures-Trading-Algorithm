package com.topstep.trading.chart;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces the TopstepX screenshot pattern end-to-end with synthetic 1m
 * candles (mirror of the offline smoke test): base/dip forms the swing-low
 * origin, a strong up-leg forms the extreme, confirmation chop lets the
 * 30m fractal confirm, then a retrace into the 0.62–0.79 band ARMs the
 * zone, a rejection back above 0.62 flips it to REACTED, and a close below
 * the leg origin INVALIDATES it.
 *
 * <p>Aggregation note: BarAggregationManager completes a 30m bar when a 1m
 * candle in the NEXT clock-aligned 30m period arrives, so each per-bucket
 * candle below finalizes the previous bucket's bar.
 */
class ChartEngineOteLifecycleTest {

    private static final String SYM = "MNQ";
    private static final double TICK = 0.25;
    /** Monday 10:00Z — clock-aligned to a 30m boundary. */
    private static final Instant T0 = Instant.parse("2026-01-05T10:00:00Z");

    /**
     * One flat 30m bar per value: base/dip (swing-low origin 19990 at bar 2),
     * up-leg to the extreme 20040 at bar 7, chop below the high so the
     * 2-bar fractal confirms.
     */
    private static final double[] BARS_30M = {
            20000, 19995, 19990, 19995, 20000,   // base + dip (origin)
            20010, 20025, 20040,                 // up-leg (extreme at idx 7)
            20035, 20030                         // confirmation chop
    };

    private ChartEngine engine;

    private static Instant bucket(int i) {
        return T0.plus(Duration.ofMinutes(30L * i));
    }

    private static Candle flat(Instant ts, double p) {
        return new Candle(SYM, ts, p, p, p, p, 1);
    }

    private static Candle ohlc(Instant ts, double o, double h, double l, double c) {
        return new Candle(SYM, ts, o, h, l, c, 1);
    }

    @BeforeEach
    void setUp() {
        engine = new ChartEngine(); // defaults: 2-bar fractals, 40-tick legs
        engine.registerInstrument(SYM, TICK);
        for (int i = 0; i < BARS_30M.length; i++) {
            engine.onCandle(flat(bucket(i), BARS_30M[i]));
        }
        // No zone yet: bar 9 is still in progress, and at n=9 completed bars
        // the high fractal at index 7 is outside the confirmation window.
        assertTrue(engine.getActiveOteZone(SYM).isEmpty(),
                "zone must not exist before the 10th 30m bar completes");
    }

    @Test
    void fullLifecycle_forming_armed_reacted_invalidated() {
        // (c) Completing bar 9 (candle in bucket 10) confirms the fractal
        // pair and draws the zone in FORMING with the screenshot fibs.
        engine.onCandle(flat(bucket(10), 20030));

        Optional<OteZoneSnapshot> zoneOpt = engine.getActiveOteZone(SYM);
        assertTrue(zoneOpt.isPresent(), "zone should form after fractal confirmation");
        OteZoneSnapshot zone = zoneOpt.get();
        assertEquals(OteState.FORMING, zone.state());
        assertTrue(zone.bullish());
        assertEquals(19990.0, zone.legOrigin(), TICK);
        assertEquals(20040.0, zone.legExtreme(), TICK);
        // Exact fib prices from origin/extreme, within one tick:
        // fib(r) = extreme + (origin - extreme) * r = 20040 - 50r
        assertEquals(20040 - 50 * 0.62, zone.oteStart(), TICK);   // 20009.0
        assertEquals(20040 - 50 * 0.705, zone.oteSweet(), TICK);  // 20004.75
        assertEquals(20040 - 50 * 0.786, zone.fib(0.786), TICK);  // 20000.7
        assertNull(zone.taggedAt(), "FORMING zone has no tag time");
        assertFalse(engine.hasReactedOte(SYM, true));

        // (d) Linear retrace into the band: low tags fib(0.62) → ARMED.
        engine.onCandle(ohlc(bucket(10).plus(Duration.ofMinutes(1)), 20020, 20020, 20012, 20012));
        assertEquals(OteState.FORMING, engine.getActiveOteZone(SYM).get().state(),
                "still above the band — must stay FORMING");
        Instant tagTs = bucket(10).plus(Duration.ofMinutes(2));
        engine.onCandle(ohlc(tagTs, 20010, 20010, 20008, 20009.5));
        zone = engine.getActiveOteZone(SYM).get();
        assertEquals(OteState.ARMED, zone.state(), "band tag must ARM the zone");
        assertEquals(tagTs, zone.taggedAt(), "taggedAt = timestamp of the tagging candle");
        assertFalse(engine.hasReactedOte(SYM, true), "ARMED is not yet REACTED");

        // (e) Rejection: a close back above the 0.62 line → REACTED.
        engine.onCandle(flat(bucket(10).plus(Duration.ofMinutes(3)), 20015));
        zone = engine.getActiveOteZone(SYM).get();
        assertEquals(OteState.REACTED, zone.state());
        assertTrue(engine.hasReactedOte(SYM, true), "the screenshot pattern is complete");
        assertFalse(engine.hasReactedOte(SYM, false), "direction must match the leg");

        // (f) Close below the leg origin (the 1.0) → INVALIDATED, zone gone.
        engine.onCandle(flat(bucket(10).plus(Duration.ofMinutes(4)), 19985));
        assertTrue(engine.getActiveOteZone(SYM).isEmpty(),
                "INVALIDATED zones are not served as active");
        assertFalse(engine.hasReactedOte(SYM, true));
    }

    @Test
    void snapshotReflectsIngestionAndCandles() {
        engine.onCandle(flat(bucket(10), 20030));
        ChartSnapshot snap = engine.snapshot(SYM, 100);
        assertEquals(SYM, snap.symbol());
        assertEquals(10, snap.candles30m().size(), "ten completed 30m bars");
        assertEquals(11, snap.oneMinuteBarsIngested());
        assertEquals(bucket(10), snap.lastCandleTime());
        assertNotNull(snap.activeOte());
        // Unknown symbol → honest empty shape, never null snapshot.
        ChartSnapshot empty = engine.snapshot("XYZ", 100);
        assertEquals(0, empty.candles30m().size());
        assertEquals(0, empty.oneMinuteBarsIngested());
        assertNull(empty.activeOte());
    }
}
