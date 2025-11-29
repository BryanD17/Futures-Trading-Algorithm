package com.topstep.api.controller;

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

    @GetMapping
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "RUNNING");
        status.put("mode", "SIMULATION");
        status.put("timestamp", Instant.now());
        status.put("version", "1.0.0-SNAPSHOT");

        return ResponseEntity.ok(status);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> health = new HashMap<>();
        health.put("status", "UP");
        return ResponseEntity.ok(health);
    }
}
