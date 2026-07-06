package com.topstep.api.controller;

import com.topstep.trading.EngineFacade;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.RiskLimits;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for risk metrics and limits.
 */
@RestController
@RequestMapping("/api/risk")
public class RiskController {

    private final EngineFacade engine = EngineFacade.getInstance();

    @GetMapping
    public ResponseEntity<Map<String, Object>> getRiskMetrics() {
        Map<String, Object> risk = new HashMap<>();

        try {
            RiskLimits limits = engine.getRiskLimits();
            AccountState account = engine.getAccountState();

            // Daily loss metrics (both old and new field names for compatibility)
            risk.put("dailyLossLimit", limits.getDailyLossLimit());
            risk.put("maxDailyLoss", limits.getDailyLossLimit()); // Frontend expects this name
            risk.put("currentDailyPnL", account.getNetDailyPnl());
            risk.put("currentDailyLoss", Math.abs(Math.min(0, account.getNetDailyPnl()))); // Frontend expects positive loss
            risk.put("remainingDailyLoss", engine.getRemainingDailyLoss());
            risk.put("remainingRiskBudget", engine.getRemainingDailyLoss()); // Frontend expects this name

            // Max loss metrics
            risk.put("maxLossLimit", limits.getMaxLossLimit());
            risk.put("currentDrawdown", engine.getCurrentDrawdown());
            risk.put("remainingDrawdown", engine.getRemainingDrawdown());

            // Profit target
            risk.put("profitTarget", limits.getProfitTarget());
            risk.put("profitTargetProgress", engine.getProfitTargetProgress());

            // Position limits
            risk.put("maxContracts", limits.getMaxContracts());
            risk.put("currentContracts", account.getTotalContracts());
            risk.put("maxPositions", limits.getMaxPositions());
            risk.put("currentPositions", account.getPositions().size());

            // Risk per trade
            risk.put("riskPerTrade", limits.getRiskPerTrade());

            // Account status
            risk.put("accountInGoodStanding", engine.isAccountInGoodStanding());

        } catch (IllegalStateException e) {
            // Engine not initialized - return default values
            risk.put("dailyLossLimit", 1000.0);
            risk.put("maxDailyLoss", 1000.0);
            risk.put("currentDailyPnL", 0.0);
            risk.put("currentDailyLoss", 0.0);
            risk.put("remainingDailyLoss", 1000.0);
            risk.put("remainingRiskBudget", 1000.0);
            risk.put("maxLossLimit", 2000.0);
            risk.put("currentDrawdown", 0.0);
            risk.put("remainingDrawdown", 2000.0);
            risk.put("profitTarget", 3000.0);
            risk.put("profitTargetProgress", 0.0);
            risk.put("maxContracts", 5);
            risk.put("currentContracts", 0);
            risk.put("maxPositions", 3);
            risk.put("currentPositions", 0);
            risk.put("riskPerTrade", 250.0);
            risk.put("accountInGoodStanding", true);
            risk.put("status", "Engine not initialized");
        } catch (Exception e) {
            System.err.println("Error fetching risk metrics: " + e.getMessage());
            risk.put("error", e.getMessage());
        }

        return ResponseEntity.ok(risk);
    }

    /**
     * Update user-adjustable risk settings (profit target, daily loss cap,
     * per-trade risk). Tighten-only: values above the engine-start baseline
     * are clamped by EngineFacade — the dashboard cannot weaken limits.
     */
    @PostMapping("/settings")
    public ResponseEntity<Map<String, Object>> updateRiskSettings(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            Double profitTarget = toDouble(body.get("profitTarget"));
            Double maxDailyLoss = toDouble(body.get("maxDailyLoss"));
            Double riskPerTrade = toDouble(body.get("riskPerTrade"));

            RiskLimits updated = engine.updateRiskSettings(profitTarget, maxDailyLoss, riskPerTrade);

            response.put("profitTarget", updated.getProfitTarget());
            response.put("maxDailyLoss", updated.getMaxDailyLoss());
            response.put("riskPerTrade", updated.getRiskPerTrade());
            response.put("status", "applied");
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            response.put("error", "Engine not initialized — start SIM or LIVE first");
            return ResponseEntity.status(409).body(response);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    private static Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        String s = value.toString().trim();
        return s.isEmpty() ? null : Double.parseDouble(s);
    }
}
