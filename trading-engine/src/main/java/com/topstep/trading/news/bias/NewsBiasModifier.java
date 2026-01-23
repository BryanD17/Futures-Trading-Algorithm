package com.topstep.trading.news.bias;

import com.topstep.trading.news.MacroNewsConfig;
import com.topstep.trading.news.impact.InstrumentNewsMapper;
import com.topstep.trading.news.impact.NewsImpactModel;
import com.topstep.trading.news.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Modifies trading bias based on recent news impulses.
 * This integrates with the existing HTF bias system.
 */
public class NewsBiasModifier {

    private static final Logger log = LoggerFactory.getLogger(NewsBiasModifier.class);

    private final NewsImpactModel impactModel;
    private final InstrumentNewsMapper mapper;
    private final MacroNewsConfig config;

    public NewsBiasModifier(NewsImpactModel impactModel,
                            InstrumentNewsMapper mapper,
                            MacroNewsConfig config) {
        this.impactModel = Objects.requireNonNull(impactModel, "impactModel cannot be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    /**
     * Get bias modifier for an instrument based on recent news.
     *
     * @param instrument The instrument to check
     * @param now Current time
     * @return Value from -1.0 (strongly bearish) to +1.0 (strongly bullish)
     */
    public double getBiasModifier(String instrument, Instant now) {
        List<NewsImpulse> impulses = impactModel.getActiveImpulsesForInstrument(instrument, now);

        if (impulses.isEmpty()) {
            return 0.0;  // No active news bias
        }

        double totalModifier = 0.0;

        for (NewsImpulse impulse : impulses) {
            EconomicEvent event = impulse.getRelease().getEvent();

            double relevance = mapper.getRelevance(event, instrument);
            int sign = mapper.getDirectionalSign(event, instrument);
            double decayedMagnitude = impulse.getCurrentMagnitude(now);

            // Modifier = relevance × direction × magnitude
            double contribution = relevance * sign * decayedMagnitude;
            totalModifier += contribution;

            log.debug("Bias contribution from {}: relevance={:.2f}, sign={}, magnitude={:.3f}, contribution={:.3f}",
                    event.getName(), relevance, sign, decayedMagnitude, contribution);
        }

        // Clamp to [-1, 1]
        double clampedModifier = Math.max(-1.0, Math.min(1.0, totalModifier));

        log.debug("Total bias modifier for {}: {:.3f} (from {} impulses)",
                instrument, clampedModifier, impulses.size());

        return clampedModifier;
    }

    /**
     * Get bias modifier using current time.
     */
    public double getBiasModifier(String instrument) {
        return getBiasModifier(instrument, Instant.now());
    }

    /**
     * Check if news bias aligns with technical bias.
     * This is the primary method for strategy integration.
     *
     * @param instrument Trading instrument
     * @param technicalBullish True if technical analysis suggests bullish
     * @param now Current time
     * @return Alignment status for confluence scoring
     */
    public MacroAlignment checkAlignment(String instrument, boolean technicalBullish, Instant now) {
        double newsBias = getBiasModifier(instrument, now);

        double alignmentThreshold = config.getAlignmentThreshold();
        double strongOppositionThreshold = config.getStrongOppositionThreshold();

        if (technicalBullish) {
            if (newsBias >= alignmentThreshold) {
                log.debug("Macro ALIGNED: {} bullish technical with {:.2f} news bias",
                        instrument, newsBias);
                return MacroAlignment.ALIGNED;
            } else if (newsBias <= -strongOppositionThreshold) {
                log.debug("Macro OPPOSING: {} bullish technical with {:.2f} news bias",
                        instrument, newsBias);
                return MacroAlignment.OPPOSING;
            }
        } else {
            // Technical is bearish
            if (newsBias <= -alignmentThreshold) {
                log.debug("Macro ALIGNED: {} bearish technical with {:.2f} news bias",
                        instrument, newsBias);
                return MacroAlignment.ALIGNED;
            } else if (newsBias >= strongOppositionThreshold) {
                log.debug("Macro OPPOSING: {} bearish technical with {:.2f} news bias",
                        instrument, newsBias);
                return MacroAlignment.OPPOSING;
            }
        }

        log.debug("Macro NEUTRAL: {} technical {} with {:.2f} news bias",
                instrument, technicalBullish ? "bullish" : "bearish", newsBias);
        return MacroAlignment.NEUTRAL;
    }

    /**
     * Check alignment using current time.
     */
    public MacroAlignment checkAlignment(String instrument, boolean technicalBullish) {
        return checkAlignment(instrument, technicalBullish, Instant.now());
    }

    /**
     * Check if news bias should block the trade entirely.
     * This is a stronger check than OPPOSING alignment.
     *
     * @param instrument Trading instrument
     * @param technicalBullish True if technical suggests bullish
     * @param now Current time
     * @return true if trade should be blocked due to strong opposing news
     */
    public boolean shouldBlockOnOpposition(String instrument, boolean technicalBullish, Instant now) {
        double newsBias = getBiasModifier(instrument, now);
        double blockThreshold = config.getBlockingOppositionThreshold();

        if (technicalBullish && newsBias <= -blockThreshold) {
            log.info("Strong opposing news bias ({:.2f}) blocking bullish trade on {}",
                    newsBias, instrument);
            return true;
        }

        if (!technicalBullish && newsBias >= blockThreshold) {
            log.info("Strong opposing news bias ({:.2f}) blocking bearish trade on {}",
                    newsBias, instrument);
            return true;
        }

        return false;
    }

    /**
     * Get detailed bias breakdown for logging/dashboard.
     *
     * @param instrument The instrument to analyze
     * @param now Current time
     * @return Detailed breakdown of bias contributions
     */
    public NewsBiasBreakdown getBiasBreakdown(String instrument, Instant now) {
        List<NewsImpulse> impulses = impactModel.getActiveImpulsesForInstrument(instrument, now);
        double totalBias = getBiasModifier(instrument, now);

        List<ImpulseContribution> contributions = impulses.stream()
            .map(imp -> {
                EconomicEvent event = imp.getRelease().getEvent();
                double contribution = mapper.getRelevance(event, instrument)
                                    * mapper.getDirectionalSign(event, instrument)
                                    * imp.getCurrentMagnitude(now);
                return new ImpulseContribution(
                    event.getName(),
                    event.getCurrency(),
                    contribution,
                    imp.getRelease().getSurpriseDirection()
                );
            })
            .sorted(Comparator.comparing(c -> -Math.abs(c.getContribution())))
            .collect(Collectors.toList());

        return new NewsBiasBreakdown(instrument, totalBias, contributions);
    }

    /**
     * Get bias breakdown using current time.
     */
    public NewsBiasBreakdown getBiasBreakdown(String instrument) {
        return getBiasBreakdown(instrument, Instant.now());
    }

    /**
     * Get a simple text description of the current bias.
     */
    public String getBiasDescription(String instrument) {
        double bias = getBiasModifier(instrument);
        if (Math.abs(bias) < 0.1) {
            return "NEUTRAL";
        } else if (bias > 0.5) {
            return "STRONGLY BULLISH";
        } else if (bias > 0.3) {
            return "MODERATELY BULLISH";
        } else if (bias > 0) {
            return "SLIGHTLY BULLISH";
        } else if (bias < -0.5) {
            return "STRONGLY BEARISH";
        } else if (bias < -0.3) {
            return "MODERATELY BEARISH";
        } else {
            return "SLIGHTLY BEARISH";
        }
    }
}
