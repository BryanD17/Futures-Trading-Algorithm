package com.topstep.trading.montecarlo;

import com.topstep.trading.domain.Trade;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Statistical metrics extracted from backtest trades that feed the Monte Carlo simulator.
 * Captures win rate, R-multiple distributions, and trading frequency.
 */
public class StrategyMetrics {

    private final double winRate;
    private final double avgWinR;
    private final double avgLossR;
    private final double tradesPerDay;
    private final double expectancyPerTrade;
    private final List<Double> winRMultiples;
    private final List<Double> lossRMultiples;
    private final int totalTrades;
    private final int tradingDays;

    private StrategyMetrics(double winRate, double avgWinR, double avgLossR,
                            double tradesPerDay, List<Double> winRMultiples,
                            List<Double> lossRMultiples, int totalTrades, int tradingDays) {
        this.winRate = winRate;
        this.avgWinR = avgWinR;
        this.avgLossR = avgLossR;
        this.tradesPerDay = tradesPerDay;
        this.winRMultiples = winRMultiples;
        this.lossRMultiples = lossRMultiples;
        this.totalTrades = totalTrades;
        this.tradingDays = tradingDays;
        this.expectancyPerTrade = (winRate * avgWinR) - ((1.0 - winRate) * Math.abs(avgLossR));
    }

    /**
     * Extract strategy metrics from backtest trades.
     */
    public static StrategyMetrics fromBacktest(List<Trade> trades) {
        if (trades == null || trades.isEmpty()) {
            return new StrategyMetrics(0, 0, -1, 0, List.of(), List.of(), 0, 0);
        }

        List<Trade> winners = trades.stream().filter(t -> t.getPnl() > 0).collect(Collectors.toList());
        List<Trade> losers = trades.stream().filter(t -> t.getPnl() <= 0).collect(Collectors.toList());

        double winRate = (double) winners.size() / trades.size();

        // Extract R-multiples
        List<Double> winRs = winners.stream()
            .map(Trade::getRMultiple)
            .collect(Collectors.toList());
        List<Double> lossRs = losers.stream()
            .map(Trade::getRMultiple)
            .collect(Collectors.toList());

        double avgWinR = winRs.stream().mapToDouble(Double::doubleValue).average().orElse(2.0);
        double avgLossR = lossRs.stream().mapToDouble(Double::doubleValue).average().orElse(-1.0);

        // Count distinct trading days
        long distinctDays = trades.stream()
            .map(t -> t.getEntryTime() != null
                ? t.getEntryTime().atZone(ZoneId.systemDefault()).toLocalDate()
                : LocalDate.now())
            .distinct()
            .count();
        int tradingDays = Math.max(1, (int) distinctDays);

        double tradesPerDay = (double) trades.size() / tradingDays;

        return new StrategyMetrics(winRate, avgWinR, avgLossR, tradesPerDay,
            winRs, lossRs, trades.size(), tradingDays);
    }

    /**
     * Create metrics from manual parameters (for testing or hypothetical scenarios).
     */
    public static StrategyMetrics fromParameters(double winRate, double avgWinR, double avgLossR,
                                                   double tradesPerDay) {
        // Generate synthetic distributions
        List<Double> winRs = new ArrayList<>();
        List<Double> lossRs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            winRs.add(avgWinR);
            lossRs.add(avgLossR);
        }
        return new StrategyMetrics(winRate, avgWinR, avgLossR, tradesPerDay,
            winRs, lossRs, 100, (int)(100 / tradesPerDay));
    }

    // Getters
    public double getWinRate() { return winRate; }
    public double getAvgWinR() { return avgWinR; }
    public double getAvgLossR() { return avgLossR; }
    public double getTradesPerDay() { return tradesPerDay; }
    public double getExpectancyPerTrade() { return expectancyPerTrade; }
    public List<Double> getWinRMultiples() { return winRMultiples; }
    public List<Double> getLossRMultiples() { return lossRMultiples; }
    public int getTotalTrades() { return totalTrades; }
    public int getTradingDays() { return tradingDays; }

    @Override
    public String toString() {
        return String.format("StrategyMetrics{winRate=%.1f%%, avgWinR=%.2f, avgLossR=%.2f, " +
            "trades/day=%.1f, expectancy=%.3fR, trades=%d over %d days}",
            winRate * 100, avgWinR, avgLossR, tradesPerDay, expectancyPerTrade, totalTrades, tradingDays);
    }
}
