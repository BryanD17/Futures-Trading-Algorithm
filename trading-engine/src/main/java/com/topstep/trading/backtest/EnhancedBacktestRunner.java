package com.topstep.trading.backtest;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.montecarlo.MonteCarloResult;
import com.topstep.trading.montecarlo.MonteCarloSimulator;
import com.topstep.trading.montecarlo.StrategyMetrics;
import com.topstep.trading.optimization.OptimizationResult;
import com.topstep.trading.optimization.RiskOptimizer;
import com.topstep.trading.risk.RiskProfile;
import com.topstep.trading.strategy.TradingStrategy;

import java.util.List;

/**
 * Enhanced backtest runner with 5-phase pipeline:
 *   Phase 1: Historical replay (existing BacktestRunner logic)
 *   Phase 2: Extract StrategyMetrics from completed trades
 *   Phase 3: Monte Carlo pass probability analysis
 *   Phase 4: Risk optimization sweep (optional)
 *   Phase 5: Combined report assembly
 */
public class EnhancedBacktestRunner {

    private final TradingStrategy strategy;
    private final AccountState accountState;
    private final RiskLimits riskLimits;
    private final boolean runOptimization;

    public EnhancedBacktestRunner(TradingStrategy strategy, AccountState accountState,
                                   RiskLimits riskLimits) {
        this(strategy, accountState, riskLimits, false);
    }

    public EnhancedBacktestRunner(TradingStrategy strategy, AccountState accountState,
                                   RiskLimits riskLimits, boolean runOptimization) {
        this.strategy = strategy;
        this.accountState = accountState;
        this.riskLimits = riskLimits;
        this.runOptimization = runOptimization;
    }

    /**
     * Run the full 5-phase enhanced backtest.
     */
    public EnhancedBacktestReport runFull(List<Candle> candles) {
        return runFull(candles, null);
    }

    /**
     * Run the full 5-phase enhanced backtest with SMT data.
     */
    public EnhancedBacktestReport runFull(List<Candle> candles, List<Candle> smtCandles) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("ENHANCED BACKTEST - 5-PHASE PIPELINE");
        System.out.println("=".repeat(60));

        // Phase 1: Historical replay
        System.out.println("\n--- Phase 1: Historical Replay ---");
        BacktestRunner runner = new BacktestRunner(strategy, accountState, riskLimits);
        BacktestReport standardReport = runner.run(candles, smtCandles);

        // Phase 2: Extract StrategyMetrics
        System.out.println("\n--- Phase 2: Extract Strategy Metrics ---");
        StrategyMetrics metrics = StrategyMetrics.fromBacktest(standardReport.getTrades());
        System.out.println("  " + metrics);

        // Phase 3: Monte Carlo pass probability
        System.out.println("\n--- Phase 3: Monte Carlo Analysis ---");
        RiskProfile evalProfile = RiskProfile.topstep50kEvaluation();
        AccountLifecycle lifecycle = AccountLifecycle.topstep50kEvaluation();
        MonteCarloSimulator mcSimulator = new MonteCarloSimulator();
        MonteCarloResult mcResult = mcSimulator.simulate(metrics, evalProfile, lifecycle);

        // Phase 4: Risk optimization (optional)
        OptimizationResult optResult = null;
        if (runOptimization) {
            System.out.println("\n--- Phase 4: Risk Optimization Sweep ---");
            RiskOptimizer optimizer = new RiskOptimizer(mcSimulator);
            optResult = optimizer.optimizeRisk(metrics, evalProfile, lifecycle);
        } else {
            System.out.println("\n--- Phase 4: Risk Optimization (skipped - use runOptimization=true) ---");
        }

        // Phase 5: Assemble report
        System.out.println("\n--- Phase 5: Report Assembly ---");
        EnhancedBacktestReport report = new EnhancedBacktestReport(
            standardReport, metrics, mcResult, optResult);

        report.printReport();
        return report;
    }
}
