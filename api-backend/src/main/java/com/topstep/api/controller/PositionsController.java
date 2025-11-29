package com.topstep.api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST controller for position management.
 */
@RestController
@RequestMapping("/api/positions")
public class PositionsController {

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getPositions() {
        // TODO: Connect to actual trading engine
        List<Map<String, Object>> positions = new ArrayList<>();

        return ResponseEntity.ok(positions);
    }
}
