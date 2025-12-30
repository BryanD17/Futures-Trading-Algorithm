package com.topstep.api.controller;

import com.topstep.trading.EngineFacade;
import com.topstep.trading.domain.Position;
import com.topstep.trading.execution.ExecutionEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for position management.
 */
@RestController
@RequestMapping("/api/positions")
public class PositionsController {

    private final EngineFacade engine = EngineFacade.getInstance();

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getPositions() {
        List<Map<String, Object>> positions = new ArrayList<>();

        try {
            Map<String, Position> enginePositions = engine.getOpenPositions();

            for (Position position : enginePositions.values()) {
                // Skip closed positions (quantity = 0)
                if (position.getQuantity() == 0) {
                    continue;
                }
                Map<String, Object> positionData = new HashMap<>();
                positionData.put("symbol", position.getSymbol());
                positionData.put("quantity", Math.abs(position.getQuantity()));
                positionData.put("side", position.getQuantity() > 0 ? "LONG" : "SHORT");
                positionData.put("avgEntryPrice", position.getAvgEntryPrice());
                positionData.put("openedAt", position.getOpenedAt().toString());

                // Get current price and calculate unrealized PnL
                double currentPrice = position.getAvgEntryPrice(); // Default to entry
                double unrealizedPnl = 0.0;
                double stopPrice = 0.0;
                double targetPrice = 0.0;

                ExecutionEngine execEngine = engine.getExecutionEngine();
                if (execEngine != null) {
                    double tickValue = execEngine.getTickValue(position.getSymbol());

                    // Try to get order levels for stop/target info
                    ExecutionEngine.EnhancedOrderLevels levels = execEngine.getOrderLevels(position.getSymbol());
                    if (levels != null) {
                        stopPrice = levels.getCurrentStopPrice();
                        targetPrice = levels.getFinalTargetPrice();
                        // Use entry price from levels if available (more accurate)
                        if (levels.getEntryPrice() > 0) {
                            currentPrice = levels.getEntryPrice();
                        }
                    }

                    unrealizedPnl = position.getUnrealizedPnL(currentPrice, tickValue);
                }

                positionData.put("currentPrice", currentPrice);
                positionData.put("unrealizedPnL", unrealizedPnl);
                positionData.put("stopPrice", stopPrice > 0 ? stopPrice : null);
                positionData.put("targetPrice", targetPrice > 0 ? targetPrice : null);

                positions.add(positionData);
            }

        } catch (Exception e) {
            // Return empty list if engine not initialized or error occurs
            System.err.println("Error fetching positions: " + e.getMessage());
            e.printStackTrace();
        }

        return ResponseEntity.ok(positions);
    }
}
