package com.topstep.trading.optimization;

import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.montecarlo.MonteCarloResult;
import com.topstep.trading.montecarlo.MonteCarloSimulator;
import com.topstep.trading.montecarlo.StrategyMetrics;
import com.topstep.trading.risk.RiskProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Risk optimizer that sweeps base risk from 0.25% to 2.0% and identifies
 * the risk percentage that maximizes expected payout.
 *
 * Features:
 * - Configurable sweep range and step size
 * - Full MC simulation at each risk level
 * - Walk-forward validation mode (70/30 split)
 */
public class RiskOptimizer {

    private static final double DEFAULT_MIN_RISK = 0.0025;  // 0.25%
    private static final double DEFAULT_MAX_RISK = 0.020;   // 2.0%
    private static final double DEFAULT_STEP = 0.0025;      // 0.25% increments
    private static final int MC_ITERATIONS = 5000;          // Reduced from 10K for speed

    private final MonteCarloSimulator simulator;

    public RiskOptimizer() {
        this(new MonteCarloSimulator());
    }

    public RiskOptimizer(MonteCarloSimulator simulator) {
        this.simulator = simulator;
    }

    /**
     * Run the full risk optimization sweep with default parameters.
     */
    public OptimizationResult optimizeRisk(StrategyMetrics metrics, RiskProfile baseProfile,
                                            AccountLifecycle templateLifecycle) {
        return optimizeRisk(metrics, baseProfile, templateLifecycle,
                           DEFAULT_MIN_RISK, DEFAULT_MAX_RISK, DEFAULT_STEP);
    }

    /**
     * Run the risk optimization sweep with custom range.
     */
    public OptimizationResult optimizeRisk(StrategyMetrics metrics, RiskProfile baseProfile,
                                            AccountLifecycle templateLifecycle,
                                            double minRisk, double maxRisk, double step) {
        List<OptimizationDataPoint> sweepCurve = new ArrayList<>();

        System.out.println("\nRunning risk optimization sweep...");
        System.out.printf("  Range: %.2f%% to %.2f%% (step %.2f%%)%n",
            minRisk * 100, maxRisk * 100, step * 100);

        for (double riskPct = minRisk; riskPct <= maxRisk + 1e-9; riskPct += step) {
            // Create a profile variant with this risk level
            RiskProfile variantProfile = baseProfile.withBaseRiskPct(riskPct);

            // Run MC simulation
            MonteCarloResult mcResult = simulator.simulate(metrics, variantProfile,
                                                           templateLifecycle, MC_ITERATIONS);

            OptimizationDataPoint point = new OptimizationDataPoint(
                riskPct,
                mcResult.getPassRate(),
                mcResult.getBlowupRate(),
                mcResult.getTimeoutRate(),
                mcResult.getExpectedPayout(),
                mcResult.getAvgTradesToPass(),
                mcResult.getMaxDrawdownP95()
            );

            sweepCurve.add(point);
            System.out.println(point);
        }

        return new OptimizationResult(sweepCurve);
    }

    /**
     * Walk-forward validation: optimize on first 70% of data, validate on remaining 30%.
     * Detects over-optimization to historical data.
     */
    public WalkForwardResult walkForwardValidation(StrategyMetrics inSampleMetrics,
                                                     StrategyMetrics outOfSampleMetrics,
                                                     RiskProfile baseProfile,
                                                     AccountLifecycle templateLifecycle) {
        // Optimize on in-sample data
        OptimizationResult inSampleResult = optimizeRisk(inSampleMetrics, baseProfile, templateLifecycle);
        double optimalRisk = inSampleResult.getOptimalRiskPct();

        // Validate on out-of-sample data at the optimal risk level
        RiskProfile optimalProfile = baseProfile.withBaseRiskPct(optimalRisk);
        MonteCarloResult oosResult = simulator.simulate(outOfSampleMetrics, optimalProfile,
                                                         templateLifecycle, MC_ITERATIONS);

        // Also test at naive 1% for comparison
        RiskProfile naiveProfile = baseProfile.withBaseRiskPct(0.01);
        MonteCarloResult naiveResult = simulator.simulate(outOfSampleMetrics, naiveProfile,
                                                           templateLifecycle, MC_ITERATIONS);

        return new WalkForwardResult(
            inSampleResult,
            optimalRisk,
            oosResult.getPassRate(),
            oosResult.getExpectedPayout(),
            naiveResult.getPassRate(),
            naiveResult.getExpectedPayout()
        );
    }

    /**
     * Result of walk-forward validation.
     */
    public static class WalkForwardResult {
        private final OptimizationResult inSampleResult;
        private final double optimalRiskPct;
        private final double oosPassRate;
        private final double oosExpectedPayout;
        private final double naivePassRate;
        private final double naiveExpectedPayout;

        public WalkForwardResult(OptimizationResult inSampleResult, double optimalRiskPct,
                                  double oosPassRate, double oosExpectedPayout,
                                  double naivePassRate, double naiveExpectedPayout) {
            this.inSampleResult = inSampleResult;
            this.optimalRiskPct = optimalRiskPct;
            this.oosPassRate = oosPassRate;
            this.oosExpectedPayout = oosExpectedPayout;
            this.naivePassRate = naivePassRate;
            this.naiveExpectedPayout = naiveExpectedPayout;
        }

        public OptimizationResult getInSampleResult() { return inSampleResult; }
        public double getOptimalRiskPct() { return optimalRiskPct; }
        public double getOosPassRate() { return oosPassRate; }
        public double getOosExpectedPayout() { return oosExpectedPayout; }
        public double getNaivePassRate() { return naivePassRate; }
        public double getNaiveExpectedPayout() { return naiveExpectedPayout; }

        /**
         * Whether the optimized risk outperforms naive 1% on out-of-sample data.
         */
        public boolean isOptimizationValid() {
            return oosExpectedPayout > naiveExpectedPayout;
        }

        public void printSummary() {
            System.out.println("\n" + "=".repeat(60));
            System.out.println("WALK-FORWARD VALIDATION");
            System.out.println("=".repeat(60));
            System.out.printf("  Optimal Risk (in-sample): %.2f%%%n", optimalRiskPct * 100);
            System.out.printf("  OOS Pass Rate:            %.1f%%%n", oosPassRate * 100);
            System.out.printf("  OOS Expected Payout:      $%.2f%n", oosExpectedPayout);
            System.out.printf("  Naive 1%% Pass Rate:       %.1f%%%n", naivePassRate * 100);
            System.out.printf("  Naive 1%% Expected Payout: $%.2f%n", naiveExpectedPayout);
            System.out.printf("  Optimization Valid:       %s%n",
                isOptimizationValid() ? "YES (optimized > naive)" : "NO (overfit detected)");
            System.out.println("=".repeat(60));
        }
    }
}
