package com.topstep.trading.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a trading order.
 * Mutable to track status changes and fills.
 */
public class Order {
    private final String orderId;
    private final String symbol;
    private final OrderSide side;
    private final OrderType type;
    private final int quantity;
    private final Double limitPrice;
    private final Double stopPrice;
    private final Instant createdAt;

    private OrderStatus status;
    private int filledQuantity;
    private Double avgFillPrice;
    private Instant updatedAt;
    private String rejectReason;

    private Order(Builder builder) {
        this.orderId = builder.orderId != null ? builder.orderId : UUID.randomUUID().toString();
        this.symbol = Objects.requireNonNull(builder.symbol, "symbol cannot be null");
        this.side = Objects.requireNonNull(builder.side, "side cannot be null");
        this.type = Objects.requireNonNull(builder.type, "type cannot be null");
        this.quantity = builder.quantity;
        this.limitPrice = builder.limitPrice;
        this.stopPrice = builder.stopPrice;
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        this.status = OrderStatus.PENDING;
        this.filledQuantity = 0;
        this.updatedAt = this.createdAt;
    }

    // Getters
    public String getOrderId() { return orderId; }
    public String getSymbol() { return symbol; }
    public OrderSide getSide() { return side; }
    public OrderType getType() { return type; }
    public int getQuantity() { return quantity; }
    public Double getLimitPrice() { return limitPrice; }
    public Double getStopPrice() { return stopPrice; }
    public Instant getCreatedAt() { return createdAt; }
    public OrderStatus getStatus() { return status; }
    public int getFilledQuantity() { return filledQuantity; }
    public Double getAvgFillPrice() { return avgFillPrice; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getRejectReason() { return rejectReason; }

    public boolean isFilled() {
        return status == OrderStatus.FILLED;
    }

    public boolean isActive() {
        return status == OrderStatus.PENDING ||
               status == OrderStatus.SUBMITTED ||
               status == OrderStatus.PARTIALLY_FILLED;
    }

    // State mutations
    public void updateStatus(OrderStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public void recordFill(int fillQuantity, double fillPrice) {
        this.filledQuantity += fillQuantity;
        if (this.avgFillPrice == null) {
            this.avgFillPrice = fillPrice;
        } else {
            // Update weighted average
            this.avgFillPrice = ((this.avgFillPrice * (this.filledQuantity - fillQuantity)) +
                                (fillPrice * fillQuantity)) / this.filledQuantity;
        }

        if (this.filledQuantity >= this.quantity) {
            this.status = OrderStatus.FILLED;
        } else {
            this.status = OrderStatus.PARTIALLY_FILLED;
        }
        this.updatedAt = Instant.now();
    }

    public void reject(String reason) {
        this.status = OrderStatus.REJECTED;
        this.rejectReason = reason;
        this.updatedAt = Instant.now();
    }

    @Override
    public String toString() {
        return String.format("Order{id='%s', symbol='%s', %s %s, qty=%d, status=%s, filled=%d, avgPrice=%s}",
                orderId, symbol, side, type, quantity, status, filledQuantity, avgFillPrice);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String orderId;
        private String symbol;
        private OrderSide side;
        private OrderType type;
        private int quantity;
        private Double limitPrice;
        private Double stopPrice;
        private Instant createdAt;

        public Builder orderId(String orderId) {
            this.orderId = orderId;
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

        public Builder type(OrderType type) {
            this.type = type;
            return this;
        }

        public Builder quantity(int quantity) {
            this.quantity = quantity;
            return this;
        }

        public Builder limitPrice(Double limitPrice) {
            this.limitPrice = limitPrice;
            return this;
        }

        public Builder stopPrice(Double stopPrice) {
            this.stopPrice = stopPrice;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Order build() {
            return new Order(this);
        }
    }
}
