package com.topstep.trading.connector;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.InstrumentCharacteristics;
import com.topstep.trading.strategy.KillzoneClock;

import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SIM-ONLY synthetic tape that actually contains the choreography this engine
 * is built to trade (V4 follow-up).
 *
 * <h2>Why this exists</h2>
 * The SIM's live stream used to be a memoryless uniform random walk:
 * {@code close = open + (rand - 0.5) * 10}. Such a tape cannot produce the
 * STDV+OTE sequence except by coincidence, because nothing in it correlates a
 * liquidity sweep with the displacement that should follow it. Measured on that
 * tape, the gate's displacement rule fired on 0.63% of 5m bars and the funnel
 * sat at SWEEP_DONE for hundreds of consecutive candles reporting
 * {@code no-recent-displacement}. The engine was behaving correctly; the
 * FIXTURE could not pose the question.
 *
 * <p>A SIM whose tape cannot assemble a single setup validates nothing.
 *
 * <h2>Why it REPLAYS a known-good act instead of computing one</h2>
 * The first version of this class scripted phases — accumulation, sweep,
 * displacement, retrace — with computed price levels. Every iteration produced
 * plausible candles that failed one predicate or another: expansion that
 * vanished under 5m aggregation, retraces that closed the wrong way, rejection
 * wicks that missed the band, depths that took out the impulse origin. Those
 * predicates are not independent, and tuning them one at a time does not
 * converge.
 *
 * <p>So the act below is the hand-verified 23-candle sequence from
 * {@code SyntheticScalpSessionGenerator} — the fixture the A/B backtest harness
 * already uses to drive this exact state machine from bias through to emission.
 * It is known to satisfy every predicate SIMULTANEOUSLY. It is quoted relative
 * to its own opening price so it can be rebased onto wherever the drift left
 * off, and scaled by tick size so every instrument sees the same geometry in
 * TICKS rather than in points.
 *
 * <h2>What it is NOT</h2>
 * It is not market data and it carries NO edge. Trades taken on this tape prove
 * the PIPELINE works — that a sweep can become a displacement, an FVG, an MSS,
 * an armed OTE zone and an order. They say nothing whatsoever about whether the
 * strategy is profitable, and every report keeps that caveat.
 *
 * <p>SIM-only by construction: referenced solely by {@link MockConnector}. The
 * LIVE path takes its candles from the broker.
 */
final class SimChoreographyTape {

    /** {@code sim.tape} — CHOREOGRAPHY (default) or RANDOM (the old walk). */
    static final String MODE_PROPERTY = "sim.tape";

    /**
     * THE ACT. Columns are {open, high, low, close}, RELATIVE to the act's own
     * opening price.
     *
     * <p>Landmarks: cluster lows at +1/+7/+11 (a raid-able equal-lows cluster),
     * sell-off at +15, sweep + raid at +16, displacement + MSS at +18, and the
     * OTE rejection at +22 that arms the entry.
     */
    private static final double[][] ACT = {
            {    0.0,    6.0,   -2.0,   -1.0},   // +0  leg high
            {   -1.0,    0.0, -17.98,   -5.0},   // +1  cluster low #1
            {   -5.0,   -4.0,   -7.0,   -6.0},
            {   -6.0,   -5.0,   -8.0,   -7.0},
            {   -7.0,   -4.0,   -7.0,   -5.0},
            {   -5.0,   -3.0,   -6.0,   -4.0},
            {   -4.0,   -3.5,  -13.0,  -12.0},
            {  -12.0,  -11.0,  -18.0,  -15.0},   // +7  cluster low #2
            {  -15.0,  -12.0,  -16.5,  -13.0},
            {  -13.0,   -8.0,  -15.0,   -9.0},
            {   -9.0,   -4.0,  -10.0,   -5.0},
            {   -5.0,    0.0, -18.02,   -1.0},   // +11 cluster low #3
            {   -1.0,    3.0,   -2.0,    2.0},   // +12 reclaim / MSS break level
            {    2.0,   2.75,   -1.0,    1.0},
            {    1.0,    2.0,    0.0,    1.0},
            {    1.0,    1.5,  -16.0,  -16.0},   // +15 sell-off to the cluster
            {  -16.0,  -12.0,  -20.0,  -14.0},   // +16 sweep + raid
            {  -14.0,   -4.0,  -15.0,   -5.0},
            {   -5.0,   12.0,   -9.0,   12.0},   // +18 displacement + MSS
            {   12.0,   14.0,    4.0,   13.0},
            {   13.0,   20.0,   10.0,   18.0},
            {   18.0,   18.5,    4.0,    5.0},
            {    5.0,    6.0,   -8.0,    6.0},   // +22 OTE rejection -> emit
            {    6.0,    7.0,  -10.0,    4.0},   // fill + 1R target
    };

    /** Quiet bars between acts, so the re-arm cooldown elapses and ATR settles. */
    private static final int BRIDGE_BARS = 8;

    /**
     * Acts per killzone. The proven fixture runs two; this runs more because
     * the funnel has to be in the right STATE when an act begins, and a setup
     * that was mid-flight when the previous act ran needs another chance.
     */
    private static final int ACTS_PER_KILLZONE = 4;

    /** Per-symbol scripted state. */
    private static final class Script {
        /** Index into {@link #ACT}; -1 means "not currently running an act". */
        int actIndex = -1;
        /** Price the running act is rebased onto. */
        double actBase;
        /** Quiet bars still to burn before the next act. */
        int bridgeBars;
        /** Acts completed in the current killzone. */
        int actsThisKillzone;
        boolean wasInKillzone;
    }

    private final KillzoneClock killzones = new KillzoneClock();
    private final Map<String, Script> scripts = new ConcurrentHashMap<>();
    private final Map<String, Random> rngs = new ConcurrentHashMap<>();
    private final long seed;

    SimChoreographyTape(long seed) {
        this.seed = seed;
    }

    static boolean enabled() {
        return !"RANDOM".equalsIgnoreCase(System.getProperty(MODE_PROPERTY, "CHOREOGRAPHY"));
    }

    /**
     * The instrument's natural per-candle scale for the DRIFT between acts.
     * Expressed in ticks so MNQ (tick 0.25) and MGC (tick 0.1) both get
     * sensible ranges, instead of the old fixed ±5 points which was enormous
     * for gold and trivial for the index.
     */
    private static double unit(String symbol) {
        return InstrumentCharacteristics.getProfile(symbol).getTickSize() * 8.0;
    }

    private Random rng(String symbol) {
        return rngs.computeIfAbsent(symbol, s -> new Random(seed * 31 + s.hashCode()));
    }

    /**
     * Produce the next 1m candle for {@code symbol} at {@code ts}, continuing
     * from {@code open}.
     *
     * <p>Outside a killzone the tape drifts gently UPWARD. That is deliberate:
     * the act is a bullish sequence, and the engine only accepts a sweep in the
     * direction of HTF bias, so the surrounding tape has to establish and HOLD
     * a bullish bias. A memoryless walk left the analyser oscillating, which
     * showed up as "HTF bias flip" and "HTF bias became NEUTRAL" invalidations
     * shredding setups mid-funnel. The short side is covered by unit tests
     * rather than by this fixture — one direction is enough to prove a pipeline.
     */
    Candle next(String symbol, Instant ts, double open) {
        Script sc = scripts.computeIfAbsent(symbol, s -> new Script());
        double scale = InstrumentCharacteristics.getProfile(symbol).getTickSize() / 0.25;

        boolean inKillzone = killzones.isInKillzone(ts);
        if (inKillzone && !sc.wasInKillzone) {
            sc.actsThisKillzone = 0;
            sc.bridgeBars = BRIDGE_BARS;      // let the killzone settle first
            sc.actIndex = -1;
        }
        if (!inKillzone) {
            sc.actIndex = -1;                 // never run an act outside a killzone
        }
        sc.wasInKillzone = inKillzone;

        // Mid-act: keep replaying it.
        if (sc.actIndex >= 0) {
            double[] row = ACT[sc.actIndex];
            sc.actIndex++;
            if (sc.actIndex >= ACT.length) {
                sc.actIndex = -1;
                sc.bridgeBars = BRIDGE_BARS;
                sc.actsThisKillzone++;
            }
            return rebased(symbol, ts, row, sc.actBase, scale);
        }

        // Between acts inside a killzone: burn the bridge, then start another.
        if (inKillzone && sc.actsThisKillzone < ACTS_PER_KILLZONE) {
            if (sc.bridgeBars > 0) {
                sc.bridgeBars--;
            } else {
                sc.actBase = open;
                sc.actIndex = 1;
                return rebased(symbol, ts, ACT[0], sc.actBase, scale);
            }
        }
        return drift(symbol, ts, open, rng(symbol), unit(symbol));
    }

    /** One ACT row, rebased onto {@code base} and scaled to the instrument. */
    private static Candle rebased(String symbol, Instant ts, double[] row,
                                  double base, double scale) {
        return new Candle(symbol, ts,
                base + row[0] * scale,
                base + row[1] * scale,
                base + row[2] * scale,
                base + row[3] * scale,
                100L);
    }

    /**
     * Gentle, PERSISTENT upward drift — and deliberately very QUIET.
     *
     * <p>Noise here is not harmless. A drift with real range prints its own
     * swing highs and lows, which the level and raid pipelines happily treat as
     * liquidity: the funnel then enters SWEEP_DONE on a meaningless wiggle and
     * is still sitting there, waiting for a displacement that belongs to a
     * different structure, when the real act begins. Keeping the drift to a
     * fraction of a tick leaves the ACT as the only structure on the tape, so
     * the machine walks the sequence in order.
     */
    private Candle drift(String symbol, Instant ts, double open, Random r, double u) {
        double close = open + u * 0.02 + (r.nextDouble() - 0.5) * u * 0.04;
        double wick = u * 0.02 * r.nextDouble();
        double high = Math.max(open, close) + wick;
        double low = Math.min(open, close) - wick;
        return new Candle(symbol, ts, open, high, low, close, 100L);
    }
}
