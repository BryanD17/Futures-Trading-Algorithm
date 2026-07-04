package com.topstep.trading.backtest;

import com.topstep.trading.domain.Candle;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic synthetic multi-session MNQ candle generator for the SA5
 * A/B backtest harness ({@link AbBacktestComparison}).
 *
 * <p>NO real historical CSV exists in this repository, so the A/B harness
 * runs on this generator: each trading day is a 1-minute-candle session
 * engineered to drive the FULL STDV+OTE state machine twice —
 * HTF warmup (7×15m of rising structure) → NY AM killzone open (9:45 ET) →
 * Judas dip with an EQUAL-LOWS cluster (three fractal swing lows within the
 * EqualLevelDetector tolerance, strictly descending) → liquidity sweep that
 * raids the cluster (raid quality score 6: HTF+2, killzone+2, Silver
 * Bullet+1, cluster≥3 +1) → displacement + FVG → MSS → OTE retrace →
 * emission — then a round-trip candle, a cooldown bridge, and a complete
 * SECOND act of the same sequence, a second round trip, and a rally that
 * fills the legacy −2σ target.
 *
 * <p>Geometry per session (day offset {@code +65×d} keeps the multi-day
 * trend bullish so the HTF bias survives day boundaries):
 * entry 21023 / stop 21011 (12-pt risk) / scalp 1R target 21035 / legacy
 * −2σ target ≈ 21062 (leg [21013.98, 21038] → RR ≈ 3.25, inside the legacy
 * risk engine's [3.0, 6.0] band).
 */
public final class SyntheticScalpSessionGenerator {

    public static final String SYMBOL = "MNQ";

    /** First session day: Monday 2026-06-15 (12:00Z warmup start = 8:00 ET). */
    private static final Instant BASE_WARMUP_START = Instant.parse("2026-06-15T12:00:00Z");
    private static final Instant BASE_KILLZONE_OPEN = Instant.parse("2026-06-15T13:45:00Z");

    /** Per-day price offset — sessions stack into a rising multi-day trend. */
    private static final double DAY_DRIFT = 65.0;

    private SyntheticScalpSessionGenerator() {}

    /** Generate {@code days} consecutive sessions (weekdays from the base Monday). */
    public static List<Candle> generateSessions(int days) {
        List<Candle> all = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            all.addAll(generateSession(d));
        }
        return all;
    }

    /** One full session: warmup + two-act killzone + round trips + legacy-target rally. */
    public static List<Candle> generateSession(int dayIndex) {
        double b = dayIndex * DAY_DRIFT; // price offset
        Instant warmupStart = BASE_WARMUP_START.plus(dayIndex, ChronoUnit.DAYS);
        Instant kzOpen = BASE_KILLZONE_OPEN.plus(dayIndex, ChronoUnit.DAYS);

        List<Candle> out = new ArrayList<>(warmup(warmupStart, b));
        // Act 1 (kz+0..22) and act 2 (kz+31..53) share the same pattern.
        out.addAll(killzoneAct(kzOpen, 0, b));
        out.add(kz(kzOpen, 23, b, 21038, 21039, 21022, 21036));   // fill + 1R target (trade 1)
        out.addAll(bridge(kzOpen, b));                            // kz+24..30 cooldown drift
        out.addAll(killzoneAct(kzOpen, 31, b));
        out.add(kz(kzOpen, 54, b, 21038, 21039, 21022, 21036));   // fill + 1R target (trade 2)
        out.add(kz(kzOpen, 55, b, 21036, 21065, 21035, 21063));   // rally fills legacy -2σ target
        out.add(kz(kzOpen, 56, b, 21063, 21064, 21062, 21063));
        out.add(kz(kzOpen, 57, b, 21063, 21064, 21062, 21063));
        return out;
    }

    // ── building blocks ───────────────────────────────────────────────────

    private static Candle at(Instant ts, double o, double h, double l, double c) {
        return new Candle(SYMBOL, ts, o, h, l, c, 100);
    }

    private static Candle kz(Instant kzOpen, int minutes, double b,
                             double o, double h, double l, double c) {
        return at(kzOpen.plus(minutes, ChronoUnit.MINUTES), o + b, h + b, l + b, c + b);
    }

    /** 15 one-minute candles interpolating a 15m OHLC window. */
    private static List<Candle> window15m(Instant start, double o, double h, double l, double c) {
        List<Candle> out = new ArrayList<>(15);
        double prevClose = o;
        for (int i = 0; i < 15; i++) {
            double barClose = o + (c - o) * (i + 1) / 15.0;
            double barOpen = prevClose;
            double hi = Math.max(barOpen, barClose) + 0.25;
            double lo = Math.min(barOpen, barClose) - 0.25;
            if (i == 7) hi = h;
            if (i == 3) lo = l;
            out.add(at(start.plus(i, ChronoUnit.MINUTES), barOpen, hi, lo, barClose));
            prevClose = barClose;
        }
        return out;
    }

    /** 7×15m rising-structure warmup that establishes the BULLISH HTF bias. */
    private static List<Candle> warmup(Instant start, double b) {
        double[][] w = {
                { 21000, 21010, 20999, 21008 },
                { 21008, 21020, 21006, 21014 },
                { 21014, 21016, 21000, 21006 },
                { 21006, 21024, 21005, 21023 },
                { 21023, 21026, 21015, 21025 },
                { 21025, 21030, 21020, 21029 },
                { 21029, 21040, 21026, 21032 },
        };
        List<Candle> all = new ArrayList<>();
        Instant t = start;
        for (double[] win : w) {
            all.addAll(window15m(t, win[0] + b, win[1] + b, win[2] + b, win[3] + b));
            t = t.plus(15, ChronoUnit.MINUTES);
        }
        return all;
    }

    /**
     * One complete setup sequence (23 candles) starting {@code shift} minutes
     * after the killzone open. Cluster lows at kz+1/+7/+11 (21014.02 /
     * 21014.00 / 21013.98), sweep + raid at kz+16, displacement FVG
     * [21020, 21023], MSS above 21038, OTE retrace and rejection at kz+22.
     */
    private static List<Candle> killzoneAct(Instant kzOpen, int shift, double b) {
        List<Candle> c = new ArrayList<>();
        c.add(kz(kzOpen, shift,      b, 21032, 21038,    21030,    21031)); // leg high 21038
        c.add(kz(kzOpen, shift + 1,  b, 21031, 21032,    21014.02, 21027)); // cluster low #1
        c.add(kz(kzOpen, shift + 2,  b, 21027, 21028,    21025,    21026));
        c.add(kz(kzOpen, shift + 3,  b, 21026, 21027,    21024,    21025));
        c.add(kz(kzOpen, shift + 4,  b, 21025, 21028,    21025,    21027));
        c.add(kz(kzOpen, shift + 5,  b, 21027, 21029,    21026,    21028));
        c.add(kz(kzOpen, shift + 6,  b, 21028, 21028.5,  21019,    21020));
        c.add(kz(kzOpen, shift + 7,  b, 21020, 21021,    21014,    21017)); // cluster low #2
        c.add(kz(kzOpen, shift + 8,  b, 21017, 21020,    21015.5,  21019));
        c.add(kz(kzOpen, shift + 9,  b, 21019, 21024,    21017,    21023));
        c.add(kz(kzOpen, shift + 10, b, 21023, 21028,    21022,    21027));
        c.add(kz(kzOpen, shift + 11, b, 21027, 21032,    21013.98, 21031)); // cluster low #3
        c.add(kz(kzOpen, shift + 12, b, 21031, 21035,    21030,    21034)); // reclaim / MSS break level
        c.add(kz(kzOpen, shift + 13, b, 21034, 21034.75, 21031,    21033));
        c.add(kz(kzOpen, shift + 14, b, 21033, 21034,    21032,    21033));
        c.add(kz(kzOpen, shift + 15, b, 21033, 21033.5,  21016,    21016)); // sell-off to cluster
        c.add(kz(kzOpen, shift + 16, b, 21016, 21020,    21012,    21018)); // sweep + raid (score 6)
        c.add(kz(kzOpen, shift + 17, b, 21018, 21028,    21017,    21027));
        c.add(kz(kzOpen, shift + 18, b, 21027, 21044,    21023,    21044)); // displacement + MSS
        c.add(kz(kzOpen, shift + 19, b, 21044, 21046,    21036,    21045));
        c.add(kz(kzOpen, shift + 20, b, 21045, 21052,    21042,    21050));
        c.add(kz(kzOpen, shift + 21, b, 21050, 21050.5,  21036,    21037));
        c.add(kz(kzOpen, shift + 22, b, 21037, 21038,    21024,    21038)); // OTE rejection → emit
        return c;
    }

    /** Cooldown bridge kz+24..30 while the scalp re-arm cooldown elapses. */
    private static List<Candle> bridge(Instant kzOpen, double b) {
        List<Candle> c = new ArrayList<>();
        c.add(kz(kzOpen, 24, b, 21036,   21037,   21033,   21034));
        c.add(kz(kzOpen, 25, b, 21034,   21035,   21032.5, 21033.5));
        c.add(kz(kzOpen, 26, b, 21033.5, 21034.5, 21032,   21033));
        c.add(kz(kzOpen, 27, b, 21033,   21034,   21031.5, 21032.5));
        c.add(kz(kzOpen, 28, b, 21032.5, 21033.5, 21031,   21032));
        c.add(kz(kzOpen, 29, b, 21032,   21033,   21031,   21032));
        c.add(kz(kzOpen, 30, b, 21032,   21033,   21031,   21032));
        return c;
    }
}
