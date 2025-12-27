package com.topstep.trading.strategy;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Complete profile for an instrument including ICT-specific trading parameters.
 *
 * Each instrument trades differently and requires customized:
 * - Entry zones (OTE levels)
 * - Stop distances
 * - Volatility thresholds
 * - Session preferences
 * - SMT/correlation pairs
 */
public class InstrumentProfile {

    // Basic identification
    private final String symbol;
    private final String contractId;
    private final String name;

    // Tick/Point values
    private final double tickSize;
    private final double tickValue;
    private final double pointValue;

    // ICT Correlation settings
    private final String smtSymbol;           // Primary SMT divergence pair
    private final double smtCorrelation;      // Expected correlation (0 to 1)
    private final String inverseCorrSymbol;   // Inverse correlation (e.g., DXY for Gold)
    private final double inverseCorrelation;  // Expected inverse correlation (-1 to 0)
    private final String correlatedCurrency;  // Related currency (e.g., 6C for CL)
    private final double currencyCorrelation;

    // OTE Zone settings (Optimal Trade Entry)
    private final double oteLow;              // Lower bound of OTE (e.g., 0.62)
    private final double oteHigh;             // Upper bound of OTE (e.g., 0.705)

    // Volatility settings
    private final double typicalDailyAtr;
    private final double lowVolatilityThreshold;
    private final double highVolatilityThreshold;
    private final double extremeVolatilityThreshold;

    // Position sizing
    private final int baseContracts;
    private final int maxContracts;
    private final int lowVolContracts;

    // Stop/Target settings
    private final double stopBufferPoints;
    private final double minStopDistance;
    private final double maxStopDistance;

    // Killzone preferences
    private final List<String> primaryKillzones;
    private final List<String> secondaryKillzones;
    private final int killzoneStartBuffer;    // Minutes to wait after killzone starts
    private final int killzoneEndBuffer;      // Minutes before killzone ends to stop trading

    // Times to avoid (e.g., news releases)
    private final List<LocalTime> avoidTimes;

    // Behavior characteristics (0.0 to 1.0)
    private final double fvgFillSpeed;        // How quickly FVGs fill
    private final double obRespectRate;       // How often OBs get respected
    private final double breakerReliability;  // Breaker block success rate
    private final double sweepFakeoutRate;    // How often sweeps are fakeouts
    private final double power3Reliability;   // Power of 3 pattern reliability

    // Notes for the trader
    private final String notes;

    private InstrumentProfile(Builder builder) {
        this.symbol = builder.symbol;
        this.contractId = builder.contractId;
        this.name = builder.name;
        this.tickSize = builder.tickSize;
        this.tickValue = builder.tickValue;
        this.pointValue = builder.pointValue;
        this.smtSymbol = builder.smtSymbol;
        this.smtCorrelation = builder.smtCorrelation;
        this.inverseCorrSymbol = builder.inverseCorrSymbol;
        this.inverseCorrelation = builder.inverseCorrelation;
        this.correlatedCurrency = builder.correlatedCurrency;
        this.currencyCorrelation = builder.currencyCorrelation;
        this.oteLow = builder.oteLow;
        this.oteHigh = builder.oteHigh;
        this.typicalDailyAtr = builder.typicalDailyAtr;
        this.lowVolatilityThreshold = builder.lowVolatilityThreshold;
        this.highVolatilityThreshold = builder.highVolatilityThreshold;
        this.extremeVolatilityThreshold = builder.extremeVolatilityThreshold;
        this.baseContracts = builder.baseContracts;
        this.maxContracts = builder.maxContracts;
        this.lowVolContracts = builder.lowVolContracts;
        this.stopBufferPoints = builder.stopBufferPoints;
        this.minStopDistance = builder.minStopDistance;
        this.maxStopDistance = builder.maxStopDistance;
        this.primaryKillzones = builder.primaryKillzones;
        this.secondaryKillzones = builder.secondaryKillzones;
        this.killzoneStartBuffer = builder.killzoneStartBuffer;
        this.killzoneEndBuffer = builder.killzoneEndBuffer;
        this.avoidTimes = builder.avoidTimes;
        this.fvgFillSpeed = builder.fvgFillSpeed;
        this.obRespectRate = builder.obRespectRate;
        this.breakerReliability = builder.breakerReliability;
        this.sweepFakeoutRate = builder.sweepFakeoutRate;
        this.power3Reliability = builder.power3Reliability;
        this.notes = builder.notes;
    }

    // Getters
    public String getSymbol() { return symbol; }
    public String getContractId() { return contractId; }
    public String getName() { return name; }
    public double getTickSize() { return tickSize; }
    public double getTickValue() { return tickValue; }
    public double getPointValue() { return pointValue; }
    public String getSmtSymbol() { return smtSymbol; }
    public double getSmtCorrelation() { return smtCorrelation; }
    public String getInverseCorrSymbol() { return inverseCorrSymbol; }
    public double getInverseCorrelation() { return inverseCorrelation; }
    public String getCorrelatedCurrency() { return correlatedCurrency; }
    public double getCurrencyCorrelation() { return currencyCorrelation; }
    public double getOteLow() { return oteLow; }
    public double getOteHigh() { return oteHigh; }
    public double getTypicalDailyAtr() { return typicalDailyAtr; }
    public double getLowVolatilityThreshold() { return lowVolatilityThreshold; }
    public double getHighVolatilityThreshold() { return highVolatilityThreshold; }
    public double getExtremeVolatilityThreshold() { return extremeVolatilityThreshold; }
    public int getBaseContracts() { return baseContracts; }
    public int getMaxContracts() { return maxContracts; }
    public int getLowVolContracts() { return lowVolContracts; }
    public double getStopBufferPoints() { return stopBufferPoints; }
    public double getMinStopDistance() { return minStopDistance; }
    public double getMaxStopDistance() { return maxStopDistance; }
    public List<String> getPrimaryKillzones() { return primaryKillzones; }
    public List<String> getSecondaryKillzones() { return secondaryKillzones; }
    public int getKillzoneStartBuffer() { return killzoneStartBuffer; }
    public int getKillzoneEndBuffer() { return killzoneEndBuffer; }
    public List<LocalTime> getAvoidTimes() { return avoidTimes; }
    public double getFvgFillSpeed() { return fvgFillSpeed; }
    public double getObRespectRate() { return obRespectRate; }
    public double getBreakerReliability() { return breakerReliability; }
    public double getSweepFakeoutRate() { return sweepFakeoutRate; }
    public double getPower3Reliability() { return power3Reliability; }
    public String getNotes() { return notes; }

    /**
     * Calculate dollar value of a price move.
     */
    public double calculatePnL(double priceMove, int contracts) {
        return (priceMove / tickSize) * tickValue * contracts;
    }

    /**
     * Check if this instrument prefers a given killzone.
     */
    public boolean prefersKillzone(String killzone) {
        return primaryKillzones.contains(killzone) ||
               (secondaryKillzones != null && secondaryKillzones.contains(killzone));
    }

    /**
     * Get tier adjustment based on instrument characteristics.
     * Some instruments are more reliable for certain setups.
     */
    public double getTierMultiplier(TradeTier tier) {
        // Adjust R:R based on instrument behavior
        switch (tier) {
            case TIER_3:
                // Premium setups - adjust based on breaker reliability
                return breakerReliability >= 0.85 ? 1.0 : 0.9;
            case TIER_2:
                // Standard setups - adjust based on OB respect rate
                return obRespectRate >= 0.75 ? 1.0 : 0.85;
            case TIER_1:
                // Scalp setups - always conservative
                return 1.0;
            default:
                return 1.0;
        }
    }

    /**
     * Check if we should avoid trading at the current time.
     */
    public boolean shouldAvoidTime(LocalTime time) {
        if (avoidTimes == null || avoidTimes.isEmpty()) {
            return false;
        }
        for (LocalTime avoid : avoidTimes) {
            // Avoid 5 minutes before and after
            if (Math.abs(time.toSecondOfDay() - avoid.toSecondOfDay()) < 300) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format("%s (%s) - $%.2f/tick, ATR: %.2f, OTE: %.2f-%.2f",
                symbol, name, tickValue, typicalDailyAtr, oteLow, oteHigh);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String symbol;
        private String contractId;
        private String name;
        private double tickSize;
        private double tickValue;
        private double pointValue;
        private String smtSymbol;
        private double smtCorrelation = 0.0;
        private String inverseCorrSymbol;
        private double inverseCorrelation = 0.0;
        private String correlatedCurrency;
        private double currencyCorrelation = 0.0;
        private double oteLow = 0.62;
        private double oteHigh = 0.705;
        private double typicalDailyAtr;
        private double lowVolatilityThreshold;
        private double highVolatilityThreshold;
        private double extremeVolatilityThreshold;
        private int baseContracts = 1;
        private int maxContracts = 2;
        private int lowVolContracts = 2;
        private double stopBufferPoints;
        private double minStopDistance;
        private double maxStopDistance;
        private List<String> primaryKillzones = new ArrayList<>();
        private List<String> secondaryKillzones = new ArrayList<>();
        private int killzoneStartBuffer = 30;
        private int killzoneEndBuffer = 30;
        private List<LocalTime> avoidTimes = new ArrayList<>();
        private double fvgFillSpeed = 0.7;
        private double obRespectRate = 0.7;
        private double breakerReliability = 0.8;
        private double sweepFakeoutRate = 0.6;
        private double power3Reliability = 0.75;
        private String notes = "";

        public Builder symbol(String symbol) { this.symbol = symbol; return this; }
        public Builder contractId(String contractId) { this.contractId = contractId; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder tickSize(double tickSize) { this.tickSize = tickSize; return this; }
        public Builder tickValue(double tickValue) { this.tickValue = tickValue; return this; }
        public Builder pointValue(double pointValue) { this.pointValue = pointValue; return this; }
        public Builder smtSymbol(String smtSymbol) { this.smtSymbol = smtSymbol; return this; }
        public Builder smtCorrelation(double smtCorrelation) { this.smtCorrelation = smtCorrelation; return this; }
        public Builder inverseCorrSymbol(String inverseCorrSymbol) { this.inverseCorrSymbol = inverseCorrSymbol; return this; }
        public Builder inverseCorrelation(double inverseCorrelation) { this.inverseCorrelation = inverseCorrelation; return this; }
        public Builder correlatedCurrency(String correlatedCurrency) { this.correlatedCurrency = correlatedCurrency; return this; }
        public Builder currencyCorrelation(double currencyCorrelation) { this.currencyCorrelation = currencyCorrelation; return this; }
        public Builder oteLow(double oteLow) { this.oteLow = oteLow; return this; }
        public Builder oteHigh(double oteHigh) { this.oteHigh = oteHigh; return this; }
        public Builder typicalDailyAtr(double typicalDailyAtr) { this.typicalDailyAtr = typicalDailyAtr; return this; }
        public Builder lowVolatilityThreshold(double lowVolatilityThreshold) { this.lowVolatilityThreshold = lowVolatilityThreshold; return this; }
        public Builder highVolatilityThreshold(double highVolatilityThreshold) { this.highVolatilityThreshold = highVolatilityThreshold; return this; }
        public Builder extremeVolatilityThreshold(double extremeVolatilityThreshold) { this.extremeVolatilityThreshold = extremeVolatilityThreshold; return this; }
        public Builder baseContracts(int baseContracts) { this.baseContracts = baseContracts; return this; }
        public Builder maxContracts(int maxContracts) { this.maxContracts = maxContracts; return this; }
        public Builder lowVolContracts(int lowVolContracts) { this.lowVolContracts = lowVolContracts; return this; }
        public Builder stopBufferPoints(double stopBufferPoints) { this.stopBufferPoints = stopBufferPoints; return this; }
        public Builder minStopDistance(double minStopDistance) { this.minStopDistance = minStopDistance; return this; }
        public Builder maxStopDistance(double maxStopDistance) { this.maxStopDistance = maxStopDistance; return this; }
        public Builder primaryKillzones(List<String> primaryKillzones) { this.primaryKillzones = primaryKillzones; return this; }
        public Builder secondaryKillzones(List<String> secondaryKillzones) { this.secondaryKillzones = secondaryKillzones; return this; }
        public Builder killzoneStartBuffer(int killzoneStartBuffer) { this.killzoneStartBuffer = killzoneStartBuffer; return this; }
        public Builder killzoneEndBuffer(int killzoneEndBuffer) { this.killzoneEndBuffer = killzoneEndBuffer; return this; }
        public Builder avoidTimes(List<LocalTime> avoidTimes) { this.avoidTimes = avoidTimes; return this; }
        public Builder fvgFillSpeed(double fvgFillSpeed) { this.fvgFillSpeed = fvgFillSpeed; return this; }
        public Builder obRespectRate(double obRespectRate) { this.obRespectRate = obRespectRate; return this; }
        public Builder breakerReliability(double breakerReliability) { this.breakerReliability = breakerReliability; return this; }
        public Builder sweepFakeoutRate(double sweepFakeoutRate) { this.sweepFakeoutRate = sweepFakeoutRate; return this; }
        public Builder power3Reliability(double power3Reliability) { this.power3Reliability = power3Reliability; return this; }
        public Builder notes(String notes) { this.notes = notes; return this; }

        public InstrumentProfile build() {
            return new InstrumentProfile(this);
        }
    }
}
