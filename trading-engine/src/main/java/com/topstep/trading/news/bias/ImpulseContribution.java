package com.topstep.trading.news.bias;

import com.topstep.trading.news.model.Currency;
import com.topstep.trading.news.model.SurpriseDirection;

/**
 * Represents the contribution of a single impulse to the total bias.
 * Used for detailed breakdown and logging.
 */
public final class ImpulseContribution {
    private final String eventName;
    private final Currency currency;
    private final double contribution;  // Signed contribution to total bias
    private final SurpriseDirection direction;

    public ImpulseContribution(String eventName, Currency currency, double contribution, SurpriseDirection direction) {
        this.eventName = eventName;
        this.currency = currency;
        this.contribution = contribution;
        this.direction = direction;
    }

    public String getEventName() {
        return eventName;
    }

    public Currency getCurrency() {
        return currency;
    }

    /**
     * Get the signed contribution to the total bias.
     * Positive = bullish contribution, Negative = bearish contribution.
     */
    public double getContribution() {
        return contribution;
    }

    public SurpriseDirection getDirection() {
        return direction;
    }

    /**
     * Get the absolute contribution magnitude.
     */
    public double getAbsoluteContribution() {
        return Math.abs(contribution);
    }

    /**
     * Check if this is a bullish contribution.
     */
    public boolean isBullish() {
        return contribution > 0;
    }

    /**
     * Check if this is a bearish contribution.
     */
    public boolean isBearish() {
        return contribution < 0;
    }

    @Override
    public String toString() {
        String sign = contribution >= 0 ? "+" : "";
        return String.format("%s (%s): %s%.1f%% [%s]",
                eventName, currency, sign, contribution * 100, direction);
    }
}
