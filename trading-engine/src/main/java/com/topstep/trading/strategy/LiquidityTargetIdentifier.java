package com.topstep.trading.strategy;

import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.chartstate.LevelType;

import java.util.*;

/**
 * Liquidity Target Identifier - Determines WHERE the HTF trend is heading.
 *
 * The 15m/30m trend isn't moving randomly — it's heading toward known liquidity
 * pools where resting stop-loss orders accumulate:
 *   - Previous Day High/Low (PDH/PDL)
 *   - Previous Week High/Low (PWH/PWL)
 *   - Session extremes (Asia H/L, London H/L)
 *   - Equal highs/lows
 *
 * This component identifies the PROBABLE DESTINATION of the current trend,
 * which provides:
 *   1. Realistic profit targets (not arbitrary R:R but actual liquidity)
 *   2. Understanding when a move is likely exhausted (target already swept)
 *   3. Filtering out setups that point AWAY from unswept liquidity
 *   4. More aggressive sizing when HTF trend aligns with high-value target
 *
 * Integration with LevelEngine: This extends LevelEngine by adding directional
 * intent analysis and liquidity significance ranking.
 */
public class LiquidityTargetIdentifier {

    private final String symbol;
    private final LevelEngine levelEngine;

    public LiquidityTargetIdentifier(String symbol, LevelEngine levelEngine) {
        this.symbol = symbol;
        this.levelEngine = levelEngine;
    }

    /**
     * Represents a liquidity target with significance scoring.
     */
    public static class LiquidityTarget {
        private final KnownLevel level;
        private final double distanceFromPrice;
        private final int significanceScore;
        private final String description;

        public LiquidityTarget(KnownLevel level, double distanceFromPrice,
                               int significanceScore, String description) {
            this.level = level;
            this.distanceFromPrice = distanceFromPrice;
            this.significanceScore = significanceScore;
            this.description = description;
        }

        public KnownLevel getLevel() { return level; }
        public double getDistanceFromPrice() { return distanceFromPrice; }
        public int getSignificanceScore() { return significanceScore; }
        public String getDescription() { return description; }
        public double getTargetPrice() { return level.getPrice(); }
        public LevelType getLevelType() { return level.getType(); }

        @Override
        public String toString() {
            return String.format("Target[%s @ %.2f, dist=%.2f, sig=%d]",
                    description, level.getPrice(), distanceFromPrice, significanceScore);
        }
    }

    /**
     * Find the next unswept liquidity target in the trend direction.
     * This is the probable DESTINATION of the current HTF trend.
     *
     * @param currentPrice Current price
     * @param bullish True if looking above (bullish trend), false for below
     * @return The most significant unswept target, or empty if none
     */
    public Optional<LiquidityTarget> findNextTarget(double currentPrice, boolean bullish) {
        List<LiquidityTarget> targets = findAllTargets(currentPrice, bullish);

        if (targets.isEmpty()) {
            return Optional.empty();
        }

        // Return the highest significance target that is nearest
        // Priority: significance > proximity (we want the most meaningful target)
        targets.sort((a, b) -> {
            // First sort by significance (descending)
            int sigCompare = Integer.compare(b.significanceScore, a.significanceScore);
            if (sigCompare != 0) return sigCompare;
            // Then by proximity (ascending)
            return Double.compare(a.distanceFromPrice, b.distanceFromPrice);
        });

        return Optional.of(targets.get(0));
    }

    /**
     * Find ALL unswept liquidity targets in the trend direction.
     * Sorted by distance from current price (nearest first).
     */
    public List<LiquidityTarget> findAllTargets(double currentPrice, boolean bullish) {
        List<LiquidityTarget> targets = new ArrayList<>();

        List<KnownLevel> unraided = levelEngine.getUnraidedLevels();

        for (KnownLevel level : unraided) {
            // Filter by direction
            if (bullish && level.getPrice() <= currentPrice) continue;
            if (!bullish && level.getPrice() >= currentPrice) continue;

            double distance = Math.abs(level.getPrice() - currentPrice);
            int significance = calculateSignificance(level);

            // Only include levels with meaningful significance
            if (significance >= 3) {
                String desc = buildDescription(level);
                targets.add(new LiquidityTarget(level, distance, significance, desc));
            }
        }

        // Sort by distance (nearest first)
        targets.sort(Comparator.comparingDouble(LiquidityTarget::getDistanceFromPrice));

        return targets;
    }

    /**
     * Calculate the liquidity significance of a level.
     * Higher score = more resting orders expected = more attractive target.
     *
     * Scoring (1-10):
     *   PDH/PDL:          10 (highest — major daily liquidity)
     *   PWH/PWL:           9 (weekly levels — massive liquidity)
     *   Session extremes:  7 (Asia/London/NY highs/lows)
     *   Equal highs/lows:  6-8 (cluster dependent — more touches = more stops)
     *   Session opens:     3 (less significant for raids)
     */
    private int calculateSignificance(KnownLevel level) {
        int baseSignificance = level.getType().getSignificance();

        // Boost for multiple touches (more stops accumulating)
        if (level.getTouchCount() >= 3) {
            baseSignificance = Math.min(10, baseSignificance + 2);
        } else if (level.getTouchCount() >= 2) {
            baseSignificance = Math.min(10, baseSignificance + 1);
        }

        // Boost for equal levels with large clusters
        if (level.getType().isEqualLevel() && level.getClusterSize() >= 3) {
            baseSignificance = Math.min(10, baseSignificance + 2);
        }

        return baseSignificance;
    }

    /**
     * Build a human-readable description of the target.
     */
    private String buildDescription(KnownLevel level) {
        String base = level.getType().getDisplayName();
        if (level.getClusterSize() > 1) {
            base += " (cluster=" + level.getClusterSize() + ")";
        }
        if (level.getTouchCount() > 0) {
            base += " (" + level.getTouchCount() + " touches)";
        }
        return base;
    }

    /**
     * Check if the trade direction points toward a significant unswept level.
     * Used as a filter: only trade in directions where liquidity exists.
     *
     * @param currentPrice Current price
     * @param bullish Trade direction
     * @param minSignificance Minimum significance required (suggest 5+)
     * @return true if a significant target exists in this direction
     */
    public boolean hasSignificantTarget(double currentPrice, boolean bullish, int minSignificance) {
        return findAllTargets(currentPrice, bullish).stream()
                .anyMatch(t -> t.getSignificanceScore() >= minSignificance);
    }

    /**
     * Calculate the distance (in points) to the nearest significant target.
     * Used for target setting and R:R calculation.
     */
    public double getDistanceToNearestTarget(double currentPrice, boolean bullish) {
        Optional<LiquidityTarget> target = findNextTarget(currentPrice, bullish);
        return target.map(LiquidityTarget::getDistanceFromPrice).orElse(Double.MAX_VALUE);
    }

    /**
     * Check if the nearest liquidity target has been recently swept.
     * If yes, the trend move may be exhausted — avoid new entries.
     *
     * @param currentPrice Current price
     * @param bullish Trade direction
     * @param maxDistance Max distance to check (in price units)
     * @return true if the nearest significant target was already swept (move may be done)
     */
    public boolean isNearestTargetSwept(double currentPrice, boolean bullish, double maxDistance) {
        // Check if there was a significant level that WAS raided recently
        List<KnownLevel> allLevels = levelEngine.getAllLevels();

        for (KnownLevel level : allLevels) {
            if (!level.isRaided()) continue;

            // Check direction
            if (bullish && level.getPrice() <= currentPrice) continue;
            if (!bullish && level.getPrice() >= currentPrice) continue;

            double distance = Math.abs(level.getPrice() - currentPrice);
            if (distance > maxDistance) continue;

            int significance = calculateSignificance(level);
            if (significance >= 7) {
                // A significant level was already swept — move may be exhausted
                return true;
            }
        }

        return false;
    }

    /**
     * Get a confluence bonus based on alignment with liquidity targets.
     * Used to boost or penalize the setup quality score.
     *
     * @param currentPrice Current price
     * @param bullish Trade direction
     * @return +2 for PDH/PDL target, +1 for other significant, 0 for none
     */
    public int getTargetAlignmentBonus(double currentPrice, boolean bullish) {
        Optional<LiquidityTarget> target = findNextTarget(currentPrice, bullish);

        if (target.isEmpty()) return 0;

        LiquidityTarget t = target.get();
        LevelType type = t.getLevelType();

        // PDH/PDL or PWH/PWL = highest bonus
        if (type == LevelType.PDH || type == LevelType.PDL ||
                type == LevelType.PWH || type == LevelType.PWL) {
            return 2;
        }

        // Session extremes = moderate bonus
        if (t.getSignificanceScore() >= 6) {
            return 1;
        }

        return 0;
    }

    /**
     * Get a summary of the liquidity target landscape for logging.
     */
    public String getSummary(double currentPrice) {
        StringBuilder sb = new StringBuilder();
        sb.append("Liquidity Targets for ").append(symbol).append(":\n");

        // Targets above
        List<LiquidityTarget> above = findAllTargets(currentPrice, true);
        sb.append("  Above (").append(above.size()).append("): ");
        for (int i = 0; i < Math.min(3, above.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(above.get(i));
        }
        sb.append("\n");

        // Targets below
        List<LiquidityTarget> below = findAllTargets(currentPrice, false);
        sb.append("  Below (").append(below.size()).append("): ");
        for (int i = 0; i < Math.min(3, below.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(below.get(i));
        }

        return sb.toString();
    }
}
