package com.topstep.api.controller;

import com.topstep.trading.EngineFacade;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Trade;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for trading metrics and performance.
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final EngineFacade engine = EngineFacade.getInstance();

    @GetMapping("/daily")
    public ResponseEntity<Map<String, Object>> getDailyMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        try {
            AccountState account = engine.getAccountState();
            List<Trade> trades = engine.getCompletedTrades();

            // Balance metrics
            metrics.put("startingBalance", account.getStartingBalance());
            metrics.put("currentBalance", account.getCurrentBalance());
            metrics.put("realizedPnL", account.getRealizedPnL());
            metrics.put("unrealizedPnL", account.getUnrealizedPnL());
            metrics.put("totalPnL", account.getRealizedPnL() + account.getUnrealizedPnL());

            // Trade statistics
            metrics.put("tradesCount", trades.size());

            // Calculate win rate
            if (!trades.isEmpty()) {
                long winningTrades = trades.stream()
                        .filter(t -> t.getRealizedPnL() > 0)
                        .count();
                metrics.put("winRate", (double) winningTrades / trades.size());
            } else {
                metrics.put("winRate", 0.0);
            }

            // Max drawdown
            metrics.put("maxDrawdown", engine.getCurrentDrawdown());

            // Daily metrics
            metrics.put("dailyPnL", account.getNetDailyPnl());
            metrics.put("tradingDay", account.getCurrentTradingDay().toString());

        } catch (IllegalStateException e) {
            // Engine not initialized - return default values
            metrics.put("startingBalance", 50000.0);
            metrics.put("currentBalance", 50000.0);
            metrics.put("realizedPnL", 0.0);
            metrics.put("unrealizedPnL", 0.0);
            metrics.put("totalPnL", 0.0);
            metrics.put("tradesCount", 0);
            metrics.put("winRate", 0.0);
            metrics.put("maxDrawdown", 0.0);
            metrics.put("dailyPnL", 0.0);
            metrics.put("status", "Engine not initialized");
        }

        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        Map<String, Object> summary = new HashMap<>();

        try {
            AccountState account = engine.getAccountState();
            List<Trade> trades = engine.getCompletedTrades();

            // Overall performance
            summary.put("totalTrades", trades.size());
            summary.put("totalPnL", account.getRealizedPnL());
            summary.put("equity", account.getEquity());

            // Win/Loss breakdown
            long winners = trades.stream().filter(t -> t.getRealizedPnL() > 0).count();
            long losers = trades.stream().filter(t -> t.getRealizedPnL() < 0).count();
            summary.put("winners", winners);
            summary.put("losers", losers);

            // Average R (if trades exist)
            if (!trades.isEmpty()) {
                double avgPnL = trades.stream()
                        .mapToDouble(Trade::getRealizedPnL)
                        .average()
                        .orElse(0.0);
                summary.put("averagePnL", avgPnL);
            }

            // Risk metrics
            summary.put("dailyLossUsed", Math.abs(Math.min(0, account.getNetDailyPnl())));
            summary.put("remainingDailyLoss", engine.getRemainingDailyLoss());
            summary.put("profitTargetProgress", engine.getProfitTargetProgress());

        } catch (IllegalStateException e) {
            summary.put("status", "Engine not initialized");
        }

        return ResponseEntity.ok(summary);
    }
}
