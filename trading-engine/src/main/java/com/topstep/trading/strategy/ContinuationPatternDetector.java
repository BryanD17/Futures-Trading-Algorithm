package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.BarAggregationManager.Timeframe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Continuation Pattern Detector - Layer 2 of the 3-layer cascade.
 *
 * When the HTF trend (Layer 1) is confirmed, this component monitors the 5m
 * timeframe for pullback-and-continuation patterns — the "mini moves to retest
 * previous zones for confirmation before continuing to follow the HTF trend."
 *
 * The ideal 5m continuation sequence in a bullish HTF trend:
 *   1. Impulsive bullish leg on 5m (creates FVG, leaves OB behind)
 *   2. Corrective pullback begins — price retraces toward FVG/OB zone
 *   3. During pullback, price may briefly break a minor 5m swing (CHoCH/liquidity grab)
 *   4. Price enters a confluent zone: FVG + OB + OTE fib (0.618-0.786)
 *   5. Price shows signs of holding (closes back above zone, bullish candle)
 *   6. This is the "confirmation" — the 5m has pulled back, retested, ready to continue
 *
 * Zone Confluence Scoring:
 *   Each structure present at the pullback zone adds to the score (1-5):
 *     - FVG present:          +1
 *     - Order Block present:  +1
 *     - Breaker Block:        +1
 *     - OTE fib zone:         +1
 *     - IFVG present:         +1
 *   Score 1 = decent, Score 3+ = high probability, Score 5 = elite
 *
 * Emits ContinuationZoneEvent when a valid pullback zone is identified.
 */
public class ContinuationPatternDetector {

    /**
     * Represents an active continuation zone where 5m has pulled back to.
     */
    public static class ContinuationZone {
        private final double zoneHigh;
        private final double zoneLow;
        private final int confluenceScore;
        private final boolean bullish;
        private final List<String> confluenceFactors;
        private final Instant detectedAt;
        private final double pullbackDepth;  // Percentage of prior impulse retraced
        private boolean activated;           // Price has entered the zone
        private boolean invalidated;         // Price closed through the zone

        public ContinuationZone(double zoneHigh, double zoneLow, int confluenceScore,
                                boolean bullish, List<String> confluenceFactors,
                                Instant detectedAt, double pullbackDepth) {
            this.zoneHigh = zoneHigh;
            this.zoneLow = zoneLow;
            this.confluenceScore = confluenceScore;
            this.bullish = bullish;
            this.confluenceFactors = confluenceFactors;
            this.detectedAt = detectedAt;
            this.pullbackDepth = pullbackDepth;
            this.activated = false;
            this.invalidated = false;
        }

        public double getZoneHigh() { return zoneHigh; }
        public double getZoneLow() { return zoneLow; }
        public double getZoneMid() { return (zoneHigh + zoneLow) / 2.0; }
        public int getConfluenceScore() { return confluenceScore; }
        public boolean isBullish() { return bullish; }
        public List<String> getConfluenceFactors() { return confluenceFactors; }
        public Instant getDetectedAt() { return detectedAt; }
        public double getPullbackDepth() { return pullbackDepth; }
        public boolean isActivated() { return activated; }
        public boolean isInvalidated() { return invalidated; }

        public void activate() { this.activated = true; }
        public void invalidate() { this.invalidated = true; }

        public boolean isValid() { return !invalidated; }

        /**
         * Check if pullback depth is in the ideal range (50-78.6%).
         */
        public boolean isIdealPullback() {
            return pullbackDepth >= 0.50 && pullbackDepth <= 0.786;
        }

        /**
         * Check if pullback is in the OTE zone (61.8-78.6%).
         */
        public boolean isInOteZone() {
            return pullbackDepth >= 0.618 && pullbackDepth <= 0.786;
        }

        /**
         * Check if price is inside this zone.
         */
        public boolean containsPrice(double price) {
            return price >= zoneLow && price <= zoneHigh;
        }

        @Override
        public String toString() {
            return String.format("ContinuationZone[%s, %.2f-%.2f, score=%d, depth=%.1f%%, factors=%s]",
                    bullish ? "BULL" : "BEAR", zoneLow, zoneHigh, confluenceScore,
                    pullbackDepth * 100, confluenceFactors);
        }
    }

    private final String symbol;
    private final BarAggregationManager barManager;

    // 5m structure detectors
    private final FvgDetector fvgDetector5m;
    private final OrderBlockDetector obDetector5m;
    private final BreakerBlockDetector breakerDetector5m;
    private final IctStructureDetector structureDetector5m;
    private final DisplacementDetector displacementDetector5m;

    // State tracking
    private final List<ContinuationZone> activeZones = new ArrayList<>();
    private static final int MAX_ACTIVE_ZONES = 3;
    private static final double MAX_ZONE_DISTANCE = 100.0;

    // 5m impulse tracking
    private double lastImpulseHigh5m = Double.MIN_VALUE;
    private double lastImpulseLow5m = Double.MAX_VALUE;
    private boolean hadRecentImpulse = false;
    private int candlesSinceImpulse = 0;
    private static final int IMPULSE_EXPIRY_CANDLES = 20;  // ~100 minutes on 5m

    // OTE Fibonacci levels
    private static final double FIB_50 = 0.50;
    private static final double FIB_618 = 0.618;
    private static final double FIB_786 = 0.786;

    public ContinuationPatternDetector(String symbol, BarAggregationManager barManager) {
        this.symbol = symbol;
        this.barManager = barManager;

        this.fvgDetector5m = new FvgDetector(20);
        this.obDetector5m = new OrderBlockDetector(30, 10);
        this.breakerDetector5m = new BreakerBlockDetector(obDetector5m, 10);
        this.structureDetector5m = new IctStructureDetector(50);
        this.displacementDetector5m = new DisplacementDetector(20);
    }

    /**
     * Update with newly completed candles.
     * Only processes 5m candles for continuation pattern detection.
     */
    public void update(Map<Timeframe, Candle> completedCandles) {
        Candle candle5m = completedCandles.get(Timeframe.M5);
        if (candle5m == null) return;

        // Update 5m detectors
        fvgDetector5m.update(candle5m);
        obDetector5m.update(candle5m);
        breakerDetector5m.update(candle5m);
        structureDetector5m.update(candle5m);
        displacementDetector5m.update(candle5m);

        // Track impulse moves
        trackImpulse(candle5m);

        // Update active zone states
        updateActiveZones(candle5m);

        // Detect new continuation zones
        detectContinuationZone(candle5m);
    }

    /**
     * Track impulsive moves on 5m to identify the leg that will be retraced.
     */
    private void trackImpulse(Candle candle5m) {
        if (hadRecentImpulse) {
            candlesSinceImpulse++;
            if (candlesSinceImpulse > IMPULSE_EXPIRY_CANDLES) {
                hadRecentImpulse = false;
            }
        }

        // Check for 5m displacement (impulsive move)
        if (displacementDetector5m.hasRecentDisplacement(2)) {
            DisplacementDetector.Displacement disp = displacementDetector5m.getLastDisplacement();
            if (disp != null) {
                hadRecentImpulse = true;
                candlesSinceImpulse = 0;

                if (disp.isBullish()) {
                    lastImpulseHigh5m = candle5m.getHigh();
                    // Track low before impulse
                    Double swingLow = structureDetector5m.getLastSwingLow();
                    if (swingLow != null) {
                        lastImpulseLow5m = swingLow;
                    }
                } else {
                    lastImpulseLow5m = candle5m.getLow();
                    Double swingHigh = structureDetector5m.getLastSwingHigh();
                    if (swingHigh != null) {
                        lastImpulseHigh5m = swingHigh;
                    }
                }
            }
        }
    }

    /**
     * Update states of active zones — check if price entered or invalidated them.
     */
    private void updateActiveZones(Candle candle5m) {
        activeZones.removeIf(zone -> {
            if (zone.isInvalidated()) return true;

            // Check if price entered the zone
            if (zone.containsPrice(candle5m.getClose()) || zone.containsPrice(candle5m.getLow()) ||
                    zone.containsPrice(candle5m.getHigh())) {
                zone.activate();
            }

            // Invalidation: price closes THROUGH the zone entirely
            if (zone.isBullish() && candle5m.getClose() < zone.getZoneLow()) {
                zone.invalidate();
                return true;
            }
            if (!zone.isBullish() && candle5m.getClose() > zone.getZoneHigh()) {
                zone.invalidate();
                return true;
            }

            return false;
        });
    }

    /**
     * Detect a new continuation zone on the 5m chart.
     * A continuation zone forms when 5m pulls back after an impulse move
     * and lands on confluent structures (FVG + OB + fib level).
     */
    private void detectContinuationZone(Candle candle5m) {
        if (!hadRecentImpulse) return;  // No impulse to pull back from
        if (activeZones.size() >= MAX_ACTIVE_ZONES) return;

        MarketBias bias5m = structureDetector5m.getBias();
        double close = candle5m.getClose();

        // We need a pullback — price moving against the prior impulse
        // In a bullish context: prior impulse was up, now price is pulling back down
        boolean bullishContext = lastImpulseHigh5m > lastImpulseLow5m &&
                lastImpulseHigh5m != Double.MIN_VALUE && lastImpulseLow5m != Double.MAX_VALUE;

        if (!bullishContext && lastImpulseHigh5m == Double.MIN_VALUE) return;

        double impulseRange = lastImpulseHigh5m - lastImpulseLow5m;
        if (impulseRange <= 0) return;

        // Calculate pullback depth
        double pullbackDepth;
        boolean isBullishContinuation;

        if (bias5m == MarketBias.BULLISH || close > lastImpulseLow5m + impulseRange * 0.5) {
            // Bullish continuation — price pulled back from the high
            pullbackDepth = (lastImpulseHigh5m - close) / impulseRange;
            isBullishContinuation = true;
        } else {
            // Bearish continuation — price pulled back from the low
            pullbackDepth = (close - lastImpulseLow5m) / impulseRange;
            isBullishContinuation = false;
        }

        // Only consider pullbacks in the ideal range (50-78.6%)
        if (pullbackDepth < FIB_50 || pullbackDepth > FIB_786) return;

        // Score zone confluence
        List<String> factors = new ArrayList<>();
        int confluenceScore = 0;
        double zoneHigh = close;
        double zoneLow = close;

        // Check for FVG at this price
        FairValueGap fvg5m = fvgDetector5m.findNearestFvg(close, isBullishContinuation, MAX_ZONE_DISTANCE);
        if (fvg5m != null && isNearPrice(fvg5m.getMidpoint(), close, MAX_ZONE_DISTANCE * 0.3)) {
            confluenceScore++;
            factors.add("5m_FVG");
            zoneHigh = Math.max(zoneHigh, fvg5m.getTop());
            zoneLow = Math.min(zoneLow, fvg5m.getBottom());
        }

        // Check for Order Block at this price
        OrderBlock ob5m = obDetector5m.findNearestValidOb(close, isBullishContinuation, MAX_ZONE_DISTANCE);
        if (ob5m != null && isNearPrice(ob5m.getMidpoint(), close, MAX_ZONE_DISTANCE * 0.3)) {
            confluenceScore++;
            factors.add("5m_OB");
            zoneHigh = Math.max(zoneHigh, ob5m.getHigh());
            zoneLow = Math.min(zoneLow, ob5m.getLow());
        }

        // Check for Breaker Block at this price
        BreakerBlock breaker5m = breakerDetector5m.findNearestBreaker(close, isBullishContinuation, MAX_ZONE_DISTANCE);
        if (breaker5m != null && isNearPrice(breaker5m.getOptimalEntry(), close, MAX_ZONE_DISTANCE * 0.3)) {
            confluenceScore++;
            factors.add("5m_Breaker");
            zoneHigh = Math.max(zoneHigh, breaker5m.getHigh());
            zoneLow = Math.min(zoneLow, breaker5m.getLow());
        }

        // Check for IFVG (Inverse FVG)
        FairValueGap ifvg5m = fvgDetector5m.findNearestIfvg(close, isBullishContinuation);
        if (ifvg5m != null && isNearPrice(ifvg5m.getMidpoint(), close, MAX_ZONE_DISTANCE * 0.3)) {
            confluenceScore++;
            factors.add("5m_IFVG");
        }

        // Check if in OTE fib zone (0.618-0.786 of prior impulse)
        if (pullbackDepth >= FIB_618 && pullbackDepth <= FIB_786) {
            confluenceScore++;
            factors.add("OTE_Zone");
        }

        // Only emit zone if we have at least 2 confluences
        if (confluenceScore >= 2) {
            // Expand zone slightly for tolerance
            double zonePadding = (zoneHigh - zoneLow) * 0.1;
            zoneHigh += zonePadding;
            zoneLow -= zonePadding;

            ContinuationZone zone = new ContinuationZone(
                    zoneHigh, zoneLow, confluenceScore, isBullishContinuation,
                    factors, candle5m.getTimestamp(), pullbackDepth
            );

            activeZones.add(zone);

            System.out.println("[" + symbol + "] CONTINUATION ZONE DETECTED: " + zone);
        }
    }

    /**
     * Check if two prices are near each other within tolerance.
     */
    private boolean isNearPrice(double price1, double price2, double tolerance) {
        return Math.abs(price1 - price2) <= tolerance;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get the best active continuation zone (highest confluence score).
     */
    public ContinuationZone getBestActiveZone() {
        return activeZones.stream()
                .filter(ContinuationZone::isValid)
                .max((a, b) -> {
                    int scoreCompare = Integer.compare(a.getConfluenceScore(), b.getConfluenceScore());
                    if (scoreCompare != 0) return scoreCompare;
                    // Prefer OTE zone entries
                    if (a.isInOteZone() && !b.isInOteZone()) return 1;
                    if (!a.isInOteZone() && b.isInOteZone()) return -1;
                    return 0;
                })
                .orElse(null);
    }

    /**
     * Get the best active zone in a specific direction.
     */
    public ContinuationZone getBestActiveZone(boolean bullish) {
        return activeZones.stream()
                .filter(z -> z.isValid() && z.isBullish() == bullish)
                .max((a, b) -> Integer.compare(a.getConfluenceScore(), b.getConfluenceScore()))
                .orElse(null);
    }

    /**
     * Check if there's an active continuation zone with price inside it.
     */
    public boolean hasActivatedZone(boolean bullish) {
        return activeZones.stream()
                .anyMatch(z -> z.isValid() && z.isActivated() && z.isBullish() == bullish);
    }

    /**
     * Check if a valid continuation zone exists (price may not have reached it yet).
     */
    public boolean hasValidZone(boolean bullish) {
        return activeZones.stream()
                .anyMatch(z -> z.isValid() && z.isBullish() == bullish);
    }

    /**
     * Get all active zones.
     */
    public List<ContinuationZone> getActiveZones() {
        return new ArrayList<>(activeZones);
    }

    /**
     * Get continuation zone confluence score for use in tier determination.
     * Returns the best zone's score, or 0 if no zone.
     */
    public int getZoneConfluenceScore(boolean bullish) {
        ContinuationZone best = getBestActiveZone(bullish);
        return best != null ? best.getConfluenceScore() : 0;
    }

    /**
     * Reset detector state (e.g., on session change).
     */
    public void reset() {
        activeZones.clear();
        hadRecentImpulse = false;
        candlesSinceImpulse = 0;
        lastImpulseHigh5m = Double.MIN_VALUE;
        lastImpulseLow5m = Double.MAX_VALUE;
    }
}
