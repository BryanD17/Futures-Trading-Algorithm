package com.topstep.trading.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents the current state of the trading account.
 * Tracks balance, equity, PnL, and positions.
 */
public class AccountState {
    private double startingBalance;
    private double currentBalance;
    private double realizedPnL;
    private double unrealizedPnL;

    // Topstep-specific tracking
    private double realizedPnlToday;
    private double unrealizedPnlToday;
    private double highestEndOfDayBalance;
    private LocalDate currentTradingDay;

    private final Map<String, Position> positions;
    private final Map<LocalDate, Double> dailyPnL;

    private Instant lastUpdated;

    public AccountState(double startingBalance) {
        this.startingBalance = startingBalance;
        this.currentBalance = startingBalance;
        this.realizedPnL = 0.0;
        this.unrealizedPnL = 0.0;
        this.realizedPnlToday = 0.0;
        this.unrealizedPnlToday = 0.0;
        this.highestEndOfDayBalance = startingBalance;
        this.currentTradingDay = LocalDate.now();
        this.positions = new ConcurrentHashMap<>();
        this.dailyPnL = new ConcurrentHashMap<>();
        this.lastUpdated = Instant.now();
    }

    // Getters
    public double getStartingBalance() { return startingBalance; }
    public double getCurrentBalance() { return currentBalance; }
    public double getRealizedPnL() { return realizedPnL; }
    public double getUnrealizedPnL() { return unrealizedPnL; }
    public double getEquity() { return currentBalance + unrealizedPnL; }
    public Map<String, Position> getPositions() { return new HashMap<>(positions); }
    public Instant getLastUpdated() { return lastUpdated; }

    // Topstep-specific getters
    public double getRealizedPnlToday() { return realizedPnlToday; }
    public double getUnrealizedPnlToday() { return unrealizedPnlToday; }
    public double getHighestEndOfDayBalance() { return highestEndOfDayBalance; }
    public double getNetDailyPnl() { return realizedPnlToday + unrealizedPnlToday; }
    public LocalDate getCurrentTradingDay() { return currentTradingDay; }

    /**
     * Set the current balance (used when syncing with live account).
     */
    public void setCurrentBalance(double balance) {
        this.currentBalance = balance;
        this.lastUpdated = Instant.now();
    }

    /**
     * Add a position to the account.
     */
    public void addPosition(Position position) {
        if (position != null) {
            positions.put(position.getSymbol(), position);
            this.lastUpdated = Instant.now();
        }
    }

    public Position getPosition(String symbol) {
        return positions.get(symbol);
    }

    /**
     * Clear all tracked positions (used during controlled shutdowns when we are not
     * actively flattening on the exchange).
     */
    public void clearAllPositions() {
        positions.clear();
        this.lastUpdated = Instant.now();
    }

    public boolean hasPosition(String symbol) {
        Position pos = positions.get(symbol);
        return pos != null && !pos.isFlat();
    }

    public int getTotalContracts() {
        return positions.values().stream()
                .mapToInt(p -> Math.abs(p.getQuantity()))
                .sum();
    }

    /**
     * Get today's realized PnL.
     */
    public double getTodayPnL() {
        return dailyPnL.getOrDefault(currentTradingDay, 0.0);
    }

    /**
     * Get PnL for a specific trading day.
     *
     * @param tradingDay Trading day to query
     * @return Realized PnL for that day
     */
    public double getPnLForDay(LocalDate tradingDay) {
        return dailyPnL.getOrDefault(tradingDay, 0.0);
    }

    /**
     * Update position based on a fill.
     */
    public void updatePosition(String symbol, int fillQuantity, double fillPrice) {
        Position position = positions.computeIfAbsent(symbol,
            s -> new Position(s, 0, 0.0));

        position.updateWithFill(fillQuantity, fillPrice);

        if (position.isFlat()) {
            positions.remove(symbol);
        }

        this.lastUpdated = Instant.now();
    }

    /**
     * Close/remove a position for a symbol.
     * Called when bracket order fills (SL or TP hit).
     */
    public void closePosition(String symbol) {
        positions.remove(symbol);
        this.lastUpdated = Instant.now();
    }

    /**
     * Record realized PnL from closing a position.
     */
    public void recordRealizedPnL(double pnl) {
        recordRealizedPnL(pnl, currentTradingDay);
    }

    /**
     * Record realized PnL from closing a position with explicit trading day.
     *
     * @param pnl Realized profit/loss
     * @param tradingDay Trading day for this PnL
     */
    public void recordRealizedPnL(double pnl, LocalDate tradingDay) {
        this.realizedPnL += pnl;
        this.currentBalance += pnl;

        checkAndResetTradingDay(tradingDay);

        this.realizedPnlToday += pnl;
        dailyPnL.merge(tradingDay, pnl, Double::sum);

        this.lastUpdated = Instant.now();
    }

    /**
     * Update unrealized PnL based on current market prices.
     */
    public void updateUnrealizedPnL(Map<String, Double> currentPrices, Map<String, Double> tickValues) {
        updateUnrealizedPnL(currentPrices, tickValues, currentTradingDay);
    }

    /**
     * Update unrealized PnL based on current market prices with explicit trading day.
     *
     * @param currentPrices Current market prices by symbol
     * @param tickValues Tick values by symbol
     * @param tradingDay Current trading day
     */
    public void updateUnrealizedPnL(Map<String, Double> currentPrices, Map<String, Double> tickValues, LocalDate tradingDay) {
        checkAndResetTradingDay(tradingDay);

        double totalUnrealized = 0.0;

        for (Position position : positions.values()) {
            Double currentPrice = currentPrices.get(position.getSymbol());
            Double tickValue = tickValues.get(position.getSymbol());

            if (currentPrice != null && tickValue != null) {
                totalUnrealized += position.getUnrealizedPnL(currentPrice, tickValue);
            }
        }

        this.unrealizedPnL = totalUnrealized;
        this.unrealizedPnlToday = totalUnrealized;
        this.lastUpdated = Instant.now();
    }

    /**
     * Reset daily counters (call at start of new trading day).
     */
    public void resetDailyCounters() {
        resetDailyCounters(currentTradingDay);
    }

    /**
     * Reset daily counters for a specific trading day.
     *
     * @param tradingDay Trading day to reset for
     */
    public void resetDailyCounters(LocalDate tradingDay) {
        checkAndResetTradingDay(tradingDay);
    }

    /**
     * End of day balance update (for Topstep Max Loss Limit calculation).
     */
    public void endOfDay() {
        double endOfDayBalance = currentBalance;
        if (endOfDayBalance > highestEndOfDayBalance) {
            highestEndOfDayBalance = endOfDayBalance;
        }
    }

    /**
     * Check if we've moved to a new trading day and reset counters if needed.
     *
     * @param tradingDay The trading day to check against
     */
    private void checkAndResetTradingDay(LocalDate tradingDay) {
        if (currentTradingDay == null) {
            // First initialization
            currentTradingDay = tradingDay;
            realizedPnlToday = 0.0;
            unrealizedPnlToday = 0.0;
            dailyPnL.put(tradingDay, 0.0);
            return;
        }

        if (!tradingDay.equals(currentTradingDay)) {
            // New trading day - update highest end of day balance from previous day
            endOfDay();

            // Reset today's counters
            realizedPnlToday = 0.0;
            unrealizedPnlToday = 0.0;
            currentTradingDay = tradingDay;
            dailyPnL.put(tradingDay, 0.0);
        }
    }

    /**
     * Force a new trading day (for backtest session boundaries).
     *
     * @param newTradingDay New trading day to start
     */
    public void startNewTradingDay(LocalDate newTradingDay) {
        if (currentTradingDay != null && !newTradingDay.equals(currentTradingDay)) {
            // Finalize previous day
            endOfDay();
        }

        // Start new day
        currentTradingDay = newTradingDay;
        realizedPnlToday = 0.0;
        unrealizedPnlToday = 0.0;
        dailyPnL.put(newTradingDay, 0.0);
    }

    @Override
    public String toString() {
        return String.format("AccountState{balance=%.2f, equity=%.2f, realizedPnL=%.2f, unrealizedPnL=%.2f, positions=%d}",
                currentBalance, getEquity(), realizedPnL, unrealizedPnL, positions.size());
    }
}
