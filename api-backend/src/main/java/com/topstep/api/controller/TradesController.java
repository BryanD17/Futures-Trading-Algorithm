package com.topstep.api.controller;

import com.topstep.trading.EngineFacade;
import com.topstep.trading.domain.Trade;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for trade history.
 */
@RestController
@RequestMapping("/api/trades")
public class TradesController {

    private final EngineFacade engine = EngineFacade.getInstance();

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getTrades(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        List<Map<String, Object>> tradesResponse = new ArrayList<>();

        try {
            List<Trade> allTrades = engine.getCompletedTrades();

            // Apply pagination
            int start = page * size;
            int end = Math.min(start + size, allTrades.size());

            List<Trade> paginatedTrades;
            if (start < allTrades.size()) {
                paginatedTrades = allTrades.subList(start, end);
            } else {
                paginatedTrades = new ArrayList<>();
            }

            // Convert to response format
            for (Trade trade : paginatedTrades) {
                Map<String, Object> tradeData = new HashMap<>();
                tradeData.put("tradeId", trade.getTradeId());
                tradeData.put("symbol", trade.getSymbol());
                tradeData.put("side", trade.getSide().toString());
                tradeData.put("quantity", trade.getQuantity());
                tradeData.put("entryPrice", trade.getEntryPrice());
                tradeData.put("exitPrice", trade.getExitPrice());
                tradeData.put("entryTime", trade.getEntryTime() != null ? trade.getEntryTime().toString() : null);
                tradeData.put("exitTime", trade.getExitTime() != null ? trade.getExitTime().toString() : null);
                tradeData.put("realizedPnL", trade.getRealizedPnL());
                tradeData.put("rMultiple", trade.getRMultiple());
                tradeData.put("notes", trade.getNotes());

                tradesResponse.add(tradeData);
            }

        } catch (Exception e) {
            System.err.println("Error fetching trades: " + e.getMessage());
        }

        return ResponseEntity.ok(tradesResponse);
    }
}
