package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

    // Swing point listener support for statistical analysis components
    private final List<SwingPointListener> swingPointListeners = new CopyOnWriteArrayList<>();

    // Track previous swing points for impulse/pullback detection
    private Double previousSwingHigh;
    private Double previousSwingLow;
    private int barsSinceLastSwing;

    public IctStructureDetector(int lookbackPeriod) {
        this.lookbackPeriod = lookbackPeriod;
        this.candles = new ArrayList<>();
        this.currentBias = MarketBias.NEUTRAL;
    }

    /**
     * Register a listener to receive swing point events.
     */
    public void addSwingPointListener(SwingPointListener listener) {
        if (listener != null) {
            swingPointListeners.add(listener);
        }
    }

    /**
     * Remove a swing point listener.
     */
    public void removeSwingPointListener(SwingPointListener listener) {
        swingPointListeners.remove(listener);
    }

    /**
     * Update with a new candle and detect structure changes.
     */
    public void update(Candle candle) {
        candles.add(candle);
        barsSinceLastSwing++;

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
                Double oldSwingHigh = lastSwingHigh;
                lastSwingHigh = curr.getHigh();

                // Notify listeners of swing point changes
                if (oldSwingHigh != null && !lastSwingHigh.equals(oldSwingHigh)) {
                    notifySwingHighChange(oldSwingHigh, lastSwingHigh);
                }
            }

            // Swing low: current low is lower than both neighbors
            // Store the most recent swing (not requiring it to be lower than previous)
            if (curr.getLow() < prev.getLow() && curr.getLow() < next.getLow()) {
                Double oldSwingLow = lastSwingLow;
                lastSwingLow = curr.getLow();

                // Notify listeners of swing point changes
                if (oldSwingLow != null && !lastSwingLow.equals(oldSwingLow)) {
                    notifySwingLowChange(oldSwingLow, lastSwingLow);
                }
            }
        }
    }

    /**
     * Notify listeners when a new swing high is detected.
     * In a BULLISH trend, a new swing high completes an impulse.
     * Also checks for completed pullback in BULLISH trend:
     * previous swing high → pullback down → new swing high above previous.
     */
    private void notifySwingHighChange(double oldSwingHigh, double newSwingHigh) {
        if (swingPointListeners.isEmpty()) return;
        int duration = barsSinceLastSwing;
        barsSinceLastSwing = 0;

        if (currentBias == MarketBias.BULLISH && previousSwingLow != null) {
            // New swing high in uptrend = impulse completed
            double impulseSize = newSwingHigh - previousSwingLow;
            if (impulseSize > 0) {
                for (SwingPointListener listener : swingPointListeners) {
                    listener.onImpulseCompleted(impulseSize, true);
                }
            }

            // Check for completed pullback: old swing high → dip to lastSwingLow → new swing high
            if (lastSwingLow != null && newSwingHigh > oldSwingHigh && lastSwingLow < oldSwingHigh) {
                double impulseRange = oldSwingHigh - previousSwingLow;
                double pullbackRange = oldSwingHigh - lastSwingLow;
                if (impulseRange > 0 && pullbackRange > 0) {
                    boolean continued = newSwingHigh > oldSwingHigh;
                    for (SwingPointListener listener : swingPointListeners) {
                        listener.onPullbackCompleted(impulseRange, pullbackRange, duration, continued);
                    }
                }
            }
        }

        previousSwingHigh = oldSwingHigh;
    }

    /**
     * Notify listeners when a new swing low is detected.
     * In a BEARISH trend, a new swing low completes an impulse.
     * Also checks for completed pullback in BEARISH trend.
     */
    private void notifySwingLowChange(double oldSwingLow, double newSwingLow) {
        if (swingPointListeners.isEmpty()) return;
        int duration = barsSinceLastSwing;
        barsSinceLastSwing = 0;

        if (currentBias == MarketBias.BEARISH && previousSwingHigh != null) {
            // New swing low in downtrend = impulse completed
            double impulseSize = previousSwingHigh - newSwingLow;
            if (impulseSize > 0) {
                for (SwingPointListener listener : swingPointListeners) {
                    listener.onImpulseCompleted(impulseSize, false);
                }
            }

            // Check for completed pullback: old swing low → bounce to lastSwingHigh → new swing low
            if (lastSwingHigh != null && newSwingLow < oldSwingLow && lastSwingHigh > oldSwingLow) {
                double impulseRange = previousSwingHigh - oldSwingLow;
                double pullbackRange = lastSwingHigh - oldSwingLow;
                if (impulseRange > 0 && pullbackRange > 0) {
                    boolean continued = newSwingLow < oldSwingLow;
                    for (SwingPointListener listener : swingPointListeners) {
                        listener.onPullbackCompleted(impulseRange, pullbackRange, duration, continued);
                    }
                }
            }
        }

        previousSwingLow = oldSwingLow;
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
        previousSwingHigh = null;
        previousSwingLow = null;
        barsSinceLastSwing = 0;
        currentBias = MarketBias.NEUTRAL;
    }

    /**
     * Check if there was a recent Market Structure Shift (MSS) within the lookback period.
     * An MSS is detected when the bias changes from one direction to another.
     *
     * @param lookback Number of candles to look back for MSS
     * @return true if MSS occurred recently
     */
    public boolean hasRecentMss(int lookback) {
        // If bias is neutral, no MSS has occurred
        if (currentBias == MarketBias.NEUTRAL) {
            return false;
        }

        // We need at least a few candles to detect MSS
        if (candles.size() < 3) {
            return false;
        }

        // Check if we have swing points that indicate a shift
        // An MSS is confirmed when price breaks structure in the new direction
        if (lastSwingHigh != null && lastSwingLow != null && !candles.isEmpty()) {
            Candle latest = candles.get(candles.size() - 1);

            // For bullish MSS: price broke above swing high (lower high broken)
            if (currentBias == MarketBias.BULLISH && latest.getClose() > lastSwingHigh) {
                return true;
            }

            // For bearish MSS: price broke below swing low (higher low broken)
            if (currentBias == MarketBias.BEARISH && latest.getClose() < lastSwingLow) {
                return true;
            }
        }

        return false;
    }
}
