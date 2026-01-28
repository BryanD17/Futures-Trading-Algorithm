package com.topstep.trading.chartstate;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.KillzoneClock;
import com.topstep.trading.strategy.KillzonePhase;
import com.topstep.trading.strategy.SilverBulletClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Detects liquidity raids on known levels with lifecycle management.
 *
 * A liquidity raid occurs when price sweeps a known level (taking stops)
 * and then shows signs of reversal. This class:
 *
 * 1. DETECTS raids by monitoring candles against known levels
 * 2. SCORES raids using RaidQualityScorer
 * 3. MANAGES raid lifecycle (active → confirmed → expired/invalidated)
 * 4. NOTIFIES listeners when high-quality raids are detected
 *
 * RAID DETECTION CRITERIA:
 * - Price exceeds level by minimum penetration (instrument-specific)
 * - Candle shows rejection (closes back toward level)
 * - Level type has significance (PDH/PDL, session, equal H/L)
 *
 * LIFECYCLE:
 * - ACTIVE: Just detected, waiting for confirmation
 * - CONFIRMED: Displacement, MSS, or FVG confirmed the reversal
 * - EXPIRED: Too many bars passed without entry
 * - INVALIDATED: Price made new extreme (continuation, not reversal)
 *
 * Thread-safe: Uses ConcurrentHashMap for raid storage.
 */
public class RaidDetector {

    private static final Logger log = LoggerFactory.getLogger(RaidDetector.class);

    private final String symbol;
    private final LevelEngine levelEngine;
    private final EqualLevelDetector equalLevelDetector;
    private final CandleSeries candleSeries;
    private final InstrumentRaidConfig config;
    private final RaidQualityScorer scorer;
    private final KillzoneClock killzoneClock;
    private final SilverBulletClock silverBulletClock;

    // Active and recent raids
    private final Map<String, LiquidityRaid> activeRaids = new ConcurrentHashMap<>();
    private final Map<String, LiquidityRaid> recentRaids = new ConcurrentHashMap<>();

    // Event listeners
    private final List<Consumer<LiquidityRaid>> raidListeners = new ArrayList<>();

    // State
    private int currentBarIndex = 0;
    private double lastHigh = 0;
    private double lastLow = Double.MAX_VALUE;

    public RaidDetector(String symbol, LevelEngine levelEngine,
                        EqualLevelDetector equalLevelDetector, CandleSeries candleSeries) {
        this.symbol = symbol;
        this.levelEngine = levelEngine;
        this.equalLevelDetector = equalLevelDetector;
        this.candleSeries = candleSeries;
        this.config = InstrumentRaidConfig.forSymbol(symbol);
        this.scorer = new RaidQualityScorer();
        this.killzoneClock = new KillzoneClock();
        this.silverBulletClock = new SilverBulletClock();
    }

    // ═══════════════════════════════════════════════════════════════════
    // CANDLE PROCESSING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Process a new candle to detect raids.
     * Called each time a new candle closes.
     *
     * @param candle The completed candle
     * @param context Optional context for scoring (SMT, HTF bias)
     */
    public synchronized void processCandle(Candle candle, RaidDetectionContext context) {
        if (candle == null) return;

        Instant timestamp = candle.getTimestamp();
        currentBarIndex++;

        // Update lifecycle of existing raids
        updateRaidLifecycles(candle);

        // Check for new raids on known levels
        checkForRaids(candle, context);

        // Track extremes for invalidation
        if (candle.getHigh() > lastHigh) {
            lastHigh = candle.getHigh();
            invalidateRaidsOnNewHigh(candle.getHigh());
        }
        if (candle.getLow() < lastLow) {
            lastLow = candle.getLow();
            invalidateRaidsOnNewLow(candle.getLow());
        }
    }

    /**
     * Check for raids on all known levels.
     */
    private void checkForRaids(Candle candle, RaidDetectionContext context) {
        Instant timestamp = candle.getTimestamp();

        // Get all unraided levels
        List<KnownLevel> levels = levelEngine.getUnraidedLevels();

        // Also check equal levels
        equalLevelDetector.refresh(timestamp);

        for (KnownLevel level : levels) {
            checkLevelForRaid(candle, level, context);
        }

        // Check equal highs
        for (EqualLevelDetector.EqualLevel eq : equalLevelDetector.getEqualHighs()) {
            if (!eq.isRaided()) {
                KnownLevel kl = new KnownLevel(LevelType.EQUAL_HIGH, eq.getPrice(), timestamp, eq.getClusterSize());
                checkLevelForRaid(candle, kl, context);
                if (kl.isRaided()) {
                    eq.markRaided();
                }
            }
        }

        // Check equal lows
        for (EqualLevelDetector.EqualLevel eq : equalLevelDetector.getEqualLows()) {
            if (!eq.isRaided()) {
                KnownLevel kl = new KnownLevel(LevelType.EQUAL_LOW, eq.getPrice(), timestamp, eq.getClusterSize());
                checkLevelForRaid(candle, kl, context);
                if (kl.isRaided()) {
                    eq.markRaided();
                }
            }
        }
    }

    /**
     * Check if a candle has raided a specific level.
     */
    private void checkLevelForRaid(Candle candle, KnownLevel level, RaidDetectionContext context) {
        if (level.isRaided()) return;

        double levelPrice = level.getPrice();
        double tolerance = config.getTolerancePrice();
        double minPenetration = config.getMinPenetrationPrice();

        RaidDirection direction = null;
        double penetrationPrice = 0;

        if (level.getType().isHigh()) {
            // Check for high sweep
            if (candle.getHigh() > levelPrice + minPenetration) {
                // Swept the high
                direction = RaidDirection.HIGH_SWEEP;
                penetrationPrice = candle.getHigh() - levelPrice;
            }
        } else {
            // Check for low sweep
            if (candle.getLow() < levelPrice - minPenetration) {
                // Swept the low
                direction = RaidDirection.LOW_SWEEP;
                penetrationPrice = levelPrice - candle.getLow();
            }
        }

        if (direction != null) {
            // Check for rejection (candle closes back toward level)
            boolean hasRejection = checkForRejection(candle, levelPrice, direction);

            if (hasRejection) {
                // Create raid
                double penetrationTicks = penetrationPrice / config.getTickSize();
                LiquidityRaid raid = createRaid(candle, level, direction, penetrationTicks);

                // Score the raid
                RaidQualityScorer.RaidScoringContext scoringContext = buildScoringContext(
                        candle.getTimestamp(), context);
                int score = scorer.calculateScore(raid, scoringContext);

                // Check if raid meets quality threshold
                if (score >= config.getMinQualityThreshold()) {
                    // Register the raid
                    activeRaids.put(raid.getId(), raid);
                    level.markRaided(candle.getTimestamp());

                    log.info("[{}] RAID DETECTED: {} @ {} (score={}, {})",
                            symbol, direction.getDisplayName(), level.getType().getDisplayName(),
                            score, raid.getQualityClassification());

                    // Notify listeners
                    notifyRaidListeners(raid);
                } else {
                    log.trace("[{}] Raid below threshold: {} @ {} (score={} < {})",
                            symbol, direction.getDisplayName(), level.getType().getDisplayName(),
                            score, config.getMinQualityThreshold());
                }
            }
        }
    }

    /**
     * Check if candle shows rejection from the level.
     */
    private boolean checkForRejection(Candle candle, double levelPrice, RaidDirection direction) {
        double close = candle.getClose();
        double open = candle.getOpen();
        double high = candle.getHigh();
        double low = candle.getLow();
        double range = high - low;

        if (range <= 0) return false;

        if (direction == RaidDirection.HIGH_SWEEP) {
            // For high sweep, want close below level or significant upper wick
            double upperWick = high - Math.max(open, close);
            double wickRatio = upperWick / range;

            // Rejection if close below level OR wick > 40% of candle
            return close <= levelPrice || wickRatio >= 0.4;
        } else {
            // For low sweep, want close above level or significant lower wick
            double lowerWick = Math.min(open, close) - low;
            double wickRatio = lowerWick / range;

            // Rejection if close above level OR wick > 40% of candle
            return close >= levelPrice || wickRatio >= 0.4;
        }
    }

    /**
     * Create a new LiquidityRaid object.
     */
    private LiquidityRaid createRaid(Candle candle, KnownLevel level, RaidDirection direction, double penetrationTicks) {
        return new LiquidityRaid(
                symbol,
                level,
                direction,
                candle.getTimestamp(),
                currentBarIndex,
                candle.getHigh(),
                candle.getLow(),
                candle.getOpen(),
                candle.getClose(),
                penetrationTicks,
                config.getTickSize()
        );
    }

    /**
     * Build scoring context from detection context.
     */
    private RaidQualityScorer.RaidScoringContext buildScoringContext(Instant timestamp, RaidDetectionContext context) {
        RaidQualityScorer.RaidScoringContext.Builder builder = new RaidQualityScorer.RaidScoringContext.Builder();

        builder.timestamp(timestamp);

        // Killzone info
        boolean inKillzone = killzoneClock.isInKillzone(timestamp);
        String killzoneName = killzoneClock.getCurrentKillzoneName(timestamp);
        KillzonePhase phase = killzoneClock.getCurrentPhase(timestamp);
        builder.killzone(inKillzone, killzoneName, phase);

        // Silver Bullet info
        boolean inSB = silverBulletClock.isInSilverBulletWindow(timestamp);
        String sbName = silverBulletClock.getCurrentWindowName(timestamp);
        builder.silverBullet(inSB, sbName);

        // Session overlap
        builder.sessionOverlap(killzoneClock.isSessionOverlap(timestamp));

        // Context-provided info
        if (context != null) {
            builder.smtDivergence(context.hasSmtDivergence());
            builder.htfBias(context.getHtfBiasBullish());
        }

        return builder.build();
    }

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Update lifecycle of all active raids.
     */
    private void updateRaidLifecycles(Candle candle) {
        Iterator<Map.Entry<String, LiquidityRaid>> it = activeRaids.entrySet().iterator();

        while (it.hasNext()) {
            LiquidityRaid raid = it.next().getValue();
            raid.incrementBarsSinceRaid();

            // Check for expiration
            if (raid.getBarsSinceRaid() > config.getMaxBarsSinceRaid()) {
                raid.expire();
                recentRaids.put(raid.getId(), raid);
                it.remove();
                log.trace("[{}] Raid expired: {}", symbol, raid.getId());
            }
        }

        // Clean up old recent raids (keep last 50)
        if (recentRaids.size() > 50) {
            // Remove oldest
            recentRaids.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getValue().getRaidTime()))
                    .limit(recentRaids.size() - 50)
                    .map(Map.Entry::getKey)
                    .forEach(recentRaids::remove);
        }
    }

    /**
     * Invalidate raids when new high is made.
     */
    private void invalidateRaidsOnNewHigh(double newHigh) {
        for (LiquidityRaid raid : activeRaids.values()) {
            // If this was a high sweep expecting bearish, new high invalidates
            if (raid.getDirection() == RaidDirection.HIGH_SWEEP) {
                if (newHigh > raid.getRaidCandleHigh()) {
                    raid.invalidate();
                    log.trace("[{}] Raid invalidated by new high: {}", symbol, raid.getId());
                }
            }
        }
    }

    /**
     * Invalidate raids when new low is made.
     */
    private void invalidateRaidsOnNewLow(double newLow) {
        for (LiquidityRaid raid : activeRaids.values()) {
            // If this was a low sweep expecting bullish, new low invalidates
            if (raid.getDirection() == RaidDirection.LOW_SWEEP) {
                if (newLow < raid.getRaidCandleLow()) {
                    raid.invalidate();
                    log.trace("[{}] Raid invalidated by new low: {}", symbol, raid.getId());
                }
            }
        }
    }

    /**
     * Confirm a raid (called when displacement/MSS detected).
     */
    public void confirmRaid(String raidId, boolean hasDisplacement, boolean hasMss, boolean hasFvg) {
        LiquidityRaid raid = activeRaids.get(raidId);
        if (raid != null) {
            if (hasDisplacement) raid.setDisplacementConfirmed(true);
            if (hasMss) raid.setMssConfirmed(true);
            if (hasFvg) raid.setFvgFormed(true);

            log.info("[{}] Raid confirmed: {} (displacement={}, mss={}, fvg={})",
                    symbol, raidId, hasDisplacement, hasMss, hasFvg);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get all active raids.
     */
    public synchronized List<LiquidityRaid> getActiveRaids() {
        return new ArrayList<>(activeRaids.values());
    }

    /**
     * Get active raids valid for entry.
     */
    public synchronized List<LiquidityRaid> getEntryValidRaids() {
        List<LiquidityRaid> valid = new ArrayList<>();
        for (LiquidityRaid raid : activeRaids.values()) {
            if (raid.isValidForEntry()) {
                valid.add(raid);
            }
        }
        return valid;
    }

    /**
     * Get confirmed raids (highest probability).
     */
    public synchronized List<LiquidityRaid> getConfirmedRaids() {
        List<LiquidityRaid> confirmed = new ArrayList<>();
        for (LiquidityRaid raid : activeRaids.values()) {
            if (raid.getState() == RaidState.CONFIRMED) {
                confirmed.add(raid);
            }
        }
        return confirmed;
    }

    /**
     * Get the best raid for entry (highest score among valid).
     */
    public synchronized Optional<LiquidityRaid> getBestRaidForEntry() {
        return activeRaids.values().stream()
                .filter(LiquidityRaid::isValidForEntry)
                .max(Comparator.comparingInt(LiquidityRaid::getQualityScore));
    }

    /**
     * Get raid by direction.
     */
    public synchronized Optional<LiquidityRaid> getRaidByDirection(RaidDirection direction) {
        return activeRaids.values().stream()
                .filter(r -> r.getDirection() == direction && r.isValidForEntry())
                .max(Comparator.comparingInt(LiquidityRaid::getQualityScore));
    }

    /**
     * Check if there's an active bullish raid (low sweep).
     */
    public synchronized Optional<LiquidityRaid> getActiveBullishRaid() {
        return getRaidByDirection(RaidDirection.LOW_SWEEP);
    }

    /**
     * Check if there's an active bearish raid (high sweep).
     */
    public synchronized Optional<LiquidityRaid> getActiveBearishRaid() {
        return getRaidByDirection(RaidDirection.HIGH_SWEEP);
    }

    /**
     * Get raid by ID.
     */
    public synchronized Optional<LiquidityRaid> getRaidById(String raidId) {
        LiquidityRaid raid = activeRaids.get(raidId);
        if (raid == null) {
            raid = recentRaids.get(raidId);
        }
        return Optional.ofNullable(raid);
    }

    /**
     * Check if any active raid matches expected direction.
     */
    public synchronized boolean hasActiveRaidForDirection(boolean expectBullish) {
        RaidDirection expected = expectBullish ? RaidDirection.LOW_SWEEP : RaidDirection.HIGH_SWEEP;
        return activeRaids.values().stream()
                .anyMatch(r -> r.getDirection() == expected && r.isValidForEntry());
    }

    /**
     * Get count of active raids.
     */
    public int getActiveRaidCount() {
        return activeRaids.size();
    }

    // ═══════════════════════════════════════════════════════════════════
    // EVENT LISTENERS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Add listener for new raid events.
     */
    public void addRaidListener(Consumer<LiquidityRaid> listener) {
        raidListeners.add(listener);
    }

    /**
     * Remove listener.
     */
    public void removeRaidListener(Consumer<LiquidityRaid> listener) {
        raidListeners.remove(listener);
    }

    private void notifyRaidListeners(LiquidityRaid raid) {
        for (Consumer<LiquidityRaid> listener : raidListeners) {
            try {
                listener.accept(raid);
            } catch (Exception e) {
                log.error("Error notifying raid listener", e);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════

    public String getSymbol() {
        return symbol;
    }

    public void reset() {
        activeRaids.clear();
        recentRaids.clear();
        currentBarIndex = 0;
        lastHigh = 0;
        lastLow = Double.MAX_VALUE;
    }

    @Override
    public String toString() {
        return String.format("RaidDetector{%s: %d active raids}", symbol, activeRaids.size());
    }

    /**
     * Get the best active raid (highest score among all active).
     * This is an alias for getBestRaidForEntry() for API compatibility.
     */
    public synchronized Optional<LiquidityRaid> getBestActiveRaid() {
        return getBestRaidForEntry();
    }

    /**
     * Get active raid by direction.
     * This is an alias for getRaidByDirection() for API compatibility.
     */
    public synchronized Optional<LiquidityRaid> getActiveRaidByDirection(RaidDirection direction) {
        return getRaidByDirection(direction);
    }

    /**
     * Get a summary of all active raids.
     * @return Human-readable summary of active raids
     */
    public synchronized String getRaidsSummary() {
        if (activeRaids.isEmpty()) {
            return "[" + symbol + "] No active raids";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[").append(symbol).append("] Active raids (").append(activeRaids.size()).append("):\n");

        for (LiquidityRaid raid : activeRaids.values()) {
            sb.append("  - ").append(raid.getDirection().getDisplayName())
              .append(" @ ").append(raid.getTargetLevel().getType().getDisplayName())
              .append(" (score=").append(raid.getQualityScore())
              .append(", state=").append(raid.getState())
              .append(", bars=").append(raid.getBarsSinceRaid())
              .append(")\n");
        }

        return sb.toString().trim();
    }

    // ═══════════════════════════════════════════════════════════════════
    // DETECTION CONTEXT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Context provided by the caller for raid detection/scoring.
     * Contains information the detector cannot determine on its own.
     */
    public static class RaidDetectionContext {
        private final boolean hasSmtDivergence;
        private final Boolean htfBiasBullish;  // null = neutral

        public RaidDetectionContext(boolean hasSmtDivergence, Boolean htfBiasBullish) {
            this.hasSmtDivergence = hasSmtDivergence;
            this.htfBiasBullish = htfBiasBullish;
        }

        public boolean hasSmtDivergence() { return hasSmtDivergence; }
        public Boolean getHtfBiasBullish() { return htfBiasBullish; }

        public static RaidDetectionContext withSmt(boolean hasSmt) {
            return new RaidDetectionContext(hasSmt, null);
        }

        public static RaidDetectionContext withHtfBias(Boolean bullish) {
            return new RaidDetectionContext(false, bullish);
        }

        public static RaidDetectionContext full(boolean hasSmt, Boolean htfBullish) {
            return new RaidDetectionContext(hasSmt, htfBullish);
        }

        public static RaidDetectionContext empty() {
            return new RaidDetectionContext(false, null);
        }
    }
}
