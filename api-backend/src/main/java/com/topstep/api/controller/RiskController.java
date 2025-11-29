package com.topstep.api.controller;

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

    @GetMapping
    public ResponseEntity<Map<String, Object>> getRiskMetrics() {
        Map<String, Object> risk = new HashMap<>();

        // TODO: Connect to actual risk engine
        risk.put("maxDailyLoss", 1000.0);
        risk.put("currentDailyLoss", 0.0);
        risk.put("remainingRiskBudget", 1000.0);
        risk.put("maxContracts", 5);
        risk.put("currentContracts", 0);
        risk.put("riskPerTrade", 100.0);

        return ResponseEntity.ok(risk);
    }
}
