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

    private final Map<String, Position> positions;
    private final Map<LocalDate, Double> dailyPnL;

    private Instant lastUpdated;

    public AccountState(double startingBalance) {
        this.startingBalance = startingBalance;
        this.currentBalance = startingBalance;
        this.realizedPnL = 0.0;
        this.unrealizedPnL = 0.0;
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

    public Position getPosition(String symbol) {
        return positions.get(symbol);
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
        LocalDate today = LocalDate.now();
        return dailyPnL.getOrDefault(today, 0.0);
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
     * Record realized PnL from closing a position.
     */
    public void recordRealizedPnL(double pnl) {
        this.realizedPnL += pnl;
        this.currentBalance += pnl;

        LocalDate today = LocalDate.now();
        dailyPnL.merge(today, pnl, Double::sum);

        this.lastUpdated = Instant.now();
    }

    /**
     * Update unrealized PnL based on current market prices.
     */
    public void updateUnrealizedPnL(Map<String, Double> currentPrices, Map<String, Double> tickValues) {
        double totalUnrealized = 0.0;

        for (Position position : positions.values()) {
            Double currentPrice = currentPrices.get(position.getSymbol());
            Double tickValue = tickValues.get(position.getSymbol());

            if (currentPrice != null && tickValue != null) {
                totalUnrealized += position.getUnrealizedPnL(currentPrice, tickValue);
            }
        }

        this.unrealizedPnL = totalUnrealized;
        this.lastUpdated = Instant.now();
    }

    /**
     * Reset daily counters (call at start of new trading day).
     */
    public void resetDailyCounters() {
        LocalDate today = LocalDate.now();
        dailyPnL.put(today, 0.0);
    }

    @Override
    public String toString() {
        return String.format("AccountState{balance=%.2f, equity=%.2f, realizedPnL=%.2f, unrealizedPnL=%.2f, positions=%d}",
                currentBalance, getEquity(), realizedPnL, unrealizedPnL, positions.size());
    }
}
