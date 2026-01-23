package com.topstep.trading.news.impact;

import com.topstep.trading.news.MacroNewsConfig;
import com.topstep.trading.news.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Calculates surprise scores from economic releases.
 * The surprise is normalized using historical standard deviations for each category.
 */
public class SurpriseCalculator {

    private static final Logger log = LoggerFactory.getLogger(SurpriseCalculator.class);

    // Historical standard deviations for normalizing surprises
    // These should be calibrated from historical data
    private static final Map<EventCategory, Double> CATEGORY_STDEV = Map.of(
        EventCategory.INFLATION, 0.2,        // CPI typically varies by ~0.2%
        EventCategory.EMPLOYMENT, 50.0,      // NFP varies by ~50K
        EventCategory.GDP_GROWTH, 0.5,       // GDP varies by ~0.5%
        EventCategory.PMI_SURVEYS, 2.0,      // PMI varies by ~2 points
        EventCategory.CONSUMER, 5.0,         // Retail sales varies by ~5%
        EventCategory.CENTRAL_BANK, 0.25,    // Rate decisions by 25bps
        EventCategory.ENERGY, 3.0,           // EIA inventory varies by ~3M barrels
        EventCategory.HOUSING, 10.0,         // Housing starts varies by ~10%
        EventCategory.TRADE, 5.0,            // Trade balance varies by ~$5B
        EventCategory.OTHER, 1.0
    );

    // Indicators where lower is better (for the currency)
    private static final String[] LOWER_IS_BETTER = {
        "unemployment",
        "jobless",
        "initial claims",
        "inventory",    // For EIA, build is bad
        "stocks"        // For EIA crude stocks
    };

    private final MacroNewsConfig config;

    public SurpriseCalculator() {
        this(MacroNewsConfig.defaults());
    }

    public SurpriseCalculator(MacroNewsConfig config) {
        this.config = config;
    }

    /**
     * Calculate surprise metrics for a release.
     * Sets the surprise, surpriseZScore, and direction fields on the release.
     *
     * @param release The release to calculate surprise for
     */
    public void calculateSurprise(EconomicRelease release) {
        EconomicEvent event = release.getEvent();
        Double actual = release.getActual();
        Double forecast = event.getForecast();

        if (actual == null || forecast == null) {
            // Can't calculate surprise without both values
            release.setSurpriseDirection(SurpriseDirection.INLINE);
            release.setSurprise(0.0);
            release.setSurpriseZScore(0.0);
            log.debug("No surprise calculation for {}: actual={}, forecast={}",
                    event.getName(), actual, forecast);
            return;
        }

        // Calculate raw surprise
        double surprise = actual - forecast;
        release.setSurprise(surprise);

        // Calculate z-score
        double stdev = CATEGORY_STDEV.getOrDefault(event.getCategory(), 1.0);
        double zScore = surprise / stdev;
        release.setSurpriseZScore(zScore);

        // Determine direction
        double threshold = config.getInlineSurpriseThreshold();
        if (Math.abs(zScore) < threshold) {
            release.setSurpriseDirection(SurpriseDirection.INLINE);
        } else if (isBetterThanExpected(event, surprise)) {
            release.setSurpriseDirection(SurpriseDirection.BETTER_THAN_EXPECTED);
        } else {
            release.setSurpriseDirection(SurpriseDirection.WORSE_THAN_EXPECTED);
        }

        log.debug("Calculated surprise for {}: actual={}, forecast={}, surprise={}, zScore={:.2f}, direction={}",
                event.getName(), actual, forecast, surprise, zScore, release.getSurpriseDirection());
    }

    /**
     * Calculate a quick surprise score without modifying the release.
     *
     * @param event The economic event
     * @param actual The actual value
     * @return The z-score of the surprise
     */
    public double calculateZScore(EconomicEvent event, double actual) {
        Double forecast = event.getForecast();
        if (forecast == null) {
            return 0.0;
        }

        double surprise = actual - forecast;
        double stdev = CATEGORY_STDEV.getOrDefault(event.getCategory(), 1.0);
        return surprise / stdev;
    }

    /**
     * Determine if a surprise is "better" for the currency.
     * Higher is better for most metrics, but not all.
     *
     * @param event The economic event
     * @param surprise The raw surprise value (actual - forecast)
     * @return true if the surprise is positive for the currency
     */
    public boolean isBetterThanExpected(EconomicEvent event, double surprise) {
        String name = event.getName().toLowerCase();

        // Check if this is an indicator where lower is better
        boolean lowerIsBetter = false;
        for (String indicator : LOWER_IS_BETTER) {
            if (name.contains(indicator)) {
                lowerIsBetter = true;
                break;
            }
        }

        if (lowerIsBetter) {
            return surprise < 0;  // Negative surprise (lower actual) is better
        }
        return surprise > 0;  // Positive surprise (higher actual) is better
    }

    /**
     * Get the historical standard deviation for a category.
     * Useful for custom calculations.
     *
     * @param category The event category
     * @return The standard deviation value
     */
    public double getCategoryStdev(EventCategory category) {
        return CATEGORY_STDEV.getOrDefault(category, 1.0);
    }

    /**
     * Check if an event name indicates lower is better.
     *
     * @param eventName The event name
     * @return true if lower values are considered better
     */
    public boolean isLowerBetter(String eventName) {
        String lower = eventName.toLowerCase();
        for (String indicator : LOWER_IS_BETTER) {
            if (lower.contains(indicator)) {
                return true;
            }
        }
        return false;
    }
}
