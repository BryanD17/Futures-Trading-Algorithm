package com.topstep.trading.event;

import com.topstep.trading.domain.Order;
import java.util.Objects;

/**
 * Event emitted when an order status changes.
 */
public class OrderEvent extends BaseEvent {
    private final Order order;
    private final int fillQuantity;
    private final Double fillPrice;

    public OrderEvent(EventType type, Order order) {
        this(type, order, 0, null);
    }

    public OrderEvent(EventType type, Order order, int fillQuantity, Double fillPrice) {
        super(type);
        this.order = Objects.requireNonNull(order);
        this.fillQuantity = fillQuantity;
        this.fillPrice = fillPrice;
    }

    public Order getOrder() { return order; }
    public int getFillQuantity() { return fillQuantity; }
    public Double getFillPrice() { return fillPrice; }

    @Override
    public String toString() {
        return String.format("OrderEvent{type=%s, order=%s, fillQty=%d, fillPrice=%s}",
                getType(), order, fillQuantity, fillPrice);
    }
}
