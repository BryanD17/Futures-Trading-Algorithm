package com.topstep.api.controller;

import com.topstep.trading.journal.TradeJournalService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/journal")
@CrossOrigin(origins = "*")
public class JournalController {

    private final TradeJournalService journalService = new TradeJournalService();

    /**
     * Return all journal entries, newest first.
     * Supports optional filtering by symbol, result (WIN/LOSS), and tier.
     */
    @GetMapping
    public List<Map<String, Object>> getAll(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String tier
    ) {
        List<Map<String, Object>> all = journalService.loadAll();

        return all.stream()
                .filter(e -> symbol == null || symbol.equalsIgnoreCase((String) e.get("symbol")))
                .filter(e -> result == null || result.equalsIgnoreCase((String) e.get("result")))
                .filter(e -> tier == null || tier.equalsIgnoreCase((String) e.get("tier")))
                .sorted((a, b) -> {
                    String ta = (String) a.get("entryTime");
                    String tb = (String) b.get("entryTime");
                    return tb != null && ta != null ? tb.compareTo(ta) : 0;
                })
                .collect(Collectors.toList());
    }

    /**
     * Return a stats summary across all journal entries.
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        List<Map<String, Object>> all = journalService.loadAll();
        long wins = all.stream().filter(e -> "WIN".equals(e.get("result"))).count();
        long losses = all.stream().filter(e -> "LOSS".equals(e.get("result"))).count();
        double totalPnl = all.stream()
                .mapToDouble(e -> e.get("realizedPnL") instanceof Number n ? n.doubleValue() : 0.0)
                .sum();
        double avgR = all.stream()
                .mapToDouble(e -> e.get("rMultiple") instanceof Number n ? n.doubleValue() : 0.0)
                .average().orElse(0.0);

        return Map.of(
                "totalTrades", all.size(),
                "wins", wins,
                "losses", losses,
                "winRate", all.isEmpty() ? 0 : (wins * 100.0 / all.size()),
                "totalPnL", totalPnl,
                "avgRMultiple", avgR
        );
    }
}
