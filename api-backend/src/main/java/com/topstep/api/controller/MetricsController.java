package com.topstep.api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for trading metrics and performance.
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    @GetMapping("/daily")
    public ResponseEntity<Map<String, Object>> getDailyMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        // TODO: Connect to actual trading engine
        metrics.put("startingBalance", 50000.0);
        metrics.put("currentBalance", 50000.0);
        metrics.put("realizedPnL", 0.0);
        metrics.put("unrealizedPnL", 0.0);
        metrics.put("totalPnL", 0.0);
        metrics.put("tradesCount", 0);
        metrics.put("winRate", 0.0);
        metrics.put("maxDrawdown", 0.0);

        return ResponseEntity.ok(metrics);
    }
}
