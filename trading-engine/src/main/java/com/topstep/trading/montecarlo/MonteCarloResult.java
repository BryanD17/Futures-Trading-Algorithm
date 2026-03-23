package com.topstep.trading.montecarlo;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Aggregated result from running multiple Monte Carlo simulation paths.
 * Contains pass/blowup/timeout rates, drawdown distributions, and percentile bands.
 */
public class MonteCarloResult {

    private final int totalIterations;
    private final int passedCount;
    private final int blownCount;
    private final int timeoutCount;

    private final double passRate;
    private final double blowupRate;
    private final double timeoutRate;

    private final double avgTradesToPass;
    private final double avgDaysToPass;
    private final double avgMaxDrawdown;

    // Percentile bands for max drawdown
    private final double maxDrawdownP5;
    private final double maxDrawdownP25;
    private final double maxDrawdownP50;
    private final double maxDrawdownP75;
    private final double maxDrawdownP95;
    private final double maxDrawdownP99;

    // Expected payout
    private final double expectedPayout;
    private final double expectedPayoutPerChallengeDollar;

    // Sample paths for visualization (first 100)
    private final List<PathResult> samplePaths;

    // Equity percentile bands at each day (for fan chart)
    private final double[][] equityBandsP5;   // [day][equity at P5]
    private final double[][] equityBandsP25;
    private final double[][] equityBandsP50;
    private final double[][] equityBandsP75;
    private final double[][] equityBandsP95;

    private final Instant simulatedAt;

    public MonteCarloResult(int totalIterations, int passedCount, int blownCount, int timeoutCount,
                            double avgTradesToPass, double avgDaysToPass, double avgMaxDrawdown,
                            double[] maxDrawdownDistribution, double expectedPayout,
                            double expectedPayoutPerChallengeDollar,
                            List<PathResult> samplePaths,
                            double[][] equityBandsP5, double[][] equityBandsP25,
                            double[][] equityBandsP50, double[][] equityBandsP75,
                            double[][] equityBandsP95) {
        this.totalIterations = totalIterations;
        this.passedCount = passedCount;
        this.blownCount = blownCount;
        this.timeoutCount = timeoutCount;

        this.passRate = totalIterations > 0 ? (double) passedCount / totalIterations : 0;
        this.blowupRate = totalIterations > 0 ? (double) blownCount / totalIterations : 0;
        this.timeoutRate = totalIterations > 0 ? (double) timeoutCount / totalIterations : 0;

        this.avgTradesToPass = avgTradesToPass;
        this.avgDaysToPass = avgDaysToPass;
        this.avgMaxDrawdown = avgMaxDrawdown;

        // Calculate percentiles from distribution
        if (maxDrawdownDistribution != null && maxDrawdownDistribution.length > 0) {
            Arrays.sort(maxDrawdownDistribution);
            this.maxDrawdownP5 = percentile(maxDrawdownDistribution, 5);
            this.maxDrawdownP25 = percentile(maxDrawdownDistribution, 25);
            this.maxDrawdownP50 = percentile(maxDrawdownDistribution, 50);
            this.maxDrawdownP75 = percentile(maxDrawdownDistribution, 75);
            this.maxDrawdownP95 = percentile(maxDrawdownDistribution, 95);
            this.maxDrawdownP99 = percentile(maxDrawdownDistribution, 99);
        } else {
            this.maxDrawdownP5 = this.maxDrawdownP25 = this.maxDrawdownP50 = 0;
            this.maxDrawdownP75 = this.maxDrawdownP95 = this.maxDrawdownP99 = 0;
        }

        this.expectedPayout = expectedPayout;
        this.expectedPayoutPerChallengeDollar = expectedPayoutPerChallengeDollar;
        this.samplePaths = samplePaths;

        this.equityBandsP5 = equityBandsP5;
        this.equityBandsP25 = equityBandsP25;
        this.equityBandsP50 = equityBandsP50;
        this.equityBandsP75 = equityBandsP75;
        this.equityBandsP95 = equityBandsP95;

        this.simulatedAt = Instant.now();
    }

    private static double percentile(double[] sorted, int pct) {
        int index = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    // Getters
    public int getTotalIterations() { return totalIterations; }
    public int getPassedCount() { return passedCount; }
    public int getBlownCount() { return blownCount; }
    public int getTimeoutCount() { return timeoutCount; }
    public double getPassRate() { return passRate; }
    public double getBlowupRate() { return blowupRate; }
    public double getTimeoutRate() { return timeoutRate; }
    public double getAvgTradesToPass() { return avgTradesToPass; }
    public double getAvgDaysToPass() { return avgDaysToPass; }
    public double getAvgMaxDrawdown() { return avgMaxDrawdown; }
    public double getMaxDrawdownP5() { return maxDrawdownP5; }
    public double getMaxDrawdownP25() { return maxDrawdownP25; }
    public double getMaxDrawdownP50() { return maxDrawdownP50; }
    public double getMaxDrawdownP75() { return maxDrawdownP75; }
    public double getMaxDrawdownP95() { return maxDrawdownP95; }
    public double getMaxDrawdownP99() { return maxDrawdownP99; }
    public double getExpectedPayout() { return expectedPayout; }
    public double getExpectedPayoutPerChallengeDollar() { return expectedPayoutPerChallengeDollar; }
    public List<PathResult> getSamplePaths() { return samplePaths; }
    public double[][] getEquityBandsP5() { return equityBandsP5; }
    public double[][] getEquityBandsP25() { return equityBandsP25; }
    public double[][] getEquityBandsP50() { return equityBandsP50; }
    public double[][] getEquityBandsP75() { return equityBandsP75; }
    public double[][] getEquityBandsP95() { return equityBandsP95; }
    public Instant getSimulatedAt() { return simulatedAt; }

    public void printSummary() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("MONTE CARLO SIMULATION RESULTS");
        System.out.println("=".repeat(60));
        System.out.printf("  Iterations:          %,d%n", totalIterations);
        System.out.printf("  Pass Rate:           %.1f%% (%,d passed)%n", passRate * 100, passedCount);
        System.out.printf("  Blowup Rate:         %.1f%% (%,d blown)%n", blowupRate * 100, blownCount);
        System.out.printf("  Timeout Rate:        %.1f%% (%,d timeout)%n", timeoutRate * 100, timeoutCount);
        System.out.printf("  Avg Trades to Pass:  %.0f%n", avgTradesToPass);
        System.out.printf("  Avg Days to Pass:    %.0f%n", avgDaysToPass);
        System.out.println("\nMax Drawdown Distribution:");
        System.out.printf("  P5:  $%.2f   P25: $%.2f   P50: $%.2f%n", maxDrawdownP5, maxDrawdownP25, maxDrawdownP50);
        System.out.printf("  P75: $%.2f   P95: $%.2f   P99: $%.2f%n", maxDrawdownP75, maxDrawdownP95, maxDrawdownP99);
        System.out.println("\nExpected Payout:");
        System.out.printf("  Per challenge:       $%.2f%n", expectedPayout);
        System.out.printf("  Per challenge dollar: $%.2f%n", expectedPayoutPerChallengeDollar);
        System.out.println("=".repeat(60));
    }
}
