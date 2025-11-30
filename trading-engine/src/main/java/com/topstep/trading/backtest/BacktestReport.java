package com.topstep.trading.backtest;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.domain.Trade;

import java.util.List;

/**
 * Backtest report containing performance metrics and statistics.
 */
public class BacktestReport {

    private final List<Trade> trades;
    private final AccountState finalAccountState;
    private final RiskLimits riskLimits;

    // Metrics
    private final int totalTrades;
    private final int winningTrades;
    private final int losingTrades;
    private final double winRate;
    private final double totalPnl;
    private final double averagePnl;
    private final double largestWin;
    private final double largestLoss;
    private final double averageWin;
    private final double averageLoss;
    private final double profitFactor;
    private final double maxDrawdown;

    public BacktestReport(List<Trade> trades, AccountState finalAccountState, RiskLimits riskLimits) {
        this.trades = trades;
        this.finalAccountState = finalAccountState;
        this.riskLimits = riskLimits;

        // Calculate metrics
        this.totalTrades = trades.size();

        long wins = trades.stream().filter(t -> t.getPnl() > 0).count();
        long losses = trades.stream().filter(t -> t.getPnl() < 0).count();

        this.winningTrades = (int) wins;
        this.losingTrades = (int) losses;
        this.winRate = totalTrades > 0 ? (double) winningTrades / totalTrades * 100.0 : 0.0;

        this.totalPnl = finalAccountState.getRealizedPnL();
        this.averagePnl = totalTrades > 0 ? totalPnl / totalTrades : 0.0;

        this.largestWin = trades.stream().mapToDouble(Trade::getPnl).max().orElse(0.0);
        this.largestLoss = trades.stream().mapToDouble(Trade::getPnl).min().orElse(0.0);

        this.averageWin = trades.stream()
                .filter(t -> t.getPnl() > 0)
                .mapToDouble(Trade::getPnl)
                .average()
                .orElse(0.0);

        this.averageLoss = trades.stream()
                .filter(t -> t.getPnl() < 0)
                .mapToDouble(Trade::getPnl)
                .average()
                .orElse(0.0);

        double totalWins = trades.stream().filter(t -> t.getPnl() > 0).mapToDouble(Trade::getPnl).sum();
        double totalLosses = Math.abs(trades.stream().filter(t -> t.getPnl() < 0).mapToDouble(Trade::getPnl).sum());
        this.profitFactor = totalLosses > 0 ? totalWins / totalLosses : 0.0;

        this.maxDrawdown = calculateMaxDrawdown();
    }

    private double calculateMaxDrawdown() {
        double peak = finalAccountState.getStartingBalance();
        double maxDD = 0.0;
        double runningPnl = 0.0;

        for (Trade trade : trades) {
            runningPnl += trade.getPnl();
            double currentEquity = finalAccountState.getStartingBalance() + runningPnl;

            if (currentEquity > peak) {
                peak = currentEquity;
            }

            double drawdown = peak - currentEquity;
            if (drawdown > maxDD) {
                maxDD = drawdown;
            }
        }

        return maxDD;
    }

    /**
     * Print the backtest report to console.
     */
    public void printReport() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("BACKTEST REPORT");
        System.out.println("=".repeat(60));

        System.out.println("\nAccount Summary:");
        System.out.println("  Starting Balance: $" + String.format("%.2f", finalAccountState.getStartingBalance()));
        System.out.println("  Final Balance:    $" + String.format("%.2f", finalAccountState.getCurrentBalance()));
        System.out.println("  Total P&L:        $" + String.format("%.2f", totalPnl));
        System.out.println("  Return:           " + String.format("%.2f%%",
                (totalPnl / finalAccountState.getStartingBalance()) * 100));

        System.out.println("\nTrade Statistics:");
        System.out.println("  Total Trades:     " + totalTrades);
        System.out.println("  Winning Trades:   " + winningTrades);
        System.out.println("  Losing Trades:    " + losingTrades);
        System.out.println("  Win Rate:         " + String.format("%.2f%%", winRate));

        System.out.println("\nP&L Metrics:");
        System.out.println("  Average P&L:      $" + String.format("%.2f", averagePnl));
        System.out.println("  Largest Win:      $" + String.format("%.2f", largestWin));
        System.out.println("  Largest Loss:     $" + String.format("%.2f", largestLoss));
        System.out.println("  Average Win:      $" + String.format("%.2f", averageWin));
        System.out.println("  Average Loss:     $" + String.format("%.2f", averageLoss));
        System.out.println("  Profit Factor:    " + String.format("%.2f", profitFactor));

        System.out.println("\nRisk Metrics:");
        System.out.println("  Max Drawdown:     $" + String.format("%.2f", maxDrawdown));
        System.out.println("  Daily Loss Limit: $" + String.format("%.2f", riskLimits.getMaxDailyLoss()));
        System.out.println("  Max Loss Limit:   $" + String.format("%.2f", riskLimits.getMaxLossLimit()));
        System.out.println("  Profit Target:    $" + String.format("%.2f", riskLimits.getProfitTarget()));

        boolean dllBreached = finalAccountState.getNetDailyPnl() <= -riskLimits.getMaxDailyLoss();
        boolean mllBreached = maxDrawdown >= riskLimits.getMaxLossLimit();
        boolean targetMet = totalPnl >= riskLimits.getProfitTarget();

        System.out.println("\nTopstep Compliance:");
        System.out.println("  DLL Breached:     " + (dllBreached ? "YES ❌" : "NO ✓"));
        System.out.println("  MLL Breached:     " + (mllBreached ? "YES ❌" : "NO ✓"));
        System.out.println("  Profit Target Met: " + (targetMet ? "YES ✓" : "NO"));

        System.out.println("\n" + "=".repeat(60) + "\n");
    }

    // Getters
    public int getTotalTrades() { return totalTrades; }
    public int getWinningTrades() { return winningTrades; }
    public int getLosingTrades() { return losingTrades; }
    public double getWinRate() { return winRate; }
    public double getTotalPnl() { return totalPnl; }
    public double getAveragePnl() { return averagePnl; }
    public double getLargestWin() { return largestWin; }
    public double getLargestLoss() { return largestLoss; }
    public double getAverageWin() { return averageWin; }
    public double getAverageLoss() { return averageLoss; }
    public double getProfitFactor() { return profitFactor; }
    public double getMaxDrawdown() { return maxDrawdown; }
    public List<Trade> getTrades() { return trades; }
}
