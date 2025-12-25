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
 */
public class DisplacementDetector {

    private final List<Candle> candles;
    private final int lookbackPeriod;
    private final double displacementMultiplier;  // Min multiplier of ATR for displacement
    private final double minBodyRatio;            // Min body/range ratio for strong candle

    private Displacement lastDisplacement;
    private int candleCountAtLastDisplacement;
    private int totalCandleCount;

    public DisplacementDetector(int lookbackPeriod) {
        this.lookbackPeriod = lookbackPeriod;
        this.displacementMultiplier = 1.5;  // 1.5x average range
        this.minBodyRatio = 0.65;           // Body must be 65% of total range
        this.candles = new ArrayList<>();
    }

    /**
     * Update with a new candle.
     */
    public void update(Candle candle) {
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

        // Confirm displacement
        if (totalMove >= minDisplacementMove) {
            lastDisplacement = new Displacement(
                    bullish,
                    totalMove,
                    consecutiveCount,
                    latestCandle.getTimestamp()
            );
            candleCountAtLastDisplacement = totalCandleCount;

            System.out.println("[DISPLACEMENT] " + (bullish ? "BULLISH" : "BEARISH") +
                    " displacement detected - Move: " + String.format("%.2f", totalMove) +
                    " points over " + consecutiveCount + " candles (min required: " +
                    String.format("%.2f", minDisplacementMove) + ")");
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
    }

    /**
     * Inner class representing a displacement event.
     */
    public static class Displacement {
        private final boolean bullish;
        private final double moveSize;
        private final int candleCount;
        private final Instant timestamp;

        public Displacement(boolean bullish, double moveSize, int candleCount, Instant timestamp) {
            this.bullish = bullish;
            this.moveSize = moveSize;
            this.candleCount = candleCount;
            this.timestamp = timestamp;
        }

        public boolean isBullish() {
            return bullish;
        }

        public double getMoveSize() {
            return moveSize;
        }

        public int getCandleCount() {
            return candleCount;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        @Override
        public String toString() {
            return String.format("Displacement[%s, %.2f pts over %d candles]",
                    bullish ? "BULLISH" : "BEARISH", moveSize, candleCount);
        }
    }
}
