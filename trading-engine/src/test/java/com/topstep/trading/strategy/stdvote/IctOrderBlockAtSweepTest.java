package com.topstep.trading.strategy.stdvote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.domain.Trade;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.OteAlarmEvent;
import com.topstep.trading.event.PositionClosedEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.DisplacementDetector;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.stdvote.PdArrayLocator.PdArray;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V5 Agent 05.5 - the ICT order block at the in-band sweep (R3: the M7 PD
 * array corrected, never skipped).
 *
 * <p>STARVATION S1 (NY_AM, 0 fills): on 2026-09-25 10:06 ET the IMPULSE_LEG
 * setup armed on the LOW@30752.0 raid inside the long band [30747.5, 30801.5]
 * and was refused 41 bars with {@code impulse-no-pd-array-at-sweep}, because
 * 05.2 only accepted an OB ON the sweep bar. The ICT bullish OB is the LAST
 * DOWN-close candle before the up-move that swept the low: the 10:05 1m bar
 * [30755.00, 30803.50]. {@code ote.pdArraySource=ICT_OB} (default) adds it.
 *
 * <p>Everything below the unit test runs the REAL runner + risk engine + SIM
 * execution on the owner's tape (src/test/resources/tape), wired exactly like
 * the FunnelAutopsyHarness, default configuration (autopsy cfg A).
 */
@DisplayName("V5 Agent 05.5 - ICT order block at the in-band sweep (09-25 NY_AM, G1 regression, SWEEP_BAR A/B)")
class IctOrderBlockAtSweepTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final double TICK = 0.25;

    private static Replay ict;
    private static Replay sweepBar;

    // ── unit: the 09-25 10:01-10:06 bars reconstructed ───────────────────

    @Test
    @DisplayName("unit: 09-25 10:05 down-close bar is the OB of the 10:06 raid -> entry 30779.25, stop 30746.5, 3 micros")
    void ictOrderBlockOnReconstructedBars() {
        System.clearProperty("ote.pdArraySource");
        OteSetupDriver driver = new OteSetupDriver("MNQ", TICK,
                new DisplacementDetector(20, 1.2, 0.5, "MNQ"), new EventBus(), 5);
        SetupContext ctx = new SetupContext();
        // Real 1m bars (ET): 10:01 .. 10:06.
        double[][] ohlc = {
                {30870.75, 30871.25, 30842.5, 30844.0},   // 10:01 DN
                {30843.0, 30852.25, 30821.25, 30839.25},  // 10:02 DN
                {30840.5, 30841.25, 30816.25, 30839.25},  // 10:03 DN
                {30839.5, 30839.75, 30798.25, 30803.75},  // 10:04 DN
                {30803.5, 30803.5, 30755.0, 30757.75},    // 10:05 DN  <- the ICT OB
                {30757.5, 30783.0, 30752.0, 30781.25},    // 10:06 UP  <- the raid + rejection
        };
        for (int i = 0; i < ohlc.length; i++) {
            Instant t = et("2026-09-25T10:0" + (i + 1));
            if (i == ohlc.length - 1) ctx.sweep = new LiquiditySweep(true, 30752.0, t, false);
            driver.onFeedCandle(new Candle("MNQ", t, ohlc[i][0], ohlc[i][1], ohlc[i][2], ohlc[i][3], 100), ctx);
        }
        Optional<PdArray> ob = driver.ictOrderBlock(true);
        assertThat(ob).isPresent();
        assertThat(ob.get().kind()).isEqualTo("OB");
        assertThat(ob.get().at()).isEqualTo(et("2026-09-25T10:05"));
        assertThat(ob.get().bottom()).isEqualTo(30755.0);
        assertThat(ob.get().top()).isEqualTo(30803.5);

        // Agent 03's dealing range on 09-25: 30679.0 - 30999.5 -> long band [30747.5, 30801.5].
        OteZone z = new OteEntryCalculator().buildZone(30679.0, 30999.5, true, TICK).orElseThrow();
        assertThat(Math.min(z.f62(), z.f79())).isEqualTo(30747.5);
        assertThat(Math.max(z.f62(), z.f79())).isEqualTo(30801.5);
        assertThat(PdArrayLocator.overlaps(ob.get(), z)).isTrue();
        // The only other candidate (the 10:06 up-close raid bar is no bullish OB;
        // its lower wick is 5.5/31 = 0.18 < 0.5) -> the ICT OB is the pick.
        assertThat(PdArrayLocator.bestInBand(List.of(ob.get()), z)).contains(ob.get());
        // Entry rule: OB -> its mean threshold (50 %), clamped into the band.
        double entry = PdArrayLocator.entryLevel(ob.get(), z);
        assertThat(entry).isEqualTo(30779.25);
        // ote.stopMode=BAND: min(0.786, OB far edge, sweep extreme) - 4 ticks.
        double stop = Math.min(Math.min(z.f79(), ob.get().farEdge()), 30752.0) - 4 * TICK;
        assertThat(stop).isEqualTo(30746.5);
        StdvOteSizer.RiskSize rs = StdvOteSizer.riskDerived(250.0, entry, stop, TICK, 0.5, 1, 20);
        assertThat(rs.perContract()).isEqualTo(65.5);
        assertThat(rs.contracts()).isEqualTo(3);
    }

    // ── real tape, cfg A ────────────────────────────────────────────────

    @BeforeAll
    static void replayBoth() throws Exception {
        ict = Replay.run(null);
        sweepBar = Replay.run("SWEEP_BAR");
        System.out.println(ict.log);
        System.out.println("---- A/B ote.pdArraySource=SWEEP_BAR ----");
        System.out.println(sweepBar.log);
    }

    @AfterAll
    static void clear() {
        System.clearProperty("ote.pdArraySource");
        StdvOteRegistry.unregister("MNQ");
    }

    @Test
    @DisplayName("tape: 09-25 10:06 LONG emits on the 10:05 ICT OB; limit fills 10:13, stop 10:14 (-1R)")
    void s1NyAmEmitsOnTheIctOrderBlock() {
        OteAlarmEvent alarm = ict.alarmAt(et("2026-09-25T10:06"));
        assertThat(alarm).as("OTE alarm at 10:06").isNotNull();
        assertThat(alarm.getPdKind()).isEqualTo("OB");
        assertThat(alarm.getPdBottom()).isEqualTo(30755.0);
        assertThat(alarm.getPdTop()).isEqualTo(30803.5);
        assertThat(alarm.getReaction()).contains("@2026-09-25T14:05:00Z").contains("src=ICT_OB");

        Replay.Sig s = ict.signalAt(et("2026-09-25T10:06"));
        assertThat(s).as("signal at 10:06").isNotNull();
        assertThat(s.long_).isTrue();
        assertThat(s.entry).isEqualTo(30779.25);
        assertThat(s.stop).isEqualTo(30746.5);
        assertThat(s.qty).isEqualTo(3);

        Trade t = ict.tradeEnteredOn("2026-09-25");
        assertThat(t).as("the 09-25 trade filled").isNotNull();
        assertThat(t.getEntryPrice()).isEqualTo(30779.25);
        // The first bar after 10:06 to trade down to 30779.25 is 10:13 (low
        // 30765.25) - NOT 10:46 as the brief estimated - and the 10:14 bar
        // (low 30734.75) takes the stop 30746.5.
        assertThat(t.getEntryTime()).isEqualTo(et("2026-09-25T10:13"));
        assertThat(t.getExitTime()).isEqualTo(et("2026-09-25T10:14"));
        assertThat(t.getExitPrice()).isEqualTo(30746.5);
        assertThat(t.getRealizedPnL()).isCloseTo(-196.5, within(1e-6));
    }

    @Test
    @DisplayName("tape: G1 still emits 14:53 (entry within 2 of 30635.75, stop above 30673, T1 30558) and fills 14:54")
    void g1Regression() {
        Replay.Sig s = ict.signalAt(et("2026-09-28T14:53"));
        assertThat(s).as("G1 signal at 14:53").isNotNull();
        assertThat(s.long_).isFalse();
        assertThat(s.entry).isCloseTo(30635.75, within(2.0));
        assertThat(s.stop).isGreaterThan(30673.0);
        assertThat(s.t1).isEqualTo(30558.0);
        Trade t = ict.tradeEnteredOn("2026-09-28", et("2026-09-28T14:53"));
        assertThat(t).as("G1 filled").isNotNull();
        assertThat(t.getEntryTime()).isEqualTo(et("2026-09-28T14:54"));
        assertThat(t.getRealizedPnL()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("tape A/B: SWEEP_BAR reproduces Main (09-25 refused impulse-no-pd-array-at-sweep, 6 closed trades, +$407.50)")
    void sweepBarSourceReproducesMain() {
        assertThat(sweepBar.signalAt(et("2026-09-25T10:06"))).isNull();
        assertThat(sweepBar.reason1006).startsWith("ALARM: impulse-no-pd-array-at-sweep");
        assertThat(sweepBar.trades).hasSize(6);
        assertThat(sweepBar.trades.stream().mapToDouble(Trade::getRealizedPnL).sum()).isCloseTo(407.5, within(1e-6));
    }

    @Test
    @DisplayName("tape: ICT_OB adds fills, drops none (fills per session >= SWEEP_BAR)")
    void noSessionLosesAFill() {
        java.util.Map<String, Integer> a = ict.fillsBySession();
        java.util.Map<String, Integer> b = sweepBar.fillsBySession();
        for (var e : b.entrySet()) {
            assertThat(a.getOrDefault(e.getKey(), 0)).as("fills in " + e.getKey()).isGreaterThanOrEqualTo(e.getValue());
        }
        assertThat(a.getOrDefault("NY_AM", 0)).isEqualTo(1);
    }

    // ── replay (FunnelAutopsyHarness wiring) ────────────────────────────

    static final class Replay {
        record SigT(Instant at, StrategySignalEvent sig) {}

        static final class Sig {
            boolean long_;
            double entry;
            double stop;
            double t1;
            int qty;
        }

        final List<OteAlarmEvent> alarms = new CopyOnWriteArrayList<>();
        final java.util.Map<Instant, Sig> signals = new java.util.HashMap<>();
        List<Trade> trades = List.of();
        String reason1006;
        final StringBuilder log = new StringBuilder();

        static Replay run(String pdArraySource) throws Exception {
            if (pdArraySource == null) System.clearProperty("ote.pdArraySource");
            else System.setProperty("ote.pdArraySource", pdArraySource);
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
            bus.subscribe(com.topstep.trading.event.EventType.OTE_ALARM,
                    (com.topstep.trading.event.EventHandler<OteAlarmEvent>) e -> {
                        if ("MNQ".equals(e.getSymbol())) r.alarms.add(e);
                    });
            bus.start();
            StdvOteRunnerStrategy runner = new StdvOteRunnerStrategy("MNQ", "MES", bus);
            runner.initialize();
            SetupContext ctx = runner.getSetupContext();
            Instant t1006 = et("2026-09-25T10:06");
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
                    if (now.equals(t1006)) r.reason1006 = ctx.lastGateFailed;
                    if (ctx.state == SetupState.IN_TRADE && before != SetupState.IN_TRADE) {
                        StrategySignalEvent sig = null;
                        for (int i = 0; i < 200 && sig == null; i++) {
                            sig = sigQ.poll();
                            if (sig == null) Thread.sleep(10);
                        }
                        if (sig == null) continue;
                        RiskDecision d = risk.evaluate(sig, account, limits);
                        Sig s = new Sig();
                        s.long_ = sig.getSignalType() == StrategySignalEvent.SignalType.LONG_ENTRY;
                        s.entry = sig.getEntryPrice();
                        s.stop = sig.getStopPrice();
                        s.t1 = ctx.t1;
                        s.qty = d.isAllowed() ? d.getOrder().getQuantity() : 0;
                        r.signals.put(now, s);
                        r.log.append(String.format("[A-05.5] %s %s e=%s s=%s T1=%s q=%d risk=%s%n",
                                now.atZone(ET).toLocalDateTime(), s.long_ ? "LONG" : "SHORT",
                                s.entry, s.stop, s.t1, s.qty, d.isAllowed() ? "ALLOW" : d.getReason()));
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
                System.clearProperty("ote.pdArraySource");
            }
            r.trades = new ArrayList<>(exec.getCompletedTrades());
            for (Trade t : r.trades) {
                r.log.append(String.format("[A-05.5] TRADE %s %s q=%d in=%s @%s out=%s @%s pnl=%.2f R=%.2f%n",
                        session(t.getEntryTime()), t.getSide(), t.getQuantity(), t.getEntryPrice(),
                        t.getEntryTime().atZone(ET).toLocalDateTime(), t.getExitPrice(),
                        t.getExitTime().atZone(ET).toLocalDateTime(), t.getRealizedPnL(), t.getRMultiple()));
            }
            for (OteAlarmEvent a : r.alarms) {
                r.log.append("[A-05.5] ALARM ").append(a.getCandleTime().atZone(ET).toLocalDateTime())
                        .append(' ').append(a.getPdKind()).append(" [").append(a.getPdBottom()).append(',')
                        .append(a.getPdTop()).append("] entry=").append(a.getEntry()).append(" | ")
                        .append(a.getReaction()).append('\n');
            }
            return r;
        }

        OteAlarmEvent alarmAt(Instant t) {
            return alarms.stream().filter(a -> t.equals(a.getCandleTime())).findFirst().orElse(null);
        }

        Sig signalAt(Instant t) {
            return signals.get(t);
        }

        Trade tradeEnteredOn(String day) {
            return tradeEnteredOn(day, null);
        }

        Trade tradeEnteredOn(String day, Instant notBefore) {
            return trades.stream()
                    .filter(t -> t.getEntryTime().atZone(ET).toLocalDate().toString().equals(day))
                    .filter(t -> notBefore == null || !t.getEntryTime().isBefore(notBefore))
                    .findFirst().orElse(null);
        }

        java.util.Map<String, Integer> fillsBySession() {
            java.util.Map<String, Integer> m = new java.util.TreeMap<>();
            for (Trade t : trades) m.merge(session(t.getEntryTime()), 1, Integer::sum);
            return m;
        }
    }

    /** Session of an ET time (the harness's buckets, entry sessions only). */
    static String session(Instant t) {
        int hm = t.atZone(ET).getHour() * 100 + t.atZone(ET).getMinute();
        if (hm >= 1800 || hm < 300) return "ASIA";
        if (hm < 800) return "LONDON";
        if (hm < 930) return "PRE_NY";
        if (hm < 1200) return "NY_AM";
        if (hm < 1330) return "NY_LUNCH";
        if (hm < 1600) return "NY_PM";
        return "PRE_ASIA";
    }

    private static List<Candle> load(String res, String symbol) throws Exception {
        try (InputStream in = IctOrderBlockAtSweepTest.class.getResourceAsStream("/tape/" + res)) {
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

    private static Instant et(String iso) {
        return LocalDateTime.parse(iso).atZone(ET).toInstant();
    }
}
