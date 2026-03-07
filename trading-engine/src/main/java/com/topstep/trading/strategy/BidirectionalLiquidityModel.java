package com.topstep.trading.strategy;

import com.topstep.trading.chartstate.EqualLevelDetector;
import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.chartstate.LevelEngine;

import java.util.Optional;

/**
 * Bidirectional Liquidity Model for structural R:R targets.
 *
 * Instead of fixed R:R multipliers (1:3, 1:4, 1:5), this model calculates
 * profit targets based on the nearest OPPOSING liquidity pool:
 * - A long's target is the nearest unraided supply pool above
 * - A short's target is the nearest unraided demand pool below
 *
 * This produces structural targets that align with real market levels,
 * providing 1:4-1:6+ on many trades where fixed R:R would use arbitrary multipliers.
 */
public class BidirectionalLiquidityModel {

    private final LevelEngine levelEngine;
    private final EqualLevelDetector equalLevelDetector;
    private final String symbol;

    public BidirectionalLiquidityModel(String symbol, LevelEngine levelEngine,
                                        EqualLevelDetector equalLevelDetector) {
        this.symbol = symbol;
        this.levelEngine = levelEngine;
        this.equalLevelDetector = equalLevelDetector;
    }

    /**
     * Get the optimal structural target for a trade.
     * For LONGS: nearest unraided high-type level above entry
     * For SHORTS: nearest unraided low-type level below entry
     *
     * @return target price, or -1 if no structural target found
     */
    public double getOptimalTarget(double entryPrice, double stopPrice, boolean bullish) {
        double tickSize = getTickSize();

        if (bullish) {
            // Find nearest supply target above
            Optional<KnownLevel> target = getNearestUnraidedLevelAbove(entryPrice);
            Optional<EqualLevelDetector.EqualLevel> eqHigh =
                    equalLevelDetector.getNearestEqualHighAbove(entryPrice);

            double levelTarget = target.map(l -> l.getPrice() - tickSize).orElse(-1.0);
            double eqTarget = eqHigh.map(e -> e.getPrice() - tickSize).orElse(-1.0);

            // Use whichever is closer (first target to hit)
            if (levelTarget > 0 && eqTarget > 0) return Math.min(levelTarget, eqTarget);
            if (levelTarget > 0) return levelTarget;
            if (eqTarget > 0) return eqTarget;
            return -1;
        } else {
            // Find nearest demand target below
            Optional<KnownLevel> target = getNearestUnraidedLevelBelow(entryPrice);
            Optional<EqualLevelDetector.EqualLevel> eqLow =
                    equalLevelDetector.getNearestEqualLowBelow(entryPrice);

            double levelTarget = target.map(l -> l.getPrice() + tickSize).orElse(-1.0);
            double eqTarget = eqLow.map(e -> e.getPrice() + tickSize).orElse(-1.0);

            // Use whichever is closer (first target to hit — higher price for shorts means closer)
            if (levelTarget > 0 && eqTarget > 0) return Math.max(levelTarget, eqTarget);
            if (levelTarget > 0) return levelTarget;
            if (eqTarget > 0) return eqTarget;
            return -1;
        }
    }

    /**
     * Calculate structural R:R. Returns 0 if no structural target.
     */
    public double getStructuralRR(double entry, double stop, boolean bullish) {
        double target = getOptimalTarget(entry, stop, bullish);
        if (target < 0) return 0;
        double risk = Math.abs(entry - stop);
        double reward = Math.abs(target - entry);
        return risk > 0 ? reward / risk : 0;
    }

    /**
     * Choose the best target: structural if R:R >= tier minimum, else fixed R:R.
     */
    public double getBestTarget(double entry, double stop, boolean bullish, TradeTier tier) {
        double structTarget = getOptimalTarget(entry, stop, bullish);
        double risk = Math.abs(entry - stop);
        double fixedTarget = bullish
                ? entry + (risk * tier.getRiskRewardRatio() * tier.getTierMultiplier())
                : entry - (risk * tier.getRiskRewardRatio() * tier.getTierMultiplier());

        if (structTarget < 0) return fixedTarget;

        double structRR = Math.abs(structTarget - entry) / risk;
        double minRR = tier.getRiskRewardRatio() * tier.getTierMultiplier();

        // Use structural if it gives at least the tier minimum R:R
        if (structRR >= minRR) return structTarget;
        // Use structural if it gives at least 2:1 and is reasonable
        if (structRR >= 2.0) return structTarget;
        return fixedTarget;
    }

    /**
     * Get the nearest unraided high-type level above a price.
     */
    private Optional<KnownLevel> getNearestUnraidedLevelAbove(double price) {
        return levelEngine.getAllLevels().stream()
                .filter(l -> l.getType().isHighLevel())
                .filter(l -> !l.isRaided())
                .filter(l -> l.getPrice() > price)
                .min((a, b) -> Double.compare(a.getPrice() - price, b.getPrice() - price));
    }

    /**
     * Get the nearest unraided low-type level below a price.
     */
    private Optional<KnownLevel> getNearestUnraidedLevelBelow(double price) {
        return levelEngine.getAllLevels().stream()
                .filter(l -> l.getType().isLowLevel())
                .filter(l -> !l.isRaided())
                .filter(l -> l.getPrice() < price)
                .min((a, b) -> Double.compare(price - a.getPrice(), price - b.getPrice()));
    }

    private double getTickSize() {
        if (symbol.contains("ES") || symbol.contains("MES")) return 0.25;
        if (symbol.contains("NQ") || symbol.contains("MNQ")) return 0.25;
        if (symbol.contains("GC")) return 0.10;
        return 0.25;
    }
}
