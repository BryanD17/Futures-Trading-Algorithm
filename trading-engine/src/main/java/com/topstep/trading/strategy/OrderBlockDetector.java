package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Detects Order Blocks (OBs) - a key ICT concept.
 *
 * Order Blocks are the last candle of the opposite color before a significant
 * price move (displacement). They represent areas where institutional orders
 * were placed and often act as support/resistance when price returns.
 *
 * - Bullish OB: Last bearish (red) candle before a strong bullish move
 * - Bearish OB: Last bullish (green) candle before a strong bearish move
 */
public class OrderBlockDetector {

    private final List<Candle> candles;
    private final List<OrderBlock> orderBlocks;
    private final int lookbackPeriod;
    private final int maxOrderBlocks;
    private final double displacementMultiplier;  // Min move size relative to ATR

    public OrderBlockDetector(int lookbackPeriod, int maxOrderBlocks) {
        this.lookbackPeriod = lookbackPeriod;
        this.maxOrderBlocks = maxOrderBlocks;
        this.displacementMultiplier = 1.5;  // Require 1.5x ATR move for displacement
        this.candles = new ArrayList<>();
        this.orderBlocks = new ArrayList<>();
    }

    /**
     * Update with a new candle.
     */
    public void update(Candle candle) {
        candles.add(candle);

        // Keep only lookback period
        if (candles.size() > lookbackPeriod) {
            candles.remove(0);
        }

        // Need at least 5 candles to detect OBs
        if (candles.size() >= 5) {
            detectOrderBlocks();
        }

        // Update existing order blocks
        updateOrderBlocks(candle);
    }

    /**
     * Detect new order blocks from recent price action.
     */
    private void detectOrderBlocks() {
        int size = candles.size();

        // Calculate average range (simple ATR approximation)
        double avgRange = candles.stream()
                .skip(Math.max(0, size - 14))
                .mapToDouble(c -> c.getHigh() - c.getLow())
                .average()
                .orElse(10.0);

        double minDisplacement = avgRange * displacementMultiplier;

        // Look for displacement followed by order block
        // Check the last few candles for a valid OB pattern
        for (int i = size - 4; i >= Math.max(0, size - 10); i--) {
            Candle potentialOb = candles.get(i);
            Candle nextCandle = candles.get(i + 1);

            // Bullish OB: Bearish candle followed by strong bullish candle
            if (isBearishCandle(potentialOb) && isBullishCandle(nextCandle)) {
                double move = nextCandle.getClose() - potentialOb.getLow();
                if (move >= minDisplacement) {
                    // Check if this OB already exists
                    if (!orderBlockExists(potentialOb.getLow(), potentialOb.getHigh(), true)) {
                        OrderBlock ob = new OrderBlock(true, potentialOb.getHigh(), potentialOb.getLow(),
                                potentialOb.getTimestamp());
                        addOrderBlock(ob);
                        System.out.println("[ORDER_BLOCK] BULLISH OB detected: " + ob);
                    }
                }
            }

            // Bearish OB: Bullish candle followed by strong bearish candle
            if (isBullishCandle(potentialOb) && isBearishCandle(nextCandle)) {
                double move = potentialOb.getHigh() - nextCandle.getClose();
                if (move >= minDisplacement) {
                    // Check if this OB already exists
                    if (!orderBlockExists(potentialOb.getLow(), potentialOb.getHigh(), false)) {
                        OrderBlock ob = new OrderBlock(false, potentialOb.getHigh(), potentialOb.getLow(),
                                potentialOb.getTimestamp());
                        addOrderBlock(ob);
                        System.out.println("[ORDER_BLOCK] BEARISH OB detected: " + ob);
                    }
                }
            }
        }
    }

    /**
     * Update existing order blocks based on current price.
     */
    private void updateOrderBlocks(Candle candle) {
        for (OrderBlock ob : orderBlocks) {
            if (ob.isBreached()) {
                continue;
            }

            // Check for mitigation (price enters OB zone)
            if (!ob.isMitigated()) {
                if (ob.isBullish() && candle.getLow() <= ob.getHigh()) {
                    ob.setMitigated(true);
                } else if (!ob.isBullish() && candle.getHigh() >= ob.getLow()) {
                    ob.setMitigated(true);
                }
            }

            // Check for breach (price closes through OB, invalidating it)
            if (ob.isBullish()) {
                if (candle.getClose() < ob.getLow()) {
                    ob.setBreached(true);
                    System.out.println("[ORDER_BLOCK] BULLISH OB breached (invalidated): " + ob);
                }
            } else {
                if (candle.getClose() > ob.getHigh()) {
                    ob.setBreached(true);
                    System.out.println("[ORDER_BLOCK] BEARISH OB breached (invalidated): " + ob);
                }
            }
        }
    }

    /**
     * Check if a similar order block already exists.
     */
    private boolean orderBlockExists(double low, double high, boolean bullish) {
        return orderBlocks.stream()
                .anyMatch(ob -> ob.isBullish() == bullish &&
                        Math.abs(ob.getLow() - low) < 2.0 &&
                        Math.abs(ob.getHigh() - high) < 2.0);
    }

    /**
     * Add a new order block.
     */
    private void addOrderBlock(OrderBlock ob) {
        orderBlocks.add(ob);
        if (orderBlocks.size() > maxOrderBlocks) {
            orderBlocks.remove(0);
        }
    }

    /**
     * Check if candle is bullish (close > open).
     */
    private boolean isBullishCandle(Candle candle) {
        return candle.getClose() > candle.getOpen();
    }

    /**
     * Check if candle is bearish (close < open).
     */
    private boolean isBearishCandle(Candle candle) {
        return candle.getClose() < candle.getOpen();
    }

    /**
     * Find the nearest valid order block to current price.
     */
    public OrderBlock findNearestValidOb(double price, boolean bullish, double maxDistance) {
        return orderBlocks.stream()
                .filter(ob -> ob.isBullish() == bullish)
                .filter(OrderBlock::isValid)
                .filter(ob -> Math.abs(ob.getMidpoint() - price) <= maxDistance)
                .min((a, b) -> {
                    double distA = Math.abs(a.getMidpoint() - price);
                    double distB = Math.abs(b.getMidpoint() - price);
                    return Double.compare(distA, distB);
                })
                .orElse(null);
    }

    /**
     * Find an order block that price is currently at (for entry).
     */
    public OrderBlock findObAtPrice(double price, boolean bullish) {
        return orderBlocks.stream()
                .filter(ob -> ob.isBullish() == bullish)
                .filter(OrderBlock::isValid)
                .filter(ob -> ob.containsPrice(price))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get all valid order blocks.
     */
    public List<OrderBlock> getValidOrderBlocks() {
        return orderBlocks.stream()
                .filter(OrderBlock::isValid)
                .collect(Collectors.toList());
    }

    /**
     * Check if there are any valid order blocks in the given direction.
     */
    public boolean hasValidOb(boolean bullish) {
        return orderBlocks.stream()
                .anyMatch(ob -> ob.isBullish() == bullish && ob.isValid());
    }

    /**
     * Get all order blocks including breached ones (for breaker block detection).
     * CRITICAL: BreakerBlockDetector needs to see breached OBs to convert them to breakers.
     */
    public List<OrderBlock> getAllOrderBlocks() {
        return new ArrayList<>(orderBlocks);
    }

    /**
     * Get breached order blocks that can become breaker blocks.
     */
    public List<OrderBlock> getBreachedOrderBlocks() {
        return orderBlocks.stream()
                .filter(OrderBlock::isBreached)
                .filter(OrderBlock::isMitigated)  // Must have been mitigated before breach
                .collect(Collectors.toList());
    }

    /**
     * Reset the detector.
     */
    public void reset() {
        candles.clear();
        orderBlocks.clear();
    }
}
