package com.topstep.trading.montecarlo;

import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.lifecycle.RiskZone;
import com.topstep.trading.risk.RiskProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

/**
 * Monte Carlo simulator that runs thousands of randomized equity paths through
 * prop firm rules to compute pass probability, blowup rate, expected payout,
 * and path statistics.
 *
 * Features:
 * - Single path simulation with dynamic risk zones
 * - Multi-path aggregation with percentile bands
 * - Block bootstrapping for serial correlation
 * - Funded phase sub-simulation for expected payout
 * - Partial exit modeling
 */
public class MonteCarloSimulator {

    private static final int DEFAULT_ITERATIONS = 10_000;
    private static final int MAX_TRADING_DAYS = 60;
    private static final int SAMPLE_PATHS_COUNT = 100;
    private static final int BLOCK_SIZE = 4; // Block bootstrap size

    private final Random random;
    private final boolean useBlockBootstrap;

    public MonteCarloSimulator() {
        this(new Random(), false);
    }

    public MonteCarloSimulator(long seed) {
        this(new Random(seed), false);
    }

    public MonteCarloSimulator(Random random, boolean useBlockBootstrap) {
        this.random = random;
        this.useBlockBootstrap = useBlockBootstrap;
    }

    // ==================== Main Simulation ====================

    /**
     * Run a full Monte Carlo simulation with default 10K iterations.
     */
    public MonteCarloResult simulate(StrategyMetrics metrics, RiskProfile profile,
                                      AccountLifecycle templateLifecycle) {
        return simulate(metrics, profile, templateLifecycle, DEFAULT_ITERATIONS);
    }

    /**
     * Run a full Monte Carlo simulation.
     */
    public MonteCarloResult simulate(StrategyMetrics metrics, RiskProfile profile,
                                      AccountLifecycle templateLifecycle, int iterations) {
        int passed = 0;
        int blown = 0;
        int timeout = 0;

        double totalTradesToPass = 0;
        double totalDaysToPass = 0;
        int passedForAvg = 0;

        double[] maxDrawdowns = new double[iterations];
        List<PathResult> samplePaths = new ArrayList<>();

        // Day-by-day equity tracking for fan chart (max 60 days)
        double[][] dayEquities = new double[iterations][MAX_TRADING_DAYS + 1];

        for (int i = 0; i < iterations; i++) {
            PathResult result = simulateSinglePath(metrics, profile, templateLifecycle);

            switch (result.getOutcome()) {
                case PASSED:
                    passed++;
                    totalTradesToPass += result.getTradesToComplete();
                    totalDaysToPass += result.getDaysToComplete();
                    passedForAvg++;
                    break;
                case BLOWN:
                    blown++;
                    break;
                case TIMEOUT:
                    timeout++;
                    break;
            }

            maxDrawdowns[i] = result.getMaxDrawdown();

            if (i < SAMPLE_PATHS_COUNT) {
                samplePaths.add(result);
            }

            // Record equity path for fan chart
            List<Double> curve = result.getEquityCurve();
            for (int d = 0; d <= MAX_TRADING_DAYS; d++) {
                dayEquities[i][d] = (d < curve.size()) ? curve.get(d) : curve.get(curve.size() - 1);
            }
        }

        double avgTradesToPass = passedForAvg > 0 ? totalTradesToPass / passedForAvg : 0;
        double avgDaysToPass = passedForAvg > 0 ? totalDaysToPass / passedForAvg : 0;
        double avgMaxDrawdown = Arrays.stream(maxDrawdowns).average().orElse(0);

        // Build equity fan chart bands
        double[][] bandsP5 = new double[MAX_TRADING_DAYS + 1][1];
        double[][] bandsP25 = new double[MAX_TRADING_DAYS + 1][1];
        double[][] bandsP50 = new double[MAX_TRADING_DAYS + 1][1];
        double[][] bandsP75 = new double[MAX_TRADING_DAYS + 1][1];
        double[][] bandsP95 = new double[MAX_TRADING_DAYS + 1][1];

        for (int d = 0; d <= MAX_TRADING_DAYS; d++) {
            double[] dayValues = new double[iterations];
            for (int i = 0; i < iterations; i++) {
                dayValues[i] = dayEquities[i][d];
            }
            Arrays.sort(dayValues);
            bandsP5[d][0] = percentile(dayValues, 5);
            bandsP25[d][0] = percentile(dayValues, 25);
            bandsP50[d][0] = percentile(dayValues, 50);
            bandsP75[d][0] = percentile(dayValues, 75);
            bandsP95[d][0] = percentile(dayValues, 95);
        }

        // Calculate expected payout
        double passRate = iterations > 0 ? (double) passed / iterations : 0;
        double expectedPayout = calculateExpectedPayout(passRate, metrics, profile, templateLifecycle);
        double challengeFee = templateLifecycle.getChallengeFee();
        double payoutPerDollar = challengeFee > 0 ? expectedPayout / challengeFee : 0;

        return new MonteCarloResult(iterations, passed, blown, timeout,
            avgTradesToPass, avgDaysToPass, avgMaxDrawdown,
            maxDrawdowns, expectedPayout, payoutPerDollar,
            samplePaths, bandsP5, bandsP25, bandsP50, bandsP75, bandsP95);
    }

    // ==================== Single Path Simulation ====================

    /**
     * Simulate a single equity path through the prop firm challenge.
     */
    public PathResult simulateSinglePath(StrategyMetrics metrics, RiskProfile profile,
                                          AccountLifecycle templateLifecycle) {
        double startingBalance = templateLifecycle.getStartingBalance();
        double profitTarget = templateLifecycle.getProfitTarget();
        double maxLossLimit = templateLifecycle.getMaxLossLimit();
        double dailyLossLimit = templateLifecycle.getDailyLossLimit();

        double equity = startingBalance;
        double highWaterMark = startingBalance;
        double maxDrawdown = 0;
        int totalTrades = 0;
        int consecutiveLosses = 0;
        int dllBreachDays = 0; // SA5: days on which the FULL DLL was breached

        List<Double> equityCurve = new ArrayList<>();
        equityCurve.add(equity);

        int maxTradesPerDay = profile.getMaxTradesPerDay();
        double baseRiskDollars = startingBalance * profile.getBaseRiskPct();

        // Pre-generate trade outcomes for block bootstrap
        List<Double> tradeOutcomes = useBlockBootstrap
            ? generateBlockBootstrapOutcomes(metrics, MAX_TRADING_DAYS * maxTradesPerDay)
            : null;
        int outcomeIndex = 0;

        for (int day = 0; day < MAX_TRADING_DAYS; day++) {
            double dailyPnl = 0;
            int tradesToday = 0;

            for (int t = 0; t < maxTradesPerDay; t++) {
                // Skip if consecutive loss limit hit
                if (consecutiveLosses >= profile.getMaxConsecutiveLosses()) {
                    break;
                }

                // Skip if self-imposed daily loss buffer hit
                double selfImposedLimit = dailyLossLimit * profile.getMaxDailyLossPct();
                if (dailyPnl <= -selfImposedLimit) {
                    break;
                }

                // Determine risk zone
                double targetCompletion = Math.max(0, (equity - startingBalance)) / profitTarget;
                double drawdownUsage = Math.max(0, (highWaterMark - equity)) / maxLossLimit;
                RiskZone zone = determineZone(targetCompletion, drawdownUsage);

                // Apply zone multiplier to risk
                double zoneMultiplier = getZoneMultiplier(zone, profile);
                double adjustedRisk = baseRiskDollars * zoneMultiplier;

                // Apply hard floors
                double riskBudgetRemaining = maxLossLimit - (highWaterMark - equity);
                adjustedRisk = Math.min(adjustedRisk, riskBudgetRemaining * profile.getMaxRiskBudgetPct());
                double dailyRoom = dailyLossLimit + dailyPnl;
                adjustedRisk = Math.min(adjustedRisk, dailyRoom * profile.getMaxDailyLimitPct());
                adjustedRisk = Math.max(0, adjustedRisk);

                if (adjustedRisk < 25.0) break; // Too little room

                // Generate trade outcome
                double outcomeR;
                if (tradeOutcomes != null && outcomeIndex < tradeOutcomes.size()) {
                    outcomeR = tradeOutcomes.get(outcomeIndex++);
                } else {
                    outcomeR = generateTradeOutcome(metrics);
                }

                // Apply partial exit logic for evaluation phase
                double tradePnl;
                if (profile.isPartialsEnabled() && outcomeR > 0) {
                    // Partial at 1R: 50% closes at 1R, 50% runs to full target
                    double partialPnl = adjustedRisk * profile.getPartialExitRMultiple() * profile.getPartialExitPct();
                    double remainderPnl = adjustedRisk * outcomeR * (1.0 - profile.getPartialExitPct());
                    tradePnl = partialPnl + remainderPnl;
                } else {
                    tradePnl = adjustedRisk * outcomeR;
                }

                equity += tradePnl;
                dailyPnl += tradePnl;
                totalTrades++;
                tradesToday++;

                if (tradePnl < 0) {
                    consecutiveLosses++;
                } else {
                    consecutiveLosses = 0;
                }

                // Check MLL breach (from HWM)
                double currentDrawdown = highWaterMark - equity;
                if (currentDrawdown >= maxLossLimit) {
                    equityCurve.add(equity);
                    return new PathResult(PathResult.Outcome.BLOWN, equityCurve, equity,
                        maxDrawdown, currentDrawdown, totalTrades, day + 1,
                        highWaterMark).withDllBreachDays(
                            dllBreachDays + (dailyPnl <= -dailyLossLimit ? 1 : 0));
                }

                // Check DLL breach
                if (dailyPnl <= -dailyLossLimit) {
                    dllBreachDays++; // SA5: count the full-DLL day
                    break; // Day is over but account survives
                }

                // Check profit target
                double totalPnl = equity - startingBalance;
                if (totalPnl >= profitTarget) {
                    equityCurve.add(equity);
                    return new PathResult(PathResult.Outcome.PASSED, equityCurve, equity,
                        maxDrawdown, currentDrawdown, totalTrades, day + 1,
                        highWaterMark).withDllBreachDays(dllBreachDays);
                }
            }

            // End of day: update HWM
            if (equity > highWaterMark) {
                highWaterMark = equity;
            }
            double drawdown = highWaterMark - equity;
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown;
            }

            equityCurve.add(equity);

            // Reset consecutive losses at day boundary
            consecutiveLosses = 0;
        }

        // Timeout: didn't pass or blow within max days
        return new PathResult(PathResult.Outcome.TIMEOUT, equityCurve, equity,
            maxDrawdown, highWaterMark - equity, totalTrades, MAX_TRADING_DAYS,
            highWaterMark).withDllBreachDays(dllBreachDays);
    }

    // ==================== Funded Phase Sub-Simulation ====================

    /**
     * Simulate funded phase to estimate average profit before blowup.
     * Used for expected payout calculation.
     */
    public double simulateFundedPhaseAvgProfit(StrategyMetrics metrics, RiskProfile fundedProfile,
                                                AccountLifecycle template, int iterations) {
        double totalProfit = 0;
        int completed = 0;

        for (int i = 0; i < iterations; i++) {
            double profit = simulateFundedPath(metrics, fundedProfile, template);
            totalProfit += profit;
            completed++;
        }

        return completed > 0 ? totalProfit / completed : 0;
    }

    private double simulateFundedPath(StrategyMetrics metrics, RiskProfile profile,
                                       AccountLifecycle template) {
        double startingBalance = template.getStartingBalance();
        double maxLossLimit = template.getMaxLossLimit();
        double dailyLossLimit = template.getDailyLossLimit();

        double equity = startingBalance;
        double highWaterMark = startingBalance;
        double totalWithdrawn = 0;
        int consecutiveLosses = 0;

        double baseRiskDollars = startingBalance * profile.getBaseRiskPct();

        // Simulate up to 252 trading days (1 year)
        for (int day = 0; day < 252; day++) {
            double dailyPnl = 0;

            for (int t = 0; t < profile.getMaxTradesPerDay(); t++) {
                if (consecutiveLosses >= profile.getMaxConsecutiveLosses()) break;

                double selfImposedLimit = dailyLossLimit * profile.getMaxDailyLossPct();
                if (dailyPnl <= -selfImposedLimit) break;

                double adjustedRisk = baseRiskDollars;
                double riskBudgetRemaining = maxLossLimit - (highWaterMark - equity);
                adjustedRisk = Math.min(adjustedRisk, riskBudgetRemaining * profile.getMaxRiskBudgetPct());

                if (adjustedRisk < 25.0) break;

                double outcomeR = generateTradeOutcome(metrics);
                double tradePnl = adjustedRisk * outcomeR;

                equity += tradePnl;
                dailyPnl += tradePnl;

                if (tradePnl < 0) consecutiveLosses++;
                else consecutiveLosses = 0;

                // MLL breach = funded account blown
                double currentDrawdown = highWaterMark - equity;
                if (currentDrawdown >= maxLossLimit) {
                    return totalWithdrawn;
                }
            }

            // End of day HWM update
            if (equity > highWaterMark) {
                highWaterMark = equity;
            }

            // Simulate periodic withdrawals (every 2 weeks when profitable)
            if (day % 10 == 0 && equity > startingBalance + 500) {
                double withdrawable = (equity - startingBalance) * 0.5; // Take half of profits
                totalWithdrawn += withdrawable;
                equity -= withdrawable;
            }

            consecutiveLosses = 0;
        }

        return totalWithdrawn + Math.max(0, equity - startingBalance);
    }

    // ==================== Expected Payout ====================

    /**
     * Calculate expected payout per challenge.
     * E[payout] = passRate * E[funded_profit] * profitSplit - challengeFee
     */
    private double calculateExpectedPayout(double passRate, StrategyMetrics metrics,
                                            RiskProfile evalProfile, AccountLifecycle template) {
        // Run funded phase simulation (fewer iterations for speed)
        RiskProfile fundedProfile = RiskProfile.topstep50kFunded();
        double avgFundedProfit = simulateFundedPhaseAvgProfit(metrics, fundedProfile, template, 1000);

        double expectedPayout = passRate * avgFundedProfit * template.getProfitSplit()
                                - template.getChallengeFee();
        return expectedPayout;
    }

    // ==================== Trade Outcome Generation ====================

    /**
     * Generate a single trade outcome in R-multiples using strategy metrics distributions.
     */
    private double generateTradeOutcome(StrategyMetrics metrics) {
        if (random.nextDouble() < metrics.getWinRate()) {
            // Win: sample from win R-multiple distribution
            List<Double> winRs = metrics.getWinRMultiples();
            if (!winRs.isEmpty()) {
                return winRs.get(random.nextInt(winRs.size()));
            }
            return metrics.getAvgWinR();
        } else {
            // Loss: sample from loss R-multiple distribution
            List<Double> lossRs = metrics.getLossRMultiples();
            if (!lossRs.isEmpty()) {
                return lossRs.get(random.nextInt(lossRs.size()));
            }
            return metrics.getAvgLossR(); // Should be negative
        }
    }

    /**
     * Generate trade outcomes using block bootstrapping to preserve serial correlation.
     * Samples blocks of consecutive trades instead of individual trades.
     */
    private List<Double> generateBlockBootstrapOutcomes(StrategyMetrics metrics, int totalNeeded) {
        List<Double> outcomes = new ArrayList<>(totalNeeded);

        // Create a pool of historical-like outcomes
        List<Double> pool = new ArrayList<>();
        int poolSize = Math.max(50, metrics.getTotalTrades());
        for (int i = 0; i < poolSize; i++) {
            pool.add(generateTradeOutcome(metrics));
        }

        // Sample in blocks
        while (outcomes.size() < totalNeeded) {
            int blockStart = random.nextInt(Math.max(1, pool.size() - BLOCK_SIZE));
            int blockEnd = Math.min(blockStart + BLOCK_SIZE, pool.size());
            for (int i = blockStart; i < blockEnd && outcomes.size() < totalNeeded; i++) {
                outcomes.add(pool.get(i));
            }
        }

        return outcomes;
    }

    // ==================== Zone Logic ====================

    private RiskZone determineZone(double targetCompletion, double drawdownUsage) {
        if (drawdownUsage >= 0.60) return RiskZone.DANGER;
        if (drawdownUsage >= 0.40) return RiskZone.CAUTION;
        if (targetCompletion >= 0.85) return RiskZone.CRUISE;
        if (targetCompletion >= 0.50) return RiskZone.PROTECTION;
        return RiskZone.NORMAL;
    }

    private double getZoneMultiplier(RiskZone zone, RiskProfile profile) {
        switch (zone) {
            case CRUISE:     return profile.getCruiseRiskMultiplier();
            case PROTECTION: return profile.getProtectionRiskMultiplier();
            case CAUTION:    return profile.getCautionRiskMultiplier();
            case DANGER:     return profile.getDangerRiskMultiplier();
            case NORMAL:
            default:         return 1.0;
        }
    }

    private static double percentile(double[] sorted, int pct) {
        int index = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    // ==================== Forward-Looking Mini MC ====================

    /**
     * Run a fast forward-looking MC from current account state.
     * Used for real-time pass probability updates on the dashboard.
     * Designed to complete in <5 seconds with 1K iterations.
     */
    public MonteCarloResult simulateForward(StrategyMetrics metrics, RiskProfile profile,
                                             double currentEquity, double highWaterMark,
                                             int daysElapsed, AccountLifecycle template) {
        int iterations = 1000;
        double startingBalance = template.getStartingBalance();
        double profitTarget = template.getProfitTarget();
        double maxLossLimit = template.getMaxLossLimit();
        double dailyLossLimit = template.getDailyLossLimit();

        int passed = 0, blown = 0, timeout = 0;
        double[] maxDrawdowns = new double[iterations];
        List<PathResult> samplePaths = new ArrayList<>();

        int remainingDays = MAX_TRADING_DAYS - daysElapsed;

        for (int i = 0; i < iterations; i++) {
            double equity = currentEquity;
            double hwm = highWaterMark;
            double maxDD = 0;
            int trades = 0;
            List<Double> curve = new ArrayList<>();
            curve.add(equity);

            boolean resolved = false;

            for (int day = 0; day < remainingDays; day++) {
                double dailyPnl = 0;
                for (int t = 0; t < profile.getMaxTradesPerDay(); t++) {
                    double riskDollars = startingBalance * profile.getBaseRiskPct();
                    double riskBudget = maxLossLimit - (hwm - equity);
                    riskDollars = Math.min(riskDollars, riskBudget * profile.getMaxRiskBudgetPct());
                    if (riskDollars < 25) break;

                    double outcomeR = generateTradeOutcome(metrics);
                    equity += riskDollars * outcomeR;
                    dailyPnl += riskDollars * outcomeR;
                    trades++;

                    if (hwm - equity >= maxLossLimit) {
                        blown++;
                        resolved = true;
                        break;
                    }
                    if (equity - startingBalance >= profitTarget) {
                        passed++;
                        resolved = true;
                        break;
                    }
                    if (dailyPnl <= -dailyLossLimit) break;
                }

                if (resolved) break;

                if (equity > hwm) hwm = equity;
                double dd = hwm - equity;
                if (dd > maxDD) maxDD = dd;
                curve.add(equity);
            }

            if (!resolved) timeout++;
            maxDrawdowns[i] = maxDD;

            if (i < 50) {
                samplePaths.add(new PathResult(
                    resolved ? (equity - startingBalance >= profitTarget ? PathResult.Outcome.PASSED : PathResult.Outcome.BLOWN) : PathResult.Outcome.TIMEOUT,
                    curve, equity, maxDD, hwm - equity, trades, remainingDays, hwm));
            }
        }

        double passRate = (double) passed / iterations;
        return new MonteCarloResult(iterations, passed, blown, timeout,
            0, 0, Arrays.stream(maxDrawdowns).average().orElse(0),
            maxDrawdowns, passRate * 2000 * template.getProfitSplit() - template.getChallengeFee(),
            0, samplePaths, null, null, null, null, null);
    }
}
