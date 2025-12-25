package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects higher-timeframe market structure using ICT concepts:
 * - Break of Structure (BOS)
 * - Change of Character (CHoCH)
 * - Swing highs and lows
 */
public class IctStructureDetector {

    private final List<Candle> candles;
    private final int lookbackPeriod;

    private Double lastSwingHigh;
    private Double lastSwingLow;
    private MarketBias currentBias;

    public IctStructureDetector(int lookbackPeriod) {
        this.lookbackPeriod = lookbackPeriod;
        this.candles = new ArrayList<>();
        this.currentBias = MarketBias.NEUTRAL;
    }

    /**
     * Update with a new candle and detect structure changes.
     */
    public void update(Candle candle) {
        candles.add(candle);

        // Keep only the lookback period
        if (candles.size() > lookbackPeriod) {
            candles.remove(0);
        }

        // Need at least a few candles to detect structure
        if (candles.size() < 3) {
            return;
        }

        detectSwingPoints();
        detectStructureChange();
    }

    /**
     * Detect swing highs and lows in the recent candles.
     * Tracks the MOST RECENT swing points (not the extreme values).
     */
    private void detectSwingPoints() {
        if (candles.size() < 3) {
            return;
        }

        // Look at recent candles to find swing points
        // Iterate forward so the most recent swing point is found last and stored
        int size = candles.size();

        // Check for swing high (middle candle higher than neighbors)
        for (int i = 1; i < size - 1; i++) {
            Candle prev = candles.get(i - 1);
            Candle curr = candles.get(i);
            Candle next = candles.get(i + 1);

            // Swing high: current high is higher than both neighbors
            // Store the most recent swing (not requiring it to be higher than previous)
            if (curr.getHigh() > prev.getHigh() && curr.getHigh() > next.getHigh()) {
                lastSwingHigh = curr.getHigh();
            }

            // Swing low: current low is lower than both neighbors
            // Store the most recent swing (not requiring it to be lower than previous)
            if (curr.getLow() < prev.getLow() && curr.getLow() < next.getLow()) {
                lastSwingLow = curr.getLow();
            }
        }
    }

    /**
     * Detect Break of Structure (BOS) or Change of Character (CHoCH).
     * This determines the market bias.
     */
    private void detectStructureChange() {
        if (lastSwingHigh == null || lastSwingLow == null || candles.isEmpty()) {
            return;
        }

        Candle latest = candles.get(candles.size() - 1);

        // Bullish BOS: price breaks above recent swing high
        if (latest.getClose() > lastSwingHigh) {
            if (currentBias != MarketBias.BULLISH) {
                System.out.println("[STRUCTURE] BOS BULLISH - Close " + latest.getClose() + " > SwingHigh " + lastSwingHigh);
                currentBias = MarketBias.BULLISH;
            }
        }
        // Bearish BOS: price breaks below recent swing low
        else if (latest.getClose() < lastSwingLow) {
            if (currentBias != MarketBias.BEARISH) {
                System.out.println("[STRUCTURE] BOS BEARISH - Close " + latest.getClose() + " < SwingLow " + lastSwingLow);
                currentBias = MarketBias.BEARISH;
            }
        }
        // Intermediate bias detection based on trend
        else if (currentBias == MarketBias.NEUTRAL && candles.size() >= 10) {
            // Check recent price action to determine trend direction
            double recentHigh = candles.stream().skip(candles.size() - 5).mapToDouble(Candle::getHigh).max().orElse(0);
            double recentLow = candles.stream().skip(candles.size() - 5).mapToDouble(Candle::getLow).min().orElse(0);
            double olderHigh = candles.stream().limit(5).mapToDouble(Candle::getHigh).max().orElse(0);
            double olderLow = candles.stream().limit(5).mapToDouble(Candle::getLow).min().orElse(0);

            // Higher highs and higher lows = bullish
            if (recentHigh > olderHigh && recentLow > olderLow) {
                System.out.println("[STRUCTURE] Trend BULLISH - Higher highs and higher lows detected");
                currentBias = MarketBias.BULLISH;
            }
            // Lower highs and lower lows = bearish
            else if (recentHigh < olderHigh && recentLow < olderLow) {
                System.out.println("[STRUCTURE] Trend BEARISH - Lower highs and lower lows detected");
                currentBias = MarketBias.BEARISH;
            }
        }
    }

    /**
     * Get the current market bias based on structure.
     */
    public MarketBias getBias() {
        return currentBias;
    }

    /**
     * Get the last detected swing high.
     */
    public Double getLastSwingHigh() {
        return lastSwingHigh;
    }

    /**
     * Get the last detected swing low.
     */
    public Double getLastSwingLow() {
        return lastSwingLow;
    }

    /**
     * Reset the detector.
     */
    public void reset() {
        candles.clear();
        lastSwingHigh = null;
        lastSwingLow = null;
        currentBias = MarketBias.NEUTRAL;
    }
}
