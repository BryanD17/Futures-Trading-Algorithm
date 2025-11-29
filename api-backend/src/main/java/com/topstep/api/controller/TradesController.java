package com.topstep.api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST controller for trade history.
 */
@RestController
@RequestMapping("/api/trades")
public class TradesController {

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getTrades(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        // TODO: Connect to actual trading engine
        List<Map<String, Object>> trades = new ArrayList<>();

        return ResponseEntity.ok(trades);
    }
}
