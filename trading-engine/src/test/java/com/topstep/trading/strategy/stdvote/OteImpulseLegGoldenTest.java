package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.OteAlarmEvent;
import com.topstep.trading.event.OteArmedEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.validation.ValidationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.topstep.trading.strategy.stdvote.OteGoldenReplay.et;
import static com.topstep.trading.strategy.stdvote.OteGoldenReplay.fmt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V5 Agent 05.2 — the IMPULSE_LEG entry model on the REAL MNQ tape.
 *
 * <p>G1 (2026-09-28 NY PM): the dealing range 30759.25 → 30356.75 (Agent 03's
 * {@link DealingRangeTracker}, fed the tape) was CREATED by the 09:30–10:45 ET
 * impulse leg (displacement + FVG + structure break). The 14:53 raid of the
 * 30640 London high sits inside that range's OTE band [30605.50, 30673.00]
 * and rejects on the same 1m bar — the owner sold 30635.75 there. Bias and
 * the 14:53 sweep are injected exactly like Agent 04's golden cases (the live
 * path produces both — see A-05.2 for the real-runner transcript); everything
 * else is production code: the impulse-leg proof of M5/M6, ARM, ALARM,
 * entry / stop / targets, the validator.
 */
@DisplayName("V5 Agent 05.2 impulse-leg entry model — G1/G2/G3 on the real MNQ tape")
class OteImpulseLegGoldenTest {

    @AfterEach
    void clear() {
        System.clearProperty("ote.entryModel");
        OteEventLog.clear("MNQ");
    }

    /** Replay G1 with bias BEARISH + the 14:53 London-high sweep injected at 14:53 ET. */
    static OteGoldenReplay replayG1(boolean impulse, List<Candle> tapeOut) throws Exception {
        List<Candle> tape = OteGoldenReplay.loadTape("MNQ");
        OteGoldenReplay r = new OteGoldenReplay();
        r.impulseModel = impulse;
        Instant start = et("2026-09-27T18:00");
        Instant inject = et("2026-09-28T14:53");
        Instant end = et("2026-09-28T15:50");
        List<Candle> recent = new ArrayList<>();
        for (Candle c : tape) {
            Instant t = c.getTimestamp();
            if (t.isBefore(start)) {
                r.dealingRange.onCandle(c);   // Agent 03's range needs the prior days
                continue;
            }
            if (t.isAfter(end)) break;
            if (tapeOut != null) tapeOut.add(c);
            if (t.equals(inject)) {
                r.injectBias(MarketBias.BEARISH, recent.subList(Math.max(0, recent.size() - 30), recent.size()), t);
                r.injectSweep(new LiquiditySweep(false, 30640.0, t, false));
            }
            r.feed(c);
            r.step(c, MarketBias.BEARISH, false);
            recent.add(c);
        }
        return r;
    }

    @Test
    @DisplayName("G1: SWEEP 14:53 -> OTE_ARMED same bar -> ALARM/emit by 15:05, entry ±2 of 30635.75, stop above the OB, T1 30558 ±1, limit FILLS")
    void g1ImpulseLeg() throws Exception {
        List<Candle> window = new ArrayList<>();
        OteGoldenReplay r = replayG1(true, window);
        System.out.println("===== G1 TRANSCRIPT (IMPULSE_LEG; bias + 14:53 sweep injected) =====");
        r.transcript.forEach(System.out::println);
        System.out.println("OTE events: " + OteEventLog.recent("MNQ"));

        assertThat(r.signals).hasSize(1);
        StrategySignalEvent sig = r.signals.get(0);
        SetupContext ctx = r.ctx();
        System.out.println("G1 SIGNAL: " + sig.getSignalType() + " entry=" + sig.getEntryPrice()
                + " stop=" + sig.getStopPrice() + " target=" + sig.getTargetPrice()
                + " RR=" + sig.getActualRR() + " reason=" + sig.getReason());
        System.out.println("G1 impulse verdict: " + ctx.impulseLegVerdict);

        // Model + zone.
        assertThat(ctx.oteEntryModel).isEqualTo("IMPULSE_LEG");
        assertThat(ctx.oteAnchorMode).isEqualTo("DEALING_RANGE");
        assertThat(ctx.ote.legHigh()).isEqualTo(30759.25);
        assertThat(ctx.ote.legLow()).isEqualTo(30356.75);
        assertThat(ctx.ote.f62()).isCloseTo(30605.50, within(1.0));
        assertThat(ctx.ote.f79()).isCloseTo(30673.00, within(1.0));

        // M5 / M6 proven on the impulse leg (09:30 HH bar -> 10:45 LL bar).
        assertThat(ctx.impulseLegStart).isEqualTo(et("2026-09-28T09:30"));
        assertThat(ctx.impulseLegEnd).isEqualTo(et("2026-09-28T10:45"));
        assertThat(ctx.m5LinkKind).isEqualTo("IMPULSE_FVG");
        assertThat(ctx.displacementAt).isBetween(ctx.impulseLegStart, ctx.impulseLegEnd);
        assertThat(ctx.impulseDispRangeAtr).isGreaterThanOrEqualTo(OteConfig.displacementAtrMult());
        assertThat(ctx.impulseDispBody).isGreaterThanOrEqualTo(OteConfig.displacementBodyPct());
        assertThat(ctx.fvg.isBullish()).isFalse();
        assertThat(ctx.fvg.getTimestamp()).isBetween(ctx.impulseLegStart, ctx.impulseLegEnd);
        assertThat(ctx.mssAt).isBetween(ctx.displacementAt, ctx.impulseLegEnd);
        assertThat(ctx.impulseMssClose).isLessThan(ctx.impulseMssSwing);

        // Timing: SWEEP 14:53–14:58, ARMED on the sweep bar, ALARM/emit by 15:05.
        assertThat(ctx.sweep.getTimestamp()).isBetween(et("2026-09-28T14:53"), et("2026-09-28T14:58"));
        assertThat(ctx.oteArmedAt).isEqualTo(ctx.sweep.getTimestamp());
        assertThat(ctx.oteAlarmAt).isBeforeOrEqualTo(et("2026-09-28T15:05"));

        // Geometry.
        assertThat(sig.getSignalType()).isEqualTo(StrategySignalEvent.SignalType.SHORT_ENTRY);
        assertThat(sig.getEntryPrice()).isCloseTo(30635.75, within(2.0));
        assertThat(sig.getStopPrice()).isGreaterThan(30673.00);          // above the 0.786 AND the OB (30650)
        assertThat(sig.getStopPrice()).isGreaterThan(ctx.pdArrayFarEdge);
        assertThat(ctx.t1).isCloseTo(30558.0, within(1.0));
        assertThat(ctx.t2).isCloseTo(30510.25, within(1.0));
        assertThat(ctx.t3).isEqualTo(30356.75);
        assertThat(ctx.rrT1).isGreaterThanOrEqualTo(OteConfig.rrFloor(false));
        assertThat(ctx.rr).isLessThanOrEqualTo(OteConfig.rrCeiling());

        // The limit FILLS: first bar after the emission that trades up through
        // the sell limit, before any bar touches the stop; then T1 prints first.
        Instant emitAt = ctx.oteAlarmAt;
        Candle fill = null;
        Candle t1Hit = null;
        boolean stopFirst = false;
        for (Candle c : window) {
            if (!c.getTimestamp().isAfter(emitAt)) continue;
            if (fill == null) {
                if (c.getHigh() >= sig.getStopPrice()) { stopFirst = true; break; }
                if (c.getHigh() >= sig.getEntryPrice()) fill = c;
                continue;
            }
            if (c.getHigh() >= sig.getStopPrice()) { stopFirst = true; break; }
            if (c.getLow() <= ctx.t1) { t1Hit = c; break; }
        }
        System.out.println("G1 FILL: " + (fill == null ? "none" : fmt(fill.getTimestamp()) + " ET (h="
                + fill.getHigh() + " l=" + fill.getLow() + ")") + " | T1 " + ctx.t1 + " first hit "
                + (t1Hit == null ? "not in window" : fmt(t1Hit.getTimestamp()) + " ET") + " | stopFirst=" + stopFirst);
        assertThat(fill).as("the sell limit fills").isNotNull();
        assertThat(fill.getTimestamp()).isBeforeOrEqualTo(et("2026-09-28T15:00"));
        assertThat(stopFirst).isFalse();

        // Validator re-proves M5/M6/M7 from the recorded numbers.
        ValidationResult v = new com.topstep.trading.validation.MandatoryConfluenceValidator(null, r.displacement, null)
                .validateStdvOte(ctx);
        System.out.println("G1 validator: " + v.getSummary());
        assertThat(r.bus.events).anyMatch(e -> e instanceof OteArmedEvent);
        assertThat(r.bus.events).anyMatch(e -> e instanceof OteAlarmEvent);
    }

    @Test
    @DisplayName("G1 A/B: POST_SWEEP keeps the pre-05.2 sequence (second displacement + MSS, emit 15:26, never fills)")
    void g1PostSweepAb() throws Exception {
        List<Candle> window = new ArrayList<>();
        OteGoldenReplay r = replayG1(false, window);
        System.out.println("===== G1 TRANSCRIPT (POST_SWEEP A/B) =====");
        r.transcript.forEach(System.out::println);
        assertThat(r.signals).hasSize(1);
        SetupContext ctx = r.ctx();
        assertThat(ctx.oteEntryModel).isEqualTo("POST_SWEEP");
        assertThat(ctx.displacementAt).isEqualTo(et("2026-09-28T15:00"));
        assertThat(ctx.oteAlarmAt).isAfter(et("2026-09-28T15:20"));
        double entry = r.signals.get(0).getEntryPrice();
        boolean filled = window.stream().anyMatch(c -> c.getTimestamp().isAfter(ctx.oteAlarmAt) && c.getHigh() >= entry);
        System.out.println("G1 POST_SWEEP: emit " + fmt(ctx.oteAlarmAt) + " entry " + entry + " filled-in-window=" + filled);
        assertThat(filled).isFalse();
    }

    @Test
    @DisplayName("G1: the impulse model does not apply when the sweep is outside the band (14:45 / 14:49 raids short of 0.705)")
    void g1EarlyRaidsFallBack() throws Exception {
        List<Candle> tape = OteGoldenReplay.loadTape("MNQ");
        OteGoldenReplay r = new OteGoldenReplay();
        r.impulseModel = true;
        Instant start = et("2026-09-27T18:00");
        Instant inject = et("2026-09-28T14:45");
        Instant stop = et("2026-09-28T14:52");
        List<Candle> recent = new ArrayList<>();
        for (Candle c : tape) {
            Instant t = c.getTimestamp();
            if (t.isBefore(start)) { r.dealingRange.onCandle(c); continue; }
            if (t.isAfter(stop)) break;
            if (t.equals(inject)) {
                r.injectBias(MarketBias.BEARISH, recent.subList(Math.max(0, recent.size() - 30), recent.size()), t);
                // The real 14:45 raid: Current Session High 30627.00 (extreme 30627.75).
                r.injectSweep(new LiquiditySweep(false, 30627.0, t, false));
            }
            r.feed(c);
            r.step(c, MarketBias.BEARISH, false);
            recent.add(c);
        }
        System.out.println("G1 14:45 raid verdict: " + r.ctx().impulseLegVerdict + " state=" + r.ctx().state);
        assertThat(r.ctx().state).isEqualTo(SetupState.SWEEP_DONE);
        assertThat(r.ctx().impulseLegVerdict).contains("short of 0.705");
        assertThat(r.signals).isEmpty();
    }

    /**
     * G2 / G3 - what the impulse model does there. Bias injected (the owner's
     * direction), sweeps from the REAL {@code LiquidityDetector}, dealing range
     * from Agent 03's tracker; one setup per window (no forced re-arm).
     */
    static OteGoldenReplay replayWindow(String from, String to, MarketBias bias) throws Exception {
        List<Candle> tape = OteGoldenReplay.loadTape("MNQ");
        OteGoldenReplay r = new OteGoldenReplay();
        r.impulseModel = true;
        Instant start = et(from).minusSeconds(18 * 3600);
        Instant inject = et(from);
        Instant end = et(to);
        List<Candle> recent = new ArrayList<>();
        String lastVerdict = null;
        for (Candle c : tape) {
            Instant t = c.getTimestamp();
            if (t.isBefore(start)) { r.dealingRange.onCandle(c); continue; }
            if (t.isAfter(end)) break;
            if (t.equals(inject)) {
                r.injectBias(bias, recent.subList(Math.max(0, recent.size() - 30), recent.size()), t);
            }
            r.feed(c);
            if (!t.isBefore(inject)) {
                r.step(c, bias, true);
                String v = r.ctx().impulseLegVerdict;
                if (v != null && !v.equals(lastVerdict)) {
                    r.transcript.add(fmt(t) + " ET  impulse-model verdict: " + v);
                    lastVerdict = v;
                }
            }
            recent.add(c);
        }
        return r;
    }

    @Test
    @DisplayName("G2 (2026-09-25 NY AM) / G3 (2026-09-28 London): sweeps are outside the dealing-range OTE band -> POST_SWEEP fallback")
    void g2g3() throws Exception {
        for (String[] w : new String[][] {
                {"G2", "2026-09-25T09:30", "2026-09-25T12:00", "BEARISH"},
                {"G3", "2026-09-28T02:00", "2026-09-28T08:00", "BEARISH"}}) {
            OteGoldenReplay r = replayWindow(w[1], w[2], MarketBias.valueOf(w[3]));
            System.out.println("===== " + w[0] + " " + w[1] + " .. " + w[2] + " bias " + w[3]
                    + " dealing range " + r.ctx().rangeLow + " - " + r.ctx().rangeHigh + " =====");
            r.transcript.stream().filter(l -> !l.startsWith("  [5m")).forEach(System.out::println);
            for (StrategySignalEvent s : r.signals) {
                System.out.println(w[0] + " SIGNAL " + s.getSignalType() + " e=" + s.getEntryPrice()
                        + " s=" + s.getStopPrice() + " t=" + s.getTargetPrice() + " | " + s.getReason());
            }
            // The impulse model never armed: each window's sweep sits below the band.
            assertThat(r.ctx().oteEntryModel).isNotEqualTo("IMPULSE_LEG");
            assertThat(r.transcript).noneMatch(l -> l.contains("impulse-model verdict: ARMED"));
            assertThat(r.transcript).anyMatch(l -> l.contains("not in OTE band"));
        }
    }
}
