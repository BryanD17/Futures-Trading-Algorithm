package com.topstep.trading.connector;

import com.topstep.trading.domain.Candle;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * SIM warm boot: deterministic SYNTHETIC 1m history for the MockConnector
 * (V2 Agent 02). Exists because SIM used to boot COLD while LIVE backfills
 * three days of real history — meaning the warm-start path the live engine
 * depends on was only ever rehearsed with real money. This generator gives
 * SIM the same startup shape: days of 1m candles replayed through the SAME
 * listener path before the first live-sim tick.
 *
 * <p>EVERYTHING here is synthetic and stays strictly inside the SIM
 * boundary (only {@link MockConnector} calls it — never the live path).
 * Generation is fully deterministic for a given (symbol, basePrice, days,
 * seed, end): identical inputs produce identical candle series, so SIM
 * sessions are reproducible.
 *
 * <p>The price path is deliberately STRUCTURED, not a pure random walk:
 * each synthetic day contains an accumulation phase, a London push, a
 * retrace into the 62–79% band, a multi-hour NY leg, and a second retrace —
 * so the ChartEngine detects real swing legs and OTE zones during SIM. The
 * final close is anchored back to the instrument's base price so live-sim
 * ticks continue seamlessly from the last backfilled close.
 */
public final class SimWarmBoot {

    /** System property: RNG seed for the synthetic history. Default 42. */
    public static final String SEED_PROPERTY = "sim.backfill.seed";
    public static final long DEFAULT_SEED = 42L;

    /** Same depth property + clamp the LIVE backfill uses: [1,7], default 3. */
    public static final String DAYS_PROPERTY = "backfill.days";

    private static final int MINUTES_PER_DAY = 1440;

    private SimWarmBoot() {}

    /** Configured depth in days, clamped to [1, 7] (default 3). */
    public static int configuredDays() {
        return clampDays(Long.getLong(DAYS_PROPERTY, 3L));
    }

    static int clampDays(long days) {
        return (int) Math.min(7L, Math.max(1L, days));
    }

    /** Configured seed (default 42) — fixed so SIM runs are reproducible. */
    public static long configuredSeed() {
        return Long.getLong(SEED_PROPERTY, DEFAULT_SEED);
    }

    /**
     * Generate {@code days} of synthetic clock-aligned 1m candles for
     * {@code symbol}, ending strictly BEFORE {@code endExclusive} (which is
     * truncated to the minute). Pure function: no wall-clock reads, no
     * global state — determinism is load-bearing for the tests.
     *
     * @return candles oldest → newest, timestamps strictly ascending by one
     *         minute, final close == {@code basePrice} (anchor for the
     *         live-sim ticks that follow)
     */
    public static List<Candle> generate(String symbol, double basePrice,
                                        int days, long seed, Instant endExclusive) {
        int d = clampDays(days);
        Instant end = endExclusive.truncatedTo(ChronoUnit.MINUTES);
        int n = d * MINUTES_PER_DAY;
        Instant start = end.minus(Duration.ofMinutes(n));

        // Per-symbol seed derivation keeps MNQ/MES/MGC series distinct while
        // remaining deterministic for the same inputs.
        Random rng = new Random(seed ^ (long) symbol.hashCode());

        // Leg amplitude: 0.4% of the base price per day — comfortably above
        // the ChartEngine's default 40-tick significance floor on all three
        // micros (MNQ 80pt, MES 20pt, MGC 9.6pt).
        double amplitude = basePrice * 0.004;
        double noiseAmp = amplitude * 0.03;

        // Raw closes, phase-structured per synthetic day.
        double[] closes = new double[n];
        double dayStart = basePrice;
        for (int day = 0; day < d; day++) {
            double dir = rng.nextBoolean() ? 1.0 : -1.0;
            // Draw the day's noise ONCE per minute in a fixed order.
            for (int m = 0; m < MINUTES_PER_DAY; m++) {
                double anchor = anchorOffset(m) * amplitude * dir;
                double vol = volatilityScale(m);
                double noise = rng.nextGaussian() * noiseAmp * vol;
                closes[day * MINUTES_PER_DAY + m] = dayStart + anchor + noise;
            }
            // Next day opens where the drift phase parked us (60% of the leg).
            dayStart = dayStart + 0.6 * amplitude * dir;
        }

        // Anchor the FINAL close exactly back to basePrice with a linear
        // ramp — preserves every leg's shape while guaranteeing live-sim
        // ticks continue from the base price the mock already tracks.
        double correction = basePrice - closes[n - 1];
        for (int t = 0; t < n; t++) {
            closes[t] += correction * ((double) (t + 1) / n);
        }

        List<Candle> out = new ArrayList<>(n);
        double prevClose = closes[0];
        for (int t = 0; t < n; t++) {
            Instant ts = start.plus(Duration.ofMinutes(t));
            double open = prevClose;
            double close = closes[t];
            double wick = Math.abs(rng.nextGaussian()) * noiseAmp * 0.5;
            double high = Math.max(open, close) + wick;
            double low = Math.min(open, close) - wick;
            long volume = 500 + rng.nextInt(1500);
            out.add(new Candle(symbol, ts, open, high, low, close, volume));
            prevClose = close;
        }
        return out;
    }

    /**
     * The day's structural shape as a fraction of the leg amplitude, by
     * minute-of-synthetic-day. Piecewise linear:
     *   [0,360)     accumulation chop at 0
     *   [360,600)   London push to 0.5
     *   [600,840)   retrace to 0.15 (a 70% retrace of the push — lands in
     *               the OTE band so zones ARM during replay)
     *   [840,1080)  multi-hour NY leg to 1.0
     *   [1080,1260) retrace to 0.55 (45% — tags the shallow band edge)
     *   [1260,1440) drift to 0.6 (the next day's open)
     */
    static double anchorOffset(int m) {
        if (m < 360)  return 0.0;
        if (m < 600)  return lerp(0.0, 0.5, (m - 360) / 240.0);
        if (m < 840)  return lerp(0.5, 0.15, (m - 600) / 240.0);
        if (m < 1080) return lerp(0.15, 1.0, (m - 840) / 240.0);
        if (m < 1260) return lerp(1.0, 0.55, (m - 1080) / 180.0);
        return lerp(0.55, 0.6, (m - 1260) / 180.0);
    }

    /** Session-scaled volatility: quiet Asia, active London, loud NY. */
    static double volatilityScale(int m) {
        if (m < 360)  return 1.0;   // accumulation
        if (m < 840)  return 1.5;   // London push + retrace
        if (m < 1260) return 2.5;   // NY leg + retrace
        return 1.2;                 // drift into the close
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }
}
