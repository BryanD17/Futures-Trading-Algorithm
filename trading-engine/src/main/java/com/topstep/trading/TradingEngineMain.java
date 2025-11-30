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
                throw new UnsupportedOperationException(
                    "SIM mode not yet implemented. Planned for Week 3.");

            case "LIVE":
                throw new UnsupportedOperationException(
                    "LIVE mode not yet implemented. Planned for Week 4.");

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
}
