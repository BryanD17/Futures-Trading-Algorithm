package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.OteAlarmEvent;
import com.topstep.trading.event.OteArmedEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
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
 * V5 Agent 04 — golden cases on the REAL tape (Appendix E). Bias and the G1
 * sweep are injected (Agent 03 fixes them in the live path); everything after
 * the sweep — displacement, FVG linkage, MSS, dealing-range anchor, ARM,
 * ALARM, entry / stop / targets, RR — is the production code.
 */
@DisplayName("V5 Agent 04 golden cases G1/G2/G3 on the real MNQ tape")
class OteGoldenCasesTest {

    @AfterEach
    void clear() {
        System.clearProperty("ote.anchorMode");
        OteEventLog.clear("MNQ");
    }

    /** Replay G1: bias BEARISH + SWEEP_DONE of the 30640 London high injected at 14:58 ET. */
    static OteGoldenReplay replayG1() throws Exception {
        List<Candle> tape = OteGoldenReplay.loadTape("MNQ");
        OteGoldenReplay r = new OteGoldenReplay();
        Instant start = et("2026-09-27T18:00");
        Instant inject = et("2026-09-28T14:58");
        Instant end = et("2026-09-28T15:50");
        List<Candle> recent = new ArrayList<>();
        for (Candle c : tape) {
            Instant t = c.getTimestamp();
            if (t.isBefore(start)) continue;
            if (t.isAfter(end)) break;
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
    @DisplayName("G1: zone [30605.50, 30673.00] ±1, entry ±2 of 30635.75, stop > 30673, T1 30558 ±1")
    void g1ChartParity() throws Exception {
        OteGoldenReplay r = replayG1();
        System.out.println("===== G1 TRANSCRIPT (unit-test driven; bias/sweep injected at 14:58 ET) =====");
        r.transcript.forEach(System.out::println);
        System.out.println("OTE events: " + OteEventLog.recent("MNQ"));

        assertThat(r.signals).hasSize(1);
        StrategySignalEvent sig = r.signals.get(0);
        SetupContext ctx = r.ctx();
        System.out.println("G1 SIGNAL: " + sig.getSignalType() + " entry=" + sig.getEntryPrice()
                + " stop=" + sig.getStopPrice() + " target=" + sig.getTargetPrice()
                + " RR=" + sig.getActualRR() + " reason=" + sig.getReason());

        assertThat(sig.getSignalType()).isEqualTo(StrategySignalEvent.SignalType.SHORT_ENTRY);
        assertThat(ctx.oteAnchorMode).isEqualTo("DEALING_RANGE");
        assertThat(ctx.ote.f62()).isCloseTo(30605.50, within(1.0));
        assertThat(ctx.ote.f79()).isCloseTo(30673.00, within(1.0));
        assertThat(sig.getEntryPrice()).isCloseTo(30635.75, within(2.0));
        assertThat(sig.getStopPrice()).isGreaterThan(30673.00);
        assertThat(ctx.t1).isCloseTo(30558.0, within(1.0));
        assertThat(ctx.rrT1).isGreaterThanOrEqualTo(OteConfig.rrFloor(false));
        assertThat(ctx.rr).isLessThanOrEqualTo(OteConfig.rrCeiling());
        // Transitions within ±1 detector bar of the owner's chart.
        assertThat(ctx.displacementAt).isEqualTo(et("2026-09-28T15:00"));
        assertThat(ctx.mssAt).isBetween(et("2026-09-28T15:00"), et("2026-09-28T15:10"));
        // Events published with zone numbers.
        assertThat(r.bus.events).anyMatch(e -> e instanceof OteArmedEvent);
        assertThat(r.bus.events).anyMatch(e -> e instanceof OteAlarmEvent);
    }

    @Test
    @DisplayName("G1: both FVG families agree on the displacement's bearish FVG")
    void g1FvgModesAgree() throws Exception {
        OteGoldenReplay r = replayG1();
        Instant disp = et("2026-09-28T15:00");
        FairValueGap legacy = r.legacyFvgStampedAt(disp, false);
        PdArrayLocator.PdArray linked = r.driver.linkedFvgFor(disp, false).orElseThrow();
        System.out.println("G1 FVG: FvgDetector=" + legacy + " | PdArrayLocator.linkedFvg=" + linked);
        assertThat(legacy).isNotNull();
        assertThat(linked.bottom()).isEqualTo(legacy.getBottom());
        assertThat(linked.top()).isEqualTo(legacy.getTop());
    }

    @Test
    @DisplayName("G1: anchor-mode residuals (DEALING_RANGE is the chart-parity mode)")
    void g1AnchorModeResiduals() throws Exception {
        StringBuilder table = new StringBuilder("ANCHOR MODE | leg | 0.618 | 0.786 | residual 0.618 | residual 0.786\n");
        double drResidual = Double.NaN;
        for (OteAnchorMode m : OteAnchorMode.values()) {
            System.setProperty("ote.anchorMode", m.name());
            OteGoldenReplay r = replayG1();
            SetupContext ctx = r.ctx();
            if (ctx.ote == null) {
                table.append(m).append(" | no zone | - | - | - | -\n");
                continue;
            }
            double r62 = Math.abs(ctx.ote.f62() - 30605.50);
            double r79 = Math.abs(ctx.ote.f79() - 30673.00);
            table.append(String.format("%s (%s) | [%.2f, %.2f] | %.2f | %.2f | %.2f | %.2f%n",
                    m, ctx.oteAnchorSource, ctx.ote.legLow(), ctx.ote.legHigh(),
                    ctx.ote.f62(), ctx.ote.f79(), r62, r79));
            if (m == OteAnchorMode.DEALING_RANGE) drResidual = Math.max(r62, r79);
        }
        System.out.println("===== G1 ANCHOR RESIDUALS =====\n" + table);
        assertThat(drResidual).isLessThanOrEqualTo(1.0);
    }

    /** Generic window replay with injected bias and REAL LiquidityDetector sweeps; re-arms. */
    static OteGoldenReplay replayWindow(String from, String to, MarketBias bias) throws Exception {
        List<Candle> tape = OteGoldenReplay.loadTape("MNQ");
        OteGoldenReplay r = new OteGoldenReplay();
        Instant warm = et(from).minusSeconds(24 * 3600);
        Instant start = et(from);
        Instant end = et(to);
        List<Candle> recent = new ArrayList<>();
        int cooldown = 0;
        for (Candle c : tape) {
            Instant t = c.getTimestamp();
            if (t.isBefore(warm)) continue;
            if (t.isAfter(end)) break;
            boolean live = !t.isBefore(start);
            SetupState s = r.ctx().state;
            if (live && (s == SetupState.IDLE || s == SetupState.INVALIDATED || s == SetupState.IN_TRADE)) {
                if (s != SetupState.IDLE && cooldown == 0) cooldown = 5;
                if (s == SetupState.IDLE || --cooldown == 0) {
                    r.core.resetForNextWindow();
                    r.injectBias(bias, recent.subList(Math.max(0, recent.size() - 30), recent.size()), t);
                    cooldown = 0;
                }
            }
            r.feed(c);
            if (live) r.step(c, bias, true);
            recent.add(c);
        }
        return r;
    }

    @Test
    @DisplayName("G2: 2026-09-25 NY AM bearish sequence (numbers recorded in A-04)")
    void g2NyAm() throws Exception {
        OteGoldenReplay r = replayWindow("2026-09-25T09:30", "2026-09-25T12:00", MarketBias.BEARISH);
        System.out.println("===== G2 TRANSCRIPT (09-25 09:30-12:00 ET, bias BEARISH injected, real sweeps) =====");
        r.transcript.stream().filter(l -> !l.startsWith("  [5m")).forEach(System.out::println);
        for (StrategySignalEvent s : r.signals) {
            System.out.println("G2 SIGNAL " + s.getSignalType() + " entry=" + s.getEntryPrice()
                    + " stop=" + s.getStopPrice() + " target=" + s.getTargetPrice() + " " + s.getReason());
        }
        // Every zone the engine drew is a SHORT zone on the NY-session dealing range.
        for (var e : r.bus.events) {
            if (e instanceof OteArmedEvent a) {
                assertThat(a.isBullish()).isFalse();
                System.out.println("G2 ARMED " + a.toMap());
            }
        }
        for (StrategySignalEvent s : r.signals) {
            assertThat(s.getSignalType()).isEqualTo(StrategySignalEvent.SignalType.SHORT_ENTRY);
        }
    }

    @Test
    @DisplayName("G3: 2026-09-28 London 02:00-08:00 is EVALUATED (both biases)")
    void g3London() throws Exception {
        for (MarketBias b : new MarketBias[] {MarketBias.BEARISH, MarketBias.BULLISH}) {
            OteGoldenReplay r = replayWindow("2026-09-28T02:00", "2026-09-28T08:00", b);
            System.out.println("===== G3 TRANSCRIPT (09-28 London 02:00-08:00 ET, bias " + b + " injected) =====");
            r.transcript.stream().filter(l -> !l.startsWith("  [5m")).forEach(System.out::println);
            for (StrategySignalEvent s : r.signals) {
                System.out.println("G3 SIGNAL " + s.getSignalType() + " entry=" + s.getEntryPrice()
                        + " stop=" + s.getStopPrice() + " target=" + s.getTargetPrice() + " " + s.getReason());
            }
            // The window is evaluated (the machine reaches SWEEP_DONE at least once).
            assertThat(r.transcript).anyMatch(l -> l.contains("-> SWEEP_DONE"));
        }
    }

    @Test
    @DisplayName("G1 events carry zone numbers and render as JSON")
    void g1EventsJson() throws Exception {
        replayG1();
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(OteEventLog.recent("MNQ"));
        System.out.println("===== OteEventLog.recent(MNQ) JSON =====\n" + json);
        assertThat(json).contains("OTE_ARMED").contains("OTE_ALARM").contains("f618");
        System.out.println("G1 core fmt check " + fmt(et("2026-09-28T15:00")));
    }
}
