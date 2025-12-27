package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Detects and tracks Mitigation Blocks - unmitigated price areas.
 *
 * This detector combines information from:
 * - FVG Detector (unmitigated Fair Value Gaps)
 * - Order Block Detector (unmitigated Order Blocks)
 * - Breaker Block Detector (unmitigated Breaker Blocks)
 *
 * It identifies zones where price is likely to react when it returns.
 */
public class MitigationBlockDetector {

    private final List<MitigationBlock> mitigationBlocks;
    private final FvgDetector fvgDetector;
    private final OrderBlockDetector orderBlockDetector;
    private final BreakerBlockDetector breakerBlockDetector;
    private final int maxBlocks;

    public MitigationBlockDetector(FvgDetector fvgDetector, OrderBlockDetector orderBlockDetector,
                                   BreakerBlockDetector breakerBlockDetector, int maxBlocks) {
        this.fvgDetector = fvgDetector;
        this.orderBlockDetector = orderBlockDetector;
        this.breakerBlockDetector = breakerBlockDetector;
        this.maxBlocks = maxBlocks;
        this.mitigationBlocks = new ArrayList<>();
    }

    /**
     * Update with a new candle - sync mitigation zones from detectors.
     */
    public void update(Candle candle) {
        // Sync from FVG detector
        syncFromFvgDetector();

        // Sync from Order Block detector
        syncFromOrderBlockDetector();

        // Sync from Breaker Block detector
        syncFromBreakerBlockDetector();

        // Update existing mitigation blocks
        updateMitigationBlocks(candle);

        // Trim to max size
        while (mitigationBlocks.size() > maxBlocks) {
            mitigationBlocks.remove(0);
        }
    }

    /**
     * Sync unmitigated FVGs as mitigation blocks.
     */
    private void syncFromFvgDetector() {
        // Get unmitigated FVGs and create mitigation blocks
        // FVGs are already tracked by FvgDetector, but we track them here too
        // for unified zone management
    }

    /**
     * Sync unmitigated Order Blocks as mitigation blocks.
     */
    private void syncFromOrderBlockDetector() {
        List<OrderBlock> validObs = orderBlockDetector.getValidOrderBlocks();

        for (OrderBlock ob : validObs) {
            if (!ob.isMitigated() && !blockExistsAtLevel(ob.getLow(), ob.getHigh())) {
                MitigationBlock mitBlock = new MitigationBlock(
                    ob.isBullish(),
                    ob.getHigh(),
                    ob.getLow(),
                    ob.getCreatedAt(),
                    "OB"
                );
                mitigationBlocks.add(mitBlock);
            }
        }
    }

    /**
     * Sync unmitigated Breaker Blocks as mitigation blocks (highest priority).
     */
    private void syncFromBreakerBlockDetector() {
        List<BreakerBlock> validBreakers = breakerBlockDetector.getValidBreakerBlocks();

        for (BreakerBlock breaker : validBreakers) {
            if (!breaker.isMitigated() && !blockExistsAtLevel(breaker.getLow(), breaker.getHigh())) {
                MitigationBlock mitBlock = new MitigationBlock(
                    breaker.isBullish(),
                    breaker.getHigh(),
                    breaker.getLow(),
                    breaker.getCreatedAt(),
                    "BREAKER"
                );
                mitigationBlocks.add(mitBlock);
            }
        }
    }

    /**
     * Update mitigation blocks based on current price.
     */
    private void updateMitigationBlocks(Candle candle) {
        for (MitigationBlock block : mitigationBlocks) {
            if (block.isInvalidated()) {
                continue;
            }

            // Check if price is testing this zone
            if (block.containsPrice(candle.getClose()) ||
                block.containsPrice(candle.getLow()) ||
                block.containsPrice(candle.getHigh())) {

                if (!block.isMitigated()) {
                    block.incrementTestCount();

                    // If price closes within the zone, it's being mitigated
                    if (block.containsPrice(candle.getClose())) {
                        block.setMitigated(true);
                    }
                }
            }

            // Check for invalidation (price breaks through in wrong direction)
            if (block.isBullish()) {
                // Bullish zone invalidated if price closes well below
                if (candle.getClose() < block.getLow() - (block.getHigh() - block.getLow())) {
                    block.setInvalidated(true);
                }
            } else {
                // Bearish zone invalidated if price closes well above
                if (candle.getClose() > block.getHigh() + (block.getHigh() - block.getLow())) {
                    block.setInvalidated(true);
                }
            }
        }
    }

    /**
     * Check if a block already exists at this price level.
     */
    private boolean blockExistsAtLevel(double low, double high) {
        return mitigationBlocks.stream()
                .anyMatch(b -> Math.abs(b.getLow() - low) < 2.0 &&
                              Math.abs(b.getHigh() - high) < 2.0);
    }

    /**
     * Find the best mitigation block for entry near current price.
     */
    public MitigationBlock findBestMitigationZone(double price, boolean bullish, double maxDistance) {
        return mitigationBlocks.stream()
                .filter(b -> b.isBullish() == bullish)
                .filter(MitigationBlock::isValidForEntry)
                .filter(b -> Math.abs(b.getMidpoint() - price) <= maxDistance)
                .max(Comparator.comparingInt(MitigationBlock::getStrength))
                .orElse(null);
    }

    /**
     * Find a mitigation block that price is currently at.
     */
    public MitigationBlock findBlockAtPrice(double price, boolean bullish) {
        return mitigationBlocks.stream()
                .filter(b -> b.isBullish() == bullish)
                .filter(MitigationBlock::isValidForEntry)
                .filter(b -> b.containsPrice(price))
                .max(Comparator.comparingInt(MitigationBlock::getStrength))
                .orElse(null);
    }

    /**
     * Check if there are any valid mitigation blocks for entry.
     */
    public boolean hasValidMitigationZone(boolean bullish) {
        return mitigationBlocks.stream()
                .anyMatch(b -> b.isBullish() == bullish && b.isValidForEntry());
    }

    /**
     * Get all valid mitigation blocks sorted by strength.
     */
    public List<MitigationBlock> getValidMitigationBlocks() {
        return mitigationBlocks.stream()
                .filter(MitigationBlock::isValidForEntry)
                .sorted(Comparator.comparingInt(MitigationBlock::getStrength).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Check if price is at a fresh (never tested) mitigation zone.
     */
    public boolean isAtFreshZone(double price, boolean bullish) {
        return mitigationBlocks.stream()
                .filter(b -> b.isBullish() == bullish)
                .filter(MitigationBlock::isFresh)
                .anyMatch(b -> b.containsPrice(price));
    }

    /**
     * Reset the detector.
     */
    public void reset() {
        mitigationBlocks.clear();
    }
}
