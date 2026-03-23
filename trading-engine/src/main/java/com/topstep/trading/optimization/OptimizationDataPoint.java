package com.topstep.trading.optimization;

/**
 * A single data point from the risk optimization sweep.
 * Represents the Monte Carlo results at a specific risk percentage.
 */
public class OptimizationDataPoint {

    private final double riskPct;
    private final double passRate;
    private final double blowupRate;
    private final double timeoutRate;
    private final double expectedPayout;
    private final double avgTradesToPass;
    private final double maxDrawdownP95;

    public OptimizationDataPoint(double riskPct, double passRate, double blowupRate,
                                  double timeoutRate, double expectedPayout,
                                  double avgTradesToPass, double maxDrawdownP95) {
        this.riskPct = riskPct;
        this.passRate = passRate;
        this.blowupRate = blowupRate;
        this.timeoutRate = timeoutRate;
        this.expectedPayout = expectedPayout;
        this.avgTradesToPass = avgTradesToPass;
        this.maxDrawdownP95 = maxDrawdownP95;
    }

    public double getRiskPct() { return riskPct; }
    public double getPassRate() { return passRate; }
    public double getBlowupRate() { return blowupRate; }
    public double getTimeoutRate() { return timeoutRate; }
    public double getExpectedPayout() { return expectedPayout; }
    public double getAvgTradesToPass() { return avgTradesToPass; }
    public double getMaxDrawdownP95() { return maxDrawdownP95; }

    @Override
    public String toString() {
        return String.format("  Risk=%.2f%% | Pass=%.1f%% | Blowup=%.1f%% | E[payout]=$%.2f | P95DD=$%.2f",
            riskPct * 100, passRate * 100, blowupRate * 100, expectedPayout, maxDrawdownP95);
    }
}
