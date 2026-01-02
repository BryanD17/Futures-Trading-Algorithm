package com.topstep.trading.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents an open position in a futures contract.
 */
public class Position {
    private final String symbol;
    private int quantity; // Positive for long, negative for short
    private double avgEntryPrice;
    private Instant openedAt;
    private Instant updatedAt;

    public Position(String symbol, int quantity, double avgEntryPrice) {
        this.symbol = Objects.requireNonNull(symbol, "symbol cannot be null");
        this.quantity = quantity;
        this.avgEntryPrice = avgEntryPrice;
        this.openedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Convenience constructor for creating a new position from an order side.
     * The position starts with zero quantity and will be updated with fills.
     */
    public Position(String symbol, OrderSide side) {
        this.symbol = Objects.requireNonNull(symbol, "symbol cannot be null");
        this.quantity = 0; // Will be updated with fills
        this.avgEntryPrice = 0.0;
        this.openedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getSymbol() { return symbol; }
    public int getQuantity() { return quantity; }
    public double getAvgEntryPrice() { return avgEntryPrice; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean isLong() {
        return quantity > 0;
    }

    public boolean isShort() {
        return quantity < 0;
    }

    public boolean isFlat() {
        return quantity == 0;
    }

    /**
     * Get the side of the position.
     * @return BUY for long positions, SELL for short positions
     * @throws IllegalStateException if position is flat (quantity == 0)
     */
    public OrderSide getSide() {
        if (quantity > 0) return OrderSide.BUY;
        if (quantity < 0) return OrderSide.SELL;
        throw new IllegalStateException("Cannot get side of flat position (quantity=0)");
    }

    /**
     * Get the side of the position, or null if flat.
     * Use this method when caller can handle flat positions.
     */
    public OrderSide getSideOrNull() {
        if (quantity > 0) return OrderSide.BUY;
        if (quantity < 0) return OrderSide.SELL;
        return null;
    }

    /**
     * Calculate unrealized PnL given current market price.
     * @param currentPrice Current market price
     * @param tickValue Dollar value per tick (e.g., $12.50 for ES)
     * @return Unrealized profit/loss
     */
    public double getUnrealizedPnL(double currentPrice, double tickValue) {
        if (isFlat()) return 0.0;
        // CRITICAL FIX: For LONG positions, profit when price rises (current - entry)
        // For SHORT positions, profit when price falls (entry - current)
        double priceDiff = isLong() ?
            (currentPrice - avgEntryPrice) :
            (avgEntryPrice - currentPrice);
        return priceDiff * Math.abs(quantity) * tickValue;
    }

    /**
     * Update position with a new fill.
     * @param fillQuantity Quantity filled (positive for buy, negative for sell)
     * @param fillPrice Fill price
     *
     * FIXED: Properly distinguishes between adding to position (update avg price)
     * vs reducing position (keep original avg price).
     */
    public void updateWithFill(int fillQuantity, double fillPrice) {
        int oldQuantity = this.quantity;
        int newQuantity = oldQuantity + fillQuantity;

        if (newQuantity == 0) {
            // Position fully closed - keep avgEntryPrice for PnL calculation
            this.quantity = 0;
        } else if (oldQuantity == 0) {
            // Opening a new position from flat
            this.quantity = newQuantity;
            this.avgEntryPrice = fillPrice;
            this.openedAt = Instant.now();
        } else if (Math.signum(oldQuantity) != Math.signum(newQuantity)) {
            // Direction flipped (e.g., long 10 -> short 5) - reset entry price to fill price
            // The remaining position is now a new position at the flip price
            this.quantity = newQuantity;
            this.avgEntryPrice = fillPrice;
            this.openedAt = Instant.now();
        } else if (Math.abs(newQuantity) > Math.abs(oldQuantity)) {
            // ADDING to position in the same direction - update weighted average entry price
            int absOldQty = Math.abs(oldQuantity);
            int absFillQty = Math.abs(fillQuantity);
            this.avgEntryPrice = ((this.avgEntryPrice * absOldQty) + (fillPrice * absFillQty)) /
                                 (absOldQty + absFillQty);
            this.quantity = newQuantity;
        } else {
            // REDUCING position in the same direction - keep original avg entry price
            // This is the CRITICAL fix: when reducing, we don't change the entry price
            this.quantity = newQuantity;
            // avgEntryPrice stays the same!
        }

        this.updatedAt = Instant.now();
    }

    /**
     * Add a fill to this position.
     * Convenience method that wraps updateWithFill with price, quantity ordering.
     */
    public void addFill(Double fillPrice, Integer fillQty) {
        if (fillPrice != null && fillQty != null) {
            updateWithFill(fillQty, fillPrice);
        }
    }

    @Override
    public String toString() {
        String direction = isLong() ? "LONG" : (isShort() ? "SHORT" : "FLAT");
        return String.format("Position{symbol='%s', %s %d @ %.2f}",
                symbol, direction, Math.abs(quantity), avgEntryPrice);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Position position = (Position) o;
        return Objects.equals(symbol, position.symbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol);
    }
}
