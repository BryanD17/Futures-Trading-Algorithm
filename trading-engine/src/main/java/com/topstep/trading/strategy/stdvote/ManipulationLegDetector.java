package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;

import java.util.List;
import java.util.Optional;

/**
 * Detects the ICT manipulation leg (Judas swing) from the killzone open.
 *
 * <p>The manipulation leg is the fake move <em>against</em> the HTF bias made
 * off the killzone open, terminated by the reclaim bar. For a bullish bias:
 * price opens the killzone, pushes DOWN to run sellside liquidity, then a
 * candle closes back above the killzone open price (the reclaim). The leg is
 * then {@code [lowest low since open, highest high from open to the low bar]}
 * — the dealing range the STDV ladder projects from. Bearish is mirrored.
 *
 * <p>Pure function over the candles observed since the killzone open — no
 * state, no detectors, no clocks. The caller owns the buffer and the
 * killzone-open bookkeeping.
 */
public final class ManipulationLegDetector {

    /** The manipulation leg's two price extremes. */
    public record Leg(double legLow, double legHigh) { }

    private ManipulationLegDetector() {
        // static utility
    }

    /**
     * Detect a completed Judas-swing leg.
     *
     * @param candlesSinceOpen candles from the killzone-open bar (inclusive)
     *                         onward, in feed order; the first candle's open
     *                         is the killzone open price
     * @param biasBullish      HTF bias direction of the setup
     * @param tickSize         instrument tick size
     * @param minLegTicks      minimum leg extent in ticks (degenerate legs
     *                         are rejected, matching the projection engine)
     * @return the leg once the manipulation has completed (excursion against
     *         bias beyond the open, followed by a reclaim close back through
     *         the open price); empty while the manipulation is still in
     *         progress or absent
     */
    public static Optional<Leg> detect(List<Candle> candlesSinceOpen,
                                       boolean biasBullish,
                                       double tickSize,
                                       int minLegTicks) {
        if (candlesSinceOpen == null || candlesSinceOpen.size() < 2) {
            return Optional.empty();
        }
        double openPrice = candlesSinceOpen.get(0).getOpen();

        // 1. Find the extreme excursion against the bias since the open.
        int extremeIdx = -1;
        double extreme = biasBullish ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        for (int i = 0; i < candlesSinceOpen.size(); i++) {
            Candle c = candlesSinceOpen.get(i);
            if (biasBullish ? c.getLow() < extreme : c.getHigh() > extreme) {
                extreme = biasBullish ? c.getLow() : c.getHigh();
                extremeIdx = i;
            }
        }
        // The Judas must actually trade beyond the open against the bias.
        if (biasBullish ? extreme >= openPrice : extreme <= openPrice) {
            return Optional.empty();
        }

        // 2. The leg's other end: the opposing extreme from the open up to
        //    (and including) the excursion bar — the origin of the fake move.
        double counter = biasBullish ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int i = 0; i <= extremeIdx; i++) {
            Candle c = candlesSinceOpen.get(i);
            counter = biasBullish
                    ? Math.max(counter, c.getHigh())
                    : Math.min(counter, c.getLow());
        }

        // 3. Termination: a reclaim bar after the extreme that closes back
        //    through the killzone open price in the bias direction.
        boolean reclaimed = false;
        for (int i = extremeIdx + 1; i < candlesSinceOpen.size(); i++) {
            double close = candlesSinceOpen.get(i).getClose();
            if (biasBullish ? close > openPrice : close < openPrice) {
                reclaimed = true;
                break;
            }
        }
        if (!reclaimed) {
            return Optional.empty();
        }

        double legLow = biasBullish ? extreme : counter;
        double legHigh = biasBullish ? counter : extreme;
        if (!(legHigh > legLow)) {
            return Optional.empty();
        }
        if (tickSize > 0 && (legHigh - legLow) / tickSize < Math.max(0, minLegTicks)) {
            return Optional.empty();
        }
        return Optional.of(new Leg(legLow, legHigh));
    }
}
