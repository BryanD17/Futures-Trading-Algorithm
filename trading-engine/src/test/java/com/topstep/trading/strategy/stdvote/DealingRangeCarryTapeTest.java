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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V5 Agent 05.7 — {@code bias.range.carryAcrossReopen} on the REAL tapes.
 *
 * <ul>
 *   <li>21-day tape (ends 2026-09-28 19:47 ET, i.e. it contains the LIVE
 *       reopen of 2026-09-28): the tracker alone, fed every MNQ 1m bar. Flag
 *       off reproduces the live {@code BULLISH[30537.0-30615.25]} read at
 *       18:59; flag on keeps the RTH impulse {@code BEARISH[30356.5-30759.25]}
 *       (the 21-day feed prints the RTH low a tick lower than the 7-day one).</li>
 *   <li>7-day tape through the REAL runner + risk + SIM execution (the
 *       FunnelAutopsyHarness wiring, every bus signal handled on the bar it
 *       is published): G1 (09-28 14:53 short) and the 09-25 11:33 NY_AM
 *       short are unchanged; bias and range differ from the A/B only inside
 *       18:00–03:00 ET (the reopen window); every trade entered 09:30–18:00
 *       ET is identical.</li>
 * </ul>
 */
@DisplayName("V5 Agent 05.7 — dealing range carries across the 18:00 ET reopen (real tapes)")
class DealingRangeCarryTapeTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static Replay carry;
    private static Replay noCarry;

    @BeforeAll
    static void replayBoth() throws Exception {
        carry = Replay.run(true);
        noCarry = Replay.run(false);
        System.out.println(carry.log);
        System.out.println("---- A/B bias.range.carryAcrossReopen=false ----");
        System.out.println(noCarry.log);
    }

    @AfterAll
    static void clear() {
        System.clearProperty("bias.range.carryAcrossReopen");
        StdvOteRegistry.unregister("MNQ");
    }

    private static Instant et(String iso) {
        return LocalDateTime.parse(iso).atZone(ET).toInstant();
    }

    @Test
    @DisplayName("LIVE 2026-09-28 reopen (21-day tape): carry keeps BEARISH [30356.5, 30759.25]; flag off = the live BULLISH [30537.0, 30615.25]")
    void liveReopenOnTape21() throws Exception {
        List<Candle> mnq = load("/tape21/real_MNQ_1m.json", "MNQ");
        DealingRangeTracker on = new DealingRangeTracker(0.08, 0.5, DealingRangeTracker.Window.AUTO, 400 * 0.25, true);
        DealingRangeTracker off = new DealingRangeTracker(0.08, 0.5, DealingRangeTracker.Window.AUTO, 400 * 0.25, false);
        Map<String, DealingRangeTracker.Snapshot[]> at = new LinkedHashMap<>();
        for (Candle c : mnq) {
            on.onCandle(c);
            off.onCandle(c);
            String k = c.getTimestamp().atZone(ET).toLocalDateTime().toString();
            if (k.startsWith("2026-09-28T1")) at.put(k, new DealingRangeTracker.Snapshot[] {on.snapshot(), off.snapshot()});
        }
        StringBuilder sb = new StringBuilder();
        for (String k : new String[] {"2026-09-28T16:59", "2026-09-28T18:00", "2026-09-28T18:30",
                "2026-09-28T18:59", "2026-09-28T19:30", "2026-09-28T19:47"}) {
            DealingRangeTracker.Snapshot[] s = at.get(k);
            if (s == null) continue;
            sb.append("\n[A-05.7] tape21 ").append(k.substring(11)).append(" carry=").append(s[0])
              .append("\n                     off  =").append(s[1]);
        }
        System.out.println(sb);
        DealingRangeTracker.Snapshot[] close = at.get("2026-09-28T16:59");
        assertThat(close[0]).isEqualTo(close[1]);
        assertThat(close[0].direction()).isEqualTo(MarketBias.BEARISH);
        DealingRangeTracker.Snapshot[] s1859 = at.get("2026-09-28T18:59");
        assertThat(s1859[1].direction()).as("flag off: the live micro-range read").isEqualTo(MarketBias.BULLISH);
        assertThat(s1859[1].low()).isEqualTo(30537.00);
        assertThat(s1859[1].high()).isEqualTo(30615.25);
        for (String k : at.keySet()) {
            if (k.compareTo("2026-09-28T18:00") < 0) continue;
            DealingRangeTracker.Snapshot s = at.get(k)[0];
            assertThat(s.direction()).as("carry bias at %s", k).isEqualTo(MarketBias.BEARISH);
            assertThat(s.high()).as("carry high at %s", k).isEqualTo(close[0].high());
            assertThat(s.low()).as("carry low at %s", k).isEqualTo(close[0].low());
        }
        assertThat(close[0].high()).isEqualTo(30759.25);
        assertThat(close[0].low()).isCloseTo(30356.75, within(0.25));
        assertThat(on.carryingPreviousRange()).as("no 100-pt Asia leg by 19:47 on this tape").isTrue();
    }

    @Test
    @DisplayName("G1 09-28 14:53 short and the 09-25 11:33 NY_AM short are unchanged")
    void goldenCasesUnchanged() {
        for (Replay r : new Replay[] {carry, noCarry}) {
            Replay.Sig g1 = r.signalAt(et("2026-09-28T14:53"));
            assertThat(g1).as(r.tag + " G1").isNotNull();
            assertThat(g1.isLong()).isFalse();
            assertThat(g1.entry()).isCloseTo(30635.75, within(2.0));
            Trade t1 = r.tradeEnteredAfter(g1.at());
            assertThat(t1).isNotNull();
            assertThat(t1.getRMultiple()).isPositive();
            Replay.Sig g2 = r.signalAt(et("2026-09-25T11:33"));
            assertThat(g2).as(r.tag + " 09-25 short").isNotNull();
            assertThat(g2.isLong()).isFalse();
            assertThat(g2.entry()).isEqualTo(30847.5);
            assertThat(g2.stop()).isEqualTo(30889.75);
            assertThat(r.tradeEnteredAfter(g2.at()).getRealizedPnL()).isCloseTo(253.5, within(1e-6));
        }
    }

    @Test
    @DisplayName("bias / range differ from the A/B only inside 18:00-03:00 ET; RTH-entered trades identical")
    void onlyTheReopenWindowChanges() {
        int diff = 0;
        for (var e : carry.rows.entrySet()) {
            Replay.Row a = e.getValue();
            Replay.Row b = noCarry.rows.get(e.getKey());
            boolean same = a.bias() == b.bias() && Double.compare(a.rangeHigh(), b.rangeHigh()) == 0
                    && Double.compare(a.rangeLow(), b.rangeLow()) == 0;
            if (!same) {
                diff++;
                LocalTime t = LocalDateTime.parse(e.getKey()).toLocalTime();
                assertThat(!t.isBefore(LocalTime.of(18, 0)) || t.isBefore(LocalTime.of(3, 0)))
                        .as("range/bias differs at %s (outside the reopen window)", e.getKey()).isTrue();
            }
        }
        System.out.println("[A-05.7] bars whose bias/range differ (all inside 18:00-03:00 ET): " + diff);
        assertThat(diff).isPositive();
        assertThat(rth(carry)).isEqualTo(rth(noCarry));
    }

    @Test
    @DisplayName("A/B flag off reproduces Main on the 7-day tape (7 closed, +$851.00)")
    void flagOffReproducesMain() {
        assertThat(noCarry.trades).hasSize(7);
        assertThat(noCarry.trades.stream().mapToDouble(Trade::getRealizedPnL).sum()).isCloseTo(851.0, within(1e-6));
        assertThat(carry.silentArmedBars).isZero();
    }

    private static List<String> rth(Replay r) {
        List<String> out = new ArrayList<>();
        for (Trade t : r.trades) {
            LocalTime lt = t.getEntryTime().atZone(ET).toLocalTime();
            if (!lt.isBefore(LocalTime.of(9, 30)) && lt.isBefore(LocalTime.of(18, 0))) {
                out.add(t.getSide() + " " + t.getQuantity() + " " + t.getEntryTime() + " " + t.getEntryPrice()
                        + " " + t.getExitTime() + " " + t.getExitPrice() + " " + t.getRealizedPnL());
            }
        }
        return out;
    }

    // ── replay (FunnelAutopsyHarness wiring) ────────────────────────────

    static final class Replay {
        record Row(MarketBias bias, double rangeHigh, double rangeLow) {}

        record Sig(Instant at, boolean isLong, double entry, double stop, int qty) {}

        final Map<String, Row> rows = new LinkedHashMap<>();
        final List<Sig> signals = new ArrayList<>();
        List<Trade> trades = List.of();
        int silentArmedBars;
        String tag;
        final StringBuilder log = new StringBuilder();

        static Replay run(boolean carryAcrossReopen) throws Exception {
            System.setProperty("bias.range.carryAcrossReopen", Boolean.toString(carryAcrossReopen));
            Replay r = new Replay();
            r.tag = carryAcrossReopen ? "CARRY(default)" : "NO_CARRY";
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
                    boolean entered = ctx.state == SetupState.IN_TRADE && before != SetupState.IN_TRADE;
                    // Every bus signal is handled on the bar it is published
                    // (a re-arm after a close can emit on an IN_TRADE -> IN_TRADE bar).
                    StrategySignalEvent sig = sigQ.poll();
                    for (int i = 0; entered && i < 200 && sig == null; i++) {
                        Thread.sleep(10);
                        sig = sigQ.poll();
                    }
                    if (ctx.state == SetupState.OTE_ARMED && sig == null && ctx.lastGateFailed == null) {
                        r.silentArmedBars++;
                    }
                    if (sig == null) continue;
                    RiskDecision d = risk.evaluate(sig, account, limits);
                    Sig s = new Sig(now, sig.getSignalType() == StrategySignalEvent.SignalType.LONG_ENTRY,
                            sig.getEntryPrice(), sig.getStopPrice(), d.isAllowed() ? d.getOrder().getQuantity() : 0);
                    r.signals.add(s);
                    r.log.append(String.format("[A-05.7] %s SIGNAL %s %s e=%s s=%s range=[%s, %s] bias=%s q=%d %s%n",
                            r.tag, key, s.isLong() ? "LONG" : "SHORT", s.entry(), s.stop(),
                            ctx.rangeLow, ctx.rangeHigh, ctx.htfBias, s.qty(), d.isAllowed() ? "ALLOW" : d.getReason()));
                    if (d.isAllowed()) {
                        Order o = d.getOrder();
                        exec.submitOrder(o, sig.getStopPrice(), sig.getTargetPrice());
                    } else {
                        bus.publish(new PositionClosedEvent("MNQ", 0.0, false, now));
                    }
                    bus.awaitIdle(5_000);
                }
            } finally {
                runner.shutdown();
                bus.stop();
                StdvOteRegistry.unregister("MNQ");
                System.clearProperty("bias.range.carryAcrossReopen");
            }
            r.trades = new ArrayList<>(exec.getCompletedTrades());
            double pnl = 0;
            double rs = 0;
            for (Trade t : r.trades) {
                pnl += t.getRealizedPnL();
                rs += t.getRMultiple();
                r.log.append(String.format("[A-05.7] %s TRADE %s %s q=%d in=%s @%s out=%s @%s pnl=%.2f R=%.2f%n",
                        r.tag, IctOrderBlockAtSweepTest.session(t.getEntryTime()), t.getSide(), t.getQuantity(),
                        t.getEntryPrice(), t.getEntryTime().atZone(ET).toLocalDateTime(), t.getExitPrice(),
                        t.getExitTime().atZone(ET).toLocalDateTime(), t.getRealizedPnL(), t.getRMultiple()));
            }
            r.log.append(String.format("[A-05.7] %s closed=%d pnl=%.2f R=%.2f silentArmed=%d%n",
                    r.tag, r.trades.size(), pnl, rs, r.silentArmedBars));
            return r;
        }

        Sig signalAt(Instant at) {
            return signals.stream().filter(s -> s.at().equals(at)).findFirst().orElse(null);
        }

        Trade tradeEnteredAfter(Instant signalAt) {
            return trades.stream().filter(t -> !t.getEntryTime().isBefore(signalAt))
                    .findFirst().orElse(null);
        }
    }

    private static List<Candle> load(String res, String symbol) throws Exception {
        try (InputStream in = DealingRangeCarryTapeTest.class.getResourceAsStream(res)) {
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
