package com.topstep.trading.strategy.stdvote;

/**
 * Pure scalp target selection (SA3). No detector imports — plain price
 * inputs in, a target decision out. Deterministic and side-effect free.
 *
 * <h2>Model</h2>
 * <ol>
 *   <li><strong>Candidate A</strong> — the nearest OPPOSING liquidity level
 *       in the trade direction (above entry for longs, below for shorts).
 *       The level price is passed in; the runner sources it from
 *       {@code LiquidityTargetIdentifier} / {@code LevelEngine}.</li>
 *   <li><strong>Candidate B</strong> — the FVG origin: the far edge of the
 *       displacement FVG consumed by the setup (top for longs, bottom for
 *       shorts, already recorded in the core via {@code recordDisplacement}).</li>
 *   <li>A candidate is <em>valid</em> when it is on the profit side of entry,
 *       clears entry by at least {@code minClearanceTicks} ticks, and lies
 *       within {@code candidateWindowR} x risk of entry. Wrong-side
 *       candidates are simply excluded (not a rejection by themselves).</li>
 *   <li>Final target = the CLOSER valid candidate to entry, then HARD-CAPPED
 *       at {@link #TARGET_CAP_R} (1R), where 1R = |entry − stop|.</li>
 *   <li>No valid candidate within the window → fall back to exactly 1R.</li>
 * </ol>
 *
 * <h2>Rejections (reason-logged)</h2>
 * <ul>
 *   <li>Stop distance zero/negative (stop on the wrong side of entry).</li>
 *   <li>Final target at/inside entry (&lt; {@code minClearanceTicks} ticks
 *       past entry) — e.g. a degenerate sub-2-tick risk distance.</li>
 *   <li>Non-positive tick size (defensive).</li>
 * </ul>
 *
 * <p>The 1R cap is deliberately a constant, not a property: it defines the
 * scalp model and is the number SA5's Monte Carlo validates against.
 */
public final class ScalpTargetCalculator {

    /** Hard cap on target distance, in R. The definition of the scalp model. */
    public static final double TARGET_CAP_R = 1.0;

    /** R-multiple used when no valid candidate exists (exactly 1R). */
    public static final double FALLBACK_R = 1.0;

    /**
     * Epsilon (in ticks) for the floor-to-grid snap. Absorbs binary
     * floating-point drift on non-binary tick sizes (e.g. MGC 0.10) without
     * ever moving the target more than a millionth of a tick past the cap.
     */
    private static final double TICK_SNAP_EPSILON = 1e-6;

    /** Where the accepted target came from. */
    public enum Source {
        /** Candidate A — nearest opposing liquidity level. */
        OPPOSING_LIQUIDITY,
        /** Candidate B — displacement-FVG far edge. */
        FVG_ORIGIN,
        /** No valid candidate within the window — exactly 1R. */
        ONE_R_FALLBACK,
        /** Rejected — no target. */
        NONE
    }

    /**
     * Immutable decision. When {@code accepted} is false, {@code targetPrice}
     * and {@code rMultiple} are meaningless ({@code Double.NaN}) and
     * {@code reason} explains the rejection.
     */
    public record Decision(boolean accepted, double targetPrice, Source source,
                           double rMultiple, String reason) {

        static Decision accept(double targetPrice, Source source, double rMultiple) {
            return new Decision(true, targetPrice, source, rMultiple,
                    "target " + targetPrice + " via " + source
                            + " at " + String.format("%.3f", rMultiple) + "R");
        }

        static Decision reject(String reason) {
            return new Decision(false, Double.NaN, Source.NONE, Double.NaN, reason);
        }
    }

    private final int minClearanceTicks;
    private final double candidateWindowR;

    /**
     * @param minClearanceTicks minimum ticks the target must clear entry by
     *                          (config {@code scalp.minTargetClearanceTicks})
     * @param candidateWindowR  candidate validity window in R
     *                          (config {@code scalp.candidateWindowR})
     */
    public ScalpTargetCalculator(int minClearanceTicks, double candidateWindowR) {
        if (minClearanceTicks < 0) {
            throw new IllegalArgumentException("minClearanceTicks must be >= 0");
        }
        if (candidateWindowR <= 0) {
            throw new IllegalArgumentException("candidateWindowR must be > 0");
        }
        this.minClearanceTicks = minClearanceTicks;
        this.candidateWindowR = candidateWindowR;
    }

    /**
     * Compute the scalp target.
     *
     * @param entry             planned entry price
     * @param stop              planned stop price (profit side check derives
     *                          the risk distance: long risk = entry − stop)
     * @param bullish           true for a long, false for a short
     * @param tickSize          instrument tick size (&gt; 0)
     * @param opposingLiquidity Candidate A price, or null when unknown
     * @param fvgOrigin         Candidate B price, or null when unknown
     */
    public Decision computeTarget(double entry, double stop, boolean bullish,
                                  double tickSize,
                                  Double opposingLiquidity, Double fvgOrigin) {
        if (tickSize <= 0 || Double.isNaN(tickSize)) {
            return Decision.reject("invalid tick size: " + tickSize);
        }
        double risk = bullish ? (entry - stop) : (stop - entry);
        if (!(risk > 0)) {
            return Decision.reject("stop distance zero/negative: risk=" + risk
                    + " (entry=" + entry + ", stop=" + stop + ", bullish=" + bullish + ")");
        }
        double clearance = minClearanceTicks * tickSize;
        double window = candidateWindowR * risk;

        // Candidate distances from entry, in the profit direction; NaN when
        // absent / wrong side / outside validity constraints.
        double distA = candidateDistance(entry, bullish, opposingLiquidity, clearance, window);
        double distB = candidateDistance(entry, bullish, fvgOrigin, clearance, window);

        Source source;
        double dist;
        if (!Double.isNaN(distA) && (Double.isNaN(distB) || distA <= distB)) {
            source = Source.OPPOSING_LIQUIDITY;
            dist = distA;
        } else if (!Double.isNaN(distB)) {
            source = Source.FVG_ORIGIN;
            dist = distB;
        } else {
            // No valid candidate within candidateWindowR — exactly 1R.
            source = Source.ONE_R_FALLBACK;
            dist = FALLBACK_R * risk;
        }

        // HARD CAP at 1R.
        double cap = TARGET_CAP_R * risk;
        if (dist > cap) {
            dist = cap;
        }

        // Snap to the tick grid without ever exceeding the cap (floor).
        dist = Math.floor(dist / tickSize + TICK_SNAP_EPSILON) * tickSize;

        if (dist < clearance) {
            return Decision.reject("target at/inside entry: distance " + dist
                    + " < required clearance " + clearance
                    + " (" + minClearanceTicks + " ticks)");
        }

        double target = bullish ? (entry + dist) : (entry - dist);
        target = roundToTick(target, tickSize);
        return Decision.accept(target, source, dist / risk);
    }

    /**
     * Distance from entry to a candidate in the profit direction, or NaN when
     * the candidate is absent, on the wrong side (excluded, not a rejection),
     * too close (&lt; clearance), or outside the validity window.
     */
    private static double candidateDistance(double entry, boolean bullish,
                                            Double candidate,
                                            double clearance, double window) {
        if (candidate == null || Double.isNaN(candidate)) return Double.NaN;
        double dist = bullish ? (candidate - entry) : (entry - candidate);
        if (dist <= 0) return Double.NaN;        // wrong side — excluded
        if (dist < clearance) return Double.NaN; // does not clear entry
        if (dist > window) return Double.NaN;    // beyond validity window
        return dist;
    }

    private static double roundToTick(double price, double tickSize) {
        return Math.round(price / tickSize) * tickSize;
    }
}
