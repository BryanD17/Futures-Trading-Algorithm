package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.chartstate.LevelType;
import com.topstep.trading.strategy.ImpulseExtensionAnalyzer;
import com.topstep.trading.strategy.ImpulseExtensionAnalyzer.TargetAssessment;
import com.topstep.trading.strategy.ImpulseExtensionAnalyzer.TargetRealism;
import com.topstep.trading.strategy.MarketBias;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Builds an STDV (Standard Deviation Projection) ladder from a manipulation
 * leg.
 *
 * <p>The canonical ICT projection set is
 * {@code { -0.27, -1.0, -2.0, -2.5, -4.0 }} measured as multiples of
 * {@code legSize = legHigh - legLow}, projected from the anchor of the leg in
 * the bias-aligned expansion direction.
 *
 * <p>Anchor convention:
 * <ul>
 *   <li>Bullish setup (sweep of lows, reversal up): {@code anchor = legLow}.</li>
 *   <li>Bearish setup (sweep of highs, reversal down): {@code anchor = legHigh}.</li>
 * </ul>
 *
 * <p>Each projected price may be <em>snapped</em> to the nearest real
 * liquidity level from {@link ChartStateQueryAPI} within an
 * instrument-specific tick tolerance, provided the snap does not reorder the
 * ladder. {@link ImpulseExtensionAnalyzer} provides a realism tag for the
 * {@code -2.0} level only (REALISTIC / AGGRESSIVE / UNREALISTIC); it never
 * alters projected prices.
 *
 * <p>SA2 implementation. The engine is pure / stateless aside from its
 * collaborators, which it never mutates — calling {@code project(...)}
 * twice with the same inputs returns equal ladders.
 */
public final class StdvProjectionEngine {

    /** Canonical sigma multipliers, in display order. */
    public static final double[] SIGMAS = { -0.27, -1.0, -2.0, -2.5, -4.0 };

    /** Default minimum leg length, in ticks, before STDV is considered meaningful. */
    public static final int DEFAULT_MIN_LEG_TICKS = 8;

    /** Sigma value that carries the realism tag (the primary target). */
    private static final double REALISM_SIGMA = -2.0;

    private final ChartStateQueryAPI chartState;
    private final ImpulseExtensionAnalyzer realism;
    private final int minLegTicks;

    public StdvProjectionEngine(ChartStateQueryAPI chartState,
                                ImpulseExtensionAnalyzer realism) {
        this(chartState, realism, DEFAULT_MIN_LEG_TICKS);
    }

    public StdvProjectionEngine(ChartStateQueryAPI chartState,
                                ImpulseExtensionAnalyzer realism,
                                int minLegTicks) {
        this.chartState = chartState;
        this.realism = realism;
        this.minLegTicks = Math.max(0, minLegTicks);
    }

    /**
     * Project the canonical STDV ladder from a manipulation leg.
     *
     * <p>Returns an empty list when the leg is degenerate
     * ({@code legLow >= legHigh}) or shorter than {@link #minLegTicks} ticks;
     * the caller is expected to skip the setup in those cases. NEUTRAL bias
     * also returns empty (no directional projection makes sense).
     *
     * @param legLow         lowest body of the manipulation leg
     * @param legHigh        highest body of the manipulation leg
     * @param bias           HTF bias (drives anchor + direction)
     * @param tickSize       instrument tick size (used for rounding + snap tol)
     * @param snapTolTicks   snap tolerance in ticks; 0 disables snapping
     * @return immutable ladder ordered as in {@link #SIGMAS}, or empty list
     */
    public List<StdvProjection> project(double legLow,
                                        double legHigh,
                                        MarketBias bias,
                                        double tickSize,
                                        int snapTolTicks) {
        if (bias == null || bias == MarketBias.NEUTRAL) {
            return Collections.emptyList();
        }
        if (!(legHigh > legLow)) {
            return Collections.emptyList();
        }
        double range = legHigh - legLow;
        if (tickSize > 0 && (range / tickSize) < minLegTicks) {
            return Collections.emptyList();
        }

        boolean bullish = (bias == MarketBias.BULLISH);
        double anchor = bullish ? legLow : legHigh;
        int dir = bullish ? +1 : -1;

        // Pass 1 — compute raw projections.
        double[] rawPrices = new double[SIGMAS.length];
        for (int i = 0; i < SIGMAS.length; i++) {
            double mult = Math.abs(SIGMAS[i]);
            double raw = anchor + dir * range * mult;
            rawPrices[i] = roundToTick(raw, tickSize);
        }

        // Pass 2 — attempt snapping per level, respecting monotonicity.
        double[] effectivePrices = rawPrices.clone();
        LevelType[] snapTypes = new LevelType[SIGMAS.length];
        boolean[] snapped = new boolean[SIGMAS.length];

        if (chartState != null && snapTolTicks > 0 && tickSize > 0) {
            double tolPrice = snapTolTicks * tickSize;
            for (int i = 0; i < SIGMAS.length; i++) {
                Optional<KnownLevel> candidate = findNearestLevel(rawPrices[i], tolPrice);
                if (candidate.isEmpty()) continue;
                double snappedPrice = roundToTick(candidate.get().getPrice(), tickSize);
                if (preservesMonotonicity(effectivePrices, i, snappedPrice, dir)) {
                    effectivePrices[i] = snappedPrice;
                    snapTypes[i] = candidate.get().getType();
                    snapped[i] = true;
                }
            }
        }

        // Pass 3 — assemble projections with the realism tag on -2.0.
        List<StdvProjection> out = new ArrayList<>(SIGMAS.length);
        for (int i = 0; i < SIGMAS.length; i++) {
            String tag = "n/a";
            if (Double.compare(SIGMAS[i], REALISM_SIGMA) == 0 && realism != null) {
                double distance = Math.abs(effectivePrices[i] - anchor);
                TargetAssessment assessment = realism.assessTarget(distance);
                TargetRealism r = assessment.getRealism();
                tag = (r == TargetRealism.UNINITIALIZED) ? "UNINITIALIZED" : r.name();
            }
            out.add(new StdvProjection(
                    SIGMAS[i],
                    rawPrices[i],
                    effectivePrices[i],
                    snapTypes[i],
                    snapped[i],
                    tag));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Find the closest known level to {@code price} within {@code tolPrice}
     * absolute price units. Falls back to scanning {@code getAllLevels()} if
     * {@code getLevelsNearPrice} returns nothing in tolerance.
     */
    private Optional<KnownLevel> findNearestLevel(double price, double tolPrice) {
        List<KnownLevel> candidates = chartState.getLevelsNearPrice(price);
        if (candidates == null || candidates.isEmpty()) {
            candidates = chartState.getAllLevels();
        }
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        KnownLevel best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (KnownLevel lvl : candidates) {
            double d = Math.abs(lvl.getPrice() - price);
            if (d <= tolPrice && d < bestDist) {
                best = lvl;
                bestDist = d;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Check that snapping index {@code i} to {@code newPrice} keeps the
     * ladder strictly monotone in the projection direction. For
     * {@code dir == +1} prices must be strictly increasing by sigma index;
     * for {@code dir == -1} strictly decreasing. The check uses the prior
     * (already-finalised) entry as the lower bound and the next raw entry as
     * the upper bound.
     */
    private static boolean preservesMonotonicity(double[] prices,
                                                  int i,
                                                  double newPrice,
                                                  int dir) {
        if (i > 0) {
            double prev = prices[i - 1];
            if (dir > 0) {
                if (!(newPrice > prev)) return false;
            } else {
                if (!(newPrice < prev)) return false;
            }
        }
        if (i + 1 < prices.length) {
            double next = prices[i + 1];
            if (dir > 0) {
                if (!(newPrice < next)) return false;
            } else {
                if (!(newPrice > next)) return false;
            }
        }
        return true;
    }

    private static double roundToTick(double price, double tickSize) {
        if (tickSize <= 0) return price;
        return Math.round(price / tickSize) * tickSize;
    }
}
