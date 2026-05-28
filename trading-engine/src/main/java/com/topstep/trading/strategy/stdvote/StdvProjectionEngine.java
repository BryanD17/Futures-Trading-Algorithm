package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.strategy.ImpulseExtensionAnalyzer;
import com.topstep.trading.strategy.MarketBias;

import java.util.List;

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
 * <p>This class is a stub in SA1. The {@code project(...)} method is
 * implemented in <strong>SA2</strong>.
 */
public final class StdvProjectionEngine {

    /** Canonical sigma multipliers, in display order. */
    public static final double[] SIGMAS = { -0.27, -1.0, -2.0, -2.5, -4.0 };

    private final ChartStateQueryAPI chartState;
    private final ImpulseExtensionAnalyzer realism;

    public StdvProjectionEngine(ChartStateQueryAPI chartState,
                                ImpulseExtensionAnalyzer realism) {
        this.chartState = chartState;
        this.realism = realism;
    }

    /**
     * Project the canonical STDV ladder from a manipulation leg.
     *
     * @param legLow         lowest body of the manipulation leg
     * @param legHigh        highest body of the manipulation leg
     * @param bias           HTF bias (drives anchor + direction); NEUTRAL throws
     * @param tickSize       instrument tick size (used for rounding + snap tol)
     * @param snapTolTicks   snap tolerance in ticks; 0 disables snapping
     * @return immutable ladder ordered as in {@link #SIGMAS}
     * @throws UnsupportedOperationException SA1 stub. Implemented in SA2.
     */
    public List<StdvProjection> project(double legLow,
                                        double legHigh,
                                        MarketBias bias,
                                        double tickSize,
                                        int snapTolTicks) {
        throw new UnsupportedOperationException("StdvProjectionEngine.project: SA2");
    }
}
