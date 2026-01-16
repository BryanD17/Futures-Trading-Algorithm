package com.topstep.trading.strategy;

/**
 * Detailed SMT (Smart Money Technique) Divergence Result.
 *
 * SMT Divergence occurs when correlated instruments fail to confirm each other's
 * moves, indicating institutional activity and potential reversals.
 *
 * DIVERGENCE TYPES:
 * - BULLISH_SMT: Primary makes new low, but correlated doesn't (bullish signal)
 * - BEARISH_SMT: Primary makes new high, but correlated doesn't (bearish signal)
 *
 * STRENGTH SCORING:
 * - Weak (1-2): Small divergence, recent correlation intact
 * - Moderate (3-4): Clear divergence, notable relative strength difference
 * - Strong (5+): Significant divergence with abnormal correlation
 */
public class SmtDivergenceResult {

    public enum DivergenceType {
        NONE,           // No divergence detected
        BULLISH_SMT,    // Primary weaker (made new low), bullish on primary
        BEARISH_SMT     // Primary stronger (made new high), bearish on primary
    }

    private final DivergenceType type;
    private final String primarySymbol;
    private final String correlatedSymbol;
    private final int strength;              // 0-5 scale
    private final double relativeStrength;   // Primary vs correlated % difference
    private final double currentCorrelation; // Current correlation coefficient
    private final double expectedCorrelation;// Expected based on historical
    private final boolean correlationAbnormal;
    private final String description;

    public SmtDivergenceResult(DivergenceType type, String primarySymbol, String correlatedSymbol,
                                int strength, double relativeStrength, double currentCorrelation,
                                double expectedCorrelation, boolean correlationAbnormal) {
        this.type = type;
        this.primarySymbol = primarySymbol;
        this.correlatedSymbol = correlatedSymbol;
        this.strength = Math.min(5, Math.max(0, strength));
        this.relativeStrength = relativeStrength;
        this.currentCorrelation = currentCorrelation;
        this.expectedCorrelation = expectedCorrelation;
        this.correlationAbnormal = correlationAbnormal;
        this.description = buildDescription();
    }

    private String buildDescription() {
        if (type == DivergenceType.NONE) {
            return "No SMT divergence";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(type == DivergenceType.BULLISH_SMT ? "Bullish" : "Bearish");
        sb.append(" SMT (");
        sb.append(getStrengthLabel());
        sb.append("): ");
        sb.append(primarySymbol).append(" vs ").append(correlatedSymbol);
        sb.append(", RelStr=").append(String.format("%.2f%%", relativeStrength));
        if (correlationAbnormal) {
            sb.append(" [ABNORMAL CORR]");
        }
        return sb.toString();
    }

    public String getStrengthLabel() {
        if (strength >= 5) return "Very Strong";
        if (strength >= 4) return "Strong";
        if (strength >= 3) return "Moderate";
        if (strength >= 2) return "Weak";
        return "Minimal";
    }

    /**
     * Calculate confluence score contribution for this SMT divergence.
     * Used in strategy tier grading.
     */
    public int getConfluenceScore() {
        if (type == DivergenceType.NONE) return 0;

        // Base score
        int score = 1;

        // Strength bonus
        if (strength >= 4) score += 2;
        else if (strength >= 3) score += 1;

        // Abnormal correlation bonus (stronger signal)
        if (correlationAbnormal) score += 1;

        return score;
    }

    /**
     * Check if this divergence suggests a bullish bias.
     */
    public boolean isBullish() {
        return type == DivergenceType.BULLISH_SMT;
    }

    /**
     * Check if this divergence suggests a bearish bias.
     */
    public boolean isBearish() {
        return type == DivergenceType.BEARISH_SMT;
    }

    /**
     * Check if divergence is present.
     */
    public boolean hasDivergence() {
        return type != DivergenceType.NONE;
    }

    /**
     * Check if divergence aligns with expected direction.
     */
    public boolean alignsWith(boolean expectBullish) {
        if (type == DivergenceType.NONE) return false;
        return expectBullish ? isBullish() : isBearish();
    }

    // Getters
    public DivergenceType getType() { return type; }
    public String getPrimarySymbol() { return primarySymbol; }
    public String getCorrelatedSymbol() { return correlatedSymbol; }
    public int getStrength() { return strength; }
    public double getRelativeStrength() { return relativeStrength; }
    public double getCurrentCorrelation() { return currentCorrelation; }
    public double getExpectedCorrelation() { return expectedCorrelation; }
    public boolean isCorrelationAbnormal() { return correlationAbnormal; }
    public String getDescription() { return description; }

    @Override
    public String toString() {
        return description;
    }

    /**
     * Create a "no divergence" result.
     */
    public static SmtDivergenceResult none(String primary, String correlated) {
        return new SmtDivergenceResult(
                DivergenceType.NONE, primary, correlated,
                0, 0, 0, 0, false
        );
    }
}
