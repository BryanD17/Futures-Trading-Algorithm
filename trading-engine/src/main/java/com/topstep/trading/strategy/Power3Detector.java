package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.time.Instant;
import java.util.LinkedList;

/**
 * Detects Power of 3 (AMD) pattern - Accumulation, Manipulation, Distribution.
 *
 * The Power of 3 is a key ICT concept describing how smart money moves price:
 *
 * 1. ACCUMULATION: Pre-killzone ranging/consolidation
 *    - Price trades in a tight range
 *    - Institutions accumulate positions
 *    - Low volatility, often before killzone opens
 *
 * 2. MANIPULATION: Liquidity sweep (fake move)
 *    - Sharp move in one direction
 *    - Takes out stop losses (liquidity)
 *    - Creates the "trap" for retail traders
 *    - Often occurs in first 30 min of killzone
 *
 * 3. DISTRIBUTION: Real move in opposite direction
 *    - Price reverses from manipulation
 *    - Strong trend in opposite direction
 *    - This is where we want to be positioned
 *
 * This detector identifies which phase we're in to optimize entries.
 */
public class Power3Detector {

    private final LinkedList<Candle> recentCandles;
    private final int lookbackPeriod;
    private final double accumulationThreshold;  // Max range for accumulation
    private final double manipulationThreshold;  // Min move for manipulation

    private Power3Phase currentPhase;
    private double accumulationHigh;
    private double accumulationLow;
    private boolean manipulationUp;    // Direction of manipulation
    private Instant phaseStartTime;

    public Power3Detector(int lookbackPeriod) {
        this.lookbackPeriod = lookbackPeriod;
        this.recentCandles = new LinkedList<>();
        this.accumulationThreshold = 15.0;   // Max 15 points range for accumulation (NQ)
        this.manipulationThreshold = 20.0;   // Min 20 point move for manipulation
        this.currentPhase = Power3Phase.UNKNOWN;
    }

    /**
     * Update with a new candle.
     */
    public void update(Candle candle) {
        recentCandles.add(candle);

        // Keep only lookback period
        while (recentCandles.size() > lookbackPeriod) {
            recentCandles.removeFirst();
        }

        if (recentCandles.size() >= 10) {
            detectPhase();
        }
    }

    /**
     * Detect the current Power of 3 phase.
     */
    private void detectPhase() {
        int size = recentCandles.size();

        // Calculate range of recent candles
        double rangeHigh = recentCandles.stream()
                .mapToDouble(Candle::getHigh)
                .max()
                .orElse(0);

        double rangeLow = recentCandles.stream()
                .mapToDouble(Candle::getLow)
                .min()
                .orElse(0);

        double totalRange = rangeHigh - rangeLow;

        // Check for ACCUMULATION: Tight range
        LinkedList<Candle> last10 = new LinkedList<>();
        for (int i = Math.max(0, size - 10); i < size; i++) {
            last10.add(recentCandles.get(i));
        }

        double recent10High = last10.stream().mapToDouble(Candle::getHigh).max().orElse(0);
        double recent10Low = last10.stream().mapToDouble(Candle::getLow).min().orElse(0);
        double recent10Range = recent10High - recent10Low;

        if (recent10Range <= accumulationThreshold && currentPhase != Power3Phase.DISTRIBUTION) {
            // In accumulation
            if (currentPhase != Power3Phase.ACCUMULATION) {
                currentPhase = Power3Phase.ACCUMULATION;
                accumulationHigh = recent10High;
                accumulationLow = recent10Low;
                phaseStartTime = recentCandles.getLast().getTimestamp();
            } else {
                // Update accumulation boundaries
                accumulationHigh = Math.max(accumulationHigh, recent10High);
                accumulationLow = Math.min(accumulationLow, recent10Low);
            }
            return;
        }

        // Check for MANIPULATION: Sharp move breaking accumulation range
        if (currentPhase == Power3Phase.ACCUMULATION) {
            Candle current = recentCandles.getLast();

            // Manipulation UP: Price spikes above accumulation high
            if (current.getHigh() > accumulationHigh + manipulationThreshold) {
                currentPhase = Power3Phase.MANIPULATION;
                manipulationUp = true;
                phaseStartTime = current.getTimestamp();
                return;
            }

            // Manipulation DOWN: Price spikes below accumulation low
            if (current.getLow() < accumulationLow - manipulationThreshold) {
                currentPhase = Power3Phase.MANIPULATION;
                manipulationUp = false;
                phaseStartTime = current.getTimestamp();
                return;
            }
        }

        // Check for DISTRIBUTION: Reversal from manipulation
        if (currentPhase == Power3Phase.MANIPULATION) {
            Candle current = recentCandles.getLast();
            Candle previous = recentCandles.get(size - 2);

            if (manipulationUp) {
                // After upward manipulation, look for bearish reversal
                if (current.getClose() < previous.getLow() &&
                    current.getClose() < accumulationHigh) {
                    currentPhase = Power3Phase.DISTRIBUTION;
                    phaseStartTime = current.getTimestamp();
                }
            } else {
                // After downward manipulation, look for bullish reversal
                if (current.getClose() > previous.getHigh() &&
                    current.getClose() > accumulationLow) {
                    currentPhase = Power3Phase.DISTRIBUTION;
                    phaseStartTime = current.getTimestamp();
                }
            }
        }
    }

    /**
     * Get the current Power of 3 phase.
     */
    public Power3Phase getCurrentPhase() {
        return currentPhase;
    }

    /**
     * Check if manipulation has occurred (liquidity sweep).
     */
    public boolean hasManipulationOccurred() {
        return currentPhase == Power3Phase.MANIPULATION ||
               currentPhase == Power3Phase.DISTRIBUTION;
    }

    /**
     * Check if we're in distribution phase (ideal for entry).
     */
    public boolean isInDistribution() {
        return currentPhase == Power3Phase.DISTRIBUTION;
    }

    /**
     * Get the direction suggested by Power of 3.
     * After upward manipulation -> expect bearish distribution (short)
     * After downward manipulation -> expect bullish distribution (long)
     */
    public boolean suggestsBullish() {
        if (currentPhase == Power3Phase.DISTRIBUTION) {
            // Opposite of manipulation direction
            return !manipulationUp;
        }
        return false;
    }

    /**
     * Get the manipulation direction.
     */
    public boolean isManipulationUp() {
        return manipulationUp;
    }

    /**
     * Get the accumulation range.
     */
    public double[] getAccumulationRange() {
        return new double[] { accumulationLow, accumulationHigh };
    }

    /**
     * Check if Power of 3 confirms a trade direction.
     */
    public boolean confirmsDirection(boolean bullish) {
        if (currentPhase == Power3Phase.DISTRIBUTION) {
            return suggestsBullish() == bullish;
        }
        return false;
    }

    /**
     * Get a summary of current Power of 3 state.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Power of 3: ").append(currentPhase);

        if (currentPhase == Power3Phase.ACCUMULATION) {
            sb.append(String.format(" [Range: %.2f - %.2f]", accumulationLow, accumulationHigh));
        } else if (currentPhase == Power3Phase.MANIPULATION) {
            sb.append(manipulationUp ? " [Sweep UP - expect SHORT]" : " [Sweep DOWN - expect LONG]");
        } else if (currentPhase == Power3Phase.DISTRIBUTION) {
            sb.append(suggestsBullish() ? " [BULLISH distribution]" : " [BEARISH distribution]");
        }

        return sb.toString();
    }

    /**
     * Reset the detector.
     */
    public void reset() {
        recentCandles.clear();
        currentPhase = Power3Phase.UNKNOWN;
        accumulationHigh = 0;
        accumulationLow = 0;
        manipulationUp = false;
    }

    /**
     * Power of 3 phases.
     */
    public enum Power3Phase {
        UNKNOWN("Unknown phase"),
        ACCUMULATION("Accumulation - institutions building positions"),
        MANIPULATION("Manipulation - liquidity sweep in progress"),
        DISTRIBUTION("Distribution - real move starting");

        private final String description;

        Power3Phase(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
