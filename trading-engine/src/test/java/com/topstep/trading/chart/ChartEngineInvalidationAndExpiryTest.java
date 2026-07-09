package com.topstep.trading.chart;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Zone-lifecycle edge cases: (a) an untagged zone EXPIRES after
 * zoneExpiryBars completed 30m bars; (b) a new extreme beyond the leg does
 * not falsely ARM a stale zone; (c) the bearish mirror of the screenshot
 * pattern (down-leg, retrace UP into the band, rejection DOWN → REACTED).
 */
class ChartEngineInvalidationAndExpiryTest {

    private static final String SYM = "MNQ";
    private static final double TICK = 0.25;
    private static final Instant T0 = Instant.parse("2026-01-05T10:00:00Z");

    private static Instant bucket(int i) {
        return T0.plus(Duration.ofMinutes(30L * i));
    }

    private static Candle flat(Instant ts, double p) {
        return new Candle(SYM, ts, p, p, p, p, 1);
    }

    private static Candle ohlc(Instant ts, double o, double h, double l, double c) {
        return new Candle(SYM, ts, o, h, l, c, 1);
    }

    @Test
    void untaggedZoneExpiresAfterExpiryBars() {
        // Short expiry so the test stays small: 4 completed 30m bars.
        ChartEngine engine = new ChartEngine(2, 40, 4);
        engine.registerInstrument(SYM, TICK);

        // Base + up-leg; the tail (20036/20033) and the following tight chop
        // are chosen so every post-zone fractal pair yields a leg < 40 ticks
        // (10 pts) and therefore never REPLACES the zone under test.
        double[] base = {20000, 19995, 19990, 19995, 20000, 20010, 20025, 20040, 20036, 20033};
        for (int i = 0; i < base.length; i++) {
            engine.onCandle(flat(bucket(i), base[i]));
        }
        // Chop bars 10..15 (each candle completes the previous bar). All
        // prices stay above the 0.62 line (20009) so the zone is never
        // tagged, and below 20040 so the leg never extends.
        double[] chop = {20034, 20036, 20038, 20036, 20034, 20036};

        engine.onCandle(flat(bucket(10), chop[0])); // completes bar 9 → zone FORMING
        OteZoneSnapshot zone = engine.getActiveOteZone(SYM).orElseThrow();
        assertEquals(OteState.FORMING, zone.state());
        assertEquals(19990.0, zone.legOrigin(), TICK);
        assertEquals(20040.0, zone.legExtreme(), TICK);

        // Bars 10..13 complete (counts 1..4): still FORMING, never tagged.
        for (int i = 1; i <= 4; i++) {
            engine.onCandle(flat(bucket(10 + i), chop[i]));
            Optional<OteZoneSnapshot> z = engine.getActiveOteZone(SYM);
            assertTrue(z.isPresent(), "zone must survive " + i + " completed bars");
            assertEquals(OteState.FORMING, z.get().state());
            assertNull(z.get().taggedAt());
        }

        // Bar 14 completes (count 5 > 4): EXPIRED, and the surrounding chop
        // fractals (leg < 10 pts) prevent an immediate same-leg redraw.
        engine.onCandle(flat(bucket(15), chop[5]));
        assertTrue(engine.getActiveOteZone(SYM).isEmpty(),
                "an untagged zone must expire after zoneExpiryBars completed 30m bars");
        assertFalse(engine.hasReactedOte(SYM, true));
    }

    @Test
    void newExtremeBeyondLegDoesNotFalselyArmStaleZone() {
        ChartEngine engine = new ChartEngine();
        engine.registerInstrument(SYM, TICK);
        double[] base = {20000, 19995, 19990, 19995, 20000, 20010, 20025, 20040, 20035, 20030};
        for (int i = 0; i < base.length; i++) {
            engine.onCandle(flat(bucket(i), base[i]));
        }
        engine.onCandle(flat(bucket(10), 20030)); // zone FORMING on stale fibs
        assertEquals(OteState.FORMING, engine.getActiveOteZone(SYM).orElseThrow().state());

        // One candle BOTH extends the leg (high > 20040) AND dips into the
        // old band (low <= 20009): the stale fibs must NOT arm.
        engine.onCandle(ohlc(bucket(10).plus(Duration.ofMinutes(1)),
                20035, 20045, 20005, 20035));
        OteZoneSnapshot zone = engine.getActiveOteZone(SYM).orElseThrow();
        assertEquals(OteState.FORMING, zone.state(),
                "a leg extension must suppress arming on stale fibs");
        assertNull(zone.taggedAt());
    }

    @Test
    void bearishMirror_downLeg_retraceUp_rejectionDown_reacted() {
        ChartEngine engine = new ChartEngine();
        engine.registerInstrument(SYM, TICK);
        // Mirror: swing-high origin 20010 at bar 2, down-leg to the extreme
        // 19960 at bar 7, chop above the low confirms the fractal.
        double[] base = {20000, 20005, 20010, 20005, 20000, 19990, 19975, 19960, 19965, 19969};
        for (int i = 0; i < base.length; i++) {
            engine.onCandle(flat(bucket(i), base[i]));
        }
        engine.onCandle(flat(bucket(10), 19970)); // completes bar 9 → zone

        OteZoneSnapshot zone = engine.getActiveOteZone(SYM).orElseThrow();
        assertEquals(OteState.FORMING, zone.state());
        assertFalse(zone.bullish(), "down-leg must produce a bearish zone");
        assertEquals(20010.0, zone.legOrigin(), TICK);
        assertEquals(19960.0, zone.legExtreme(), TICK);
        // fib(0.62) = 19960 + 50 * 0.62 = 19991 (band is ABOVE the extreme).
        assertEquals(19960 + 50 * 0.62, zone.oteStart(), TICK);

        // Retrace UP into the band: high >= fib(0.62) → ARMED.
        Instant tagTs = bucket(10).plus(Duration.ofMinutes(1));
        engine.onCandle(ohlc(tagTs, 19985, 19992, 19985, 19990.5));
        zone = engine.getActiveOteZone(SYM).orElseThrow();
        assertEquals(OteState.ARMED, zone.state());
        assertEquals(tagTs, zone.taggedAt());

        // Rejection DOWN (close back below the 0.62) → REACTED, bearish.
        engine.onCandle(flat(bucket(10).plus(Duration.ofMinutes(2)), 19985));
        zone = engine.getActiveOteZone(SYM).orElseThrow();
        assertEquals(OteState.REACTED, zone.state());
        assertFalse(zone.bullish());
        assertTrue(engine.hasReactedOte(SYM, false));
        assertFalse(engine.hasReactedOte(SYM, true));

        // Close ABOVE the bearish origin invalidates.
        engine.onCandle(flat(bucket(10).plus(Duration.ofMinutes(3)), 20015));
        assertTrue(engine.getActiveOteZone(SYM).isEmpty());
    }
}
