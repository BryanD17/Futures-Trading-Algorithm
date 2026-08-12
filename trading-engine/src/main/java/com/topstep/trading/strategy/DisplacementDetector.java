package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects Displacement - a key ICT concept.
 *
 * Displacement is a strong, impulsive price move that indicates institutional
 * activity. It's characterized by:
 * 1. Large-bodied candles (body > 70% of total range)
 * 2. Move size significantly larger than average (typically 1.5-2x ATR)
 * 3. Usually creates FVGs in its path
 *
 * Displacement confirms the direction of smart money and often precedes
 * high-probability entries.
 *
 * ENHANCED (Multi-Timeframe Trend Integration):
 * Now also serves as the Layer 3 entry trigger in the 3-layer cascade:
 *   - Tracks whether displacement created a potential FVG (3-candle gap)
 *   - Detects Market Structure Shift (MSS) on 1m within the displacement
 *   - Provides body-to-range ratio for configurable thresholds per instrument
 *   - Supports configurable thresholds for instrument-specific tuning
 *
 * Layer 3 Entry Model:
 *   1. Wait for displacement (large candle + 1m MSS in trend direction)
 *   2. Look for FVG created by the displacement
 *   3. Enter when price retraces into the 1m FVG
 *   4. Stop below/above the 5m zone that was retested
 *   5. Target is the next liquidity pool identified by HTF analysis
 */
public class DisplacementDetector {

    private final List<Candle> candles;
    private final int lookbackPeriod;
    private final double displacementMultiplier;  // Min multiplier of ATR for displacement
    private final double minBodyRatio;            // Min body/range ratio for strong candle

    private Displacement lastDisplacement;
    private int candleCountAtLastDisplacement;
    private int totalCandleCount;

    // Layer 3 entry model tracking
    private boolean lastDisplacementCreatedFvg;
    private double displacementFvgHigh;
    private double displacementFvgLow;
    private Double priorSwingHigh;  // Swing point before displacement for MSS check
    private Double priorSwingLow;

    /** Symbol tag for log attribution (may be empty for legacy callers). */
    private final String logTag;

    public DisplacementDetector(int lookbackPeriod) {
        this(lookbackPeriod, 1.5, 0.65);
    }

    /**
     * Constructor with configurable thresholds for instrument-specific tuning.
     *
     * @param lookbackPeriod Candle lookback window
     * @param displacementMultiplier Min ATR multiplier for displacement (default 1.5)
     * @param minBodyRatio Min body/range ratio (default 0.65, use 0.70 for stricter)
     */
    public DisplacementDetector(int lookbackPeriod, double displacementMultiplier, double minBodyRatio) {
        this(lookbackPeriod, displacementMultiplier, minBodyRatio, "");
    }

    /**
     * Constructor with a symbol tag so multi-instrument logs are
     * attributable (field lesson 2026-07-09: untagged [DISPLACEMENT] lines
     * made a per-symbol diagnosis needlessly slow).
     */
    public DisplacementDetector(int lookbackPeriod, double displacementMultiplier,
                                double minBodyRatio, String logTag) {
        this.lookbackPeriod = lookbackPeriod;
        this.displacementMultiplier = displacementMultiplier;
        this.minBodyRatio = minBodyRatio;
        this.logTag = (logTag == null) ? "" : logTag;
        this.candles = new ArrayList<>();
    }

    /**
     * Update with a new candle.
     */
    public void update(Candle candle) {
        // Track swing points before adding candle (for MSS detection)
        trackSwingPoints();

        candles.add(candle);
        totalCandleCount++;

        if (candles.size() > lookbackPeriod) {
            candles.remove(0);
        }

        if (candles.size() >= 3) {
            detectDisplacement(candle);
        }
    }

    /**
     * Track swing points for MSS detection within displacement.
     */
    private void trackSwingPoints() {
        if (candles.size() < 3) return;

        int size = candles.size();
        Candle prev = candles.get(size - 3);
        Candle mid = candles.get(size - 2);
        Candle curr = candles.get(size - 1);

        // Swing high
        if (mid.getHigh() > prev.getHigh() && mid.getHigh() > curr.getHigh()) {
            priorSwingHigh = mid.getHigh();
        }
        // Swing low
        if (mid.getLow() < prev.getLow() && mid.getLow() < curr.getLow()) {
            priorSwingLow = mid.getLow();
        }
    }

    /**
     * Detect displacement from recent candles.
     */
    private void detectDisplacement(Candle latestCandle) {
        // Calculate average range (ATR approximation)
        double avgRange = candles.stream()
                .skip(Math.max(0, candles.size() - 14))
                .mapToDouble(c -> c.getHigh() - c.getLow())
                .average()
                .orElse(10.0);

        double minDisplacementMove = avgRange * displacementMultiplier;

        // Check latest candle for displacement characteristics
        double range = latestCandle.getHigh() - latestCandle.getLow();
        double body = Math.abs(latestCandle.getClose() - latestCandle.getOpen());
        double bodyRatio = range > 0 ? body / range : 0;

        // Is this a strong candle?
        boolean isStrongCandle = bodyRatio >= minBodyRatio && range >= minDisplacementMove;

        if (!isStrongCandle) {
            return;
        }

        // Determine direction
        boolean bullish = latestCandle.getClose() > latestCandle.getOpen();

        // Check for continuation (2-3 consecutive strong candles in same direction)
        int consecutiveCount = 1;
        for (int i = candles.size() - 2; i >= Math.max(0, candles.size() - 3); i--) {
            Candle prevCandle = candles.get(i);
            double prevBody = Math.abs(prevCandle.getClose() - prevCandle.getOpen());
            double prevRange = prevCandle.getHigh() - prevCandle.getLow();
            double prevBodyRatio = prevRange > 0 ? prevBody / prevRange : 0;

            boolean prevBullish = prevCandle.getClose() > prevCandle.getOpen();
            boolean prevStrong = prevBodyRatio >= minBodyRatio * 0.8;  // Slightly relaxed

            if (prevBullish == bullish && prevStrong) {
                consecutiveCount++;
            } else {
                break;
            }
        }

        // Displacement requires at least the current strong candle
        // Multiple consecutive strong candles increase confidence
        double totalMove = 0;
        if (consecutiveCount >= 2) {
            // Calculate total move over consecutive candles
            int startIdx = candles.size() - consecutiveCount;
            Candle startCandle = candles.get(startIdx);
            totalMove = bullish ?
                    latestCandle.getClose() - startCandle.getOpen() :
                    startCandle.getOpen() - latestCandle.getClose();
        } else {
            totalMove = body;
        }

        // Confirm displacement.
        //
        // DEFECT FIX (V4 follow-up): this used to apply `totalMove >=
        // minDisplacementMove` to the SINGLE-candle case as well. That
        // compares a BODY against a threshold derived from average RANGE
        // (avgRange * multiplier), and since body <= range it silently
        // SUBSUMED the expansion test already performed in isStrongCandle.
        // The effective bar became body >= 1.5 * avgRange which, with
        // bodyRatio >= 0.65, demands range >= ~2.3x the 14-bar average range
        // — nearly double the 1.5x-ATR intent this class documents.
        //
        // Measured consequence: the detector fired ONCE or TWICE per entire
        // SIM session, the SWEEP_DONE -> DISPLACED transition never happened,
        // and the funnel could not reach an entry.
        //
        // A single candle that is both an expansion (range >= avgRange *
        // multiplier) and body-dominated (bodyRatio >= minBodyRatio) has
        // already met the definition. The multi-candle branch keeps its own
        // distance test: a RUN of candles must still cover the ground.
        boolean confirmed = consecutiveCount < 2 || totalMove >= minDisplacementMove;
        if (confirmed) {
            // Check for FVG creation (Layer 3 entry model)
            boolean createdFvg = checkForFvgCreation(bullish);

            // Check for MSS (1m swing point broken in trend direction)
            boolean brokeMss = checkForMss(latestCandle, bullish);

            lastDisplacement = new Displacement(
                    bullish,
                    totalMove,
                    consecutiveCount,
                    latestCandle.getTimestamp(),
                    bodyRatio,
                    createdFvg,
                    brokeMss
            );
            candleCountAtLastDisplacement = totalCandleCount;
            lastDisplacementCreatedFvg = createdFvg;

            System.out.println("[DISPLACEMENT" + (logTag.isEmpty() ? "" : " " + logTag) + "] "
                    + (bullish ? "BULLISH" : "BEARISH") +
                    " displacement detected - Move: " + String.format("%.2f", totalMove) +
                    " points over " + consecutiveCount + " candles" +
                    " (bodyRatio=" + String.format("%.0f%%", bodyRatio * 100) +
                    ", FVG=" + createdFvg + ", MSS=" + brokeMss + ")");
        }
    }

    /**
     * Check if the displacement created a Fair Value Gap.
     * An FVG occurs when candle 1's wick and candle 3's wick don't overlap.
     */
    private boolean checkForFvgCreation(boolean bullish) {
        if (candles.size() < 3) return false;

        int size = candles.size();
        Candle c1 = candles.get(size - 3);
        Candle c2 = candles.get(size - 2);
        Candle c3 = candles.get(size - 1);

        if (bullish) {
            // Bullish FVG: c1's high < c3's low (gap between c1 high and c3 low)
            if (c1.getHigh() < c3.getLow()) {
                displacementFvgHigh = c3.getLow();
                displacementFvgLow = c1.getHigh();
                return true;
            }
        } else {
            // Bearish FVG: c1's low > c3's high (gap between c3 high and c1 low)
            if (c1.getLow() > c3.getHigh()) {
                displacementFvgHigh = c1.getLow();
                displacementFvgLow = c3.getHigh();
                return true;
            }
        }

        return false;
    }

    /**
     * Check if the displacement broke a 1m swing point (MSS confirmation).
     */
    private boolean checkForMss(Candle latestCandle, boolean bullish) {
        if (bullish) {
            // Bullish MSS: price broke above a prior swing high
            return priorSwingHigh != null && latestCandle.getClose() > priorSwingHigh;
        } else {
            // Bearish MSS: price broke below a prior swing low
            return priorSwingLow != null && latestCandle.getClose() < priorSwingLow;
        }
    }

    /**
     * Check if there's a recent displacement (within N candles).
     */
    public boolean hasRecentDisplacement(int withinCandles) {
        if (lastDisplacement == null || candleCountAtLastDisplacement == 0) {
            return false;
        }
        int candlesSinceDisplacement = totalCandleCount - candleCountAtLastDisplacement;
        return candlesSinceDisplacement <= withinCandles;
    }

    /**
     * Check if there's a recent displacement in the specified direction.
     */
    public boolean hasRecentDisplacement(int withinCandles, boolean bullish) {
        if (!hasRecentDisplacement(withinCandles)) {
            return false;
        }
        return lastDisplacement.isBullish() == bullish;
    }

    /**
     * Check if the last displacement is a full Layer 3 entry trigger.
     * Full trigger = displacement + FVG creation + MSS break in trend direction.
     *
     * @param withinCandles Lookback window
     * @param bullish Expected direction
     * @return true if all three conditions met
     */
    public boolean hasLayer3EntryTrigger(int withinCandles, boolean bullish) {
        if (!hasRecentDisplacement(withinCandles, bullish)) return false;
        return lastDisplacement.createdFvg() && lastDisplacement.brokeMss();
    }

    /**
     * Get the FVG zone created by the last displacement (for entry retracement).
     * Returns null if no FVG was created.
     */
    public double[] getDisplacementFvgZone() {
        if (!lastDisplacementCreatedFvg) return null;
        return new double[]{displacementFvgLow, displacementFvgHigh};
    }

    /**
     * Get the last detected displacement.
     */
    public Displacement getLastDisplacement() {
        return lastDisplacement;
    }

    /**
     * Reset the detector.
     */
    public void reset() {
        candles.clear();
        lastDisplacement = null;
        candleCountAtLastDisplacement = 0;
        totalCandleCount = 0;
        lastDisplacementCreatedFvg = false;
        priorSwingHigh = null;
        priorSwingLow = null;
    }

    /**
     * Inner class representing a displacement event.
     * Enhanced with Layer 3 entry model data.
     */
    public static class Displacement {
        private final boolean bullish;
        private final double moveSize;
        private final int candleCount;
        private final Instant timestamp;
        private final double bodyRatio;
        private final boolean createdFvg;
        private final boolean brokeMss;

        public Displacement(boolean bullish, double moveSize, int candleCount, Instant timestamp) {
            this(bullish, moveSize, candleCount, timestamp, 0.0, false, false);
        }

        public Displacement(boolean bullish, double moveSize, int candleCount, Instant timestamp,
                            double bodyRatio, boolean createdFvg, boolean brokeMss) {
            this.bullish = bullish;
            this.moveSize = moveSize;
            this.candleCount = candleCount;
            this.timestamp = timestamp;
            this.bodyRatio = bodyRatio;
            this.createdFvg = createdFvg;
            this.brokeMss = brokeMss;
        }

        public boolean isBullish() { return bullish; }
        public double getMoveSize() { return moveSize; }
        public int getCandleCount() { return candleCount; }
        public Instant getTimestamp() { return timestamp; }
        public double getBodyRatio() { return bodyRatio; }
        public boolean createdFvg() { return createdFvg; }
        public boolean brokeMss() { return brokeMss; }

        /**
         * Check if this displacement qualifies as a full Layer 3 trigger.
         * Full trigger: displacement + FVG + MSS.
         */
        public boolean isFullEntryTrigger() {
            return createdFvg && brokeMss;
        }

        @Override
        public String toString() {
            return String.format("Displacement[%s, %.2f pts over %d candles, body=%.0f%%, fvg=%s, mss=%s]",
                    bullish ? "BULLISH" : "BEARISH", moveSize, candleCount,
                    bodyRatio * 100, createdFvg, brokeMss);
        }
    }
}
