package com.topstep.trading.risk;

import com.topstep.trading.domain.Order;

/**
 * Result of a risk evaluation.
 * Contains whether the trade is allowed and the order to execute (if allowed).
 */
public class RiskDecision {
    private final boolean allowed;
    private final Order order;
    private final String reason;

    public RiskDecision(boolean allowed, Order order, String reason) {
        this.allowed = allowed;
        this.order = order;
        this.reason = reason;
    }

    public static RiskDecision allow(Order order, String reason) {
        return new RiskDecision(true, order, reason);
    }

    public static RiskDecision deny(String reason) {
        return new RiskDecision(false, null, reason);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public Order getOrder() {
        return order;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        if (allowed) {
            return String.format("ALLOWED: %s (%s)", order, reason);
        } else {
            return String.format("DENIED: %s", reason);
        }
    }
}
