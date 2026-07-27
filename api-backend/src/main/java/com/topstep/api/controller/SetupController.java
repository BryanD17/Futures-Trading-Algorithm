package com.topstep.api.controller;

import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.stdvote.OteZone;
import com.topstep.trading.strategy.stdvote.SetupContext;
import com.topstep.trading.strategy.stdvote.StdvOteRegistry;
import com.topstep.trading.strategy.stdvote.StdvOteStrategy;
import com.topstep.trading.strategy.stdvote.StdvProjection;
import com.topstep.trading.strategy.stdvote.TradeableInstrument;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints surfacing the live STDV+OTE setup state per instrument.
 *
 * <p>The Setup panel on the dashboard ({@code SetupPanel.tsx}, SA7) reads
 * from these endpoints + the WebSocket stream to render the state-machine
 * stepper, STDV ladder, OTE zone, confluence checklist, and the last
 * failing gate.
 *
 * <p>SA6 surface:
 * <ul>
 *   <li>{@code GET /api/setup} — list of active symbols + strategy name.</li>
 *   <li>{@code GET /api/setup/{symbol}} — full setup snapshot for the
 *       instrument, or 404 when the instrument is not registered.</li>
 *   <li>{@code GET /api/setup/{symbol}/projections} — STDV ladder only.</li>
 *   <li>{@code GET /api/setup/instruments} — the allowed instrument list
 *       (MNQ/MES/MGC) with their specs.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> listActive() {
        return ResponseEntity.ok(Map.of(
                "strategy", StdvOteStrategy.NAME,
                "activeSymbols", StdvOteRegistry.activeSymbols()
        ));
    }

    @GetMapping("/instruments")
    public ResponseEntity<List<InstrumentDto>> listInstruments() {
        List<InstrumentDto> out = new ArrayList<>();
        for (TradeableInstrument.Spec s : TradeableInstrument.all()) {
            out.add(InstrumentDto.from(s));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{symbol}")
    public ResponseEntity<SetupSnapshotDto> getSetup(@PathVariable String symbol) {
        if (!TradeableInstrument.isTradeable(symbol)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Optional<StdvOteStrategy> s = StdvOteRegistry.get(symbol);
        if (s.isEmpty()) {
            // Tradeable instrument, but no strategy registered yet — return an
            // empty IDLE snapshot rather than a 404 so the panel can render.
            SetupSnapshotDto idle = SetupSnapshotDto.idle(symbol);
            return ResponseEntity.ok(idle);
        }
        return ResponseEntity.ok(SetupSnapshotDto.from(s.get().getSetupContext()));
    }

    @GetMapping("/{symbol}/projections")
    public ResponseEntity<List<ProjectionDto>> getProjections(@PathVariable String symbol) {
        if (!TradeableInstrument.isTradeable(symbol)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Optional<StdvOteStrategy> s = StdvOteRegistry.get(symbol);
        if (s.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        SetupContext ctx = s.get().getSetupContext();
        List<ProjectionDto> out = new ArrayList<>();
        for (StdvProjection p : ctx.projections) {
            out.add(ProjectionDto.from(p));
        }
        return ResponseEntity.ok(out);
    }

    // ──────────────────────────────────────────────────────────────────────
    // DTOs (records — Jackson serializes cleanly without configuration)
    // ──────────────────────────────────────────────────────────────────────

    public record InstrumentDto(
            String symbol,
            double tickSize,
            double tickValue,
            double pointValue,
            int minMicros,
            int maxMicros,
            int raidMinQuality,
            String correlate) {
        public static InstrumentDto from(TradeableInstrument.Spec s) {
            return new InstrumentDto(
                    s.symbol().name(), s.tickSize(), s.tickValue(),
                    s.pointValue(), s.minMicros(), s.maxMicros(),
                    s.raidMinQuality(), s.correlate());
        }
    }

    public record ProjectionDto(
            double sigma,
            double rawPrice,
            double snappedPrice,
            String snappedLevelType,
            boolean isLiquidityBacked,
            String realismTag) {
        public static ProjectionDto from(StdvProjection p) {
            return new ProjectionDto(
                    p.sigma(), p.rawPrice(), p.snappedPrice(),
                    p.snappedLevelType() == null ? null : p.snappedLevelType().name(),
                    p.isLiquidityBacked(), p.realismTag());
        }
    }

    public record OteZoneDto(
            boolean bullish,
            double legLow,
            double legHigh,
            double eq50,
            double f62,
            double f705,
            double f79,
            double one00) {
        public static OteZoneDto from(OteZone z) {
            if (z == null) return null;
            return new OteZoneDto(
                    z.bullish(), z.legLow(), z.legHigh(),
                    z.eq50(), z.f62(), z.f705(), z.f79(), z.one00());
        }
    }

    public record SweepDto(boolean bullish, double sweptLevel, boolean hasSmt) {
        public static SweepDto from(LiquiditySweep s) {
            if (s == null) return null;
            return new SweepDto(s.isBullish(), s.getSweptLevel(), s.hasSmtDivergence());
        }
    }

    public record FvgDto(boolean bullish, double top, double bottom, boolean filled) {
        public static FvgDto from(FairValueGap f) {
            if (f == null) return null;
            return new FvgDto(f.isBullish(), f.getTop(), f.getBottom(), f.isFilled());
        }
    }

    public record SetupSnapshotDto(
            String symbol,
            String state,
            String htfBias,
            boolean killzoneOpen,
            String smtState,
            double legLow,
            double legHigh,
            boolean legBullish,
            List<ProjectionDto> projections,
            SweepDto sweep,
            int raidScore,
            boolean displacement,
            FvgDto fvg,
            boolean mss,
            OteZoneDto ote,
            Double pdArrayInOte,
            String pdArrayKind,
            String tier,
            List<String> confluenceFactors,
            double entry,
            double stop,
            double rr,
            int sizeRequest,
            int sizeFilled,
            String lastGateFailed,
            Map<String, Object> pd) {

        public static SetupSnapshotDto from(SetupContext ctx) {
            List<ProjectionDto> projs = new ArrayList<>();
            for (StdvProjection p : ctx.projections) projs.add(ProjectionDto.from(p));
            Double pdArr = Double.isNaN(ctx.pdArrayInOte) ? null : ctx.pdArrayInOte;
            return new SetupSnapshotDto(
                    ctx.symbol,
                    ctx.state.name(),
                    ctx.htfBias == null ? "NEUTRAL" : ctx.htfBias.name(),
                    ctx.killzoneOpen,
                    ctx.smtState,
                    ctx.legLow, ctx.legHigh, ctx.legBullish,
                    projs,
                    SweepDto.from(ctx.sweep),
                    ctx.raidScore,
                    ctx.displacement,
                    FvgDto.from(ctx.fvg),
                    ctx.mss,
                    OteZoneDto.from(ctx.ote),
                    pdArr,
                    ctx.pdArrayKind,
                    ctx.tier == null ? null : ctx.tier.name(),
                    List.copyOf(ctx.confluenceFactors),
                    ctx.entry, ctx.stop, ctx.rr,
                    ctx.sizeRequest, ctx.sizeFilled,
                    ctx.lastGateFailed,
                    pdBlockFor(ctx.symbol));
        }

        public static SetupSnapshotDto idle(String symbol) {
            return new SetupSnapshotDto(
                    symbol, "IDLE", "NEUTRAL", false, "NEUTRAL",
                    0.0, 0.0, false, List.of(),
                    null, 0, false, null, false,
                    null, null, null, null, List.of(),
                    0.0, 0.0, 0.0, 0, 0, null,
                    pdBlockFor(symbol));
        }

        /**
         * M2b premium/discount telemetry (V3 Agent 02): session-scoped
         * counters + mode from the per-symbol evaluator registry. Null
         * until the runner has wired the evaluator (cold start) — the
         * panel renders the row as pending, never as an error.
         */
        private static Map<String, Object> pdBlockFor(String symbol) {
            return com.topstep.trading.strategy.stdvote.PremiumDiscountEvaluator
                    .get(symbol)
                    .map(com.topstep.trading.strategy.stdvote.PremiumDiscountEvaluator::toApiMap)
                    .orElse(null);
        }
    }
}
