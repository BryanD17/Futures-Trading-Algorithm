package com.topstep.trading.news.bias;

import com.topstep.trading.news.MacroNewsConfig;
import com.topstep.trading.news.model.MacroAlignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;

/**
 * Calculates confluence score adjustments based on macro alignment.
 * Used by the strategy to adjust the total confluence score.
 */
public class MacroAlignmentScorer {

    private static final Logger log = LoggerFactory.getLogger(MacroAlignmentScorer.class);

    private final NewsBiasModifier biasModifier;
    private final MacroNewsConfig config;

    // Default score adjustments
    private int alignedBonus = 1;
    private int neutralAdjustment = 0;
    private int opposingPenalty = -1;

    public MacroAlignmentScorer(NewsBiasModifier biasModifier, MacroNewsConfig config) {
        this.biasModifier = Objects.requireNonNull(biasModifier, "biasModifier cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    /**
     * Get the confluence score adjustment for an instrument and direction.
     *
     * @param instrument The trading instrument
     * @param technicalBullish Whether technical analysis suggests bullish
     * @return Score adjustment to apply to confluence
     */
    public int getScoreAdjustment(String instrument, boolean technicalBullish) {
        return getScoreAdjustment(instrument, technicalBullish, Instant.now());
    }

    /**
     * Get the confluence score adjustment at a specific time.
     *
     * @param instrument The trading instrument
     * @param technicalBullish Whether technical analysis suggests bullish
     * @param now Current time
     * @return Score adjustment to apply to confluence
     */
    public int getScoreAdjustment(String instrument, boolean technicalBullish, Instant now) {
        MacroAlignment alignment = biasModifier.checkAlignment(instrument, technicalBullish, now);

        int adjustment = switch (alignment) {
            case ALIGNED -> alignedBonus;
            case NEUTRAL -> neutralAdjustment;
            case OPPOSING -> opposingPenalty;
        };

        log.debug("Macro score adjustment for {} ({}): {} -> {}",
                instrument, technicalBullish ? "LONG" : "SHORT", alignment, adjustment);

        return adjustment;
    }

    /**
     * Get the alignment and score as a combined result.
     *
     * @param instrument The trading instrument
     * @param technicalBullish Whether technical analysis suggests bullish
     * @return Combined alignment result
     */
    public AlignmentResult getAlignmentResult(String instrument, boolean technicalBullish) {
        return getAlignmentResult(instrument, technicalBullish, Instant.now());
    }

    /**
     * Get the alignment and score at a specific time.
     */
    public AlignmentResult getAlignmentResult(String instrument, boolean technicalBullish, Instant now) {
        MacroAlignment alignment = biasModifier.checkAlignment(instrument, technicalBullish, now);
        double bias = biasModifier.getBiasModifier(instrument, now);
        int scoreAdjustment = getScoreAdjustment(instrument, technicalBullish, now);
        boolean shouldBlock = biasModifier.shouldBlockOnOpposition(instrument, technicalBullish, now);

        return new AlignmentResult(alignment, bias, scoreAdjustment, shouldBlock);
    }

    /**
     * Check if a trade should proceed based on macro alignment.
     * Returns false if the trade should be blocked.
     *
     * @param instrument The trading instrument
     * @param technicalBullish Whether technical analysis suggests bullish
     * @param minimumConfluence Minimum required confluence score
     * @param currentConfluence Current confluence score before adjustment
     * @return true if trade should proceed
     */
    public boolean shouldProceed(String instrument, boolean technicalBullish,
                                  int minimumConfluence, int currentConfluence) {
        AlignmentResult result = getAlignmentResult(instrument, technicalBullish);

        if (result.shouldBlock()) {
            log.info("Trade blocked by strong macro opposition: {} {} with bias {:.2f}",
                    instrument, technicalBullish ? "LONG" : "SHORT", result.getBias());
            return false;
        }

        int adjustedConfluence = currentConfluence + result.getScoreAdjustment();
        if (adjustedConfluence < minimumConfluence) {
            log.debug("Adjusted confluence {} below minimum {}: {} {} (adjustment {})",
                    adjustedConfluence, minimumConfluence,
                    instrument, technicalBullish ? "LONG" : "SHORT",
                    result.getScoreAdjustment());
            return false;
        }

        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Configuration
    // ═══════════════════════════════════════════════════════════════════════

    public MacroAlignmentScorer setAlignedBonus(int bonus) {
        this.alignedBonus = bonus;
        return this;
    }

    public MacroAlignmentScorer setNeutralAdjustment(int adjustment) {
        this.neutralAdjustment = adjustment;
        return this;
    }

    public MacroAlignmentScorer setOpposingPenalty(int penalty) {
        this.opposingPenalty = penalty;
        return this;
    }

    public int getAlignedBonus() {
        return alignedBonus;
    }

    public int getNeutralAdjustment() {
        return neutralAdjustment;
    }

    public int getOpposingPenalty() {
        return opposingPenalty;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Result Class
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Combined result of macro alignment analysis.
     */
    public static class AlignmentResult {
        private final MacroAlignment alignment;
        private final double bias;
        private final int scoreAdjustment;
        private final boolean shouldBlock;

        public AlignmentResult(MacroAlignment alignment, double bias, int scoreAdjustment, boolean shouldBlock) {
            this.alignment = alignment;
            this.bias = bias;
            this.scoreAdjustment = scoreAdjustment;
            this.shouldBlock = shouldBlock;
        }

        public MacroAlignment getAlignment() {
            return alignment;
        }

        public double getBias() {
            return bias;
        }

        public int getScoreAdjustment() {
            return scoreAdjustment;
        }

        public boolean shouldBlock() {
            return shouldBlock;
        }

        @Override
        public String toString() {
            return String.format("AlignmentResult{alignment=%s, bias=%.3f, adjustment=%d, block=%s}",
                    alignment, bias, scoreAdjustment, shouldBlock);
        }
    }
}
