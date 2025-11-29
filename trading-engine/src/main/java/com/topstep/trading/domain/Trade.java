package com.topstep.trading.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a completed trade (entry + exit).
 * Immutable record of trading activity.
 */
public final class Trade {
    private final String tradeId;
    private final String symbol;
    private final OrderSide side;
    private final int quantity;
    private final double entryPrice;
    private final double exitPrice;
    private final Instant entryTime;
    private final Instant exitTime;
    private final double realizedPnL;
    private final double riskAmount;      // 1R value
    private final double rMultiple;       // Actual R multiple achieved
    private final String notes;           // Why this trade was taken

    private Trade(Builder builder) {
        this.tradeId = builder.tradeId != null ? builder.tradeId : UUID.randomUUID().toString();
        this.symbol = Objects.requireNonNull(builder.symbol);
        this.side = Objects.requireNonNull(builder.side);
        this.quantity = builder.quantity;
        this.entryPrice = builder.entryPrice;
        this.exitPrice = builder.exitPrice;
        this.entryTime = Objects.requireNonNull(builder.entryTime);
        this.exitTime = Objects.requireNonNull(builder.exitTime);
        this.realizedPnL = builder.realizedPnL;
        this.riskAmount = builder.riskAmount;
        this.rMultiple = builder.riskAmount > 0 ? builder.realizedPnL / builder.riskAmount : 0.0;
        this.notes = builder.notes;
    }

    // Getters
    public String getTradeId() { return tradeId; }
    public String getSymbol() { return symbol; }
    public OrderSide getSide() { return side; }
    public int getQuantity() { return quantity; }
    public double getEntryPrice() { return entryPrice; }
    public double getExitPrice() { return exitPrice; }
    public Instant getEntryTime() { return entryTime; }
    public Instant getExitTime() { return exitTime; }
    public double getRealizedPnL() { return realizedPnL; }
    public double getRiskAmount() { return riskAmount; }
    public double getRMultiple() { return rMultiple; }
    public String getNotes() { return notes; }

    public boolean isWinner() {
        return realizedPnL > 0;
    }

    public boolean isLoser() {
        return realizedPnL < 0;
    }

    @Override
    public String toString() {
        return String.format("Trade{id='%s', symbol='%s', %s %d @ %.2f -> %.2f, PnL=%.2f, R=%.2f, notes='%s'}",
                tradeId, symbol, side, quantity, entryPrice, exitPrice, realizedPnL, rMultiple, notes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String tradeId;
        private String symbol;
        private OrderSide side;
        private int quantity;
        private double entryPrice;
        private double exitPrice;
        private Instant entryTime;
        private Instant exitTime;
        private double realizedPnL;
        private double riskAmount;
        private String notes;

        public Builder tradeId(String tradeId) {
            this.tradeId = tradeId;
            return this;
        }

        public Builder symbol(String symbol) {
            this.symbol = symbol;
            return this;
        }

        public Builder side(OrderSide side) {
            this.side = side;
            return this;
        }

        public Builder quantity(int quantity) {
            this.quantity = quantity;
            return this;
        }

        public Builder entryPrice(double entryPrice) {
            this.entryPrice = entryPrice;
            return this;
        }

        public Builder exitPrice(double exitPrice) {
            this.exitPrice = exitPrice;
            return this;
        }

        public Builder entryTime(Instant entryTime) {
            this.entryTime = entryTime;
            return this;
        }

        public Builder exitTime(Instant exitTime) {
            this.exitTime = exitTime;
            return this;
        }

        public Builder realizedPnL(double realizedPnL) {
            this.realizedPnL = realizedPnL;
            return this;
        }

        public Builder riskAmount(double riskAmount) {
            this.riskAmount = riskAmount;
            return this;
        }

        public Builder notes(String notes) {
            this.notes = notes;
            return this;
        }

        public Trade build() {
            return new Trade(this);
        }
    }
}
