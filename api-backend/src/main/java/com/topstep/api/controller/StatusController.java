package com.topstep.api.controller;

import com.topstep.trading.EngineFacade;
import com.topstep.trading.domain.AccountState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for system status and health.
 */
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private final EngineFacade engine = EngineFacade.getInstance();

    @GetMapping
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();

        try {
            // Get engine state
            boolean running = engine.isRunning();
            boolean paused = engine.isPaused();
            EngineFacade.Mode mode = engine.getMode();

            // Determine status string
            String statusStr;
            if (!running) {
                statusStr = "STOPPED";
            } else if (paused) {
                statusStr = "PAUSED";
            } else {
                statusStr = "RUNNING";
            }

            status.put("status", statusStr);
            status.put("mode", mode.toString());
            status.put("timestamp", Instant.now());
            status.put("version", "1.0.0-SNAPSHOT");

            // Add account info if engine is initialized
            if (running) {
                try {
                    AccountState account = engine.getAccountState();
                    status.put("balance", account.getCurrentBalance());
                    status.put("equity", account.getEquity());
                    status.put("dailyPnL", account.getNetDailyPnl());
                    status.put("totalPnL", account.getRealizedPnL());
                    status.put("openPositions", account.getPositions().size());
                    status.put("accountInGoodStanding", engine.isAccountInGoodStanding());

                    // Add lifecycle info if available
                    if (engine.getCurrentPhase() != null) {
                        status.put("phase", engine.getCurrentPhase().name());
                        status.put("phaseDisplayName", engine.getCurrentPhase().getDisplayName());
                        status.put("riskZone", engine.getCurrentRiskZone().name());
                        status.put("targetCompletionPct", engine.getTargetCompletionPct());
                        status.put("drawdownUsagePct", engine.getDrawdownUsagePct());
                        status.put("distanceToTarget", engine.getDistanceToTarget());
                    }
                } catch (IllegalStateException e) {
                    // Engine not fully initialized yet
                    status.put("accountStatus", "Initializing...");
                }
            }

        } catch (Exception e) {
            status.put("error", "Failed to get engine status: " + e.getMessage());
            status.put("status", "ERROR");
            status.put("mode", "UNKNOWN");
            status.put("timestamp", Instant.now());
            status.put("version", "1.0.0-SNAPSHOT");
        }

        return ResponseEntity.ok(status);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> health = new HashMap<>();
        health.put("status", "UP");
        return ResponseEntity.ok(health);
    }
}
