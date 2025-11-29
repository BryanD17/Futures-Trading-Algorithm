package com.topstep.trading.connector;

import com.topstep.trading.domain.Order;

/**
 * Listener interface for order updates.
 */
public interface OrderListener {
    /**
     * Called when an order is submitted.
     */
    void onOrderSubmitted(Order order);

    /**
     * Called when an order is filled (partially or completely).
     */
    void onOrderFilled(Order order, int fillQuantity, double fillPrice);

    /**
     * Called when an order is canceled.
     */
    void onOrderCanceled(Order order);

    /**
     * Called when an order is rejected.
     */
    void onOrderRejected(Order order, String reason);
}
