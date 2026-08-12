package com.topstep.api.controller;

import com.topstep.trading.EngineFacade;
import com.topstep.trading.confluence.ConfluenceService;
import com.topstep.trading.confluence.ConfluenceSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/confluence/{symbol} — the confluence stack for BOTH directions
 * (V4 Agent 07).
 *
 * <p>Read-only by construction: the service aggregates facts their owners
 * already computed and gates nothing, so this endpoint can never influence a
 * trade. Its job is to make "why did / didn't this set up" answerable without
 * grepping a log.
 *
 * <p>Cold sources report UNKNOWN rather than false, and the response carries
 * both {@code score} and {@code maxScore} so a cold stack looks cold instead
 * of looking bad.
 */
@RestController
@RequestMapping("/api/confluence")
public class ConfluenceController {

    // Resolved per request, like ChartStateController: a SIM/LIVE restart
    // registers a fresh service and this must always serve the CURRENT one.
    private final EngineFacade engine = EngineFacade.getInstance();

    @GetMapping("/{symbol}")
    public ResponseEntity<Map<String, Object>> confluence(@PathVariable String symbol) {
        String sym = symbol.toUpperCase();
        ConfluenceService service = engine.getConfluenceService();

        ConfluenceSnapshot longs = service.snapshot(sym, true);
        ConfluenceSnapshot shorts = service.snapshot(sym, false);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", sym);
        body.put("long", longs.toApiMap());
        body.put("short", shorts.toApiMap());
        body.put("line", service.logLine(sym));
        return ResponseEntity.ok(body);
    }
}
