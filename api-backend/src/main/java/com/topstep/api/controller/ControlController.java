package com.topstep.api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for engine control operations (pause/resume).
 * SECURITY NOTE: In production, this should have proper authentication!
 */
@RestController
@RequestMapping("/api/control")
public class ControlController {

    @PostMapping("/pause")
    public ResponseEntity<Map<String, String>> pause() {
        // TODO: Connect to actual trading engine
        Map<String, String> response = new HashMap<>();
        response.put("status", "PAUSED");
        response.put("message", "Trading engine paused");

        return ResponseEntity.ok(response);
    }

    @PostMapping("/resume")
    public ResponseEntity<Map<String, String>> resume() {
        // TODO: Connect to actual trading engine
        Map<String, String> response = new HashMap<>();
        response.put("status", "RUNNING");
        response.put("message", "Trading engine resumed");

        return ResponseEntity.ok(response);
    }
}
