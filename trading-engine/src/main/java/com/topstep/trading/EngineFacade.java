package com.topstep.trading;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Position;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.domain.Trade;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.strategy.TradingStrategy;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Facade for accessing the trading engine from external components (API, dashboard).
 *
 * This singleton provides a clean interface for:
 * - Querying account state, positions, trades
 * - Starting/stopping/pausing the engine
 * - Checking risk status
 *
 * Week 3: Used by api-backend controllers to expose engine state.
 */
public class EngineFacade {

    private static EngineFacade instance;

    // Operating mode
    public enum Mode {
        BACKTEST,
        SIM,
        LIVE,
        STOPPED
    }

    private Mode currentMode = Mode.STOPPED;

    // Core components (injected by runners)
    private AccountState accountState;
    private ExecutionEngine executionEngine;
    private RiskLimits riskLimits;
    private TradingStrategy strategy;
    private PropFirmRiskEngine riskEngine;
    private SimEngineRunner simRunner;

    /**
     * Private constructor for singleton.
     */
    private EngineFacade() {
    }

    /**
     * Get the singleton instance.
     */
    public static synchronized EngineFacade getInstance() {
        if (instance == null) {
            instance = new EngineFacade();
        }
        return instance;
    }

    /**
     * Initialize the facade with engine components.
     * Called by SimEngineRunner or BacktestRunner when starting.
     */
    public void initialize(
            Mode mode,
            AccountState accountState,
            ExecutionEngine executionEngine,
            RiskLimits riskLimits,
            TradingStrategy strategy,
            PropFirmRiskEngine riskEngine) {

        this.currentMode = mode;
        this.accountState = accountState;
        this.executionEngine = executionEngine;
        this.riskLimits = riskLimits;
        this.strategy = strategy;
        this.riskEngine = riskEngine;
    }

    /**
     * Register the SIM runner for control operations.
     */
    public void setSimRunner(SimEngineRunner simRunner) {
        this.simRunner = simRunner;
    }

    // ==================== Control Operations ====================

    /**
     * Start SIM mode.
     */
    public synchronized void startSim() {
        if (currentMode == Mode.SIM && simRunner != null && simRunner.isRunning()) {
            throw new IllegalStateException("SIM mode already running");
        }

        // Create and start new SIM runner in background thread
        simRunner = new SimEngineRunner();
        initialize(Mode.SIM,
                simRunner.getAccountState(),
                simRunner.getExecutionEngine(),
                simRunner.getRiskLimits(),
                null, // Strategy reference not needed by API
                null  // RiskEngine reference not needed by API
        );

        Thread simThread = new Thread(() -> {
            simRunner.start();
        }, "SIM-Engine-Thread");
        simThread.setDaemon(false);
        simThread.start();

        // Give it a moment to start
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Pause SIM mode.
     */
    public synchronized void pauseSim() {
        if (currentMode != Mode.SIM) {
            throw new IllegalStateException("Not in SIM mode");
        }
        if (simRunner == null) {
            throw new IllegalStateException("SIM runner not initialized");
        }

        simRunner.pause();
    }

    /**
     * Resume SIM mode.
     */
    public synchronized void resumeSim() {
        if (currentMode != Mode.SIM) {
            throw new IllegalStateException("Not in SIM mode");
        }
        if (simRunner == null) {
            throw new IllegalStateException("SIM runner not initialized");
        }

        simRunner.resume();
    }

    /**
     * Stop the current engine.
     */
    public synchronized void stop() {
        if (currentMode == Mode.SIM && simRunner != null) {
            simRunner.stop();
        }

        currentMode = Mode.STOPPED;
        simRunner = null;
    }

    // ==================== Query Operations ====================

    /**
     * Get current operating mode.
     */
    public Mode getMode() {
        return currentMode;
    }

    /**
     * Get account state.
     */
    public AccountState getAccountState() {
        if (accountState == null) {
            throw new IllegalStateException("Engine not initialized");
        }
        return accountState;
    }

    /**
     * Get all open positions.
     */
    public Map<String, Position> getOpenPositions() {
        if (accountState == null) {
            return Collections.emptyMap();
        }
        return accountState.getPositions();
    }

    /**
     * Get completed trades.
     */
    public List<Trade> getCompletedTrades() {
        if (executionEngine == null) {
            return Collections.emptyList();
        }
        return executionEngine.getCompletedTrades();
    }

    /**
     * Get risk limits.
     */
    public RiskLimits getRiskLimits() {
        if (riskLimits == null) {
            // Return default Topstep 50K limits if not initialized
            return RiskLimits.topstep50k();
        }
        return riskLimits;
    }

    /**
     * Check if engine is running.
     */
    public boolean isRunning() {
        return currentMode != Mode.STOPPED &&
               (currentMode != Mode.SIM || (simRunner != null && simRunner.isRunning()));
    }

    /**
     * Check if engine is paused.
     */
    public boolean isPaused() {
        return currentMode == Mode.SIM &&
               simRunner != null &&
               simRunner.isPaused();
    }

    /**
     * Check if account is in good standing (not breached).
     */
    public boolean isAccountInGoodStanding() {
        if (accountState == null || riskLimits == null) {
            return true; // Default to good standing if not initialized
        }

        if (riskEngine != null) {
            return riskEngine.isAccountInGoodStanding(accountState, riskLimits);
        }

        // Manual check if risk engine not available
        return accountState.getNetDailyPnl() > -riskLimits.getDailyLossLimit() &&
               (accountState.getHighestEndOfDayBalance() - accountState.getEquity()) < riskLimits.getMaxLossLimit();
    }

    /**
     * Get remaining daily loss allowed.
     */
    public double getRemainingDailyLoss() {
        if (accountState == null || riskLimits == null) {
            return 0.0;
        }

        double dailyPnl = accountState.getNetDailyPnl();
        return riskLimits.getDailyLossLimit() + dailyPnl; // PnL is negative for losses
    }

    /**
     * Get current drawdown from highest balance.
     */
    public double getCurrentDrawdown() {
        if (accountState == null) {
            return 0.0;
        }

        return accountState.getHighestEndOfDayBalance() - accountState.getEquity();
    }

    /**
     * Get remaining drawdown allowed before max loss limit.
     */
    public double getRemainingDrawdown() {
        if (accountState == null || riskLimits == null) {
            return 0.0;
        }

        double currentDrawdown = getCurrentDrawdown();
        return riskLimits.getMaxLossLimit() - currentDrawdown;
    }

    /**
     * Get profit target progress (0.0 to 1.0).
     */
    public double getProfitTargetProgress() {
        if (accountState == null || riskLimits == null) {
            return 0.0;
        }

        double totalPnl = accountState.getRealizedPnL();
        double target = riskLimits.getProfitTarget();

        if (target <= 0) {
            return 0.0;
        }

        return Math.min(1.0, Math.max(0.0, totalPnl / target));
    }

    /**
     * Reset the facade (for testing).
     */
    public synchronized void reset() {
        stop();
        currentMode = Mode.STOPPED;
        accountState = null;
        executionEngine = null;
        riskLimits = null;
        strategy = null;
        riskEngine = null;
        simRunner = null;
    }

    /**
     * Get execution engine (for advanced access).
     */
    public ExecutionEngine getExecutionEngine() {
        return executionEngine;
    }

    /**
     * Get strategy (for advanced access).
     */
    public TradingStrategy getStrategy() {
        return strategy;
    }

    /**
     * Get risk engine (for advanced access).
     */
    public PropFirmRiskEngine getRiskEngine() {
        return riskEngine;
    }
}
