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
import com.topstep.trading.strategy.session.SessionWindow;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V5 Agent 05.8 — the counter-trend scalp on the REAL 7-day tape through the
 * real runner + PropFirmRiskEngine + SIM ExecutionEngine (the
 * FunnelAutopsyHarness wiring, every bus signal handled on its bar).
 *
 * <ul>
 *   <li>flag OFF (default) = Main: 8 closed, 8W/0L, +$1,147.80, and the
 *       with-trend machine's state on every bar is recorded as the baseline;</li>
 *   <li>flag ON: the with-trend machine is bar-for-bar identical (state, bias,
 *       range, entry, stop, RR, lastGateFailed) and emits the same signals;
 *       the scalp and the with-trend setup NEVER overlap on the symbol (every
 *       signal is published flat with no working order, at most one position
 *       ever); every scalp is tagged STDV_OTE_CT:, inside its sessions, at most
 *       maxPerDay per trading day and sized on half the budget.</li>
 * </ul>
 */
@DisplayName("V5 Agent 05.8 — counter-trend scalp on the real 7-day tape (no overlap, with-trend unchanged, OFF = Main)")
class CounterTrendScalpTapeTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static Replay off;
    private static Replay on;

    @BeforeAll
    static void replayBoth() throws Exception {
        off = Replay.run(false);
        on = Replay.run(true);
        System.out.println(off.log);
        System.out.println("---- entry.counterTrendScalp=true ----");
        System.out.println(on.log);
    }

    @AfterAll
    static void clear() {
        System.clearProperty("entry.counterTrendScalp");
        StdvOteRegistry.unregister("MNQ");
    }

    @Test
    @DisplayName("flag OFF reproduces Main (8 closed, 8W/0L, +$1,147.80) and publishes no scalp")
    void offIsMain() {
        assertThat(off.trades).hasSize(8);
        assertThat(off.trades.stream().mapToDouble(Trade::getRealizedPnL).sum()).isCloseTo(1147.80, within(1e-6));
        assertThat(off.trades.stream().filter(Trade::isWinner).count()).isEqualTo(8);
        assertThat(off.signals).noneMatch(Replay.Sig::ct);
        assertThat(off.ctGateEvents).isZero();
    }

    @Test
    @DisplayName("flag ON: the with-trend machine is bar-for-bar identical and emits the same signals")
    void withTrendUnchanged() {
        assertThat(on.rows).hasSameSizeAs(off.rows);
        for (Map.Entry<String, String> e : off.rows.entrySet()) {
            assertThat(on.rows.get(e.getKey())).as("with-trend state at %s", e.getKey()).isEqualTo(e.getValue());
        }
        List<String> wtOn = on.signals.stream().filter(s -> !s.ct()).map(Replay.Sig::key).toList();
        List<String> wtOff = off.signals.stream().map(Replay.Sig::key).toList();
        assertThat(wtOn).isEqualTo(wtOff);
        List<String> wtTradesOn = on.tradeKeys(false);
        assertThat(wtTradesOn).isEqualTo(off.tradeKeys(false));
    }

    @Test
    @DisplayName("flag ON: scalp and with-trend setup never overlap (flat + no working order at every signal, <= 1 position)")
    void neverOverlap() {
        assertThat(on.signals).anyMatch(Replay.Sig::ct);
        for (Replay.Sig s : on.signals) {
            assertThat(s.flatAtSignal()).as("%s published while the symbol had a position / working order", s).isTrue();
        }
        assertThat(on.maxPositions).isLessThanOrEqualTo(1);
        assertThat(on.maxWorkingOrders).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("flag ON: every scalp is tagged, in its sessions, <= maxPerDay, half-budget sized, T1 = the range equilibrium")
    void scalpsAreBounded() {
        Map<LocalDate, Integer> perDay = new HashMap<>();
        for (Replay.Sig s : on.signals) {
            if (!s.ct()) continue;
            assertThat(s.reason()).startsWith(CounterTrendScalp.REASON_PREFIX);
            SessionWindow w = SessionClassifier.classify(s.at());
            assertThat(w).isIn(SessionWindow.ASIA, SessionWindow.LONDON, SessionWindow.PRE_NY);
            perDay.merge(CounterTrendScalp.tradingDay(s.at()), 1, Integer::sum);
            assertThat(s.riskDollars()).as("%s $ risk", s).isLessThanOrEqualTo(125.0 + 1e-6);
            // T1 = equilibrium of the range the scalp faded (printed in the reason).
            assertThat(s.reason()).contains("T1(eq)=");
        }
        assertThat(perDay.values()).allMatch(n -> n <= 2);
    }

    // ── replay (FunnelAutopsyHarness wiring) ────────────────────────────

    static final class Replay {
        record Sig(Instant at, boolean ct, boolean isLong, double entry, double stop, int qty,
                   double riskDollars, boolean flatAtSignal, String reason) {
            String key() {
                return at + " " + (isLong ? "LONG" : "SHORT") + " " + entry + " " + stop + " " + qty;
            }
        }

        final Map<String, String> rows = new LinkedHashMap<>();
        final List<Sig> signals = new ArrayList<>();
        List<Trade> trades = List.of();
        final Map<Instant, Boolean> entryIsCt = new HashMap<>();
        int maxPositions;
        int maxWorkingOrders;
        int ctGateEvents;
        final StringBuilder log = new StringBuilder();

        static Replay run(boolean ct) throws Exception {
            if (ct) System.setProperty("entry.counterTrendScalp", "true");
            else System.clearProperty("entry.counterTrendScalp");
            Replay r = new Replay();
            String tag = ct ? "CT_ON" : "CT_OFF";
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
            int[] ctEvents = {0};
            bus.subscribe(com.topstep.trading.event.GateDecisionEvent.class, g -> {
                if (CounterTrendScalp.GATE.equals(g.getGate())) ctEvents[0]++;
            });
            bus.start();
            StdvOteRunnerStrategy runner = new StdvOteRunnerStrategy("MNQ", "MES", bus);
            runner.initialize();
            SetupContext ctx = runner.getSetupContext();
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
                    String key = now.atZone(ET).toLocalDateTime().toString();
                    r.rows.put(key, ctx.state + "|" + ctx.htfBias + "|" + ctx.rangeHigh + "|" + ctx.rangeLow
                            + "|" + ctx.entry + "|" + ctx.stop + "|" + ctx.rr + "|" + ctx.lastGateFailed);
                    boolean entered = ctx.state == SetupState.IN_TRADE && before != SetupState.IN_TRADE;
                    StrategySignalEvent sig = sigQ.poll();
                    for (int i = 0; entered && i < 200 && sig == null; i++) {
                        Thread.sleep(10);
                        sig = sigQ.poll();
                    }
                    if (sig != null) {
                        boolean isCt = sig.getReason() != null
                                && sig.getReason().startsWith(CounterTrendScalp.REASON_PREFIX);
                        boolean flat = !account.hasPosition("MNQ") && exec.getActiveOrdersList("MNQ").isEmpty();
                        RiskDecision d = risk.evaluate(sig, account, limits);
                        int qty = d.isAllowed() ? d.getOrder().getQuantity() : 0;
                        double perMicro = Math.abs(sig.getEntryPrice() - sig.getStopPrice()) / 0.25 * 0.5;
                        Sig s = new Sig(now, isCt, sig.getSignalType() == StrategySignalEvent.SignalType.LONG_ENTRY,
                                sig.getEntryPrice(), sig.getStopPrice(), qty, qty * perMicro, flat, sig.getReason());
                        r.signals.add(s);
                        r.log.append(String.format("[A-05.8] %s %s SIGNAL %s %s e=%s s=%s t=%s q=%d flat=%s %s | %s%n",
                                tag, key, isCt ? "CT" : "WT", s.isLong() ? "LONG" : "SHORT", s.entry(), s.stop(),
                                sig.getTargetPrice(), qty, flat, d.isAllowed() ? "ALLOW" : d.getReason(), sig.getReason()));
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
            } finally {
                runner.shutdown();
                bus.stop();
                StdvOteRegistry.unregister("MNQ");
                System.clearProperty("entry.counterTrendScalp");
            }
            r.ctGateEvents = ctEvents[0];
            r.trades = new ArrayList<>(exec.getCompletedTrades());
            double[] sum = new double[4];
            for (Trade t : r.trades) {
                boolean isCt = r.isCtTrade(t);
                int k = isCt ? 2 : 0;
                sum[k] += t.getRealizedPnL();
                sum[k + 1] += t.getRMultiple();
                r.log.append(String.format("[A-05.8] %s TRADE %s %s %s q=%d in=%s @%s out=%s @%s pnl=%.2f R=%.2f%n",
                        tag, isCt ? "CT" : "WT", IctOrderBlockAtSweepTest.session(t.getEntryTime()), t.getSide(),
                        t.getQuantity(), t.getEntryPrice(), t.getEntryTime().atZone(ET).toLocalDateTime(),
                        t.getExitPrice(), t.getExitTime().atZone(ET).toLocalDateTime(), t.getRealizedPnL(),
                        t.getRMultiple()));
            }
            r.log.append(String.format("[A-05.8] %s closed=%d with-trend pnl=%.2f R=%.2f | CT pnl=%.2f R=%.2f"
                            + " | CT gate events=%d maxPositions=%d%n",
                    tag, r.trades.size(), sum[0], sum[1], sum[2], sum[3], r.ctGateEvents, r.maxPositions));
            return r;
        }

        /** A trade belongs to the latest signal published at/before its entry. */
        boolean isCtTrade(Trade t) {
            Sig last = null;
            for (Sig s : signals) {
                if (!s.at().isAfter(t.getEntryTime())) last = s;
            }
            return last != null && last.ct();
        }

        List<String> tradeKeys(boolean ct) {
            List<String> out = new ArrayList<>();
            for (Trade t : trades) {
                if (isCtTrade(t) != ct) continue;
                out.add(t.getSide() + " " + t.getQuantity() + " " + t.getEntryTime() + " " + t.getEntryPrice()
                        + " " + t.getExitTime() + " " + t.getExitPrice() + " " + t.getRealizedPnL());
            }
            return out;
        }
    }

    private static List<Candle> load(String res, String symbol) throws Exception {
        try (InputStream in = CounterTrendScalpTapeTest.class.getResourceAsStream(res)) {
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
