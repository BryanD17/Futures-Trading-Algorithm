package com.topstep.trading.chartstate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a detected liquidity raid with quality scoring.
 *
 * A liquidity raid is when price sweeps a known level (taking out stop losses)
 * and then reverses. Not all raids are equal - this class tracks quality factors
 * that determine the probability of a successful reversal trade.
 *
 * QUALITY SCORING FACTORS:
 * - Timing (killzone, Silver Bullet window)
 * - Level significance (PDH/PDL vs session levels vs equal H/L)
 * - Confirmation (SMT, HTF alignment)
 * - Price action quality (penetration depth, rejection strength)
 *
 * A raid with score 8+ is "Elite" and has highest probability.
 * A raid with score 6-7 is "Premium" - standard entry.
 * A raid with score 4-5 is "Standard" - reduced size.
 * A raid with score 1-3 should be skipped.
 */
public class LiquidityRaid {

    // ═══════════════════════════════════════════════════════════════════
    // CORE IDENTIFICATION
    // ═══════════════════════════════════════════════════════════════════

    private final String id;                        // Unique ID for tracking
    private final String instrument;                // Symbol (ES, NQ, GC, etc.)
    private final KnownLevel targetLevel;           // Which level was raided
    private final RaidDirection direction;          // HIGH_SWEEP or LOW_SWEEP

    // ═══════════════════════════════════════════════════════════════════
    // TIMING
    // ═══════════════════════════════════════════════════════════════════

    private final Instant raidTime;                 // When the raid was detected
    private final int raidBarIndex;                 // Bar index at detection
    private int barsSinceRaid;                      // Updated each candle

    // ═══════════════════════════════════════════════════════════════════
    // PRICE ACTION EVIDENCE
    // ═══════════════════════════════════════════════════════════════════

    private final double raidCandleHigh;            // High of raid candle
    private final double raidCandleLow;             // Low of raid candle
    private final double raidCandleOpen;            // Open of raid candle
    private final double raidCandleClose;           // Close of raid candle
    private final double penetrationTicks;          // How far past level (in ticks)
    private final double penetrationPct;            // Penetration as % of level
    private final double rejectionTicks;            // Close distance from level
    private final double wickToBodyRatio;           // Rejection candle wick ratio

    // ═══════════════════════════════════════════════════════════════════
    // QUALITY SCORING
    // ═══════════════════════════════════════════════════════════════════

    private int qualityScore;                       // 1-10
    private final List<String> qualityFactors;      // What contributed to score

    // ═══════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════

    private RaidState state;                        // ACTIVE, CONFIRMED, EXPIRED, INVALIDATED

    // ═══════════════════════════════════════════════════════════════════
    // CONFIRMATION TRACKING
    // ═══════════════════════════════════════════════════════════════════

    private boolean hasDisplacement;                // Displacement candle followed
    private boolean hasMss;                         // MSS occurred after raid
    private boolean hasFvgFormed;                   // FVG formed in expected direction
    private boolean hasSmtConfirmation;             // SMT divergence present

    // ═══════════════════════════════════════════════════════════════════
    // CONSTRUCTOR
    // ═══════════════════════════════════════════════════════════════════

    public LiquidityRaid(String instrument, KnownLevel targetLevel, RaidDirection direction,
                         Instant raidTime, int raidBarIndex,
                         double raidCandleHigh, double raidCandleLow,
                         double raidCandleOpen, double raidCandleClose,
                         double penetrationTicks, double tickSize) {

        this.id = generateId(instrument, targetLevel, raidTime);
        this.instrument = instrument;
        this.targetLevel = targetLevel;
        this.direction = direction;
        this.raidTime = raidTime;
        this.raidBarIndex = raidBarIndex;
        this.barsSinceRaid = 0;

        this.raidCandleHigh = raidCandleHigh;
        this.raidCandleLow = raidCandleLow;
        this.raidCandleOpen = raidCandleOpen;
        this.raidCandleClose = raidCandleClose;
        this.penetrationTicks = penetrationTicks;
        this.penetrationPct = (penetrationTicks * tickSize) / targetLevel.getPrice() * 100;

        // Calculate rejection (how far close is from level)
        this.rejectionTicks = Math.abs(raidCandleClose - targetLevel.getPrice()) / tickSize;

        // Calculate wick to body ratio for rejection strength
        double candleRange = raidCandleHigh - raidCandleLow;
        if (candleRange > 0) {
            if (direction == RaidDirection.HIGH_SWEEP) {
                // Upper wick ratio for high sweep
                double upperWick = raidCandleHigh - Math.max(raidCandleOpen, raidCandleClose);
                this.wickToBodyRatio = upperWick / candleRange;
            } else {
                // Lower wick ratio for low sweep
                double lowerWick = Math.min(raidCandleOpen, raidCandleClose) - raidCandleLow;
                this.wickToBodyRatio = lowerWick / candleRange;
            }
        } else {
            this.wickToBodyRatio = 0;
        }

        this.qualityScore = 0;
        this.qualityFactors = new ArrayList<>();
        this.state = RaidState.ACTIVE;

        this.hasDisplacement = false;
        this.hasMss = false;
        this.hasFvgFormed = false;
        this.hasSmtConfirmation = false;
    }

    private static String generateId(String instrument, KnownLevel level, Instant time) {
        return String.format("RAID_%s_%s_%d", instrument, level.getType().name(), time.toEpochMilli());
    }

    // ═══════════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════════

    public String getId() { return id; }
    public String getInstrument() { return instrument; }
    public KnownLevel getTargetLevel() { return targetLevel; }
    public RaidDirection getDirection() { return direction; }
    public Instant getRaidTime() { return raidTime; }
    public int getRaidBarIndex() { return raidBarIndex; }
    public int getBarsSinceRaid() { return barsSinceRaid; }
    public double getRaidCandleHigh() { return raidCandleHigh; }
    public double getRaidCandleLow() { return raidCandleLow; }
    public double getRaidCandleOpen() { return raidCandleOpen; }
    public double getRaidCandleClose() { return raidCandleClose; }
    public double getPenetrationTicks() { return penetrationTicks; }
    public double getPenetrationPct() { return penetrationPct; }
    public double getRejectionTicks() { return rejectionTicks; }
    public double getWickToBodyRatio() { return wickToBodyRatio; }
    public int getQualityScore() { return qualityScore; }
    public List<String> getQualityFactors() { return Collections.unmodifiableList(qualityFactors); }
    public RaidState getState() { return state; }
    public boolean hasDisplacement() { return hasDisplacement; }
    public boolean hasMss() { return hasMss; }
    public boolean hasFvgFormed() { return hasFvgFormed; }
    public boolean hasSmtConfirmation() { return hasSmtConfirmation; }

    // ═══════════════════════════════════════════════════════════════════
    // STATE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Increment bars since raid (called each candle).
     */
    public void incrementBarsSinceRaid() {
        this.barsSinceRaid++;
    }

    /**
     * Set the quality score and factors.
     */
    public void setQualityScore(int score, List<String> factors) {
        this.qualityScore = Math.max(1, Math.min(10, score));
        this.qualityFactors.clear();
        this.qualityFactors.addAll(factors);
    }

    /**
     * Update state to CONFIRMED.
     */
    public void confirm() {
        if (this.state == RaidState.ACTIVE) {
            this.state = RaidState.CONFIRMED;
        }
    }

    /**
     * Update state to EXPIRED.
     */
    public void expire() {
        if (this.state.isTracked()) {
            this.state = RaidState.EXPIRED;
        }
    }

    /**
     * Update state to INVALIDATED.
     */
    public void invalidate() {
        if (this.state.isTracked()) {
            this.state = RaidState.INVALIDATED;
        }
    }

    /**
     * Mark displacement confirmed.
     */
    public void setDisplacementConfirmed(boolean confirmed) {
        this.hasDisplacement = confirmed;
        if (confirmed && this.state == RaidState.ACTIVE) {
            confirm();
        }
    }

    /**
     * Mark MSS confirmed.
     */
    public void setMssConfirmed(boolean confirmed) {
        this.hasMss = confirmed;
        if (confirmed && this.state == RaidState.ACTIVE) {
            confirm();
        }
    }

    /**
     * Mark FVG formed.
     */
    public void setFvgFormed(boolean formed) {
        this.hasFvgFormed = formed;
    }

    /**
     * Mark SMT confirmed.
     */
    public void setSmtConfirmed(boolean confirmed) {
        this.hasSmtConfirmation = confirmed;
    }

    // ═══════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Check if this raid is still valid for trade entry.
     */
    public boolean isValidForEntry() {
        return state.allowsEntry();
    }

    /**
     * Get the expected trade direction based on this raid.
     */
    public boolean expectsBullish() {
        return direction.expectsBullish();
    }

    /**
     * Get quality classification.
     */
    public String getQualityClassification() {
        if (qualityScore >= 8) return "Elite";
        if (qualityScore >= 6) return "Premium";
        if (qualityScore >= 4) return "Standard";
        return "Low Quality";
    }

    /**
     * Get confirmation count.
     */
    public int getConfirmationCount() {
        int count = 0;
        if (hasDisplacement) count++;
        if (hasMss) count++;
        if (hasFvgFormed) count++;
        if (hasSmtConfirmation) count++;
        return count;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LiquidityRaid that = (LiquidityRaid) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format("LiquidityRaid{%s %s @ %s, score=%d (%s), state=%s, bars=%d}",
                instrument, direction.getDisplayName(), targetLevel.getType().getDisplayName(),
                qualityScore, getQualityClassification(), state.name(), barsSinceRaid);
    }
}
