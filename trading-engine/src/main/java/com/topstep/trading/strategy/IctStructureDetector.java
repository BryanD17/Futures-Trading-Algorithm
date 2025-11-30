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
     */
    private void detectSwingPoints() {
        if (candles.size() < 3) {
            return;
        }

        // Look at recent candles to find swing points
        int size = candles.size();

        // Check for swing high (middle candle higher than neighbors)
        for (int i = 1; i < size - 1; i++) {
            Candle prev = candles.get(i - 1);
            Candle curr = candles.get(i);
            Candle next = candles.get(i + 1);

            // Swing high: current high is higher than both neighbors
            if (curr.getHigh() > prev.getHigh() && curr.getHigh() > next.getHigh()) {
                if (lastSwingHigh == null || curr.getHigh() > lastSwingHigh) {
                    lastSwingHigh = curr.getHigh();
                }
            }

            // Swing low: current low is lower than both neighbors
            if (curr.getLow() < prev.getLow() && curr.getLow() < next.getLow()) {
                if (lastSwingLow == null || curr.getLow() < lastSwingLow) {
                    lastSwingLow = curr.getLow();
                }
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
                // Change of Character to bullish
                currentBias = MarketBias.BULLISH;
            }
        }
        // Bearish BOS: price breaks below recent swing low
        else if (latest.getClose() < lastSwingLow) {
            if (currentBias != MarketBias.BEARISH) {
                // Change of Character to bearish
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
