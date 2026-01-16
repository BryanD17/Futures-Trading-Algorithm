package com.topstep.trading.chartstate;

import java.time.Instant;
import java.util.*;

/**
 * Detects equal highs and equal lows (liquidity pools).
 *
 * Equal highs/lows are swing points that form at similar price levels,
 * creating clusters of stop losses that become attractive targets for
 * institutional liquidity raids.
 *
 * DETECTION METHOD:
 * 1. Identify swing highs/lows using fractal detection (N bars on each side)
 * 2. Cluster swings that are within tolerance of each other
 * 3. Score clusters by size (more touches = stronger level)
 * 4. Register qualifying clusters as equal highs/lows
 *
 * CLUSTER REQUIREMENTS:
 * - Minimum 2 swings to form an equal level
 * - 3+ swings is a "strong" equal level (higher quality score)
 * - Swings must be within tolerance ticks of each other
 *
 * REFRESH LOGIC:
 * Equal levels are recalculated periodically (not every candle) to balance
 * accuracy with performance. Typically refreshed every 15-30 minutes.
 */
public class EqualLevelDetector {

    private static final int DEFAULT_LOOKBACK = 200;     // Bars to scan for swings
    private static final int DEFAULT_SWING_SIZE = 3;     // Bars on each side for fractal
    private static final int MIN_CLUSTER_SIZE = 2;       // Minimum swings for equal level

    private final String symbol;
    private final CandleSeries candleSeries;
    private final InstrumentRaidConfig config;

    // Detected equal levels
    private final List<EqualLevel> equalHighs = new ArrayList<>();
    private final List<EqualLevel> equalLows = new ArrayList<>();

    // Last refresh time (to avoid recalculating every candle)
    private Instant lastRefreshTime;
    private static final long REFRESH_INTERVAL_MS = 15 * 60 * 1000; // 15 minutes

    public EqualLevelDetector(String symbol, CandleSeries candleSeries) {
        this.symbol = symbol;
        this.candleSeries = candleSeries;
        this.config = InstrumentRaidConfig.forSymbol(symbol);
    }

    // ═══════════════════════════════════════════════════════════════════
    // DETECTION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Refresh equal level detection.
     * Called periodically or when levels may have changed.
     */
    public synchronized void refresh(Instant currentTime) {
        // Check if refresh is needed
        if (lastRefreshTime != null) {
            long elapsed = currentTime.toEpochMilli() - lastRefreshTime.toEpochMilli();
            if (elapsed < REFRESH_INTERVAL_MS) {
                return; // Too soon to refresh
            }
        }

        // Clear old levels
        equalHighs.clear();
        equalLows.clear();

        // Detect swing highs and lows
        List<double[]> swingHighs = candleSeries.detectSwingHighs(DEFAULT_LOOKBACK, DEFAULT_SWING_SIZE);
        List<double[]> swingLows = candleSeries.detectSwingLows(DEFAULT_LOOKBACK, DEFAULT_SWING_SIZE);

        // Cluster swing highs
        List<EqualLevel> highClusters = clusterSwings(swingHighs, true);
        equalHighs.addAll(highClusters);

        // Cluster swing lows
        List<EqualLevel> lowClusters = clusterSwings(swingLows, false);
        equalLows.addAll(lowClusters);

        lastRefreshTime = currentTime;
    }

    /**
     * Force refresh regardless of time interval.
     */
    public synchronized void forceRefresh(Instant currentTime) {
        lastRefreshTime = null;
        refresh(currentTime);
    }

    /**
     * Cluster swings that are within tolerance of each other.
     */
    private List<EqualLevel> clusterSwings(List<double[]> swings, boolean isHighs) {
        List<EqualLevel> clusters = new ArrayList<>();
        if (swings.isEmpty()) return clusters;

        double tolerance = config.getToleranceTicks() * config.getTickSize();

        // Sort by price for clustering
        swings.sort(Comparator.comparingDouble(s -> s[0]));

        // Group into clusters
        List<List<double[]>> clusterGroups = new ArrayList<>();
        List<double[]> currentCluster = new ArrayList<>();
        currentCluster.add(swings.get(0));

        for (int i = 1; i < swings.size(); i++) {
            double[] prev = swings.get(i - 1);
            double[] curr = swings.get(i);

            if (Math.abs(curr[0] - prev[0]) <= tolerance) {
                // Within tolerance - add to current cluster
                currentCluster.add(curr);
            } else {
                // Start new cluster
                if (currentCluster.size() >= MIN_CLUSTER_SIZE) {
                    clusterGroups.add(currentCluster);
                }
                currentCluster = new ArrayList<>();
                currentCluster.add(curr);
            }
        }

        // Don't forget the last cluster
        if (currentCluster.size() >= MIN_CLUSTER_SIZE) {
            clusterGroups.add(currentCluster);
        }

        // Convert cluster groups to EqualLevel objects
        for (List<double[]> group : clusterGroups) {
            double avgPrice = group.stream().mapToDouble(s -> s[0]).average().orElse(0);
            int minBarsAgo = (int) group.stream().mapToDouble(s -> s[1]).min().orElse(0);
            int maxBarsAgo = (int) group.stream().mapToDouble(s -> s[1]).max().orElse(0);

            EqualLevel level = new EqualLevel(
                    avgPrice,
                    group.size(),
                    minBarsAgo,
                    maxBarsAgo,
                    isHighs
            );
            clusters.add(level);
        }

        // Sort by cluster size (strongest first)
        clusters.sort((a, b) -> Integer.compare(b.getClusterSize(), a.getClusterSize()));

        return clusters;
    }

    // ═══════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get all detected equal highs.
     */
    public synchronized List<EqualLevel> getEqualHighs() {
        return new ArrayList<>(equalHighs);
    }

    /**
     * Get all detected equal lows.
     */
    public synchronized List<EqualLevel> getEqualLows() {
        return new ArrayList<>(equalLows);
    }

    /**
     * Get the strongest equal high (largest cluster).
     */
    public synchronized Optional<EqualLevel> getStrongestEqualHigh() {
        return equalHighs.isEmpty() ? Optional.empty() : Optional.of(equalHighs.get(0));
    }

    /**
     * Get the strongest equal low (largest cluster).
     */
    public synchronized Optional<EqualLevel> getStrongestEqualLow() {
        return equalLows.isEmpty() ? Optional.empty() : Optional.of(equalLows.get(0));
    }

    /**
     * Get equal highs above a price.
     */
    public synchronized List<EqualLevel> getEqualHighsAbove(double price) {
        List<EqualLevel> result = new ArrayList<>();
        for (EqualLevel level : equalHighs) {
            if (level.getPrice() > price) {
                result.add(level);
            }
        }
        return result;
    }

    /**
     * Get equal lows below a price.
     */
    public synchronized List<EqualLevel> getEqualLowsBelow(double price) {
        List<EqualLevel> result = new ArrayList<>();
        for (EqualLevel level : equalLows) {
            if (level.getPrice() < price) {
                result.add(level);
            }
        }
        return result;
    }

    /**
     * Get the nearest equal high above current price.
     */
    public synchronized Optional<EqualLevel> getNearestEqualHighAbove(double price) {
        EqualLevel nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (EqualLevel level : equalHighs) {
            if (level.getPrice() > price) {
                double dist = level.getPrice() - price;
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = level;
                }
            }
        }

        return Optional.ofNullable(nearest);
    }

    /**
     * Get the nearest equal low below current price.
     */
    public synchronized Optional<EqualLevel> getNearestEqualLowBelow(double price) {
        EqualLevel nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (EqualLevel level : equalLows) {
            if (level.getPrice() < price) {
                double dist = price - level.getPrice();
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = level;
                }
            }
        }

        return Optional.ofNullable(nearest);
    }

    /**
     * Check if there's an equal level near a price.
     */
    public synchronized Optional<EqualLevel> getEqualLevelNear(double price) {
        double tolerance = config.getToleranceTicks() * config.getTickSize();

        // Check highs
        for (EqualLevel level : equalHighs) {
            if (Math.abs(level.getPrice() - price) <= tolerance) {
                return Optional.of(level);
            }
        }

        // Check lows
        for (EqualLevel level : equalLows) {
            if (Math.abs(level.getPrice() - price) <= tolerance) {
                return Optional.of(level);
            }
        }

        return Optional.empty();
    }

    /**
     * Check if a level qualifies as "strong" (cluster size >= 3).
     */
    public boolean isStrongLevel(EqualLevel level) {
        return level != null && level.getClusterSize() >= 3;
    }

    public String getSymbol() {
        return symbol;
    }

    public Instant getLastRefreshTime() {
        return lastRefreshTime;
    }

    /**
     * Age all levels and remove expired ones.
     * Call this each candle to keep levels fresh.
     */
    public synchronized void ageAndCleanupLevels() {
        // Age all highs and remove expired
        equalHighs.forEach(EqualLevel::incrementAge);
        equalHighs.removeIf(level -> level.isExpired() || level.isRaided());

        // Age all lows and remove expired
        equalLows.forEach(EqualLevel::incrementAge);
        equalLows.removeIf(level -> level.isExpired() || level.isRaided());
    }

    /**
     * Get only non-stale equal highs (fresher levels).
     */
    public synchronized List<EqualLevel> getFreshEqualHighs() {
        List<EqualLevel> fresh = new ArrayList<>();
        for (EqualLevel level : equalHighs) {
            if (!level.isStale() && !level.isRaided()) {
                fresh.add(level);
            }
        }
        return fresh;
    }

    /**
     * Get only non-stale equal lows (fresher levels).
     */
    public synchronized List<EqualLevel> getFreshEqualLows() {
        List<EqualLevel> fresh = new ArrayList<>();
        for (EqualLevel level : equalLows) {
            if (!level.isStale() && !level.isRaided()) {
                fresh.add(level);
            }
        }
        return fresh;
    }

    /**
     * Get the best equal level near a price, prioritizing freshness.
     */
    public synchronized Optional<EqualLevel> getBestEqualLevelNear(double price) {
        double tolerance = config.getToleranceTicks() * config.getTickSize();
        EqualLevel best = null;
        double bestScore = -1;

        // Check highs
        for (EqualLevel level : equalHighs) {
            if (Math.abs(level.getPrice() - price) <= tolerance && !level.isRaided()) {
                double score = level.getAgeAdjustedScore();
                if (score > bestScore) {
                    bestScore = score;
                    best = level;
                }
            }
        }

        // Check lows
        for (EqualLevel level : equalLows) {
            if (Math.abs(level.getPrice() - price) <= tolerance && !level.isRaided()) {
                double score = level.getAgeAdjustedScore();
                if (score > bestScore) {
                    bestScore = score;
                    best = level;
                }
            }
        }

        return Optional.ofNullable(best);
    }

    @Override
    public String toString() {
        return String.format("EqualLevelDetector{%s: %d highs, %d lows}",
                symbol, equalHighs.size(), equalLows.size());
    }

    // ═══════════════════════════════════════════════════════════════════
    // EQUAL LEVEL DATA CLASS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Represents a detected equal high or equal low.
     */
    public static class EqualLevel {
        private final double price;          // Average price of cluster
        private final int clusterSize;       // Number of swings in cluster
        private final int newestBarsAgo;     // Most recent swing (bars ago)
        private final int oldestBarsAgo;     // Oldest swing (bars ago)
        private final boolean isHigh;        // true = equal high, false = equal low
        private boolean raided;              // Has been swept
        private Instant createdAt;           // When this level was detected
        private int barsSinceCreation;       // Track staleness

        // Expiration configuration
        private static final int MAX_BARS_AGE = 500;     // Expire after 500 bars (~8 hours of 1m data)
        private static final int STALE_BARS_AGE = 200;   // Consider stale after 200 bars

        public EqualLevel(double price, int clusterSize, int newestBarsAgo, int oldestBarsAgo, boolean isHigh) {
            this.price = price;
            this.clusterSize = clusterSize;
            this.newestBarsAgo = newestBarsAgo;
            this.oldestBarsAgo = oldestBarsAgo;
            this.isHigh = isHigh;
            this.raided = false;
            this.createdAt = Instant.now();
            this.barsSinceCreation = 0;
        }

        public double getPrice() { return price; }
        public int getClusterSize() { return clusterSize; }
        public int getNewestBarsAgo() { return newestBarsAgo; }
        public int getOldestBarsAgo() { return oldestBarsAgo; }
        public boolean isHigh() { return isHigh; }
        public boolean isRaided() { return raided; }
        public Instant getCreatedAt() { return createdAt; }
        public int getBarsSinceCreation() { return barsSinceCreation; }

        public void markRaided() {
            this.raided = true;
        }

        /**
         * Increment age counter (called each candle).
         */
        public void incrementAge() {
            this.barsSinceCreation++;
        }

        /**
         * Check if this level has expired (too old to be relevant).
         */
        public boolean isExpired() {
            return barsSinceCreation > MAX_BARS_AGE;
        }

        /**
         * Check if this level is stale (still valid but aging).
         */
        public boolean isStale() {
            return barsSinceCreation > STALE_BARS_AGE;
        }

        /**
         * Check if this level is fresh (recently formed).
         */
        public boolean isFresh() {
            return barsSinceCreation < 50;  // Less than 50 bars old
        }

        public LevelType getLevelType() {
            return isHigh ? LevelType.EQUAL_HIGH : LevelType.EQUAL_LOW;
        }

        /**
         * Get quality bonus based on cluster size and freshness.
         * cluster=2: +0 (base), cluster=3: +1, cluster=4+: +2
         * Fresh levels get additional bonus.
         */
        public int getQualityBonus() {
            int bonus = 0;

            // Cluster size bonus
            if (clusterSize >= 4) bonus += 2;
            else if (clusterSize >= 3) bonus += 1;

            // Freshness bonus
            if (isFresh()) bonus += 1;

            // Staleness penalty
            if (isStale()) bonus -= 1;

            return Math.max(0, bonus);
        }

        /**
         * Get age-adjusted quality score for sorting/ranking.
         */
        public double getAgeAdjustedScore() {
            double baseScore = clusterSize;

            // Decay factor based on age
            double ageFactor = Math.max(0.3, 1.0 - (barsSinceCreation / (double) MAX_BARS_AGE));

            return baseScore * ageFactor;
        }

        @Override
        public String toString() {
            String freshness = isFresh() ? "FRESH" : (isStale() ? "STALE" : "NORMAL");
            return String.format("EqualLevel{%s @ %.2f, cluster=%d, bars=%d-%d, age=%d (%s)%s}",
                    isHigh ? "HIGH" : "LOW", price, clusterSize, newestBarsAgo, oldestBarsAgo,
                    barsSinceCreation, freshness,
                    raided ? ", RAIDED" : "");
        }
    }
}
