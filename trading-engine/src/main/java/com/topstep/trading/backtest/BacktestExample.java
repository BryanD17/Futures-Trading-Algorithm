package com.topstep.trading.backtest;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.IctHighConfluenceStrategy;

import java.util.List;

/**
 * Example demonstrating how to run a backtest with the ICT High Confluence Strategy.
 *
 * Usage:
 * 1. Prepare historical data CSV files for ES and NQ
 * 2. Configure the backtest parameters
 * 3. Run and analyze the results
 */
public class BacktestExample {

    public static void main(String[] args) {
        System.out.println("ICT High Confluence Strategy - Backtest Example");
        System.out.println("=" + "=".repeat(59));

        // Step 1: Set up Topstep 50K account
        double startingBalance = 0.0;  // Topstep Express starts at $0
        AccountState accountState = new AccountState(startingBalance);
        RiskLimits riskLimits = RiskLimits.topstep50k();

        System.out.println("\nAccount Configuration:");
        System.out.println("  Starting Balance: $" + startingBalance);
        System.out.println("  " + riskLimits);

        // Step 2: Load historical data
        HistoricalDataProvider dataProvider = new HistoricalDataProvider();

        // For this example, we'll use synthetic data
        // In production, load from CSV: dataProvider.loadFromCsv("path/to/ES_data.csv", "ES")
        System.out.println("\nLoading historical data...");
        List<Candle> esCandles = dataProvider.generateSyntheticData("ES", 500);
        List<Candle> nqCandles = dataProvider.generateSyntheticData("NQ", 500);

        // Step 3: Create strategy
        EventBus eventBus = new EventBus();
        IctHighConfluenceStrategy strategy = new IctHighConfluenceStrategy("ES", "NQ", eventBus);

        System.out.println("\nStrategy: " + strategy.getName());

        // Step 4: Create backtest runner
        BacktestRunner runner = new BacktestRunner(strategy, accountState, riskLimits);

        // Step 5: Run backtest
        System.out.println("\nRunning backtest...");
        System.out.println("-".repeat(60));

        BacktestReport report = runner.run(esCandles);

        // Step 6: Print results
        report.printReport();

        // Step 7: Analysis and recommendations
        printRecommendations(report, riskLimits);
    }

    /**
     * Print recommendations based on backtest results.
     */
    private static void printRecommendations(BacktestReport report, RiskLimits limits) {
        System.out.println("Recommendations:");
        System.out.println("-".repeat(60));

        // Check if strategy is profitable
        if (report.getTotalPnl() >= limits.getProfitTarget()) {
            System.out.println("✓ Strategy met profit target! Consider live simulation.");
        } else if (report.getTotalPnl() > 0) {
            System.out.println("⚠ Strategy is profitable but hasn't met target yet.");
            System.out.println("  Consider longer backtest period or parameter tuning.");
        } else {
            System.out.println("❌ Strategy is not profitable in this backtest.");
            System.out.println("  Review strategy logic and confluences.");
        }

        // Check win rate
        if (report.getWinRate() < 40) {
            System.out.println("⚠ Win rate is low (" + String.format("%.2f%%", report.getWinRate()) + ").");
            System.out.println("  Consider stricter entry criteria or better filtering.");
        } else if (report.getWinRate() > 60) {
            System.out.println("✓ Good win rate (" + String.format("%.2f%%", report.getWinRate()) + ").");
        }

        // Check profit factor
        if (report.getProfitFactor() < 1.5) {
            System.out.println("⚠ Profit factor is low (" + String.format("%.2f", report.getProfitFactor()) + ").");
            System.out.println("  Aim for profit factor > 1.5 for consistent profitability.");
        } else {
            System.out.println("✓ Good profit factor (" + String.format("%.2f", report.getProfitFactor()) + ").");
        }

        // Check trade frequency
        if (report.getTotalTrades() < 10) {
            System.out.println("⚠ Low trade frequency (" + report.getTotalTrades() + " trades).");
            System.out.println("  This is expected for high-confluence strategy.");
            System.out.println("  Consider running longer backtest for more data.");
        } else {
            System.out.println("✓ Sufficient trades (" + report.getTotalTrades() + ") for analysis.");
        }

        System.out.println("\n" + "=".repeat(60));
    }
}
