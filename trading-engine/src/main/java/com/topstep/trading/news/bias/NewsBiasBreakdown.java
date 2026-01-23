package com.topstep.trading.news.bias;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Detailed breakdown of news bias for an instrument.
 * Provides transparency into how the total bias was calculated.
 */
public final class NewsBiasBreakdown {
    private final String instrument;
    private final double totalBias;
    private final List<ImpulseContribution> contributions;

    public NewsBiasBreakdown(String instrument, double totalBias, List<ImpulseContribution> contributions) {
        this.instrument = instrument;
        this.totalBias = totalBias;
        this.contributions = contributions != null
            ? Collections.unmodifiableList(contributions)
            : Collections.emptyList();
    }

    public String getInstrument() {
        return instrument;
    }

    /**
     * Get the total bias value (-1.0 to +1.0).
     */
    public double getTotalBias() {
        return totalBias;
    }

    /**
     * Get all contributions to the bias.
     */
    public List<ImpulseContribution> getContributions() {
        return contributions;
    }

    /**
     * Check if there are any active contributions.
     */
    public boolean hasContributions() {
        return !contributions.isEmpty();
    }

    /**
     * Get the number of contributing events.
     */
    public int getContributionCount() {
        return contributions.size();
    }

    /**
     * Get the top N contributors by absolute contribution.
     */
    public List<ImpulseContribution> getTopContributors(int n) {
        return contributions.stream()
            .sorted((a, b) -> Double.compare(b.getAbsoluteContribution(), a.getAbsoluteContribution()))
            .limit(n)
            .collect(Collectors.toList());
    }

    /**
     * Get only bullish contributions.
     */
    public List<ImpulseContribution> getBullishContributions() {
        return contributions.stream()
            .filter(ImpulseContribution::isBullish)
            .collect(Collectors.toList());
    }

    /**
     * Get only bearish contributions.
     */
    public List<ImpulseContribution> getBearishContributions() {
        return contributions.stream()
            .filter(ImpulseContribution::isBearish)
            .collect(Collectors.toList());
    }

    /**
     * Check if the bias is bullish (positive).
     */
    public boolean isBullish() {
        return totalBias > 0;
    }

    /**
     * Check if the bias is bearish (negative).
     */
    public boolean isBearish() {
        return totalBias < 0;
    }

    /**
     * Check if the bias is neutral (near zero).
     */
    public boolean isNeutral(double threshold) {
        return Math.abs(totalBias) <= threshold;
    }

    /**
     * Get a formatted summary of the breakdown.
     */
    public String getSummary() {
        if (contributions.isEmpty()) {
            return String.format("%s: No active news bias", instrument);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s: %.1f%% news bias (%d events)\n",
                instrument, totalBias * 100, contributions.size()));

        for (ImpulseContribution c : getTopContributors(3)) {
            sb.append(String.format("  %s\n", c.toString()));
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("NewsBiasBreakdown{instrument='%s', totalBias=%.3f, contributions=%d}",
                instrument, totalBias, contributions.size());
    }
}
