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

            // Daily loss metrics
            risk.put("dailyLossLimit", limits.getDailyLossLimit());
            risk.put("currentDailyPnL", account.getNetDailyPnl());
            risk.put("remainingDailyLoss", engine.getRemainingDailyLoss());

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

            // Account status
            risk.put("accountInGoodStanding", engine.isAccountInGoodStanding());

        } catch (IllegalStateException e) {
            // Engine not initialized - return default values
            risk.put("dailyLossLimit", 1000.0);
            risk.put("currentDailyPnL", 0.0);
            risk.put("remainingDailyLoss", 1000.0);
            risk.put("maxLossLimit", 2000.0);
            risk.put("currentDrawdown", 0.0);
            risk.put("remainingDrawdown", 2000.0);
            risk.put("profitTarget", 3000.0);
            risk.put("profitTargetProgress", 0.0);
            risk.put("maxContracts", 5);
            risk.put("currentContracts", 0);
            risk.put("maxPositions", 3);
            risk.put("currentPositions", 0);
            risk.put("accountInGoodStanding", true);
            risk.put("status", "Engine not initialized");
        } catch (Exception e) {
            System.err.println("Error fetching risk metrics: " + e.getMessage());
            risk.put("error", e.getMessage());
        }

        return ResponseEntity.ok(risk);
    }
}
