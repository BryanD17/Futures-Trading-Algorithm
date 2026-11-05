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

        // V5 Agent 01 (R9): the CLI entry point loads the same EngineConfig
        // (-D > ENGINE_* env > ~/topstep-trading/engine.properties > defaults)
        // and prints the EFFECTIVE ENGINE CONFIG table before any runner starts.
        EngineFacade.getInstance();

        switch (mode) {
            case "BACKTEST":
                runBacktestMode();
                break;

            case "ABTEST":
                // SA5: legacy-vs-scalp A/B comparison on the same candle set
                // with explicit commission + slippage (gross AND net PnL).
                com.topstep.trading.backtest.AbBacktestComparison.main(new String[0]);
                break;

            case "MONTECARLO":
                // SA5: scalp ($150, 6/day, 3-loss stop) vs naive ($500, no
                // stop) DLL/MLL breach probability comparison.
                com.topstep.trading.montecarlo.MonteCarloScalpComparison.main(new String[0]);
                break;

            case "SIM":
                runSimMode();
                break;

            case "LIVE":
                runLiveMode();
                break;

            default:
                System.err.println("Unknown mode: " + mode);
                System.err.println("Supported modes: BACKTEST, ABTEST, MONTECARLO, SIM, LIVE");
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
     * V6 Agent 05.1: LIVE mode is removed from the Java engine (R6). Fails
     * loud with the V6 message and a non-zero exit; no credential is read and
     * no connection is attempted.
     */
    private static void runLiveMode() {
        System.err.println();
        System.err.println("❌ " + LiveTradingRemovedException.MESSAGE);
        System.err.println("   The Java engine is a research / backtest lab and the parity oracle in V6.");
        System.exit(2);
    }
}
