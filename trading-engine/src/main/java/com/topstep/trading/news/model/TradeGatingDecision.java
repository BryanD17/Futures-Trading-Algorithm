package com.topstep.trading.news.model;

import java.util.Objects;

/**
 * Decision object for trade gating with action, size multiplier, and reason.
 * Immutable value object returned by EventProximityChecker.
 */
public final class TradeGatingDecision {
    private final GatingAction action;
    private final double sizeMultiplier;  // 1.0 for ALLOW, 0.0 for BLOCK, 0.5 for REDUCE_SIZE
    private final String reason;
    private final EconomicEvent triggeringEvent;  // nullable

    private TradeGatingDecision(GatingAction action, double sizeMultiplier, String reason, EconomicEvent triggeringEvent) {
        this.action = Objects.requireNonNull(action, "action cannot be null");
        this.sizeMultiplier = validateSizeMultiplier(sizeMultiplier);
        this.reason = Objects.requireNonNull(reason, "reason cannot be null");
        this.triggeringEvent = triggeringEvent;
    }

    private static double validateSizeMultiplier(double multiplier) {
        if (multiplier < 0.0 || multiplier > 1.0) {
            throw new IllegalArgumentException("sizeMultiplier must be between 0.0 and 1.0, got: " + multiplier);
        }
        return multiplier;
    }

    /**
     * Create an ALLOW decision (normal trading).
     */
    public static TradeGatingDecision allow() {
        return new TradeGatingDecision(GatingAction.ALLOW, 1.0, "No blocking events", null);
    }

    /**
     * Create a BLOCK decision (no new entries).
     */
    public static TradeGatingDecision block(String reason, EconomicEvent event) {
        return new TradeGatingDecision(GatingAction.BLOCK, 0.0, reason, event);
    }

    /**
     * Create a REDUCE_SIZE decision (trade with reduced position size).
     */
    public static TradeGatingDecision reduceSize(double multiplier, String reason, EconomicEvent event) {
        return new TradeGatingDecision(GatingAction.REDUCE_SIZE, multiplier, reason, event);
    }

    public GatingAction getAction() {
        return action;
    }

    public double getSizeMultiplier() {
        return sizeMultiplier;
    }

    public String getReason() {
        return reason;
    }

    public EconomicEvent getTriggeringEvent() {
        return triggeringEvent;
    }

    /**
     * Check if trading is allowed (ALLOW or REDUCE_SIZE).
     */
    public boolean isTradeAllowed() {
        return action != GatingAction.BLOCK;
    }

    /**
     * Check if trading is blocked.
     */
    public boolean isBlocked() {
        return action == GatingAction.BLOCK;
    }

    /**
     * Check if size should be reduced.
     */
    public boolean isSizeReduced() {
        return action == GatingAction.REDUCE_SIZE;
    }

    /**
     * Check if there is a triggering event.
     */
    public boolean hasTriggeringEvent() {
        return triggeringEvent != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TradeGatingDecision that = (TradeGatingDecision) o;
        return Double.compare(that.sizeMultiplier, sizeMultiplier) == 0 &&
               action == that.action &&
               Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, sizeMultiplier, reason);
    }

    @Override
    public String toString() {
        return String.format("TradeGatingDecision{action=%s, sizeMultiplier=%.2f, reason='%s', event=%s}",
                action, sizeMultiplier, reason,
                triggeringEvent != null ? triggeringEvent.getName() : "none");
    }
}
