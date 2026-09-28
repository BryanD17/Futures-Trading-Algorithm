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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V5 Agent 05.6 — {@code bias.range.window} on the owner's REAL tape
 * (7,830 bars, 2026-09-21 → 2026-09-28 ET) through the REAL runner + risk
 * engine + SIM execution (FunnelAutopsyHarness wiring), DEFAULT config
 * (AUTO) vs the SESSION_DAY A/B.
 *
 * <ul>
 *   <li>G2 (2026-09-25 NY AM): range HH 30926.50 → LL 30684.00, bias BEARISH,
 *       OTE band from that range, the 10:06 LONG (overnight-high range) gone,
 *       the bearish OTE short taken and filled;</li>
 *   <li>G1 (2026-09-28): bias BEARISH by 11:30, range 30759.25 / 30356.75,
 *       short 14:53 within 2 pt of 30635.75, filled, positive;</li>
 *   <li>ASIA / LONDON trades identical to SESSION_DAY; SESSION_DAY
 *       reproduces Main (7 closed, +$401.00).</li>
 * </ul>
 */
@DisplayName("V5 Agent 05.6 — dealing-range window on the real tape (G1 + G2, SESSION_DAY A/B)")
class RangeWindowTapeTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static Replay auto;
    private static Replay sessionDay;

    @BeforeAll
    static void replayBoth() throws Exception {
        auto = Replay.run(null);
        sessionDay = Replay.run("SESSION_DAY");
        System.out.println(auto.log);
        System.out.println("---- A/B bias.range.window=SESSION_DAY ----");
        System.out.println(sessionDay.log);
    }

    @AfterAll
    static void clear() {
        System.clearProperty("bias.range.window");
        StdvOteRegistry.unregister("MNQ");
    }

    private static Instant et(String iso) {
        return LocalDateTime.parse(iso).atZone(ET).toInstant();
    }

    @Test
    @DisplayName("G2 09-25: range HH 30926.50 -> LL 30684.00, bias BEARISH after the 10:15 15m close")
    void g2RangeAndBias() {
        Replay.Row r1030 = auto.rows.get("2026-09-25T10:30");
        Replay.Row r1130 = auto.rows.get("2026-09-25T11:30");
        assertThat(r1030.bias()).isEqualTo(MarketBias.BEARISH);
        assertThat(r1130.bias()).isEqualTo(MarketBias.BEARISH);
        for (Replay.Row r : new Replay.Row[] {r1030, r1130}) {
            assertThat(r.rangeHigh()).isCloseTo(30926.50, within(5.0));
            assertThat(r.rangeLow()).isBetween(30684.0, 30721.0);
        }
        // SESSION_DAY: the overnight high 30999.50, BULLISH (the defect).
        Replay.Row sd = sessionDay.rows.get("2026-09-25T10:30");
        assertThat(sd.bias()).isEqualTo(MarketBias.BULLISH);
        assertThat(sd.rangeHigh()).isEqualTo(30999.50);
    }

    @Test
    @DisplayName("G2 09-25: no 10:06 LONG; the bearish OTE short from [30833.75, 30874.50] fills and wins")
    void g2Trade() {
        assertThat(auto.signals).noneSatisfy(s -> assertThat(s.at()).isEqualTo(et("2026-09-25T10:06")));
        assertThat(sessionDay.signals).anySatisfy(s -> {
            assertThat(s.at()).isEqualTo(et("2026-09-25T10:06"));
            assertThat(s.isLong()).isTrue();
        });
        Replay.Sig g2 = auto.signals.stream()
                .filter(s -> s.at().atZone(ET).toLocalDate().toString().equals("2026-09-25"))
                .findFirst().orElseThrow();
        assertThat(g2.isLong()).isFalse();
        assertThat(g2.bandLo()).isEqualTo(30833.75);
        assertThat(g2.bandHi()).isEqualTo(30874.50);
        assertThat(g2.entry()).isBetween(30833.75, 30874.50);
        assertThat(g2.stop()).isGreaterThan(30874.50);
        Trade t = auto.tradeEnteredAfter(g2.at());
        assertThat(t).as("G2 short filled").isNotNull();
        assertThat(t.getRealizedPnL()).isPositive();
    }

    @Test
    @DisplayName("G1 09-28: bias BEARISH 11:30, range 30759.25 / 30356.75, short 14:53 ~30635.75 fills +R")
    void g1() {
        Replay.Row r = auto.rows.get("2026-09-28T11:30");
        assertThat(r.bias()).isEqualTo(MarketBias.BEARISH);
        Replay.Row r1453 = auto.rows.get("2026-09-28T14:53");
        assertThat(r1453.rangeHigh()).isEqualTo(30759.25);
        assertThat(r1453.rangeLow()).isEqualTo(30356.75);
        Replay.Sig g1 = auto.signals.stream().filter(s -> s.at().equals(et("2026-09-28T14:53")))
                .findFirst().orElseThrow();
        assertThat(g1.isLong()).isFalse();
        assertThat(g1.entry()).isCloseTo(30635.75, within(2.0));
        Trade t = auto.tradeEnteredAfter(g1.at());
        assertThat(t).as("G1 filled").isNotNull();
        assertThat(t.getRMultiple()).isPositive();
    }

    @Test
    @DisplayName("ASIA / LONDON trades identical to SESSION_DAY; SESSION_DAY reproduces Main (7 closed, +$401.00)")
    void abAndUnaffectedSessions() {
        assertThat(overnight(auto)).isEqualTo(overnight(sessionDay));
        assertThat(sessionDay.trades).hasSize(7);
        assertThat(sessionDay.trades.stream().mapToDouble(Trade::getRealizedPnL).sum())
                .isCloseTo(401.0, within(1e-6));
        Map<String, Integer> a = auto.fillsBySession();
        Map<String, Integer> b = sessionDay.fillsBySession();
        for (var e : b.entrySet()) {
            assertThat(a.getOrDefault(e.getKey(), 0)).as("fills in " + e.getKey())
                    .isGreaterThanOrEqualTo(e.getValue());
        }
        assertThat(auto.silentArmedBars).as("OTE_ARMED bars without a named reason").isZero();
    }

    private static List<String> overnight(Replay r) {
        List<String> out = new ArrayList<>();
        for (Trade t : r.trades) {
            String s = IctOrderBlockAtSweepTest.session(t.getEntryTime());
            if (s.equals("ASIA") || s.equals("LONDON")) {
                out.add(s + " " + t.getSide() + " " + t.getQuantity() + " " + t.getEntryTime() + " "
                        + t.getEntryPrice() + " " + t.getExitTime() + " " + t.getExitPrice() + " "
                        + t.getRealizedPnL());
            }
        }
        return out;
    }

    // ── replay (FunnelAutopsyHarness wiring) ────────────────────────────

    static final class Replay {
        record Row(MarketBias bias, double rangeHigh, double rangeLow) {}

        record Sig(Instant at, boolean isLong, double entry, double stop, double bandLo, double bandHi, int qty) {}

        final Map<String, Row> rows = new LinkedHashMap<>();
        final List<Sig> signals = new ArrayList<>();
        List<Trade> trades = List.of();
        int silentArmedBars;
        final StringBuilder log = new StringBuilder();

        static Replay run(String window) throws Exception {
            if (window == null) System.clearProperty("bias.range.window");
            else System.setProperty("bias.range.window", window);
            String tag = window == null ? "AUTO(default)" : window;
            Replay r = new Replay();
            List<Candle> mnq = load("real_MNQ_1m.json", "MNQ");
            List<Candle> mes = load("real_MES_1m.json", "MES");
            EventBus bus = new EventBus();
            AccountState account = new AccountState(50_000.0);
            RiskLimits limits = ScalpConfig.activeRiskLimits();
            PropFirmRiskEngine risk = new PropFirmRiskEngine();
            ExecutionEngine exec = new ExecutionEngine(account);
            exec.setEventBus(bus);
            DefaultStrategyContext context = new DefaultStrategyContext(account);
            ConcurrentLinkedQueue<StrategySignalEvent> sigQ = new ConcurrentLinkedQueue<>();
            bus.subscribe(StrategySignalEvent.class, sigQ::add);
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
                    r.rows.put(key, new Row(ctx.htfBias, ctx.rangeHigh, ctx.rangeLow));
                    boolean emitted = ctx.state == SetupState.IN_TRADE && before != SetupState.IN_TRADE;
                    if (ctx.state == SetupState.OTE_ARMED && !emitted && ctx.lastGateFailed == null) {
                        r.silentArmedBars++;
                    }
                    if (key.startsWith("2026-09-25T") && key.compareTo("2026-09-25T09:30") >= 0
                            && key.compareTo("2026-09-25T12:00") < 0 && key.endsWith("0")) {
                        r.log.append(String.format("[A-05.6] %s %s 09-25 %s bias=%s range=[%s, %s] state=%s%n",
                                tag, "row", key.substring(11), ctx.htfBias, ctx.rangeLow, ctx.rangeHigh, ctx.state));
                    }
                    if (emitted) {
                        StrategySignalEvent sig = null;
                        for (int i = 0; i < 200 && sig == null; i++) {
                            sig = sigQ.poll();
                            if (sig == null) Thread.sleep(10);
                        }
                        if (sig == null) continue;
                        RiskDecision d = risk.evaluate(sig, account, limits);
                        OteZone z = ctx.ote;
                        Sig s = new Sig(now, sig.getSignalType() == StrategySignalEvent.SignalType.LONG_ENTRY,
                                sig.getEntryPrice(), sig.getStopPrice(),
                                z == null ? Double.NaN : Math.min(z.f62(), z.f79()),
                                z == null ? Double.NaN : Math.max(z.f62(), z.f79()),
                                d.isAllowed() ? d.getOrder().getQuantity() : 0);
                        r.signals.add(s);
                        r.log.append(String.format("[A-05.6] %s SIGNAL %s %s e=%s s=%s band=[%s, %s] range=[%s, %s] bias=%s q=%d %s%n",
                                tag, key, s.isLong() ? "LONG" : "SHORT", s.entry(), s.stop(), s.bandLo(), s.bandHi(),
                                ctx.rangeLow, ctx.rangeHigh, ctx.htfBias, s.qty(),
                                d.isAllowed() ? "ALLOW" : d.getReason()));
                        if (d.isAllowed()) {
                            Order o = d.getOrder();
                            exec.submitOrder(o, sig.getStopPrice(), sig.getTargetPrice());
                        } else {
                            bus.publish(new PositionClosedEvent("MNQ", 0.0, false, now));
                        }
                        bus.awaitIdle(5_000);
                    }
                }
            } finally {
                runner.shutdown();
                bus.stop();
                StdvOteRegistry.unregister("MNQ");
                System.clearProperty("bias.range.window");
            }
            r.trades = new ArrayList<>(exec.getCompletedTrades());
            double pnl = 0;
            double rs = 0;
            for (Trade t : r.trades) {
                pnl += t.getRealizedPnL();
                rs += t.getRMultiple();
                r.log.append(String.format("[A-05.6] %s TRADE %s %s q=%d in=%s @%s out=%s @%s pnl=%.2f R=%.2f%n",
                        tag, IctOrderBlockAtSweepTest.session(t.getEntryTime()), t.getSide(), t.getQuantity(),
                        t.getEntryPrice(), t.getEntryTime().atZone(ET).toLocalDateTime(), t.getExitPrice(),
                        t.getExitTime().atZone(ET).toLocalDateTime(), t.getRealizedPnL(), t.getRMultiple()));
            }
            r.log.append(String.format("[A-05.6] %s closed=%d pnl=%.2f R=%.2f silentArmed=%d%n",
                    tag, r.trades.size(), pnl, rs, r.silentArmedBars));
            return r;
        }

        Trade tradeEnteredAfter(Instant signalAt) {
            return trades.stream().filter(t -> !t.getEntryTime().isBefore(signalAt))
                    .findFirst().orElse(null);
        }

        Map<String, Integer> fillsBySession() {
            Map<String, Integer> m = new java.util.TreeMap<>();
            for (Trade t : trades) m.merge(IctOrderBlockAtSweepTest.session(t.getEntryTime()), 1, Integer::sum);
            return m;
        }
    }

    private static List<Candle> load(String res, String symbol) throws Exception {
        try (InputStream in = RangeWindowTapeTest.class.getResourceAsStream("/tape/" + res)) {
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
