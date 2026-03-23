package com.topstep.trading.optimization;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Complete result of a risk optimization sweep.
 * Contains all data points and identifies the optimal risk percentage.
 */
public class OptimizationResult {

    private final List<OptimizationDataPoint> sweepCurve;
    private final OptimizationDataPoint optimalPoint;
    private final double optimalRiskPct;
    private final Instant optimizedAt;

    public OptimizationResult(List<OptimizationDataPoint> sweepCurve) {
        this.sweepCurve = sweepCurve;

        // Find the point with maximum expected payout
        this.optimalPoint = sweepCurve.stream()
            .max(Comparator.comparingDouble(OptimizationDataPoint::getExpectedPayout))
            .orElse(null);

        this.optimalRiskPct = optimalPoint != null ? optimalPoint.getRiskPct() : 0.005;
        this.optimizedAt = Instant.now();
    }

    public List<OptimizationDataPoint> getSweepCurve() { return sweepCurve; }
    public OptimizationDataPoint getOptimalPoint() { return optimalPoint; }
    public double getOptimalRiskPct() { return optimalRiskPct; }
    public Instant getOptimizedAt() { return optimizedAt; }

    public void printSummary() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("RISK OPTIMIZATION RESULTS");
        System.out.println("=".repeat(60));

        System.out.println("\nSweep Curve:");
        for (OptimizationDataPoint point : sweepCurve) {
            String marker = (point == optimalPoint) ? " *** OPTIMAL ***" : "";
            System.out.println(point + marker);
        }

        if (optimalPoint != null) {
            System.out.printf("%nOptimal Risk: %.2f%%%n", optimalRiskPct * 100);
            System.out.printf("  Pass Rate: %.1f%%%n", optimalPoint.getPassRate() * 100);
            System.out.printf("  Expected Payout: $%.2f%n", optimalPoint.getExpectedPayout());
            System.out.printf("  P95 Max Drawdown: $%.2f%n", optimalPoint.getMaxDrawdownP95());
        }

        System.out.println("=".repeat(60));
    }
}
