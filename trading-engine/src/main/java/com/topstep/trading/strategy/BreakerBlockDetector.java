package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Detects Breaker Blocks - failed Order Blocks that flip to the opposite S/R.
 *
 * A Breaker Block forms when:
 * 1. An Order Block is established
 * 2. Price returns to test the OB
 * 3. Price breaks through the OB (the OB fails)
 * 4. The broken OB now acts as the OPPOSITE type of S/R
 *
 * Bullish OB breaks down -> Bearish Breaker (now resistance)
 * Bearish OB breaks up -> Bullish Breaker (now support)
 *
 * Breaker Blocks are HIGHER PROBABILITY because:
 * - They represent trapped traders
 * - Institutions use them for repositioning
 * - Clear invalidation levels
 */
public class BreakerBlockDetector {

    private final List<BreakerBlock> breakerBlocks;
    private final OrderBlockDetector orderBlockDetector;
    private final int maxBreakerBlocks;

    public BreakerBlockDetector(OrderBlockDetector orderBlockDetector, int maxBreakerBlocks) {
        this.orderBlockDetector = orderBlockDetector;
        this.maxBreakerBlocks = maxBreakerBlocks;
        this.breakerBlocks = new ArrayList<>();
    }

    /**
     * Update with a new candle - check for OB failures and breaker formations.
     */
    public void update(Candle candle) {
        // Check for Order Block failures (which become Breaker Blocks)
        checkForBreakerFormation(candle);

        // Update existing breaker blocks
        updateBreakerBlocks(candle);
    }

    /**
     * Check if any Order Blocks have failed and should become Breaker Blocks.
     * CRITICAL FIX: Use getBreachedOrderBlocks() instead of getValidOrderBlocks()
     * because breached OBs are what become breaker blocks!
     */
    private void checkForBreakerFormation(Candle candle) {
        // Get OBs that were just breached (after being mitigated)
        // These are candidates for becoming breaker blocks
        List<OrderBlock> breachedObs = orderBlockDetector.getBreachedOrderBlocks();

        for (OrderBlock ob : breachedObs) {
            // A breaker forms when a mitigated OB gets breached
            // The breaker is the OPPOSITE direction of the original OB

            if (ob.isBullish()) {
                // Bullish OB was breached -> becomes BEARISH breaker (resistance)
                createBreakerBlock(ob, false, candle);
            } else {
                // Bearish OB was breached -> becomes BULLISH breaker (support)
                createBreakerBlock(ob, true, candle);
            }
        }
    }

    /**
     * Create a new Breaker Block from a failed Order Block.
     */
    private void createBreakerBlock(OrderBlock failedOb, boolean bullishBreaker, Candle candle) {
        // Check if we already have a breaker at this level
        if (breakerExistsAtLevel(failedOb.getLow(), failedOb.getHigh())) {
            return;
        }

        BreakerBlock breaker = new BreakerBlock(
            bullishBreaker,
            failedOb.getHigh(),
            failedOb.getLow(),
            candle.getTimestamp(),
            failedOb
        );

        breakerBlocks.add(breaker);
        System.out.println("[BREAKER_BLOCK] " + (bullishBreaker ? "BULLISH" : "BEARISH") +
                          " Breaker formed from failed " + (failedOb.isBullish() ? "BULLISH" : "BEARISH") +
                          " OB: " + breaker);

        // Keep list within max size
        while (breakerBlocks.size() > maxBreakerBlocks) {
            breakerBlocks.remove(0);
        }
    }

    /**
     * Update existing breaker blocks based on current price.
     */
    private void updateBreakerBlocks(Candle candle) {
        for (BreakerBlock breaker : breakerBlocks) {
            if (breaker.isInvalidated()) {
                continue;
            }

            // Check for mitigation (price returns to breaker zone)
            if (!breaker.isMitigated()) {
                if (breaker.containsPrice(candle.getClose()) ||
                    breaker.containsPrice(candle.getLow()) ||
                    breaker.containsPrice(candle.getHigh())) {
                    breaker.setMitigated(true);
                    System.out.println("[BREAKER_BLOCK] Breaker mitigated: " + breaker);
                }
            }

            // Check for invalidation (price breaks through the breaker in wrong direction)
            if (breaker.isBullish()) {
                // Bullish breaker invalidated if price closes below its low
                if (candle.getClose() < breaker.getLow()) {
                    breaker.setInvalidated(true);
                    System.out.println("[BREAKER_BLOCK] BULLISH Breaker invalidated: " + breaker);
                }
            } else {
                // Bearish breaker invalidated if price closes above its high
                if (candle.getClose() > breaker.getHigh()) {
                    breaker.setInvalidated(true);
                    System.out.println("[BREAKER_BLOCK] BEARISH Breaker invalidated: " + breaker);
                }
            }
        }
    }

    /**
     * Check if a breaker already exists at this price level.
     */
    private boolean breakerExistsAtLevel(double low, double high) {
        return breakerBlocks.stream()
                .anyMatch(b -> Math.abs(b.getLow() - low) < 2.0 &&
                              Math.abs(b.getHigh() - high) < 2.0);
    }

    /**
     * Find the nearest valid breaker block to current price.
     */
    public BreakerBlock findNearestBreaker(double price, boolean bullish, double maxDistance) {
        return breakerBlocks.stream()
                .filter(b -> b.isBullish() == bullish)
                .filter(BreakerBlock::isValid)
                .filter(b -> Math.abs(b.getMidpoint() - price) <= maxDistance)
                .min((a, b) -> {
                    double distA = Math.abs(a.getMidpoint() - price);
                    double distB = Math.abs(b.getMidpoint() - price);
                    return Double.compare(distA, distB);
                })
                .orElse(null);
    }

    /**
     * Find a breaker block that price is currently at.
     */
    public BreakerBlock findBreakerAtPrice(double price, boolean bullish) {
        return breakerBlocks.stream()
                .filter(b -> b.isBullish() == bullish)
                .filter(BreakerBlock::isValid)
                .filter(b -> b.containsPrice(price))
                .findFirst()
                .orElse(null);
    }

    /**
     * Check if there are any valid breaker blocks.
     */
    public boolean hasValidBreaker(boolean bullish) {
        return breakerBlocks.stream()
                .anyMatch(b -> b.isBullish() == bullish && b.isValid());
    }

    /**
     * Get all valid breaker blocks.
     */
    public List<BreakerBlock> getValidBreakerBlocks() {
        return breakerBlocks.stream()
                .filter(BreakerBlock::isValid)
                .collect(Collectors.toList());
    }

    /**
     * Register a zone flip as a synthetic breaker block (FIX 3).
     * When a demand/supply level is broken by displacement, it becomes a breaker.
     *
     * @param level The level that was flipped
     * @param isBullish true if the breaker is now bullish (broken upward)
     */
    public void registerZoneFlipBreaker(com.topstep.trading.chartstate.KnownLevel level, boolean isBullish) {
        double tickSize = 0.25; // Default for NQ/ES
        double zoneSize = tickSize * 10; // Create a zone around the level

        double high = level.getPrice() + zoneSize / 2;
        double low = level.getPrice() - zoneSize / 2;

        if (breakerExistsAtLevel(low, high)) {
            return;
        }

        // Create a synthetic OrderBlock for the breaker
        OrderBlock syntheticOb = new OrderBlock(!isBullish, high, low,
                level.getFlipTimestamp() != null ? level.getFlipTimestamp() : java.time.Instant.now());

        BreakerBlock breaker = new BreakerBlock(
                isBullish, high, low,
                level.getFlipTimestamp() != null ? level.getFlipTimestamp() : java.time.Instant.now(),
                syntheticOb
        );

        breakerBlocks.add(breaker);
        System.out.println("[BREAKER_BLOCK] ZONE FLIP BREAKER: " + (isBullish ? "BULLISH" : "BEARISH") +
                " from " + level.getType().getDisplayName() + " @ " + String.format("%.2f", level.getPrice()));

        while (breakerBlocks.size() > maxBreakerBlocks) {
            breakerBlocks.remove(0);
        }
    }

    /**
     * Reset the detector.
     */
    public void reset() {
        breakerBlocks.clear();
    }
}
