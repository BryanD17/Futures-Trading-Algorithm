package com.topstep.trading.strategy.stdvote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.domain.Trade;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.PositionClosedEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.session.SessionClassifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V5 Agent 05.9 - the LTF dealing-range machine on the REAL 7-day tape through
 * the real runner + PropFirmRiskEngine + SIM ExecutionEngine (the
 * FunnelAutopsyHarness wiring):
 * <ul>
 *   <li>flag OFF = Main (8 closed, 8W/0L, +$1,147.80), no LTF context, no LTF signal;</li>
 *   <li>INDEPENDENT: LTF trades are tagged STDV_OTE_LTF:, one position per symbol
 *       (every signal published flat with no working order, at most one position),
 *       at least one LTF trade AGAINST the HTF bias (the LTF bias is its own),
 *       the HTF machine's trades are unchanged on this tape;</li>
 *   <li>HTF_ALIGNED: every LTF signal has LTF direction = HTF bias and the entry on
 *       the HTF discount / premium side (the INDEPENDENT counter-HTF trades are gone);</li>
 *   <li>range.ltf.maxPerDay=1: at most one LTF emission per trading day, the refusal is reasoned.</li>
 * </ul>
 */
@DisplayName("V5 Agent 05.9 - LTF dealing-range machine on the real 7-day tape (OFF = Main, INDEPENDENT, HTF_ALIGNED, maxPerDay)")
class LtfRangeMachineTapeTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static Replay off;
    private static Replay independent;
    private static Replay aligned;
    private static Replay onePerDay;

    @BeforeAll
    static void replayAll() throws Exception {
        off = Replay.run("OFF", Map.of());
        independent = Replay.run("INDEPENDENT", Map.of("range.ltf.enabled", "true"));
        aligned = Replay.run("HTF_ALIGNED", Map.of("range.ltf.enabled", "true", "range.ltf.gating", "HTF_ALIGNED"));
        onePerDay = Replay.run("MAX1", Map.of("range.ltf.enabled", "true", "range.ltf.maxPerDay", "1"));
        for (Replay r : List.of(off, independent, aligned, onePerDay)) System.out.println(r.log);
    }

    @AfterAll
    static void clear() {
        StdvOteRegistry.unregister("MNQ");
    }

    @Test
    @DisplayName("flag OFF reproduces Main (8 closed, 8W/0L, +$1,147.80) with no LTF machine and no LTF signal")
    void offIsMain() {
        assertThat(off.ltfPresent).isFalse();
        assertThat(off.trades).hasSize(8);
        assertThat(off.trades.stream().mapToDouble(Trade::getRealizedPnL).sum()).isCloseTo(1147.80, within(1e-6));
        assertThat(off.trades.stream().filter(Trade::isWinner).count()).isEqualTo(8);
        assertThat(off.signals).noneMatch(Replay.Sig::ltf);
        assertThat(off.ltfGateEvents).isZero();
    }

    @Test
    @DisplayName("INDEPENDENT: LTF trades are tagged, bounded, and one position per symbol holds")
    void independentOnePositionPerSymbol() {
        assertThat(independent.signals).anyMatch(Replay.Sig::ltf);
        for (Replay.Sig s : independent.signals) {
            assertThat(s.flatAtSignal()).as("%s published while the symbol had a position / working order", s).isTrue();
            if (!s.ltf()) continue;
            assertThat(s.reason()).startsWith(LtfRangeConfig.REASON_PREFIX);
            assertThat(SessionClassifier.classify(s.at()).blocksEntry()).isFalse();
            // T1 = the LTF equilibrium: the midpoint of the LTF range the setup was planned on.
            assertThat(s.t1()).isCloseTo((s.legLow() + s.legHigh()) / 2.0, within(0.25));
        }
        assertThat(independent.maxPositions).isLessThanOrEqualTo(1);
        assertThat(independent.maxWorkingOrders).isLessThanOrEqualTo(1);
        Map<LocalDate, Integer> perDay = new HashMap<>();
        independent.signals.stream().filter(Replay.Sig::ltf)
                .forEach(s -> perDay.merge(CounterTrendScalp.tradingDay(s.at()), 1, Integer::sum));
        assertThat(perDay.values()).allMatch(n -> n <= LtfRangeConfig.DEFAULT_MAX_PER_DAY);
    }

    @Test
    @DisplayName("INDEPENDENT: the LTF bias is its own - at least one LTF trade opposes the HTF bias (e.g. a bearish LTF short inside a BULLISH HTF)")
    void independentTradesAgainstTheHtf() {
        assertThat(independent.signals).anyMatch(s -> s.ltf() && s.htfBias() != MarketBias.NEUTRAL
                && s.ltfBias() != s.htfBias());
        assertThat(independent.signals).anyMatch(s -> s.ltf() && !s.isLong() && s.htfBias() == MarketBias.BULLISH);
    }

    @Test
    @DisplayName("INDEPENDENT: the HTF machine's closed trades are unchanged on the 7-day tape")
    void htfTradesUnchanged() {
        assertThat(independent.tradeKeys(false)).isEqualTo(off.tradeKeys(false));
    }

    @Test
    @DisplayName("HTF_ALIGNED: every LTF signal has LTF direction = HTF bias and the entry on the HTF discount/premium side")
    void htfAlignedOnlyAligned() {
        for (Replay.Sig s : aligned.signals) {
            if (!s.ltf()) continue;
            assertThat(s.ltfBias()).as("%s", s).isEqualTo(s.htfBias());
            assertThat(s.isLong() ? s.entry() < s.htfEq() : s.entry() > s.htfEq()).as("%s", s).isTrue();
        }
        assertThat(aligned.signals).noneMatch(s -> s.ltf() && s.ltfBias() != s.htfBias());
    }

    @Test
    @DisplayName("range.ltf.maxPerDay=1: at most one LTF emission per trading day, the quota refusal is reasoned")
    void maxPerDay() {
        Map<LocalDate, Integer> perDay = new HashMap<>();
        onePerDay.signals.stream().filter(Replay.Sig::ltf)
                .forEach(s -> perDay.merge(CounterTrendScalp.tradingDay(s.at()), 1, Integer::sum));
        assertThat(perDay).isNotEmpty();
        assertThat(perDay.values()).allMatch(n -> n <= 1);
        assertThat(onePerDay.ltfReasons).anyMatch(r -> r.contains("LTF: range.ltf.maxPerDay 1 reached"));
    }

    // ── replay (FunnelAutopsyHarness wiring) ────────────────────────────

    static final class Replay {
        record Sig(Instant at, boolean ltf, boolean isLong, double entry, double stop, int qty, boolean flatAtSignal,
                   String reason, MarketBias ltfBias, MarketBias htfBias, double htfEq, double legLow, double legHigh,
                   double t1) {
            String key() {
                return at + " " + (isLong ? "LONG" : "SHORT") + " " + entry + " " + stop + " " + qty;
            }
        }

        final List<Sig> signals = new ArrayList<>();
        List<Trade> trades = List.of();
        final List<String> ltfReasons = new ArrayList<>();
        boolean ltfPresent;
        int maxPositions;
        int maxWorkingOrders;
        int ltfGateEvents;
        final StringBuilder log = new StringBuilder();

        static Replay run(String tag, Map<String, String> props) throws Exception {
            props.forEach(System::setProperty);
            Replay r = new Replay();
            List<Candle> mnq = load("/tape/real_MNQ_1m.json", "MNQ");
            List<Candle> mes = load("/tape/real_MES_1m.json", "MES");
            EventBus bus = new EventBus();
            AccountState account = new AccountState(50_000.0);
            RiskLimits limits = ScalpConfig.activeRiskLimits();
            PropFirmRiskEngine risk = new PropFirmRiskEngine();
            ExecutionEngine exec = new ExecutionEngine(account);
            exec.setEventBus(bus);
            DefaultStrategyContext context = new DefaultStrategyContext(account);
            ConcurrentLinkedQueue<StrategySignalEvent> sigQ = new ConcurrentLinkedQueue<>();
            bus.subscribe(StrategySignalEvent.class, sigQ::add);
            ConcurrentLinkedQueue<String> reasons = new ConcurrentLinkedQueue<>();
            bus.subscribe(com.topstep.trading.event.GateDecisionEvent.class, g -> {
                if (g.getReason() != null && g.getReason().startsWith(LtfRangeConfig.REASON_PREFIX)) reasons.add(g.getReason());
            });
            bus.start();
            StdvOteRunnerStrategy runner = new StdvOteRunnerStrategy("MNQ", "MES", bus);
            runner.initialize();
            SetupContext ctx = runner.getSetupContext();
            SetupContext lctx = runner.getLtfContext();
            r.ltfPresent = lctx != null;
            int si = 0;
            try {
                for (Candle c : mnq) {
                    Instant now = c.getTimestamp();
                    while (si < mes.size() && !mes.get(si).getTimestamp().isAfter(now)) {
                        runner.onCandle(mes.get(si++), context);
                    }
                    SetupState before = ctx.state;
                    context.setCurrentTime(now);
                    exec.onNewCandle(c);
                    bus.awaitIdle(5_000);
                    runner.onCandle(c, context);
                    bus.awaitIdle(5_000);
                    boolean entered = ctx.state == SetupState.IN_TRADE && before != SetupState.IN_TRADE;
                    StrategySignalEvent sig = sigQ.poll();
                    for (int i = 0; entered && i < 200 && sig == null; i++) {
                        Thread.sleep(10);
                        sig = sigQ.poll();
                    }
                    if (sig != null) {
                        boolean isLtf = sig.getReason() != null && sig.getReason().startsWith(LtfRangeConfig.REASON_PREFIX);
                        boolean flat = !account.hasPosition("MNQ") && exec.getActiveOrdersList("MNQ").isEmpty();
                        RiskDecision d = risk.evaluate(sig, account, limits);
                        int qty = d.isAllowed() ? d.getOrder().getQuantity() : 0;
                        SetupContext sc = isLtf ? lctx : ctx;
                        Sig s = new Sig(now, isLtf, sig.getSignalType() == StrategySignalEvent.SignalType.LONG_ENTRY,
                                sig.getEntryPrice(), sig.getStopPrice(), qty, flat, sig.getReason(),
                                isLtf ? lctx.htfBias : null, isLtf ? lctx.ltfHtfBias : ctx.htfBias,
                                isLtf ? lctx.ltfHtfEq : ctx.rangeEq,
                                sc.ote == null ? Double.NaN : sc.ote.legLow(),
                                sc.ote == null ? Double.NaN : sc.ote.legHigh(), sc.t1);
                        r.signals.add(s);
                        r.log.append(String.format("[A-05.9] %s %s SIGNAL %s %s e=%s s=%s t=%s q=%d flat=%s ltfBias=%s htfBias=%s %s | %s%n",
                                tag, now.atZone(ET).toLocalDateTime(), isLtf ? "LTF" : "HTF", s.isLong() ? "LONG" : "SHORT",
                                s.entry(), s.stop(), sig.getTargetPrice(), qty, flat, s.ltfBias(), s.htfBias(),
                                d.isAllowed() ? "ALLOW" : d.getReason(), sig.getReason()));
                        if (d.isAllowed()) {
                            Order o = d.getOrder();
                            exec.submitOrder(o, sig.getStopPrice(), sig.getTargetPrice());
                        } else {
                            bus.publish(new PositionClosedEvent("MNQ", 0.0, false, now));
                        }
                        bus.awaitIdle(5_000);
                    }
                    r.maxPositions = Math.max(r.maxPositions, account.getPositions().size());
                    r.maxWorkingOrders = Math.max(r.maxWorkingOrders, exec.getActiveOrdersList("MNQ").size());
                }
                bus.awaitIdle(5_000);
            } finally {
                runner.shutdown();
                bus.stop();
                StdvOteRegistry.unregister("MNQ");
                props.keySet().forEach(System::clearProperty);
            }
            r.ltfReasons.addAll(reasons);
            r.ltfGateEvents = r.ltfReasons.size();
            r.trades = new ArrayList<>(exec.getCompletedTrades());
            double[] sum = new double[4];
            int[] n = new int[2];
            for (Trade t : r.trades) {
                boolean isLtf = r.isLtfTrade(t);
                int k = isLtf ? 2 : 0;
                sum[k] += t.getRealizedPnL();
                sum[k + 1] += t.getRMultiple();
                n[isLtf ? 1 : 0]++;
            }
            r.log.append(String.format("[A-05.9] %s closed=%d HTF n=%d pnl=%.2f R=%.2f | LTF n=%d pnl=%.2f R=%.2f"
                            + " | LTF gate events=%d maxPositions=%d%n",
                    tag, r.trades.size(), n[0], sum[0], sum[1], n[1], sum[2], sum[3], r.ltfGateEvents, r.maxPositions));
            return r;
        }

        /** A trade belongs to the latest signal published at/before its entry (one position per symbol). */
        boolean isLtfTrade(Trade t) {
            Sig last = null;
            for (Sig s : signals) {
                if (s.at().isBefore(t.getEntryTime())) last = s;   // a signal fills on a LATER bar
            }
            return last != null && last.ltf();
        }

        List<String> tradeKeys(boolean ltf) {
            List<String> out = new ArrayList<>();
            for (Trade t : trades) {
                if (isLtfTrade(t) != ltf) continue;
                out.add(t.getSide() + " " + t.getQuantity() + " " + t.getEntryTime() + " " + t.getEntryPrice()
                        + " " + t.getExitTime() + " " + t.getExitPrice() + " " + t.getRealizedPnL());
            }
            return out;
        }
    }

    private static List<Candle> load(String res, String symbol) throws Exception {
        try (InputStream in = LtfRangeMachineTapeTest.class.getResourceAsStream(res)) {
            JsonNode arr = new ObjectMapper().readTree(in);
            List<Candle> out = new ArrayList<>(arr.size());
            for (JsonNode b : arr) {
                out.add(new Candle(symbol,
                        java.time.OffsetDateTime.parse(b.get("t").asText()).toInstant(),
                        b.get("o").asDouble(), b.get("h").asDouble(),
                        b.get("l").asDouble(), b.get("c").asDouble(),
                        b.get("v").asLong()));
            }
            return out;
        }
    }
}
