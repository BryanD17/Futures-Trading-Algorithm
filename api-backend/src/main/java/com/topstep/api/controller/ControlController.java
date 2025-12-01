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
     * Start the engine in SIM mode.
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
                response.put("status", "ERROR");
                response.put("message", "LIVE mode not yet implemented (Week 4)");
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(response);
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
            engine.pauseSim();
            response.put("status", "PAUSED");
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
            engine.resumeSim();
            response.put("status", "RUNNING");
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
}
