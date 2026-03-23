package com.topstep.trading;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Position;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.domain.Trade;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.lifecycle.AccountPhase;
import com.topstep.trading.lifecycle.RiskZone;
import com.topstep.trading.risk.PhaseAwareRiskCalculator;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskProfile;
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
    private LiveEngineRunner liveRunner;

    // Convex Payoff Optimization Layer
    private AccountLifecycle accountLifecycle;
    private RiskProfile activeRiskProfile;
    private PhaseAwareRiskCalculator phaseAwareRiskCalculator;

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

    /**
     * Register the LIVE runner for control operations.
     */
    public void setLiveRunner(LiveEngineRunner liveRunner) {
        this.liveRunner = liveRunner;
    }

    // ==================== Control Operations ====================

    /**
     * Start SIM mode.
     */
    public synchronized void startSim() {
        if (currentMode == Mode.SIM && simRunner != null && simRunner.isRunning()) {
            throw new IllegalStateException("SIM mode already running");
        }
        if (currentMode == Mode.LIVE && liveRunner != null && liveRunner.isRunning()) {
            throw new IllegalStateException("Cannot start SIM while LIVE is running");
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
     * Start LIVE mode.
     * WARNING: This connects to real markets and uses real money!
     */
    public synchronized void startLive() {
        if (currentMode == Mode.LIVE && liveRunner != null && liveRunner.isRunning()) {
            throw new IllegalStateException("LIVE mode already running");
        }
        if (currentMode == Mode.SIM && simRunner != null && simRunner.isRunning()) {
            throw new IllegalStateException("Cannot start LIVE while SIM is running. Stop SIM first.");
        }

        // Create and start new LIVE runner in background thread
        liveRunner = new LiveEngineRunner();
        initialize(Mode.LIVE,
                liveRunner.getAccountState(),
                liveRunner.getExecutionEngine(),
                liveRunner.getRiskLimits(),
                null, // Strategy reference not needed by API
                null  // RiskEngine reference not needed by API
        );

        // Wire lifecycle components from LiveEngineRunner for dashboard access
        this.accountLifecycle = liveRunner.getLifecycle();
        this.phaseAwareRiskCalculator = liveRunner.getRiskCalculator();
        this.activeRiskProfile = liveRunner.getRiskProfile();

        Thread liveThread = new Thread(() -> {
            liveRunner.start();
        }, "LIVE-Engine-Thread");
        liveThread.setDaemon(false);
        liveThread.start();

        // Give it a moment to start
        try {
            Thread.sleep(1000);
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
     * Pause LIVE mode.
     */
    public synchronized void pauseLive() {
        if (currentMode != Mode.LIVE) {
            throw new IllegalStateException("Not in LIVE mode");
        }
        if (liveRunner == null) {
            throw new IllegalStateException("LIVE runner not initialized");
        }

        liveRunner.pause();
    }

    /**
     * Pause current mode (SIM or LIVE).
     */
    public synchronized void pause() {
        if (currentMode == Mode.SIM) {
            pauseSim();
        } else if (currentMode == Mode.LIVE) {
            pauseLive();
        } else {
            throw new IllegalStateException("No engine running to pause");
        }
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
     * Resume LIVE mode.
     */
    public synchronized void resumeLive() {
        if (currentMode != Mode.LIVE) {
            throw new IllegalStateException("Not in LIVE mode");
        }
        if (liveRunner == null) {
            throw new IllegalStateException("LIVE runner not initialized");
        }

        liveRunner.resume();
    }

    /**
     * Resume current mode (SIM or LIVE).
     */
    public synchronized void resume() {
        if (currentMode == Mode.SIM) {
            resumeSim();
        } else if (currentMode == Mode.LIVE) {
            resumeLive();
        } else {
            throw new IllegalStateException("No engine running to resume");
        }
    }

    /**
     * Stop the current engine.
     */
    public synchronized void stop() {
        if (currentMode == Mode.SIM && simRunner != null) {
            simRunner.stop();
        }
        if (currentMode == Mode.LIVE && liveRunner != null) {
            liveRunner.stop();
        }

        currentMode = Mode.STOPPED;
        simRunner = null;
        liveRunner = null;
    }

    /**
     * Activate kill switch (LIVE mode only).
     */
    public synchronized void activateKillSwitch(String reason) {
        if (currentMode != Mode.LIVE) {
            throw new IllegalStateException("Kill switch only available in LIVE mode");
        }
        if (liveRunner == null) {
            throw new IllegalStateException("LIVE runner not initialized");
        }

        liveRunner.activateKillSwitch(reason);
    }

    /**
     * Flatten all positions (LIVE mode).
     */
    public synchronized void flattenAllPositions(String reason) {
        if (currentMode != Mode.LIVE) {
            throw new IllegalStateException("Flatten only available in LIVE mode");
        }
        if (liveRunner == null) {
            throw new IllegalStateException("LIVE runner not initialized");
        }

        liveRunner.flattenAllPositions(reason);
    }

    /**
     * Check if kill switch is active (LIVE mode).
     */
    public boolean isKillSwitchActive() {
        return currentMode == Mode.LIVE && liveRunner != null && liveRunner.isKillSwitchActive();
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
        if (currentMode == Mode.STOPPED) {
            return false;
        }
        if (currentMode == Mode.SIM) {
            return simRunner != null && simRunner.isRunning();
        }
        if (currentMode == Mode.LIVE) {
            return liveRunner != null && liveRunner.isRunning();
        }
        return false;
    }

    /**
     * Check if engine is paused.
     */
    public boolean isPaused() {
        if (currentMode == Mode.SIM && simRunner != null) {
            return simRunner.isPaused();
        }
        if (currentMode == Mode.LIVE && liveRunner != null) {
            return liveRunner.isPaused();
        }
        return false;
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
        liveRunner = null;
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

    // ==================== Lifecycle Operations ====================

    /**
     * Initialize the account lifecycle for convex payoff optimization.
     */
    public void initializeLifecycle(AccountLifecycle lifecycle) {
        this.accountLifecycle = lifecycle;
        this.phaseAwareRiskCalculator = new PhaseAwareRiskCalculator();
        this.activeRiskProfile = getProfileForPhase(lifecycle.getCurrentPhase());
    }

    /**
     * Get the active account lifecycle.
     */
    public AccountLifecycle getAccountLifecycle() {
        return accountLifecycle;
    }

    /**
     * Get the active risk profile for the current phase.
     */
    public RiskProfile getActiveRiskProfile() {
        if (activeRiskProfile == null && accountLifecycle != null) {
            activeRiskProfile = getProfileForPhase(accountLifecycle.getCurrentPhase());
        }
        return activeRiskProfile;
    }

    /**
     * Get the phase-aware risk calculator.
     */
    public PhaseAwareRiskCalculator getPhaseAwareRiskCalculator() {
        return phaseAwareRiskCalculator;
    }

    /**
     * Get current account phase.
     */
    public AccountPhase getCurrentPhase() {
        return accountLifecycle != null ? accountLifecycle.getCurrentPhase() : null;
    }

    /**
     * Get current risk zone.
     */
    public RiskZone getCurrentRiskZone() {
        return accountLifecycle != null ? accountLifecycle.getCurrentRiskZone() : RiskZone.NORMAL;
    }

    /**
     * Get distance to profit target.
     */
    public double getDistanceToTarget() {
        return accountLifecycle != null ? accountLifecycle.distanceToTarget() : 0.0;
    }

    /**
     * Get drawdown usage percentage.
     */
    public double getDrawdownUsagePct() {
        return accountLifecycle != null ? accountLifecycle.drawdownUsagePct() : 0.0;
    }

    /**
     * Get target completion percentage.
     */
    public double getTargetCompletionPct() {
        return accountLifecycle != null ? accountLifecycle.targetCompletionPct() : 0.0;
    }

    /**
     * Get the risk profile for a given account phase.
     */
    private RiskProfile getProfileForPhase(AccountPhase phase) {
        switch (phase) {
            case EVALUATION:        return RiskProfile.topstep50kEvaluation();
            case FUNDED_PROBATION:  return RiskProfile.topstep50kFunded();
            case FUNDED_SCALING:    return RiskProfile.topstep50kScaling();
            default:                return RiskProfile.topstep50kEvaluation();
        }
    }
}
