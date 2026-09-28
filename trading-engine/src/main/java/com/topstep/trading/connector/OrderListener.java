package com.topstep.trading.connector;

import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.OrderStatus;

/**
 * Listener interface for order updates.
 *
 * This interface is designed to be functional when using the simple onOrderUpdate method,
 * allowing lambda expressions for basic order tracking.
 */
@FunctionalInterface
public interface OrderListener {

    /**
     * Called when an order status is updated.
     * This is the primary method for lambda-based listeners.
     */
    void onOrderUpdate(String orderId, OrderStatus status, Double fillPrice, Integer fillQty);

    /**
     * Called when an order is submitted.
     */
    default void onOrderSubmitted(Order order) {
        // Default no-op
    }

    /**
     * Called when an order is filled (partially or completely).
     */
    default void onOrderFilled(Order order, int fillQuantity, double fillPrice) {
        // AGENT-05 (V5 RC-17): route to the primary callback. The old no-op
        // default made every MockConnector-driven fill VANISH for lambda
        // listeners (they implement only onOrderUpdate).
        onOrderUpdate(order != null ? order.getOrderId() : null,
                OrderStatus.FILLED, fillPrice, fillQuantity);
    }

    /**
     * Called when an order is canceled.
     */
    default void onOrderCanceled(Order order) {
        // Default no-op
    }

    /**
     * Called when an order is rejected.
     */
    default void onOrderRejected(Order order, String reason) {
        // AGENT-05: a rejection is terminal — route it to the primary
        // callback so the caller can release its state (never silent).
        onOrderUpdate(order != null ? order.getOrderId() : null,
                OrderStatus.REJECTED, null, 0);
    }
}
