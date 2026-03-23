package com.topstep.trading.backtest;

import com.topstep.trading.montecarlo.MonteCarloResult;
import com.topstep.trading.montecarlo.StrategyMetrics;
import com.topstep.trading.optimization.OptimizationResult;

/**
 * Enhanced backtest report combining standard backtest metrics with
 * Monte Carlo analysis and risk optimization results.
 *
 * Phase 1: Standard historical replay metrics (PnL, win rate, Sharpe, max DD)
 * Phase 2: Strategy metrics extraction
 * Phase 3: MC pass probability analysis
 * Phase 4: Risk optimization sweep
 * Phase 5: Combined report
 */
public class EnhancedBacktestReport {

    // Standard backtest report
    private final BacktestReport standardReport;

    // Strategy metrics extracted from trades
    private final StrategyMetrics strategyMetrics;

    // Monte Carlo simulation results
    private final MonteCarloResult monteCarloResult;

    // Risk optimization results (optional)
    private final OptimizationResult optimizationResult;

    public EnhancedBacktestReport(BacktestReport standardReport,
                                   StrategyMetrics strategyMetrics,
                                   MonteCarloResult monteCarloResult,
                                   OptimizationResult optimizationResult) {
        this.standardReport = standardReport;
        this.strategyMetrics = strategyMetrics;
        this.monteCarloResult = monteCarloResult;
        this.optimizationResult = optimizationResult;
    }

    // Getters
    public BacktestReport getStandardReport() { return standardReport; }
    public StrategyMetrics getStrategyMetrics() { return strategyMetrics; }
    public MonteCarloResult getMonteCarloResult() { return monteCarloResult; }
    public OptimizationResult getOptimizationResult() { return optimizationResult; }

    /**
     * Print the full enhanced report.
     */
    public void printReport() {
        // Phase 1: Standard report
        standardReport.printReport();

        // Phase 2: Strategy Metrics
        System.out.println("\n" + "=".repeat(60));
        System.out.println("STRATEGY METRICS (Extracted from Backtest)");
        System.out.println("=".repeat(60));
        System.out.printf("  Win Rate:        %.1f%%%n", strategyMetrics.getWinRate() * 100);
        System.out.printf("  Avg Win R:       %.2fR%n", strategyMetrics.getAvgWinR());
        System.out.printf("  Avg Loss R:      %.2fR%n", strategyMetrics.getAvgLossR());
        System.out.printf("  Expectancy:      %.3fR per trade%n", strategyMetrics.getExpectancyPerTrade());
        System.out.printf("  Trades/Day:      %.1f%n", strategyMetrics.getTradesPerDay());
        System.out.printf("  Total Trades:    %d over %d days%n",
            strategyMetrics.getTotalTrades(), strategyMetrics.getTradingDays());

        // Phase 3: Monte Carlo Results
        if (monteCarloResult != null) {
            monteCarloResult.printSummary();
        }

        // Phase 4: Optimization Results
        if (optimizationResult != null) {
            optimizationResult.printSummary();
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("END OF ENHANCED BACKTEST REPORT");
        System.out.println("=".repeat(60));
    }
}
