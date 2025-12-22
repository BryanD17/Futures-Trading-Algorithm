package com.topstep.trading;

import com.topstep.trading.backtest.BacktestExample;

/**
 * Main entry point for the Topstep Futures Trading Engine.
 *
 * Supports multiple operating modes:
 * - BACKTEST: Run historical backtests
 * - SIM: Run in simulation mode (Week 3)
 * - LIVE: Run in live trading mode (Week 4)
 *
 * Week 2: Only BACKTEST mode is implemented.
 *
 * Usage:
 *   ./gradlew :trading-engine:run --args="BACKTEST"
 */
public class TradingEngineMain {

    public static void main(String[] args) {
        System.out.println("Topstep Futures Trading Engine");
        System.out.println("=" + "=".repeat(39));
        System.out.println();

        // Parse mode argument
        String mode = (args.length > 0) ? args[0].toUpperCase() : "BACKTEST";

        System.out.println("Mode: " + mode);
        System.out.println();

        switch (mode) {
            case "BACKTEST":
                runBacktestMode();
                break;

            case "SIM":
                runSimMode();
                break;

            case "LIVE":
                runLiveMode();
                break;

            default:
                System.err.println("Unknown mode: " + mode);
                System.err.println("Supported modes: BACKTEST, SIM, LIVE");
                System.exit(1);
        }
    }

    /**
     * Run backtest mode using BacktestExample.
     */
    private static void runBacktestMode() {
        System.out.println("Starting Backtest Mode...");
        System.out.println("-".repeat(40));
        System.out.println();

        try {
            BacktestExample.main(new String[0]);
        } catch (Exception e) {
            System.err.println("Backtest failed with error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println();
        System.out.println("Backtest completed successfully.");
    }

    /**
     * Run SIM mode using SimEngineRunner.
     */
    private static void runSimMode() {
        System.out.println("Starting SIM Mode...");
        System.out.println("-".repeat(40));
        System.out.println();

        try {
            SimEngineRunner.run();
        } catch (Exception e) {
            System.err.println("SIM mode failed with error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Run LIVE mode using LiveEngineRunner.
     * WARNING: This connects to real markets and uses real money!
     */
    private static void runLiveMode() {
        System.out.println();
        System.out.println("!".repeat(60));
        System.out.println("! WARNING: STARTING LIVE TRADING MODE !");
        System.out.println("! REAL MONEY IS AT RISK !");
        System.out.println("!".repeat(60));
        System.out.println();
        System.out.println("Pre-flight checks:");

        // Check environment variables
        String[] requiredEnvVars = {
            "TOPSTEP_API_URL",
            "TOPSTEP_USERNAME",
            "TOPSTEP_API_KEY",
            "TOPSTEP_ACCOUNT_ID"
        };

        boolean allSet = true;
        for (String envVar : requiredEnvVars) {
            String value = System.getenv(envVar);
            if (value == null || value.isEmpty()) {
                System.out.println("  ❌ " + envVar + " - NOT SET");
                allSet = false;
            } else {
                System.out.println("  ✓ " + envVar + " - Set");
            }
        }

        if (!allSet) {
            System.err.println();
            System.err.println("❌ Missing required environment variables.");
            System.err.println("Please set all Topstep credentials before starting LIVE mode.");
            System.exit(1);
        }

        System.out.println();
        System.out.println("Starting LIVE Mode...");
        System.out.println("-".repeat(40));
        System.out.println();

        try {
            LiveEngineRunner.run();
        } catch (Exception e) {
            System.err.println("LIVE mode failed with error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
