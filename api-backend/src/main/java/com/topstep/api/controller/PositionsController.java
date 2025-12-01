package com.topstep.api.controller;

import com.topstep.trading.EngineFacade;
import com.topstep.trading.domain.Position;
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
                Map<String, Object> positionData = new HashMap<>();
                positionData.put("symbol", position.getSymbol());
                positionData.put("quantity", position.getQuantity());
                positionData.put("side", position.getSide().toString());
                positionData.put("averagePrice", position.getAvgEntryPrice());
                positionData.put("openedAt", position.getOpenedAt().toString());

                // Calculate unrealized PnL if we have execution engine
                if (engine.getExecutionEngine() != null) {
                    // For now, we'll use the position's built-in unrealized PnL calculation
                    // In a real scenario, this would use current market prices
                    double currentPrice = position.getAvgEntryPrice(); // Placeholder
                    double tickValue = engine.getExecutionEngine().getTickValue(position.getSymbol());
                    double unrealizedPnl = position.getUnrealizedPnL(currentPrice, tickValue);
                    positionData.put("unrealizedPnL", unrealizedPnl);
                } else {
                    positionData.put("unrealizedPnL", 0.0);
                }

                positions.add(positionData);
            }

        } catch (Exception e) {
            // Return empty list if engine not initialized or error occurs
            System.err.println("Error fetching positions: " + e.getMessage());
        }

        return ResponseEntity.ok(positions);
    }
}
