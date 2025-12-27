package com.topstep.trading.risk;

import com.topstep.trading.domain.Order;

/**
 * Result of a risk evaluation.
 * Contains whether the trade is allowed and the order to execute (if allowed).
 *
 * Enhanced to support both order-based and signal-based risk evaluation.
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

    // ==================== ORDER-BASED METHODS ====================

    public static RiskDecision allow(Order order, String reason) {
        return new RiskDecision(true, order, reason);
    }

    public static RiskDecision deny(String reason) {
        return new RiskDecision(false, null, reason);
    }

    // ==================== SIGNAL-BASED METHODS ====================
    // For use with TradingRiskManager signal validation

    /**
     * Approve a signal/trade without a specific order.
     */
    public static RiskDecision approve(String reason) {
        return new RiskDecision(true, null, reason);
    }

    /**
     * Reject a signal/trade.
     */
    public static RiskDecision reject(String reason) {
        return new RiskDecision(false, null, reason);
    }

    // ==================== GETTERS ====================

    public boolean isAllowed() {
        return allowed;
    }

    /**
     * Alias for isAllowed() - more intuitive for signal validation.
     */
    public boolean isApproved() {
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
            if (order != null) {
                return String.format("ALLOWED: %s (%s)", order, reason);
            } else {
                return String.format("APPROVED: %s", reason);
            }
        } else {
            return String.format("DENIED: %s", reason);
        }
    }
}
