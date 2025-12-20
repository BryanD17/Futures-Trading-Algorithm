package com.topstep.api.controller;

import com.topstep.trading.EngineFacade;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for engine control operations (start/pause/resume/stop).
 * SECURITY NOTE: In production, this should have proper authentication!
 */
@RestController
@RequestMapping("/api/control")
public class ControlController {

    private final EngineFacade engine = EngineFacade.getInstance();

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
                // Extra warning for LIVE mode
                response.put("warning", "LIVE mode uses real money. Ensure you have tested in SIM first.");

                engine.startLive();
                response.put("status", "STARTED");
                response.put("mode", "LIVE");
                response.put("message", "LIVE engine started successfully - REAL MONEY AT RISK");
                return ResponseEntity.ok(response);

            } else {
                response.put("status", "ERROR");
                response.put("message", "Unknown mode: " + mode + ". Supported: SIM, LIVE");
                return ResponseEntity.badRequest().body(response);
            }

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
     * Activate kill switch (LIVE mode only) - emergency stop all trading.
     */
    @PostMapping("/killswitch")
    public ResponseEntity<Map<String, String>> activateKillSwitch(
            @RequestParam(defaultValue = "Manual kill switch activation") String reason) {
        Map<String, String> response = new HashMap<>();

        try {
            engine.activateKillSwitch(reason);
            response.put("status", "KILL_SWITCH_ACTIVE");
            response.put("message", "Kill switch activated. All positions being flattened.");
            response.put("reason", reason);
            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);

        } catch (Exception e) {
            response.put("status", "ERROR");
            response.put("message", "Failed to activate kill switch: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Flatten all positions (LIVE mode only).
     */
    @PostMapping("/flatten")
    public ResponseEntity<Map<String, String>> flattenPositions(
            @RequestParam(defaultValue = "Manual flatten request") String reason) {
        Map<String, String> response = new HashMap<>();

        try {
            engine.flattenAllPositions(reason);
            response.put("status", "FLATTENING");
            response.put("message", "Flattening all positions");
            response.put("reason", reason);
            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);

        } catch (Exception e) {
            response.put("status", "ERROR");
            response.put("message", "Failed to flatten positions: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
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

        return ResponseEntity.ok(status);
    }
}
