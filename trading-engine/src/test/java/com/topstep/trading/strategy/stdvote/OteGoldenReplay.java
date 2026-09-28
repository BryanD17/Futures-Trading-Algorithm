package com.topstep.trading.strategy.stdvote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.Event;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.strategy.BarAggregationManager;
import com.topstep.trading.strategy.BarAggregationManager.Timeframe;
import com.topstep.trading.strategy.DisplacementDetector;
import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.FvgDetector;
import com.topstep.trading.strategy.ImpulseExtensionAnalyzer;
import com.topstep.trading.strategy.LiquidityDetector;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.MarketStructureShiftDetector;
import com.topstep.trading.strategy.TradeTier;
import com.topstep.trading.validation.MandatoryConfluenceValidator;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V5 Agent 04 — tape-driven replay of the POST-SWEEP funnel with the SAME
 * components the live runner wires ({@link OteSetupDriver}, the calibrated
 * {@link DisplacementDetector}, {@link MarketStructureShiftDetector#forStdvOte()},
 * {@link StdvOteStrategy} + {@link MandatoryConfluenceValidator}).
 *
 * <p>Bias and (optionally) the sweep are INJECTED: on this branch the bias
 * (Agent 03) is still wrong on G1, so the golden cases prove the OTE / anchor /
 * arm / alarm / planning code in isolation. Nothing here hard-codes a G1
 * number — the tape and the rules produce them.
 */
final class OteGoldenReplay {

    static final ZoneId ET = ZoneId.of("America/New_York");
    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    static final double TICK = 0.25;

    /** Captures published events without a running bus. */
    static final class CapturingBus extends EventBus {
        final List<Event> events = new ArrayList<>();

        @Override
        public void publish(Event event) {
            events.add(event);
        }
    }

    static List<Candle> loadTape(String symbol) throws Exception {
        try (InputStream in = OteGoldenReplay.class.getResourceAsStream("/tape/real_" + symbol + "_1m.json")) {
            if (in == null) throw new IllegalStateException("tape resource missing");
            JsonNode arr = new ObjectMapper().readTree(in);
            List<Candle> out = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                out.add(new Candle(symbol, Instant.parse(n.get("t").asText()),
                        n.get("o").asDouble(), n.get("h").asDouble(), n.get("l").asDouble(),
                        n.get("c").asDouble(), n.get("v").asLong()));
            }
            return out;
        }
    }

    static Instant et(String isoLocal) {
        return LocalDateTime.parse(isoLocal).atZone(ET).toInstant();
    }

    static String fmt(Instant t) {
        return t == null ? "-" : HM.format(t.atZone(ET));
    }

    // ── the replay ───────────────────────────────────────────────────────

    final CapturingBus bus = new CapturingBus();
    final DisplacementDetector displacement;
    final MarketStructureShiftDetector mss = MarketStructureShiftDetector.forStdvOte();
    final FvgDetector fvg = new FvgDetector(20);
    final BarAggregationManager bars = new BarAggregationManager("MNQ", 500);
    final OteSetupDriver driver;
    final StdvOteStrategy core;
    final LiquidityDetector liquidity = new LiquidityDetector(30);
    final List<String> transcript = new ArrayList<>();
    final List<StrategySignalEvent> signals = new ArrayList<>();
    final List<Map<String, Object>> zones = new ArrayList<>();
    final List<Candle> anatomy = new ArrayList<>();
    private String lastReject = "";

    OteGoldenReplay() {
        displacement = new DisplacementDetector(20, OteConfig.displacementAtrMult(),
                OteConfig.displacementBodyPct(), "MNQ")
                .usePriorTrueRangeAtr(OteConfig.DISPLACEMENT_ATR_LEN);
        driver = new OteSetupDriver("MNQ", TICK, displacement, bus, 5);
        MandatoryConfluenceValidator validator =
                new MandatoryConfluenceValidator(null, displacement, null);
        core = new StdvOteStrategy("MNQ",
                new StdvProjectionEngine(null, new ImpulseExtensionAnalyzer("MNQ", 30)),
                new OteEntryCalculator(), validator, bus, 0L);
    }

    SetupContext ctx() {
        return core.getSetupContext();
    }

    /** Feed one 1m candle into every detector (runner step 1 order). */
    void feed(Candle c) {
        Map<Timeframe, Candle> done = bars.processCandle(c);
        Candle a = done.get(Timeframe.M5);
        if (a != null) {
            anatomy.add(a);
            fvg.update(a);
            displacement.update(a);
            MarketStructureShiftDetector.MSS m = mss.update(a);
            driver.onAnatomyCandle(a, m);
            DisplacementDetector.Displacement last = displacement.getLastDisplacement();
            if (last != null && a.getTimestamp().equals(last.getTimestamp())) {
                transcript.add(String.format("  [5m %s] displacement %s range/ATR=%.2f body=%.0f%%",
                        fmt(a.getTimestamp()), last.isBullish() ? "BULL" : "BEAR",
                        displacement.getLastRangeOverAtr(), displacement.getLastBodyRatio() * 100));
            }
            if (m != null) {
                transcript.add(String.format("  [5m %s] MSS %s close %.2f beyond swing %.2f",
                        fmt(a.getTimestamp()), m.isBullish ? "BULL" : "BEAR", a.getClose(), m.breakLevel));
            }
        }
        liquidity.updatePrimary(c);
        driver.onFeedCandle(c, ctx());
    }

    /** Inject bias + manipulation leg (retrace leg of the last {@code legBars} 1m bars). */
    void injectBias(MarketBias bias, List<Candle> recent, Instant at) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Candle c : recent) { lo = Math.min(lo, c.getLow()); hi = Math.max(hi, c.getHigh()); }
        ctx().killzoneOpen = true;
        core.recordHtfBias(bias);
        core.recordManipulationLeg(lo, hi, TICK, 3);
        transcript.add(fmt(at) + " ET  BIAS_SET/MANIP_DONE (injected) bias=" + bias
                + " manipLeg=[" + lo + "," + hi + "] state=" + ctx().state);
    }

    void injectSweep(LiquiditySweep sweep) {
        core.recordSweep(sweep, 5);
        transcript.add(fmt(sweep.getTimestamp()) + " ET  SWEEP_DONE (injected) "
                + (sweep.isBullish() ? "LOW@" : "HIGH@") + sweep.getSweptLevel() + " state=" + ctx().state);
    }

    /** Steps 9–13 for one candle, exactly in runner order. */
    void step(Candle c, MarketBias bias, boolean autoSweep) {
        SetupContext ctx = ctx();
        SetupState before = ctx.state;
        if (autoSweep && ctx.state == SetupState.MANIP_DONE && liquidity.hasRecentSweep(3)) {
            LiquiditySweep s = liquidity.getLastSweep();
            if (s != null && s.isBullish() == (bias == MarketBias.BULLISH)
                    && (ctx.sweep == null || !s.getTimestamp().equals(ctx.sweep.getTimestamp()))) {
                core.recordSweep(s, 5);
            }
        }
        String st10 = null, st12 = null;
        if (ctx.state == SetupState.SWEEP_DONE) st10 = driver.tryRecordDisplacement(core, bias);
        if (ctx.state == SetupState.DISPLACED) driver.tryRecordMss(core, bias);
        if (ctx.state == SetupState.MSS_CONFIRMED) {
            st12 = driver.tryArmOte(core, bias, c);
            if (ctx.state == SetupState.OTE_ARMED) {
                transcript.add(fmt(c.getTimestamp()) + " ET  MSS_CONFIRMED -> OTE_ARMED  zone[" + ctx.ote.f62() + ", "
                        + ctx.ote.f79() + "] 0.705=" + ctx.ote.f705() + " eq=" + ctx.ote.eq50()
                        + " anchor=" + ctx.oteAnchorMode + "/" + ctx.oteAnchorSource
                        + " leg[" + ctx.ote.legLow() + ", " + ctx.ote.legHigh() + "] touch high=" + c.getHigh()
                        + " low=" + c.getLow());
                before = SetupState.OTE_ARMED;
            }
        }
        if (ctx.state == SetupState.OTE_ARMED && ctx.oteAlarmAt == null) {
            boolean fired = driver.alarm(core, c);
            if (fired) {
                transcript.add(fmt(c.getTimestamp()) + " ET  OTE ALARM  pd=" + ctx.pdArrayKind + " entry="
                        + ctx.pdArrayInOte + " farEdge=" + ctx.pdArrayFarEdge + " close=" + c.getClose());
            }
        }
        if (ctx.state == SetupState.OTE_ARMED && driver.alarm(core, c)) {
            boolean ok = core.tryEmit(TICK, 4, TradeTier.TIER_1, 5);
            if (ok) signals.add(core.getLastEmittedSignal());
            else if (!ctx.lastGateFailed.equals(lastReject)) {
                lastReject = ctx.lastGateFailed;
                transcript.add(fmt(c.getTimestamp()) + " ET  emit attempt rejected: " + ctx.lastGateFailed
                        + String.format(" (entry=%.2f stop=%.2f T1=%.2f RR(T1)=%.2f RR(final)=%.2f; repeats suppressed)",
                        ctx.entry, ctx.stop, ctx.t1, ctx.rrT1, ctx.rr));
            }
        }
        driver.afterCandle(ctx, c);
        if (ctx.state != before) {
            StringBuilder sb = new StringBuilder(fmt(c.getTimestamp()) + " ET  " + before + " -> " + ctx.state);
            switch (ctx.state) {
                case SWEEP_DONE -> sb.append("  sweep=").append(ctx.sweep.isBullish() ? "LOW@" : "HIGH@")
                        .append(ctx.sweep.getSweptLevel()).append(" at ").append(fmt(ctx.sweep.getTimestamp()));
                case DISPLACED -> sb.append("  displacement bar ").append(fmt(ctx.displacementAt))
                        .append(" linked ").append(ctx.m5LinkKind).append(" [").append(ctx.fvg.getBottom())
                        .append(", ").append(ctx.fvg.getTop()).append("]");
                case MSS_CONFIRMED -> sb.append("  MSS bar ").append(fmt(ctx.mssAt));
                case OTE_ARMED -> sb.append("  zone[").append(ctx.ote.f62()).append(", ").append(ctx.ote.f79())
                        .append("] 0.705=").append(ctx.ote.f705()).append(" eq=").append(ctx.ote.eq50())
                        .append(" anchor=").append(ctx.oteAnchorMode).append("/").append(ctx.oteAnchorSource)
                        .append(" leg[").append(ctx.ote.legLow()).append(", ").append(ctx.ote.legHigh()).append("]");
                case IN_TRADE -> sb.append("  EMIT entry=").append(ctx.entry).append(" stop=").append(ctx.stop)
                        .append(" T1=").append(ctx.t1).append(" T2=").append(ctx.t2).append(" T3=").append(ctx.t3)
                        .append(" target=").append(ctx.finalTarget)
                        .append(String.format(" RR(T1)=%.2f RR(final)=%.2f", ctx.rrT1, ctx.rr))
                        .append(" pd=").append(ctx.pdArrayKind).append(" alarm@").append(fmt(ctx.oteAlarmAt));
                case INVALIDATED -> sb.append("  reason=").append(ctx.lastGateFailed);
                default -> { }
            }
            transcript.add(sb.toString());
        }
        if (st10 != null && st10.startsWith("fvg-link")) {
            // quiet: pending linkage is expected for one or two bars
        }
    }

    FairValueGap legacyFvgStampedAt(Instant ts, boolean bullish) {
        for (FairValueGap g : fvg.getAllFvgs()) {
            if (g.isBullish() == bullish && ts.equals(g.getTimestamp())) return g;
        }
        return null;
    }
}
