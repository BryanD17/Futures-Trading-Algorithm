package com.topstep.api.controller;

import com.topstep.trading.EngineFacade;
import com.topstep.trading.LiveTradingRemovedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for engine control operations (start/pause/resume/stop).
 * SECURITY NOTE: In production, this should have proper authentication!
 *
 * <p>V6 Agent 05.6: the Java engine is a research / backtest lab. Every LIVE
 * control ({@code start?mode=LIVE}, {@code killswitch}, {@code flatten}, all of
 * which were LIVE-only) answers <b>410 Gone</b> with the V6 message
 * ({@link LiveTradingRemovedException#MESSAGE}). SIM controls are unchanged.
 */
@RestController
@RequestMapping("/api/control")
public class ControlController {

    private final EngineFacade engine = EngineFacade.getInstance();

    /** 410 Gone body for every removed LIVE control. */
    static ResponseEntity<Map<String, String>> liveGone() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "GONE");
        response.put("message", LiveTradingRemovedException.MESSAGE);
        response.put("liveTrading", "TradingView");
        return ResponseEntity.status(HttpStatus.GONE).body(response);
    }

    /**
     * Start the engine in specified mode.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> start(
            @RequestParam(defaultValue = "SIM") String mode) {

        Map<String, String> response = new HashMap<>();

        try {
            if ("SIM".equalsIgnoreCase(mode)) {
                engine.startSim();
                response.put("status", "STARTED");
                response.put("mode", "SIM");
                response.put("message", "SIM engine started successfully");
                return ResponseEntity.ok(response);

            } else if ("LIVE".equalsIgnoreCase(mode)) {
                // V6 05.6: LIVE is gone from the Java engine (R6).
                return liveGone();

            } else {
                response.put("status", "ERROR");
                response.put("message", "Unknown mode: " + mode + ". Supported: SIM (LIVE runs on TradingView)");
                return ResponseEntity.badRequest().body(response);
            }

        } catch (LiveTradingRemovedException e) {
            return liveGone();

        } catch (IllegalStateException e) {
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);

        } catch (Exception e) {
            response.put("status", "ERROR");
            response.put("message", "Failed to start engine: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Pause the engine (stops processing new signals).
     */
    @PostMapping("/pause")
    public ResponseEntity<Map<String, String>> pause() {
        Map<String, String> response = new HashMap<>();

        try {
            engine.pause();
            response.put("status", "PAUSED");
            response.put("mode", engine.getMode().toString());
            response.put("message", "Trading engine paused - no new signals will be processed");
            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);

        } catch (Exception e) {
            response.put("status", "ERROR");
            response.put("message", "Failed to pause engine: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Resume the engine (continues processing signals).
     */
    @PostMapping("/resume")
    public ResponseEntity<Map<String, String>> resume() {
        Map<String, String> response = new HashMap<>();

        try {
            engine.resume();
            response.put("status", "RUNNING");
            response.put("mode", engine.getMode().toString());
            response.put("message", "Trading engine resumed - signals will be processed");
            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);

        } catch (Exception e) {
            response.put("status", "ERROR");
            response.put("message", "Failed to resume engine: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Stop the engine completely.
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stop() {
        Map<String, String> response = new HashMap<>();

        try {
            engine.stop();
            response.put("status", "STOPPED");
            response.put("message", "Trading engine stopped");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("status", "ERROR");
            response.put("message", "Failed to stop engine: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Activate kill switch (LIVE mode only) — V6: 410 Gone.
     */
    @PostMapping("/killswitch")
    public ResponseEntity<Map<String, String>> activateKillSwitch(
            @RequestParam(defaultValue = "Manual kill switch activation") String reason) {
        // V6 05.6: LIVE-only control; LIVE runs on TradingView (kill switch /
        // flatten live in the relay + TradingView, RUNBOOK_TRADINGVIEW.md).
        return liveGone();
    }

    /**
     * Flatten all positions (LIVE mode only) — V6: 410 Gone.
     */
    @PostMapping("/flatten")
    public ResponseEntity<Map<String, String>> flattenPositions(
            @RequestParam(defaultValue = "Manual flatten request") String reason) {
        // V6 05.6: LIVE-only control; LIVE runs on TradingView (kill switch /
        // flatten live in the relay + TradingView, RUNBOOK_TRADINGVIEW.md).
        return liveGone();
    }

    /**
     * Get current engine status.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getControlStatus() {
        Map<String, Object> status = new HashMap<>();

        status.put("mode", engine.getMode().toString());
        status.put("running", engine.isRunning());
        status.put("paused", engine.isPaused());
        status.put("killSwitchActive", engine.isKillSwitchActive());
        // V6 05.6: the dashboard banner reads these.
        status.put("researchMode", true);
        status.put("liveTrading", "TradingView");
        status.put("banner", "Research mode — live trading on TradingView");

        return ResponseEntity.ok(status);
    }
}
