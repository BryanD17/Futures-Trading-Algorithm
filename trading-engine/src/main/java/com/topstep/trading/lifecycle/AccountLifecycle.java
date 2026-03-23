package com.topstep.trading.lifecycle;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Tracks the full lifecycle of a prop firm challenge account.
 * Manages phase transitions, equity path history, and computed metrics
 * that drive downstream risk/strategy behavior.
 *
 * Core computed metrics:
 *   - distanceToTarget(): dollars remaining to hit profit target
 *   - distanceToBlowup(): dollars remaining before MLL breach
 *   - targetCompletionPct(): progress toward passing (0.0 to 1.0)
 *   - drawdownUsagePct(): how much of MLL is consumed (0.0 to 1.0)
 *   - riskBudgetRemaining(): max dollars that can be risked before MLL
 */
public class AccountLifecycle {

    // Configuration
    private final double challengeFee;
    private final double profitTarget;
    private final double maxLossLimit;
    private final double dailyLossLimit;
    private final int maxContracts;
    private final double startingBalance;
    private final double profitSplit;

    // State tracking
    private volatile AccountPhase currentPhase;
    private volatile double currentEquity;
    private volatile double currentHighWaterMark;
    private volatile double dailyPnl;
    private volatile int tradingDaysElapsed;
    private volatile int totalTradesTaken;
    private volatile int consecutiveLosses;
    private volatile int consecutiveCruiseDays;
    private volatile boolean tradingPausedForDay;

    // History
    private final List<Double> dailyPnlHistory;
    private final List<Double> equityPathHistory;
    private final List<PhaseTransitionEvent> transitionHistory;

    // Event listeners
    private final List<Consumer<PhaseTransitionEvent>> transitionListeners;

    private AccountLifecycle(Builder builder) {
        this.challengeFee = builder.challengeFee;
        this.profitTarget = builder.profitTarget;
        this.maxLossLimit = builder.maxLossLimit;
        this.dailyLossLimit = builder.dailyLossLimit;
        this.maxContracts = builder.maxContracts;
        this.startingBalance = builder.startingBalance;
        this.profitSplit = builder.profitSplit;

        this.currentPhase = AccountPhase.EVALUATION;
        this.currentEquity = startingBalance;
        this.currentHighWaterMark = startingBalance;
        this.dailyPnl = 0.0;
        this.tradingDaysElapsed = 0;
        this.totalTradesTaken = 0;
        this.consecutiveLosses = 0;
        this.consecutiveCruiseDays = 0;
        this.tradingPausedForDay = false;

        this.dailyPnlHistory = new ArrayList<>();
        this.equityPathHistory = new ArrayList<>();
        this.equityPathHistory.add(startingBalance);
        this.transitionHistory = new ArrayList<>();
        this.transitionListeners = new CopyOnWriteArrayList<>();
    }

    // ==================== Computed Metrics ====================

    /**
     * Dollars remaining to hit the profit target.
     */
    public double distanceToTarget() {
        double totalPnl = currentEquity - startingBalance;
        return Math.max(0, profitTarget - totalPnl);
    }

    /**
     * Dollars remaining before MLL breach (from high water mark).
     */
    public double distanceToBlowup() {
        double currentDrawdown = currentHighWaterMark - currentEquity;
        return Math.max(0, maxLossLimit - currentDrawdown);
    }

    /**
     * Progress toward passing the evaluation (0.0 to 1.0).
     */
    public double targetCompletionPct() {
        double totalPnl = currentEquity - startingBalance;
        if (profitTarget <= 0) return 0.0;
        return Math.min(1.0, Math.max(0.0, totalPnl / profitTarget));
    }

    /**
     * How much of the max loss limit has been consumed (0.0 to 1.0).
     * Higher = more danger.
     */
    public double drawdownUsagePct() {
        double currentDrawdown = currentHighWaterMark - currentEquity;
        if (maxLossLimit <= 0) return 0.0;
        return Math.min(1.0, Math.max(0.0, currentDrawdown / maxLossLimit));
    }

    /**
     * Maximum dollars that can still be risked before MLL breach.
     */
    public double riskBudgetRemaining() {
        return distanceToBlowup();
    }

    /**
     * Remaining daily loss room (self-imposed buffer is tighter than firm's DLL).
     */
    public double dailyLossRoomRemaining() {
        return Math.max(0, dailyLossLimit + dailyPnl); // dailyPnl is negative for losses
    }

    /**
     * Determine the current risk zone based on equity position.
     */
    public RiskZone getCurrentRiskZone() {
        double targetPct = targetCompletionPct();
        double drawdownPct = drawdownUsagePct();

        if (drawdownPct >= 0.60) return RiskZone.DANGER;
        if (drawdownPct >= 0.40) return RiskZone.CAUTION;
        if (targetPct >= 0.85) return RiskZone.CRUISE;
        if (targetPct >= 0.50) return RiskZone.PROTECTION;
        return RiskZone.NORMAL;
    }

    // ==================== Day End Processing ====================

    /**
     * Process end-of-day: record daily PnL, update HWM, check transitions.
     * @param dayPnl The realized PnL for the completed trading day
     * @return Phase transition event if a transition occurred, null otherwise
     */
    public PhaseTransitionEvent onDayEnd(double dayPnl) {
        this.dailyPnl = 0.0; // Reset for next day
        this.tradingPausedForDay = false;
        this.tradingDaysElapsed++;

        dailyPnlHistory.add(dayPnl);
        currentEquity = startingBalance + dailyPnlHistory.stream().mapToDouble(Double::doubleValue).sum();
        equityPathHistory.add(currentEquity);

        // Update high water mark
        if (currentEquity > currentHighWaterMark) {
            currentHighWaterMark = currentEquity;
        }

        // Track cruise control days
        if (getCurrentRiskZone() == RiskZone.CRUISE) {
            if (dayPnl <= 0) {
                consecutiveCruiseDays++;
            } else {
                consecutiveCruiseDays = 0;
            }
        } else {
            consecutiveCruiseDays = 0;
        }

        return shouldTransitionPhase();
    }

    /**
     * Update intraday PnL tracking (called during the trading day).
     */
    public void updateIntradayPnl(double currentDailyPnl) {
        this.dailyPnl = currentDailyPnl;
    }

    /**
     * Record a trade completion.
     */
    public void recordTrade(double tradePnl) {
        totalTradesTaken++;
        if (tradePnl < 0) {
            consecutiveLosses++;
        } else {
            consecutiveLosses = 0;
        }
    }

    /**
     * Set trading paused for the rest of the day (e.g., after consecutive losses).
     */
    public void pauseTradingForDay() {
        this.tradingPausedForDay = true;
    }

    // ==================== Phase Transitions ====================

    /**
     * Check if a phase transition should occur based on current state.
     */
    private PhaseTransitionEvent shouldTransitionPhase() {
        if (currentPhase.isTerminal()) return null;

        // Check MLL breach -> BLOWN (from any active phase)
        double currentDrawdown = currentHighWaterMark - currentEquity;
        if (currentDrawdown >= maxLossLimit) {
            return performTransition(AccountPhase.BLOWN,
                "Max Loss Limit breached: drawdown $" + String.format("%.2f", currentDrawdown) +
                " >= MLL $" + String.format("%.2f", maxLossLimit));
        }

        // Check phase-specific transitions
        switch (currentPhase) {
            case EVALUATION:
                // EVALUATION -> FUNDED_PROBATION when profit target hit
                double totalPnl = currentEquity - startingBalance;
                if (totalPnl >= profitTarget) {
                    return performTransition(AccountPhase.FUNDED_PROBATION,
                        "Profit target reached: $" + String.format("%.2f", totalPnl) +
                        " >= $" + String.format("%.2f", profitTarget));
                }
                break;

            case FUNDED_PROBATION:
                // FUNDED_PROBATION -> FUNDED_SCALING after sufficient time/payouts
                // Transition after 30 trading days of funded trading
                if (tradingDaysElapsed >= 30) {
                    return performTransition(AccountPhase.FUNDED_SCALING,
                        "Completed 30 trading days in funded probation");
                }
                break;

            default:
                break;
        }

        return null;
    }

    /**
     * Force a phase transition (for external triggers like payout milestones).
     */
    public PhaseTransitionEvent forceTransition(AccountPhase targetPhase, String reason) {
        return performTransition(targetPhase, reason);
    }

    private PhaseTransitionEvent performTransition(AccountPhase targetPhase, String reason) {
        AccountPhase fromPhase = currentPhase;
        currentPhase = fromPhase.transitionTo(targetPhase);

        PhaseTransitionEvent event = new PhaseTransitionEvent(
            fromPhase, targetPhase, reason, currentEquity, tradingDaysElapsed);

        transitionHistory.add(event);
        notifyListeners(event);

        return event;
    }

    // ==================== Event Listeners ====================

    public void addTransitionListener(Consumer<PhaseTransitionEvent> listener) {
        transitionListeners.add(listener);
    }

    public void removeTransitionListener(Consumer<PhaseTransitionEvent> listener) {
        transitionListeners.remove(listener);
    }

    private void notifyListeners(PhaseTransitionEvent event) {
        for (Consumer<PhaseTransitionEvent> listener : transitionListeners) {
            listener.accept(event);
        }
    }

    // ==================== Getters ====================

    public AccountPhase getCurrentPhase() { return currentPhase; }
    public double getChallengeFee() { return challengeFee; }
    public double getProfitTarget() { return profitTarget; }
    public double getMaxLossLimit() { return maxLossLimit; }
    public double getDailyLossLimit() { return dailyLossLimit; }
    public int getMaxContracts() { return maxContracts; }
    public double getStartingBalance() { return startingBalance; }
    public double getProfitSplit() { return profitSplit; }
    public double getCurrentEquity() { return currentEquity; }
    public double getCurrentHighWaterMark() { return currentHighWaterMark; }
    public double getDailyPnl() { return dailyPnl; }
    public int getTradingDaysElapsed() { return tradingDaysElapsed; }
    public int getTotalTradesTaken() { return totalTradesTaken; }
    public int getConsecutiveLosses() { return consecutiveLosses; }
    public int getConsecutiveCruiseDays() { return consecutiveCruiseDays; }
    public boolean isTradingPausedForDay() { return tradingPausedForDay; }
    public List<Double> getDailyPnlHistory() { return Collections.unmodifiableList(dailyPnlHistory); }
    public List<Double> getEquityPathHistory() { return Collections.unmodifiableList(equityPathHistory); }
    public List<PhaseTransitionEvent> getTransitionHistory() { return Collections.unmodifiableList(transitionHistory); }

    // ==================== Live Equity Sync ====================

    /**
     * Sync equity from the live AccountState.
     * Called periodically (e.g., on each candle) to keep lifecycle metrics current.
     * Updates currentEquity and high water mark based on live balance.
     *
     * @param liveEquity The current equity from AccountState
     */
    public void syncEquityFromLive(double liveEquity) {
        this.currentEquity = liveEquity;
        if (liveEquity > currentHighWaterMark) {
            currentHighWaterMark = liveEquity;
        }
    }

    /**
     * Reset consecutive loss counter (e.g., at start of new trading day).
     */
    public void resetConsecutiveLosses() {
        this.consecutiveLosses = 0;
    }

    // ==================== Factories ====================

    /**
     * Create lifecycle for Topstep 50K evaluation account.
     */
    public static AccountLifecycle topstep50kEvaluation() {
        return builder()
            .challengeFee(49.0)
            .profitTarget(3000.0)
            .maxLossLimit(2000.0)
            .dailyLossLimit(1000.0)
            .maxContracts(5)
            .startingBalance(50000.0)
            .profitSplit(0.90)
            .build();
    }

    /**
     * Create lifecycle for Topstep 100K evaluation account.
     */
    public static AccountLifecycle topstep100kEvaluation() {
        return builder()
            .challengeFee(99.0)
            .profitTarget(6000.0)
            .maxLossLimit(3000.0)
            .dailyLossLimit(2000.0)
            .maxContracts(10)
            .startingBalance(100000.0)
            .profitSplit(0.90)
            .build();
    }

    /**
     * Create lifecycle for Topstep 150K evaluation account.
     */
    public static AccountLifecycle topstep150kEvaluation() {
        return builder()
            .challengeFee(149.0)
            .profitTarget(9000.0)
            .maxLossLimit(4500.0)
            .dailyLossLimit(3000.0)
            .maxContracts(15)
            .startingBalance(150000.0)
            .profitSplit(0.90)
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return String.format("AccountLifecycle{phase=%s, equity=%.2f, target=%.1f%%, drawdown=%.1f%%, zone=%s}",
            currentPhase, currentEquity, targetCompletionPct() * 100, drawdownUsagePct() * 100, getCurrentRiskZone());
    }

    // ==================== Builder ====================

    public static class Builder {
        private double challengeFee = 49.0;
        private double profitTarget = 3000.0;
        private double maxLossLimit = 2000.0;
        private double dailyLossLimit = 1000.0;
        private int maxContracts = 5;
        private double startingBalance = 50000.0;
        private double profitSplit = 0.90;

        public Builder challengeFee(double challengeFee) { this.challengeFee = challengeFee; return this; }
        public Builder profitTarget(double profitTarget) { this.profitTarget = profitTarget; return this; }
        public Builder maxLossLimit(double maxLossLimit) { this.maxLossLimit = maxLossLimit; return this; }
        public Builder dailyLossLimit(double dailyLossLimit) { this.dailyLossLimit = dailyLossLimit; return this; }
        public Builder maxContracts(int maxContracts) { this.maxContracts = maxContracts; return this; }
        public Builder startingBalance(double startingBalance) { this.startingBalance = startingBalance; return this; }
        public Builder profitSplit(double profitSplit) { this.profitSplit = profitSplit; return this; }

        public AccountLifecycle build() {
            return new AccountLifecycle(this);
        }
    }
}
