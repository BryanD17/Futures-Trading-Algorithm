package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.BarAggregationManager.Timeframe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTF Trend State Machine - THE PRIMARY DIRECTIONAL FILTER.
 *
 * This is the first gate in the 3-layer decision cascade:
 *   Layer 1: HTF Trend Direction (this component) → determines IF and WHICH direction to trade
 *   Layer 2: 5m Continuation Zone → confirms pullback to confluent zone
 *   Layer 3: 1m Displacement Entry → precise entry trigger
 *
 * Classifies the 15m/30m into one of five states:
 *   STRONG_BULLISH  - HH/HL on 15m, 30m aligned, displacement present → Only longs
 *   WEAK_BULLISH    - HH/HL but fading momentum → Longs only, reduced size
 *   RANGING         - No clear structure → NO TRADES
 *   WEAK_BEARISH    - LH/LL but fading momentum → Shorts only, reduced size
 *   STRONG_BEARISH  - LH/LL on 15m, 30m aligned, displacement present → Only shorts
 *
 * The HTF trend is NOT just one confluence factor — it is the DOMINANT gate.
 * When HTF is ranging, NO trades are taken regardless of lower-timeframe setups.
 */
public class HtfTrendAnalyzer {

    /**
     * HTF trend states with trading implications.
     */
    public enum HtfTrendState {
        STRONG_BULLISH("Strong Bullish", true, false, 1.0),
        WEAK_BULLISH("Weak Bullish", true, false, 0.5),
        RANGING("Ranging", false, false, 0.0),
        WEAK_BEARISH("Weak Bearish", false, true, 0.5),
        STRONG_BEARISH("Strong Bearish", false, true, 1.0);

        private final String displayName;
        private final boolean allowsLongs;
        private final boolean allowsShorts;
        private final double sizeMultiplier;

        HtfTrendState(String displayName, boolean allowsLongs, boolean allowsShorts, double sizeMultiplier) {
            this.displayName = displayName;
            this.allowsLongs = allowsLongs;
            this.allowsShorts = allowsShorts;
            this.sizeMultiplier = sizeMultiplier;
        }

        public String getDisplayName() { return displayName; }
        public boolean allowsLongs() { return allowsLongs; }
        public boolean allowsShorts() { return allowsShorts; }
        public double getSizeMultiplier() { return sizeMultiplier; }

        public boolean allowsDirection(boolean bullish) {
            return bullish ? allowsLongs : allowsShorts;
        }

        public boolean isStrong() {
            return this == STRONG_BULLISH || this == STRONG_BEARISH;
        }

        public boolean isTrending() {
            return this != RANGING;
        }
    }

    private final String symbol;
    private final BarAggregationManager barManager;
    private final IctStructureDetector structure15m;
    private final IctStructureDetector structure30m;
    private final DisplacementDetector displacement15m;
    private final DisplacementDetector displacement30m;

    // State
    private HtfTrendState currentState = HtfTrendState.RANGING;
    private HtfTrendState previousState = HtfTrendState.RANGING;
    private Instant lastStateChangeTime;

    // 15m swing tracking for HH/HL/LH/LL detection
    private final List<Double> swingHighs15m = new ArrayList<>();
    private final List<Double> swingLows15m = new ArrayList<>();
    private static final int MAX_SWING_HISTORY = 6;

    // Momentum tracking
    private final List<Double> candleBodies15m = new ArrayList<>();
    private static final int MOMENTUM_LOOKBACK = 8;

    // Pullback depth tracking
    private double lastImpulseHigh = Double.MIN_VALUE;
    private double lastImpulseLow = Double.MAX_VALUE;

    public HtfTrendAnalyzer(String symbol, BarAggregationManager barManager) {
        this.symbol = symbol;
        this.barManager = barManager;

        // Dedicated structure detectors for HTF trend
        this.structure15m = new IctStructureDetector(50);
        this.structure30m = new IctStructureDetector(50);
        this.displacement15m = new DisplacementDetector(20);
        this.displacement30m = new DisplacementDetector(20);
    }

    /**
     * Update with newly completed candles from the bar aggregation manager.
     * Call after barManager.processCandle().
     */
    public void update(Map<Timeframe, Candle> completedCandles) {
        Candle candle15m = completedCandles.get(Timeframe.M15);
        Candle candle30m = completedCandles.get(Timeframe.M30);

        if (candle15m != null) {
            structure15m.update(candle15m);
            displacement15m.update(candle15m);
            trackSwingPoints15m(candle15m);
            trackMomentum15m(candle15m);
        }

        if (candle30m != null) {
            structure30m.update(candle30m);
            displacement30m.update(candle30m);
        }

        // Re-evaluate trend state on any HTF candle close
        if (candle15m != null || candle30m != null) {
            evaluateTrendState();
        }
    }

    /**
     * Track swing points on 15m for HH/HL/LH/LL pattern detection.
     */
    private void trackSwingPoints15m(Candle candle) {
        Double swingHigh = structure15m.getLastSwingHigh();
        Double swingLow = structure15m.getLastSwingLow();

        if (swingHigh != null) {
            if (swingHighs15m.isEmpty() || !swingHighs15m.get(swingHighs15m.size() - 1).equals(swingHigh)) {
                swingHighs15m.add(swingHigh);
                if (swingHighs15m.size() > MAX_SWING_HISTORY) {
                    swingHighs15m.remove(0);
                }
            }
        }

        if (swingLow != null) {
            if (swingLows15m.isEmpty() || !swingLows15m.get(swingLows15m.size() - 1).equals(swingLow)) {
                swingLows15m.add(swingLow);
                if (swingLows15m.size() > MAX_SWING_HISTORY) {
                    swingLows15m.remove(0);
                }
            }
        }
    }

    /**
     * Track candle body sizes for momentum analysis.
     * Fading momentum = bodies getting smaller → WEAK state.
     */
    private void trackMomentum15m(Candle candle) {
        double body = Math.abs(candle.getClose() - candle.getOpen());
        candleBodies15m.add(body);
        if (candleBodies15m.size() > MOMENTUM_LOOKBACK) {
            candleBodies15m.remove(0);
        }
    }

    /**
     * Core state machine evaluation.
     * Determines HTF trend state from 15m/30m structure, swing patterns, and momentum.
     */
    private void evaluateTrendState() {
        MarketBias bias15m = structure15m.getBias();
        MarketBias bias30m = structure30m.getBias();

        boolean has15mDisplacement = displacement15m.hasRecentDisplacement(5);
        boolean has30mDisplacement = displacement30m.hasRecentDisplacement(3);

        boolean makingHH = isHigherHighs();
        boolean makingHL = isHigherLows();
        boolean makingLH = isLowerHighs();
        boolean makingLL = isLowerLows();

        boolean momentumFading = isMomentumFading();

        HtfTrendState newState;

        // STRONG_BULLISH: HH + HL on 15m, 30m aligned bullish, displacement present
        if (makingHH && makingHL && bias15m == MarketBias.BULLISH &&
                (bias30m == MarketBias.BULLISH || bias30m == MarketBias.NEUTRAL) &&
                (has15mDisplacement || has30mDisplacement) && !momentumFading) {
            newState = HtfTrendState.STRONG_BULLISH;
        }
        // WEAK_BULLISH: HH/HL pattern but momentum fading, or 30m not fully aligned
        else if ((makingHH || makingHL) && bias15m == MarketBias.BULLISH) {
            if (momentumFading || bias30m == MarketBias.BEARISH) {
                newState = HtfTrendState.WEAK_BULLISH;
            } else {
                // Not full strong criteria but bullish structure
                newState = HtfTrendState.WEAK_BULLISH;
            }
        }
        // STRONG_BEARISH: LH + LL on 15m, 30m aligned bearish, displacement present
        else if (makingLH && makingLL && bias15m == MarketBias.BEARISH &&
                (bias30m == MarketBias.BEARISH || bias30m == MarketBias.NEUTRAL) &&
                (has15mDisplacement || has30mDisplacement) && !momentumFading) {
            newState = HtfTrendState.STRONG_BEARISH;
        }
        // WEAK_BEARISH: LH/LL pattern but momentum fading, or 30m not fully aligned
        else if ((makingLH || makingLL) && bias15m == MarketBias.BEARISH) {
            if (momentumFading || bias30m == MarketBias.BULLISH) {
                newState = HtfTrendState.WEAK_BEARISH;
            } else {
                newState = HtfTrendState.WEAK_BEARISH;
            }
        }
        // Aligned bias but no clear swing pattern yet — allow weak directional
        else if (bias15m == MarketBias.BULLISH && bias30m == MarketBias.BULLISH) {
            newState = HtfTrendState.WEAK_BULLISH;
        } else if (bias15m == MarketBias.BEARISH && bias30m == MarketBias.BEARISH) {
            newState = HtfTrendState.WEAK_BEARISH;
        }
        // RANGING: No clear pattern
        else {
            newState = HtfTrendState.RANGING;
        }

        // Apply state transition
        if (newState != currentState) {
            previousState = currentState;
            currentState = newState;
            lastStateChangeTime = Instant.now();

            System.out.println("[" + symbol + "] HTF TREND STATE: " + previousState.getDisplayName() +
                    " → " + currentState.getDisplayName());
        }
    }

    /**
     * Check if 15m is making higher highs.
     */
    private boolean isHigherHighs() {
        if (swingHighs15m.size() < 2) return false;
        int last = swingHighs15m.size() - 1;
        return swingHighs15m.get(last) > swingHighs15m.get(last - 1);
    }

    /**
     * Check if 15m is making higher lows.
     */
    private boolean isHigherLows() {
        if (swingLows15m.size() < 2) return false;
        int last = swingLows15m.size() - 1;
        return swingLows15m.get(last) > swingLows15m.get(last - 1);
    }

    /**
     * Check if 15m is making lower highs.
     */
    private boolean isLowerHighs() {
        if (swingHighs15m.size() < 2) return false;
        int last = swingHighs15m.size() - 1;
        return swingHighs15m.get(last) < swingHighs15m.get(last - 1);
    }

    /**
     * Check if 15m is making lower lows.
     */
    private boolean isLowerLows() {
        if (swingLows15m.size() < 2) return false;
        int last = swingLows15m.size() - 1;
        return swingLows15m.get(last) < swingLows15m.get(last - 1);
    }

    /**
     * Check if momentum is fading (candle bodies getting smaller).
     * Compares average body size of recent candles vs earlier candles.
     */
    private boolean isMomentumFading() {
        if (candleBodies15m.size() < 4) return false;

        int mid = candleBodies15m.size() / 2;
        double recentAvg = 0, olderAvg = 0;
        int recentCount = 0, olderCount = 0;

        for (int i = 0; i < candleBodies15m.size(); i++) {
            if (i < mid) {
                olderAvg += candleBodies15m.get(i);
                olderCount++;
            } else {
                recentAvg += candleBodies15m.get(i);
                recentCount++;
            }
        }

        recentAvg = recentCount > 0 ? recentAvg / recentCount : 0;
        olderAvg = olderCount > 0 ? olderAvg / olderCount : 1;

        // Momentum fading if recent bodies < 60% of older bodies
        return olderAvg > 0 && (recentAvg / olderAvg) < 0.60;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get the current HTF trend state.
     * This is the PRIMARY gate — check this FIRST before evaluating any setup.
     */
    public HtfTrendState getTrendState() {
        return currentState;
    }

    /**
     * Check if a direction is allowed by the current HTF trend.
     */
    public boolean allowsDirection(boolean bullish) {
        return currentState.allowsDirection(bullish);
    }

    /**
     * Check if the HTF trend is strong in the given direction.
     */
    public boolean isStrongTrend(boolean bullish) {
        return (bullish && currentState == HtfTrendState.STRONG_BULLISH) ||
               (!bullish && currentState == HtfTrendState.STRONG_BEARISH);
    }

    /**
     * Check if the HTF is trending (not ranging).
     */
    public boolean isTrending() {
        return currentState.isTrending();
    }

    /**
     * Get the size multiplier based on trend strength.
     * STRONG = 1.0, WEAK = 0.5, RANGING = 0.0
     */
    public double getSizeMultiplier() {
        return currentState.getSizeMultiplier();
    }

    /**
     * Get the premium/discount zone.
     * Returns true if price is in discount (below 50% of HTF range) for bullish,
     * or in premium (above 50% of HTF range) for bearish.
     */
    public boolean isInFavorableZone(double price, boolean bullish) {
        Double swingHigh = structure15m.getLastSwingHigh();
        Double swingLow = structure15m.getLastSwingLow();

        if (swingHigh == null || swingLow == null) return true; // No data, don't filter

        double range = swingHigh - swingLow;
        if (range <= 0) return true;

        double equilibrium = swingLow + (range * 0.5);

        if (bullish) {
            // For longs, price should be in discount (below equilibrium)
            return price <= equilibrium;
        } else {
            // For shorts, price should be in premium (above equilibrium)
            return price >= equilibrium;
        }
    }

    /**
     * Get the 15m bias directly.
     */
    public MarketBias getBias15m() {
        return structure15m.getBias();
    }

    /**
     * Get the 30m bias directly.
     */
    public MarketBias getBias30m() {
        return structure30m.getBias();
    }

    /**
     * Get time since last state change.
     */
    public Instant getLastStateChangeTime() {
        return lastStateChangeTime;
    }

    /**
     * Get summary for logging.
     */
    public String getSummary() {
        return String.format("HTF[%s] 15m=%s 30m=%s HH=%s HL=%s LH=%s LL=%s Fading=%s",
                currentState.getDisplayName(),
                structure15m.getBias(), structure30m.getBias(),
                isHigherHighs(), isHigherLows(), isLowerHighs(), isLowerLows(),
                isMomentumFading());
    }
}
