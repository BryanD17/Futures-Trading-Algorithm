package com.topstep.api.controller;

import com.topstep.trading.EngineFacade;
import com.topstep.trading.chart.ChartSnapshot;
import com.topstep.trading.domain.Candle;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes the bot's internal chart so you can verify — visually, in the
 * dashboard — that what the algorithm sees matches the TopstepX chart.
 *
 * GET /api/chart/{symbol}?lookback=100
 *   → { candles30m: [...], ote: {...fib levels + state...}, warm: true/false }
 *
 * If "warm" is false or barsIngested is tiny, the backfill did not run and
 * the bot is effectively blind — the exact condition that caused the
 * two-days-no-trades behaviour.
 */
@RestController
@RequestMapping("/api/chart")
public class ChartStateController {

    // Same pattern as StatusController: read the EngineFacade singleton and
    // resolve the ChartEngine per request — a SIM/LIVE (re)start registers a
    // fresh engine with the facade, and this controller must always serve
    // the CURRENT one. getChartEngine() is never null (empty engine when no
    // runner registered), so an unwarmed API returns the honest empty shape.
    private final EngineFacade engine = EngineFacade.getInstance();

    @GetMapping("/{symbol}")
    public ResponseEntity<Map<String, Object>> chart(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "100") int lookback) {

        int safeLookback = Math.min(Math.max(lookback, 1), 2000);
        ChartSnapshot snap = engine.getChartEngine().snapshot(symbol.toUpperCase(), safeLookback);

        List<Map<String, Object>> candles = new ArrayList<>(snap.candles30m().size() + 1);
        for (Candle c : snap.candles30m()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", c.getTimestamp().toString());
            m.put("o", c.getOpen());
            m.put("h", c.getHigh());
            m.put("l", c.getLow());
            m.put("c", c.getClose());
            m.put("v", c.getVolume());
            candles.add(m);
        }
        // The FORMING 30m bar (2026-07-09): appended so the chart's right
        // edge matches the broker's live bar instead of lagging up to 30
        // minutes. Marked partial; display-only (the engine's swing
        // analysis only ever sees confirmed bars).
        if (snap.partial30m() != null) {
            Candle p = snap.partial30m();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", p.getTimestamp().toString());
            m.put("o", p.getOpen());
            m.put("h", p.getHigh());
            m.put("l", p.getLow());
            m.put("c", p.getClose());
            m.put("v", p.getVolume());
            m.put("partial", true);
            candles.add(m);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", snap.symbol());
        body.put("timeframe", "30m");
        body.put("candles30m", candles);
        body.put("ote", snap.activeOte() == null ? null : snap.activeOte().toApiMap());
        body.put("barsIngested1m", snap.oneMinuteBarsIngested());
        body.put("lastCandleTime",
                snap.lastCandleTime() == null ? null : snap.lastCandleTime().toString());
        // "Warm" = enough history for HTF bias + PDH/PDL + a real 30m leg.
        body.put("warm", snap.oneMinuteBarsIngested() >= 1500);

        // V2 Agent 06: session-scoped machine-vs-chart30m agreement
        // counters (read-only evidence stream — nothing gates on these).
        body.put("oteStats", com.topstep.trading.strategy.stdvote
                .OteAgreementStats.forSymbol(snap.symbol()).toApiMap());

        return ResponseEntity.ok(body);
    }
}
