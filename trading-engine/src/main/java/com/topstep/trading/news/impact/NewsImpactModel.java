package com.topstep.trading.news.impact;

import com.topstep.trading.news.MacroNewsConfig;
import com.topstep.trading.news.gating.EventProximityChecker;
import com.topstep.trading.news.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Converts economic releases into tradeable impulses.
 * Manages active impulses and their decay over time.
 */
public class NewsImpactModel {

    private static final Logger log = LoggerFactory.getLogger(NewsImpactModel.class);

    private final SurpriseCalculator surpriseCalculator;
    private final InstrumentNewsMapper mapper;
    private final MacroNewsConfig config;
    private final EventProximityChecker proximityChecker;

    // Active impulses per currency
    private final Map<Currency, List<NewsImpulse>> activeImpulses = new ConcurrentHashMap<>();

    public NewsImpactModel(SurpriseCalculator surpriseCalculator,
                           InstrumentNewsMapper mapper,
                           MacroNewsConfig config,
                           EventProximityChecker proximityChecker) {
        this.surpriseCalculator = Objects.requireNonNull(surpriseCalculator, "surpriseCalculator cannot be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.proximityChecker = Objects.requireNonNull(proximityChecker, "proximityChecker cannot be null");
    }

    /**
     * Process a new economic release and generate impulse.
     *
     * @param release The release to process
     * @return The generated impulse, if any
     */
    public Optional<NewsImpulse> processRelease(EconomicRelease release) {
        // Calculate surprise metrics
        surpriseCalculator.calculateSurprise(release);

        // Mark as processed in proximity checker
        proximityChecker.markReleaseProcessed(release.getEvent().getId());

        // Skip if no meaningful surprise
        if (release.getSurpriseDirection() == SurpriseDirection.INLINE) {
            log.info("Release {} in-line with forecast, no impulse generated",
                    release.getEvent().getName());
            return Optional.empty();
        }

        // Calculate impulse
        NewsImpulse impulse = calculateImpulse(release);

        // Store active impulse
        activeImpulses.computeIfAbsent(impulse.getCurrency(), k -> new CopyOnWriteArrayList<>())
                      .add(impulse);

        // Clean up expired impulses
        cleanupExpiredImpulses();

        log.info("Generated impulse for {}: magnitude={:.3f}, confidence={:.2f}, direction={}",
                release.getEvent().getName(),
                impulse.getMagnitude(),
                impulse.getConfidence(),
                release.getSurpriseDirection());

        return Optional.of(impulse);
    }

    /**
     * Calculate impulse from a release.
     */
    private NewsImpulse calculateImpulse(EconomicRelease release) {
        EconomicEvent event = release.getEvent();

        // Base magnitude from z-score, normalized to [-1, 1] using tanh
        double zScore = release.getSurpriseZScore();
        double baseMagnitude = Math.tanh(zScore);  // Soft clip to [-1, 1]

        // Apply sign based on direction (better = positive for currency)
        if (release.getSurpriseDirection() == SurpriseDirection.WORSE_THAN_EXPECTED) {
            baseMagnitude = -Math.abs(baseMagnitude);
        } else {
            baseMagnitude = Math.abs(baseMagnitude);
        }

        // Scale by impact level
        double impactMultiplier = switch (event.getImpact()) {
            case HIGH -> 1.0;
            case MEDIUM -> 0.6;
            case LOW -> 0.3;
        };

        double magnitude = baseMagnitude * impactMultiplier;

        // Confidence based on z-score magnitude
        // Higher absolute z-score = higher confidence
        double confidence = Math.min(0.9, 0.5 + Math.abs(zScore) * 0.2);

        // Decay duration based on impact
        Duration decay = switch (event.getImpact()) {
            case HIGH -> config.getHighImpactDecay();
            case MEDIUM -> config.getMediumImpactDecay();
            case LOW -> config.getLowImpactDecay();
        };

        return new NewsImpulse(
            release,
            event.getCurrency(),
            magnitude,
            confidence,
            Instant.now(),
            decay
        );
    }

    /**
     * Get current aggregate impulse for a currency.
     * Sums all active impulses with decay applied.
     *
     * @param currency The currency to check
     * @param now Current time
     * @return The aggregate impulse value
     */
    public double getCurrencyImpulse(Currency currency, Instant now) {
        List<NewsImpulse> impulses = activeImpulses.getOrDefault(currency, Collections.emptyList());

        return impulses.stream()
            .mapToDouble(imp -> imp.getCurrentMagnitude(now))
            .sum();
    }

    /**
     * Get current aggregate impulse for a currency using current time.
     */
    public double getCurrencyImpulse(Currency currency) {
        return getCurrencyImpulse(currency, Instant.now());
    }

    /**
     * Get all active impulses affecting an instrument.
     * Filters by relevance and non-expired status.
     *
     * @param instrument The instrument to check
     * @param now Current time
     * @return List of active impulses
     */
    public List<NewsImpulse> getActiveImpulsesForInstrument(String instrument, Instant now) {
        return activeImpulses.values().stream()
            .flatMap(List::stream)
            .filter(imp -> Math.abs(imp.getCurrentMagnitude(now)) > 0.05)  // Still active
            .filter(imp -> mapper.getRelevance(imp.getRelease().getEvent(), instrument) > 0.3)
            .collect(Collectors.toList());
    }

    /**
     * Get all active impulses.
     */
    public List<NewsImpulse> getAllActiveImpulses(Instant now) {
        return activeImpulses.values().stream()
            .flatMap(List::stream)
            .filter(imp -> Math.abs(imp.getCurrentMagnitude(now)) > 0.01)
            .collect(Collectors.toList());
    }

    /**
     * Get the number of active impulses per currency.
     */
    public Map<Currency, Integer> getActiveImpulseCounts() {
        Instant now = Instant.now();
        Map<Currency, Integer> counts = new HashMap<>();

        for (Map.Entry<Currency, List<NewsImpulse>> entry : activeImpulses.entrySet()) {
            int count = (int) entry.getValue().stream()
                .filter(imp -> Math.abs(imp.getCurrentMagnitude(now)) > 0.01)
                .count();
            if (count > 0) {
                counts.put(entry.getKey(), count);
            }
        }

        return counts;
    }

    /**
     * Remove fully decayed impulses.
     */
    private void cleanupExpiredImpulses() {
        Instant now = Instant.now();
        int removed = 0;

        for (List<NewsImpulse> impulses : activeImpulses.values()) {
            int before = impulses.size();
            impulses.removeIf(imp -> Math.abs(imp.getCurrentMagnitude(now)) < 0.01);
            removed += (before - impulses.size());
        }

        if (removed > 0) {
            log.debug("Cleaned up {} expired impulses", removed);
        }
    }

    /**
     * Force cleanup of all impulses (useful for testing or reset).
     */
    public void clearAllImpulses() {
        activeImpulses.clear();
        log.info("Cleared all active impulses");
    }

    /**
     * Get the strongest active impulse for an instrument.
     */
    public Optional<NewsImpulse> getStrongestImpulse(String instrument, Instant now) {
        return getActiveImpulsesForInstrument(instrument, now).stream()
            .max(Comparator.comparingDouble(imp -> Math.abs(imp.getCurrentMagnitude(now))));
    }
}
