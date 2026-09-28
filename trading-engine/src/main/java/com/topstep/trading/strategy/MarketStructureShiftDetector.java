package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Market Structure Shift (MSS) Detector.
 *
 * A Market Structure Shift is a key ICT concept indicating a potential reversal.
 * It occurs when price breaks a swing high (bullish MSS) or swing low (bearish MSS)
 * in the opposite direction of the prior trend.
 *
 * For Silver Bullet setups:
 * - After a liquidity raid (sweep), look for MSS in the reversal direction
 * - MSS confirms that smart money has reversed the market
 * - The displacement candle that creates MSS often also creates an entry FVG
 *
 * Bullish MSS: Price was making lower lows, then breaks above a prior swing high
 * Bearish MSS: Price was making higher highs, then breaks below a prior swing low
 */
public class MarketStructureShiftDetector {

    /**
     * Represents a detected Market Structure Shift.
     */
    public static class MSS {
        public final boolean isBullish;
        public final double breakLevel;        // The swing level that was broken
        public final double displacementHigh;  // High of the MSS candle
        public final double displacementLow;   // Low of the MSS candle
        public final Instant timestamp;
        public final double strength;          // How much price moved through the level
        public final int candleIndex;          // Index in candle list when detected

        public MSS(boolean isBullish, double breakLevel, double displacementHigh,
                   double displacementLow, Instant timestamp, double strength, int candleIndex) {
            this.isBullish = isBullish;
            this.breakLevel = breakLevel;
            this.displacementHigh = displacementHigh;
            this.displacementLow = displacementLow;
            this.timestamp = timestamp;
            this.strength = strength;
            this.candleIndex = candleIndex;
        }

        @Override
        public String toString() {
            return String.format("MSS{%s, level=%.2f, strength=%.2f, time=%s}",
                    isBullish ? "BULLISH" : "BEARISH", breakLevel, strength, timestamp);
        }
    }

    /**
     * Represents a swing point (high or low).
     */
    private static class SwingPoint {
        final boolean isHigh;
        final double price;
        final int index;
        final Instant timestamp;

        SwingPoint(boolean isHigh, double price, int index, Instant timestamp) {
            this.isHigh = isHigh;
            this.price = price;
            this.index = index;
            this.timestamp = timestamp;
        }
    }

    private final List<Candle> candles;
    private final List<SwingPoint> swingHighs;
    private final List<SwingPoint> swingLows;
    private final int lookbackPeriod;
    private final int swingLookback;  // How many candles on each side to confirm swing

    private MSS lastMSS;
    private int totalCandleCount;

    /**
     * V5 Agent 04 — the STDV+OTE M6 rule (the ONE MSS source the strategy
     * gates on). {@code false} = the historical rule every other strategy
     * keeps. When true an MSS is simply a CLOSE beyond the MOST RECENT
     * confirmed opposite swing (fractal, {@code swingLookback} bars each side),
     * each swing breakable once:
     * <ul>
     *   <li>no "must open on the other side of the level" condition (a bar
     *       that gaps/opens through a level and closes beyond it is still a
     *       shift);</li>
     *   <li>no "strength &gt; 30 % of body" condition;</li>
     *   <li>no separate prior-structure test — the sweep that precedes M6 in
     *       the state machine IS the prior structure.</li>
     * </ul>
     * G1 (2026-09-28): most recent 5m swing low before the drop = 14:35 bar low
     * 30578.75; the 15:10 bar closes 30576.75 below it → bearish MSS (the
     * historical rule rejected it: strength 2.0 &lt; 0.3 &times; body 17.75).
     */
    private final boolean closeBeyondRecentSwing;
    /** Timestamps of swings already broken (relaxed rule: one MSS per swing). */
    private final java.util.Set<Instant> brokenSwings = new java.util.HashSet<>();

    public MarketStructureShiftDetector(int lookbackPeriod, int swingLookback) {
        this(lookbackPeriod, swingLookback, false);
    }

    public MarketStructureShiftDetector(int lookbackPeriod, int swingLookback,
                                        boolean closeBeyondRecentSwing) {
        this.lookbackPeriod = lookbackPeriod;
        this.swingLookback = swingLookback;
        this.closeBeyondRecentSwing = closeBeyondRecentSwing;
        this.candles = new ArrayList<>();
        this.swingHighs = new ArrayList<>();
        this.swingLows = new ArrayList<>();
        this.totalCandleCount = 0;
    }

    /**
     * The STDV+OTE M6 detector — constructed ONLY through this factory so the
     * strategy and ictlib's shadow comparison can never drift apart.
     */
    public static MarketStructureShiftDetector forStdvOte() {
        return new MarketStructureShiftDetector(50, 2, true);
    }

    public boolean isCloseBeyondRecentSwingRule() {
        return closeBeyondRecentSwing;
    }

    /**
     * Update with a new candle and detect any MSS.
     *
     * @return MSS if detected on this candle, null otherwise
     */
    public MSS update(Candle candle) {
        candles.add(candle);
        totalCandleCount++;

        // Trim to lookback period
        while (candles.size() > lookbackPeriod) {
            candles.remove(0);
            // Adjust swing indices
            adjustSwingIndices();
        }

        // Need enough candles for swing detection
        if (candles.size() < swingLookback * 2 + 1) {
            return null;
        }

        // Identify new swing points
        updateSwingPoints();

        // Check for MSS
        MSS mss = detectMSS(candle);
        if (mss != null) {
            lastMSS = mss;
        }

        return mss;
    }

    /**
     * Adjust swing indices when candles are removed from front.
     *
     * <p>SA5 fix: every stored swing index must SHIFT DOWN by one when the
     * oldest candle is dropped — the previous implementation kept the stale
     * indices, so once the buffer saturated ({@code lookbackPeriod} candles)
     * the pivot index was permanently {@code size - swingLookback - 1} and
     * the "already exists" check blocked every new swing forever: structure
     * detection froze on stale prices after ~50 bars (no MSS could fire on
     * later sessions of a continuous run).
     */
    private void adjustSwingIndices() {
        shiftIndicesDown(swingHighs);
        shiftIndicesDown(swingLows);
    }

    private static void shiftIndicesDown(List<SwingPoint> swings) {
        List<SwingPoint> shifted = new ArrayList<>(swings.size());
        for (SwingPoint sp : swings) {
            if (sp.index > 0) {
                shifted.add(new SwingPoint(sp.isHigh, sp.price, sp.index - 1, sp.timestamp));
            }
        }
        swings.clear();
        swings.addAll(shifted);
    }

    /**
     * Identify swing highs and lows in the candle list.
     */
    private void updateSwingPoints() {
        int size = candles.size();
        int pivotIndex = size - swingLookback - 1;

        if (pivotIndex < swingLookback) {
            return;
        }

        Candle pivotCandle = candles.get(pivotIndex);

        // Check for swing high
        boolean isSwingHigh = true;
        for (int i = pivotIndex - swingLookback; i <= pivotIndex + swingLookback; i++) {
            if (i == pivotIndex) continue;
            if (i < 0 || i >= size) continue;
            if (candles.get(i).getHigh() > pivotCandle.getHigh()) {
                isSwingHigh = false;
                break;
            }
        }

        if (isSwingHigh) {
            // Check if we already have this swing
            boolean exists = swingHighs.stream()
                    .anyMatch(sp -> sp.index == pivotIndex);
            if (!exists) {
                swingHighs.add(new SwingPoint(true, pivotCandle.getHigh(), pivotIndex,
                        pivotCandle.getTimestamp()));
                // Keep only recent swings
                while (swingHighs.size() > 10) {
                    swingHighs.remove(0);
                }
            }
        }

        // Check for swing low
        boolean isSwingLow = true;
        for (int i = pivotIndex - swingLookback; i <= pivotIndex + swingLookback; i++) {
            if (i == pivotIndex) continue;
            if (i < 0 || i >= size) continue;
            if (candles.get(i).getLow() < pivotCandle.getLow()) {
                isSwingLow = false;
                break;
            }
        }

        if (isSwingLow) {
            boolean exists = swingLows.stream()
                    .anyMatch(sp -> sp.index == pivotIndex);
            if (!exists) {
                swingLows.add(new SwingPoint(false, pivotCandle.getLow(), pivotIndex,
                        pivotCandle.getTimestamp()));
                while (swingLows.size() > 10) {
                    swingLows.remove(0);
                }
            }
        }
    }

    /**
     * Detect Market Structure Shift on the current candle.
     */
    private MSS detectMSS(Candle current) {
        if (closeBeyondRecentSwing) {
            return detectCloseBeyondRecentSwing(current);
        }
        int currentIndex = candles.size() - 1;

        // Look for bullish MSS: price breaks above a recent swing high
        // after making lower lows (bearish structure)
        for (int i = swingHighs.size() - 1; i >= 0; i--) {
            SwingPoint swingHigh = swingHighs.get(i);

            // Only consider swing highs that are at least 2 candles old
            if (currentIndex - swingHigh.index < 2) {
                continue;
            }

            // Check if current candle closes above the swing high
            if (current.getClose() > swingHigh.price && current.getOpen() <= swingHigh.price) {
                // Confirm displacement (strong move through level)
                double strength = current.getClose() - swingHigh.price;
                double bodySize = Math.abs(current.getClose() - current.getOpen());

                // MSS requires displacement - at least 50% of body above level
                if (strength > bodySize * 0.3) {
                    // Check for prior bearish structure (lower lows exist)
                    if (hasBearishStructure(swingHigh.index)) {
                        return new MSS(true, swingHigh.price, current.getHigh(),
                                current.getLow(), current.getTimestamp(), strength, currentIndex);
                    }
                }
            }
        }

        // Look for bearish MSS: price breaks below a recent swing low
        // after making higher highs (bullish structure)
        for (int i = swingLows.size() - 1; i >= 0; i--) {
            SwingPoint swingLow = swingLows.get(i);

            if (currentIndex - swingLow.index < 2) {
                continue;
            }

            // Check if current candle closes below the swing low
            if (current.getClose() < swingLow.price && current.getOpen() >= swingLow.price) {
                double strength = swingLow.price - current.getClose();
                double bodySize = Math.abs(current.getClose() - current.getOpen());

                // MSS requires displacement
                if (strength > bodySize * 0.3) {
                    // Check for prior bullish structure
                    if (hasBullishStructure(swingLow.index)) {
                        return new MSS(false, swingLow.price, current.getHigh(),
                                current.getLow(), current.getTimestamp(), strength, currentIndex);
                    }
                }
            }
        }

        return null;
    }

    /** V5 relaxed rule — see {@link #closeBeyondRecentSwing}. */
    private MSS detectCloseBeyondRecentSwing(Candle current) {
        int currentIndex = candles.size() - 1;
        SwingPoint hi = swingHighs.isEmpty() ? null : swingHighs.get(swingHighs.size() - 1);
        SwingPoint lo = swingLows.isEmpty() ? null : swingLows.get(swingLows.size() - 1);
        if (hi != null && currentIndex - hi.index >= 2 && !brokenSwings.contains(hi.timestamp)
                && current.getClose() > hi.price) {
            brokenSwings.add(hi.timestamp);
            return new MSS(true, hi.price, current.getHigh(), current.getLow(),
                    current.getTimestamp(), current.getClose() - hi.price, totalCandleCount);
        }
        if (lo != null && currentIndex - lo.index >= 2 && !brokenSwings.contains(lo.timestamp)
                && current.getClose() < lo.price) {
            brokenSwings.add(lo.timestamp);
            return new MSS(false, lo.price, current.getHigh(), current.getLow(),
                    current.getTimestamp(), lo.price - current.getClose(), totalCandleCount);
        }
        // Only the newest swing on each side is ever checked, so older
        // broken markers are dead weight.
        if (brokenSwings.size() > 2) {
            java.util.Set<Instant> keep = new java.util.HashSet<>();
            if (hi != null) keep.add(hi.timestamp);
            if (lo != null) keep.add(lo.timestamp);
            brokenSwings.retainAll(keep);
        }
        return null;
    }

    /**
     * Check if there was bearish structure (lower lows) before the swing high.
     */
    private boolean hasBearishStructure(int swingHighIndex) {
        // Find swing lows before this swing high
        List<SwingPoint> priorLows = new ArrayList<>();
        for (SwingPoint sl : swingLows) {
            if (sl.index < swingHighIndex) {
                priorLows.add(sl);
            }
        }

        // Need at least 2 lows to compare
        if (priorLows.size() < 2) {
            return false;
        }

        // Check if most recent low is lower than previous
        SwingPoint lastLow = priorLows.get(priorLows.size() - 1);
        SwingPoint prevLow = priorLows.get(priorLows.size() - 2);

        return lastLow.price < prevLow.price;
    }

    /**
     * Check if there was bullish structure (higher highs) before the swing low.
     */
    private boolean hasBullishStructure(int swingLowIndex) {
        List<SwingPoint> priorHighs = new ArrayList<>();
        for (SwingPoint sh : swingHighs) {
            if (sh.index < swingLowIndex) {
                priorHighs.add(sh);
            }
        }

        if (priorHighs.size() < 2) {
            return false;
        }

        SwingPoint lastHigh = priorHighs.get(priorHighs.size() - 1);
        SwingPoint prevHigh = priorHighs.get(priorHighs.size() - 2);

        return lastHigh.price > prevHigh.price;
    }

    /**
     * Get the last detected MSS.
     */
    public MSS getLastMSS() {
        return lastMSS;
    }

    /**
     * Check if there's a recent MSS within the specified number of candles.
     */
    public boolean hasRecentMSS(int withinCandles) {
        if (lastMSS == null) {
            return false;
        }
        return (totalCandleCount - lastMSS.candleIndex) <= withinCandles;
    }

    /**
     * Check if there's a recent bullish MSS.
     */
    public boolean hasRecentBullishMSS(int withinCandles) {
        return hasRecentMSS(withinCandles) && lastMSS.isBullish;
    }

    /**
     * Check if there's a recent bearish MSS.
     */
    public boolean hasRecentBearishMSS(int withinCandles) {
        return hasRecentMSS(withinCandles) && !lastMSS.isBullish;
    }

    /**
     * Get swing high levels for stop placement.
     */
    public List<Double> getRecentSwingHighs(int count) {
        List<Double> levels = new ArrayList<>();
        int start = Math.max(0, swingHighs.size() - count);
        for (int i = start; i < swingHighs.size(); i++) {
            levels.add(swingHighs.get(i).price);
        }
        return levels;
    }

    /**
     * Get swing low levels for stop placement.
     */
    public List<Double> getRecentSwingLows(int count) {
        List<Double> levels = new ArrayList<>();
        int start = Math.max(0, swingLows.size() - count);
        for (int i = start; i < swingLows.size(); i++) {
            levels.add(swingLows.get(i).price);
        }
        return levels;
    }

    /**
     * Reset the detector.
     */
    public void reset() {
        candles.clear();
        swingHighs.clear();
        swingLows.clear();
        lastMSS = null;
        totalCandleCount = 0;
        brokenSwings.clear();
    }
}
