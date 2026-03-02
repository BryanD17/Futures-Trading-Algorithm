package com.topstep.trading.news.model;

/**
 * Impact level of economic events.
 * Higher impact events have stronger effects on market volatility and bias.
 */
public enum EventImpact {
    HIGH(3),      // Fed decisions, CPI, NFP
    MEDIUM(2),    // PMI, Retail Sales, Jobless Claims
    LOW(1);       // Minor releases, revisions

    private final int weight;

    EventImpact(int weight) {
        this.weight = weight;
    }

    /**
     * Get the weight factor for this impact level.
     * Used in calculations for gating and bias.
     */
    public int getWeight() {
        return weight;
    }
}
