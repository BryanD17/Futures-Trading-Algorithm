package com.topstep.trading.domain;

/**
 * Lifecycle states of an order.
 */
public enum OrderStatus {
    PENDING,
    SUBMITTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    REJECTED
}
