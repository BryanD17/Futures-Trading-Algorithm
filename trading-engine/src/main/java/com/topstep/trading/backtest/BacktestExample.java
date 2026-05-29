package com.topstep.trading.backtest;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.IctHighConfluenceStrategy;

import java.io.File;
import java.util.List;

/**
 * Example demonstrating how to run a backtest with the ICT High Confluence Strategy.
 *
 * Data Source:
 * - Uses real historical data from Oanda (NAS100/SPX500 CFD data)
 * - Data sourced from: https://github.com/FutureSharks/financial-data
 * - Format: 5-minute OHLCV candles
 *
 * Usage:
 * 1. Run: ./gradlew :trading-engine:run --args="BACKTEST"
 * 2. Data files should be in: data/NQ_5min.csv and data/ES_5min.csv
 */
public class BacktestExample {

    // Path to data files (relative to project root)
    private static final String DATA_DIR = "data";
    private static final String NQ_DATA_FILE = "NQ_5min.csv";
    private static final String ES_DATA_FILE = "ES_5min.csv";
    private static final String NQ_2019_FILE = "NQ_2019_5min.csv";

    public static void main(String[] args) {
        System.out.println("=" + "=".repeat(59));
        System.out.println("ICT HIGH CONFLUENCE STRATEGY - BACKTEST WITH REAL DATA");
        System.out.println("=" + "=".repeat(59));

        // Step 1: Set up Topstep 50K account
        // CRITICAL FIX: Topstep 50K funded account has $50,000 starting balance
        // (The "Express" $0 start only applies to combine evaluation phase)
        double startingBalance = 50_000.0;  // Topstep 50K funded account
        AccountState accountState = new AccountState(startingBalance);
        RiskLimits riskLimits = RiskLimits.topstep50k();

        System.out.println("\nAccount Configuration:");
        System.out.println("  Starting Balance: $" + startingBalance);
        System.out.println("  " + riskLimits);

        // Step 2: Load historical data
        HistoricalDataProvider dataProvider = new HistoricalDataProvider();
        List<Candle> nqCandles = null;
        List<Candle> esCandles = null;

        // Debug: show current working directory
        String cwd = System.getProperty("user.dir");
        System.out.println("\nDEBUG: Current working directory: " + cwd);

        // Try to find and load real data files
        String[] possiblePaths = {
            DATA_DIR,                           // Relative to CWD
            "../" + DATA_DIR,                   // Up one level
            "../../" + DATA_DIR,                // Up two levels
            cwd + "/" + DATA_DIR,               // Absolute from CWD
            cwd + "/../" + DATA_DIR,            // Parent of CWD
            "/home/user/Futures-Trading-Algorithm/" + DATA_DIR  // Absolute fallback
        };

        String foundDataPath = null;
        for (String path : possiblePaths) {
            File dir = new File(path);
            File nqFile = new File(path, NQ_DATA_FILE);
            System.out.println("DEBUG: Checking path: " + path + " (exists: " + dir.exists() + ", nq file: " + nqFile.exists() + ")");
            if (nqFile.exists()) {
                foundDataPath = path;
                System.out.println("DEBUG: Found data at: " + path);
                break;
            }
        }

        if (foundDataPath != null) {
            System.out.println("\n📊 Loading REAL historical data from: " + foundDataPath);
            System.out.println("  Data source: Oanda NAS100/SPX500 CFD (proxy for NQ/ES futures)");
            System.out.println("  Timeframe: 5-minute candles");

            try {
                // Load NQ data (try 2019 full year first, then 2020)
                File nq2019File = new File(foundDataPath, NQ_2019_FILE);
                if (nq2019File.exists()) {
                    nqCandles = dataProvider.loadFromCsv(nq2019File.getAbsolutePath(), "NQ");
                    System.out.println("  ✓ Loaded NQ 2019 data: " + nqCandles.size() + " candles (~12 months)");
                } else {
                    File nqFile = new File(foundDataPath, NQ_DATA_FILE);
                    nqCandles = dataProvider.loadFromCsv(nqFile.getAbsolutePath(), "NQ");
                    System.out.println("  ✓ Loaded NQ 2020 data: " + nqCandles.size() + " candles");
                }

                // Load ES data
                File esFile = new File(foundDataPath, ES_DATA_FILE);
                if (esFile.exists()) {
                    esCandles = dataProvider.loadFromCsv(esFile.getAbsolutePath(), "ES");
                    System.out.println("  ✓ Loaded ES data: " + esCandles.size() + " candles");
                }

            } catch (Exception e) {
                System.err.println("  ❌ Error loading data: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // Fall back to synthetic data if real data not found
        if (nqCandles == null || nqCandles.isEmpty()) {
            System.out.println("\n⚠️  Real data not found, using synthetic data");
            System.out.println("  To use real data, ensure these files exist:");
            System.out.println("  - data/NQ_5min.csv");
            System.out.println("  - data/ES_5min.csv");
            nqCandles = dataProvider.generateSyntheticData("NQ", 5000);
            esCandles = dataProvider.generateSyntheticData("ES", 5000);
        }

        // Step 3: Create strategy with shared EventBus.
        // The factory returns the new StdvOteRunnerStrategy by default; it
        // falls back to IctHighConfluenceStrategy for non-MNQ/MES/MGC
        // symbols (this backtest uses full-size NQ for synthetic-data demo
        // purposes — switch to MNQ to exercise the new path).
        EventBus eventBus = new EventBus();
        com.topstep.trading.strategy.TradingStrategy strategy =
                com.topstep.trading.strategy.stdvote.StdvOteFactory.build("NQ", "ES", eventBus);

        System.out.println("\nStrategy: " + strategy.getName());
        System.out.println("Primary Instrument: NQ (Nasdaq 100 E-mini)");
        System.out.println("SMT Pair: ES (S&P 500 E-mini)");

        // Step 4: Create backtest runner with SHARED EventBus (critical for signal flow)
        BacktestRunner runner = new BacktestRunner(strategy, accountState, riskLimits, eventBus);

        // Step 5: Run backtest with both NQ (primary) and ES (SMT) candles
        System.out.println("\n" + "-".repeat(60));
        System.out.println("RUNNING BACKTEST...");
        System.out.println("-".repeat(60));

        BacktestReport report = runner.run(nqCandles, esCandles);

        // Step 6: Print results
        report.printReport();

        // Step 7: Print risk manager summary
        System.out.println("\n" + "=".repeat(60));
        System.out.println("TRADING RISK MANAGER SUMMARY");
        System.out.println("=".repeat(60));
        System.out.println(runner.getTradingRiskManager().getStatusSummary());

        // Step 8: Analysis and recommendations
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
