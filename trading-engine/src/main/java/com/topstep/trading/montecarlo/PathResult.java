package com.topstep.trading.montecarlo;

import java.util.List;

/**
 * Result of a single Monte Carlo simulated equity path.
 */
public class PathResult {

    public enum Outcome {
        PASSED,     // Hit profit target
        BLOWN,      // Breached max loss limit
        TIMEOUT     // Exceeded max trading days without passing or blowing
    }

    private final Outcome outcome;
    private final List<Double> equityCurve;
    private final double finalEquity;
    private final double maxDrawdown;
    private final double maxDrawdownFromHwm;
    private final int tradesToComplete;
    private final int daysToComplete;
    private final double peakEquity;

    /**
     * Number of days on which the FULL Daily Loss Limit was breached
     * (dailyPnl &le; −DLL). Additive SA5 field (default 0) so DLL breach
     * probability can be reported alongside the MLL/blown probability.
     */
    private int dllBreachDays;

    public PathResult(Outcome outcome, List<Double> equityCurve, double finalEquity,
                      double maxDrawdown, double maxDrawdownFromHwm,
                      int tradesToComplete, int daysToComplete, double peakEquity) {
        this.outcome = outcome;
        this.equityCurve = equityCurve;
        this.finalEquity = finalEquity;
        this.maxDrawdown = maxDrawdown;
        this.maxDrawdownFromHwm = maxDrawdownFromHwm;
        this.tradesToComplete = tradesToComplete;
        this.daysToComplete = daysToComplete;
        this.peakEquity = peakEquity;
    }

    public Outcome getOutcome() { return outcome; }
    public List<Double> getEquityCurve() { return equityCurve; }
    public double getFinalEquity() { return finalEquity; }
    public double getMaxDrawdown() { return maxDrawdown; }
    public double getMaxDrawdownFromHwm() { return maxDrawdownFromHwm; }
    public int getTradesToComplete() { return tradesToComplete; }
    public int getDaysToComplete() { return daysToComplete; }
    public double getPeakEquity() { return peakEquity; }

    public boolean passed() { return outcome == Outcome.PASSED; }
    public boolean blown() { return outcome == Outcome.BLOWN; }

    /** Days with a full DLL breach on this path (SA5 additive metric). */
    public int getDllBreachDays() { return dllBreachDays; }

    /** True if the Daily Loss Limit was fully breached at least once. */
    public boolean hadDllBreach() { return dllBreachDays > 0; }

    /** Attach the DLL-breach-day count (fluent; used by the simulator). */
    public PathResult withDllBreachDays(int days) {
        this.dllBreachDays = days;
        return this;
    }
}
