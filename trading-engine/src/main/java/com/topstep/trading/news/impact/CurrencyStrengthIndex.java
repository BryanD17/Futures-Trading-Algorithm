package com.topstep.trading.news.impact;

import com.topstep.trading.news.model.Currency;
import com.topstep.trading.news.model.NewsImpulse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks currency strength based on accumulated news impulses.
 * Provides relative strength comparisons between currencies.
 */
public class CurrencyStrengthIndex {

    private static final Logger log = LoggerFactory.getLogger(CurrencyStrengthIndex.class);

    private final NewsImpactModel impactModel;

    // Cached strength values for efficiency
    private final Map<Currency, Double> cachedStrength = new ConcurrentHashMap<>();
    private Instant lastCalculation;
    private static final Duration CACHE_DURATION = Duration.ofSeconds(10);

    public CurrencyStrengthIndex(NewsImpactModel impactModel) {
        this.impactModel = Objects.requireNonNull(impactModel, "impactModel cannot be null");
    }

    /**
     * Get the strength index for a currency.
     * Positive = strong, Negative = weak.
     *
     * @param currency The currency to check
     * @return Strength index from -1.0 to +1.0
     */
    public double getStrength(Currency currency) {
        return getStrength(currency, Instant.now());
    }

    /**
     * Get the strength index for a currency at a specific time.
     *
     * @param currency The currency to check
     * @param now The time to evaluate at
     * @return Strength index from -1.0 to +1.0
     */
    public double getStrength(Currency currency, Instant now) {
        refreshIfNeeded(now);
        return cachedStrength.getOrDefault(currency, 0.0);
    }

    /**
     * Get relative strength between two currencies.
     * Positive means currency1 is stronger than currency2.
     *
     * @param currency1 First currency
     * @param currency2 Second currency
     * @return Relative strength difference
     */
    public double getRelativeStrength(Currency currency1, Currency currency2) {
        return getStrength(currency1) - getStrength(currency2);
    }

    /**
     * Get all currencies ranked by strength (strongest first).
     *
     * @return List of currencies in strength order
     */
    public List<Currency> getRankedCurrencies() {
        Instant now = Instant.now();
        refreshIfNeeded(now);

        List<Currency> ranked = new ArrayList<>(Arrays.asList(Currency.values()));
        ranked.sort((a, b) -> Double.compare(getStrength(b, now), getStrength(a, now)));
        return ranked;
    }

    /**
     * Get a strength snapshot for all currencies.
     *
     * @return Map of currency to strength value
     */
    public Map<Currency, Double> getStrengthSnapshot() {
        Instant now = Instant.now();
        refreshIfNeeded(now);
        return new HashMap<>(cachedStrength);
    }

    /**
     * Get the strongest currency currently.
     *
     * @return The strongest currency, or empty if no data
     */
    public Optional<Currency> getStrongestCurrency() {
        refreshIfNeeded(Instant.now());
        return cachedStrength.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey);
    }

    /**
     * Get the weakest currency currently.
     *
     * @return The weakest currency, or empty if no data
     */
    public Optional<Currency> getWeakestCurrency() {
        refreshIfNeeded(Instant.now());
        return cachedStrength.entrySet().stream()
            .min(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey);
    }

    /**
     * Check if a currency is in a strong state (above threshold).
     *
     * @param currency The currency to check
     * @param threshold The strength threshold (e.g., 0.3)
     * @return true if currency strength exceeds threshold
     */
    public boolean isStrong(Currency currency, double threshold) {
        return getStrength(currency) >= threshold;
    }

    /**
     * Check if a currency is in a weak state (below negative threshold).
     *
     * @param currency The currency to check
     * @param threshold The weakness threshold (e.g., -0.3)
     * @return true if currency strength is below threshold
     */
    public boolean isWeak(Currency currency, double threshold) {
        return getStrength(currency) <= threshold;
    }

    /**
     * Force refresh of the cache (useful for testing).
     */
    public void forceRefresh() {
        calculateAllStrengths(Instant.now());
    }

    /**
     * Clear the cache.
     */
    public void clearCache() {
        cachedStrength.clear();
        lastCalculation = null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private Methods
    // ═══════════════════════════════════════════════════════════════════════

    private void refreshIfNeeded(Instant now) {
        if (lastCalculation == null ||
            Duration.between(lastCalculation, now).compareTo(CACHE_DURATION) > 0) {
            calculateAllStrengths(now);
        }
    }

    private void calculateAllStrengths(Instant now) {
        for (Currency currency : Currency.values()) {
            double strength = impactModel.getCurrencyImpulse(currency, now);
            // Clamp to [-1, 1]
            strength = Math.max(-1.0, Math.min(1.0, strength));
            cachedStrength.put(currency, strength);
        }
        lastCalculation = now;

        log.debug("Refreshed currency strength index at {}", now);
    }
}
