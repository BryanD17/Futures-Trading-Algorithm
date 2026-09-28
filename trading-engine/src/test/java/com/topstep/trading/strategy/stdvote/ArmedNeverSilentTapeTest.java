package com.topstep.trading.strategy.stdvote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.GateDecisionEvent;
import com.topstep.trading.event.PositionClosedEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.strategy.DefaultStrategyContext;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 Agent 05.4 - the "armed but silent" invariant on the owner's REAL tape
 * (src/test/resources/tape, 7,830 bars, 2026-09-21 -> 2026-09-28 ET), through
 * the REAL runner + risk engine + SIM execution exactly as the
 * FunnelAutopsyHarness wires them, DEFAULT configuration (autopsy cfg A).
 *
 * <ul>
 *   <li>INVARIANT: no primary bar ends OTE_ARMED with
 *       {@code lastGateFailed == null} and no emission (the harness label
 *       {@code OTE_ARMED-sizer-standdown-or-tier} is extinct);</li>
 *   <li>REPRODUCTION of Fable's S1 (2026-09-25 10:06 ET LONG, 41 armed bars,
 *       no emission): prints the band, the planned stop, the stop distance,
 *       $/micro, the budget and the sizer's decision, and asserts the real
 *       reason reached SetupContext and a GateDecisionEvent.</li>
 * </ul>
 */
@DisplayName("V5 Agent 05.4 - real tape: no OTE_ARMED bar is silent")
class ArmedNeverSilentTapeTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final List<String> silentBars = new ArrayList<>();
    private static int armedNotEmittedBars;
    private static final java.util.Map<String, Integer> armedReasons = new java.util.TreeMap<>();
    private static final List<GateDecisionEvent> armedGates = new CopyOnWriteArrayList<>();
    private static String reason1006;
    private static SetupState state1006;
    private static final StringBuilder repro = new StringBuilder();

    private static List<Candle> load(String res, String symbol) throws Exception {
        try (InputStream in = ArmedNeverSilentTapeTest.class.getResourceAsStream("/tape/" + res)) {
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

    @BeforeAll
    static void replay() throws Exception {
        // V5 Agent 05.6: the 09-25 10:06 LONG exists only on the SESSION_DAY
        // dealing range (overnight high 30999.50); the default AUTO window
        // reads that morning BEARISH (RangeWindowTapeTest). Pinned so this
        // class keeps documenting the 05.4/05.5 mechanics on that setup.
        System.setProperty("bias.range.window", "SESSION_DAY");
        List<Candle> mnq = load("real_MNQ_1m.json", "MNQ");
        List<Candle> mes = load("real_MES_1m.json", "MES");
        EventBus bus = new EventBus();
        AccountState account = new AccountState(50_000.0);
        RiskLimits limits = ScalpConfig.activeRiskLimits();
        PropFirmRiskEngine risk = new PropFirmRiskEngine();
        ExecutionEngine exec = new ExecutionEngine(account);
        exec.setEventBus(bus);
        DefaultStrategyContext context = new DefaultStrategyContext(account);
        ConcurrentLinkedQueue<StrategySignalEvent> signals = new ConcurrentLinkedQueue<>();
        bus.subscribe(StrategySignalEvent.class, signals::add);
        java.util.Set<String> armedGateNames = java.util.Set.of("SIZE", "ALARM", "NO_ENTRY",
                "POSITION", "TIER", "EMIT", "BIAS");
        bus.subscribe(GateDecisionEvent.class, g -> {
            if ("MNQ".equals(g.getSymbol()) && armedGateNames.contains(g.getGate())) armedGates.add(g);
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
                SetupState after = ctx.state;
                boolean emitted = after == SetupState.IN_TRADE && before != SetupState.IN_TRADE;
                if (emitted) {
                    StrategySignalEvent sig = null;
                    for (int i = 0; i < 200 && sig == null; i++) {
                        sig = signals.poll();
                        if (sig == null) Thread.sleep(10);
                    }
                    if (sig != null) {
                        RiskDecision d = risk.evaluate(sig, account, limits);
                        if (d.isAllowed()) {
                            Order o = d.getOrder();
                            exec.submitOrder(o, sig.getStopPrice(), sig.getTargetPrice());
                        } else {
                            bus.publish(new PositionClosedEvent("MNQ", 0.0, false, now));
                        }
                        bus.awaitIdle(5_000);
                    }
                }
                if (after == SetupState.OTE_ARMED) {
                    armedNotEmittedBars++;
                    if (ctx.lastGateFailed == null) {
                        silentBars.add(now.atZone(ET).toLocalDateTime().toString());
                    } else {
                        String r = ctx.lastGateFailed;
                        int colon = r.indexOf(':');
                        armedReasons.merge(colon > 0 ? r.substring(0, colon) : r, 1, Integer::sum);
                    }
                }
                if (now.equals(t1006)) {
                    state1006 = after;
                    reason1006 = ctx.lastGateFailed;
                    reproduce(runner, ctx, account, limits, c);
                }
            }
        } finally {
            runner.shutdown();
            bus.stop();
        }
        System.out.println(repro);
        System.out.println("[A-05.4] tape: OTE_ARMED bars without emission=" + armedNotEmittedBars
                + " reasons=" + armedReasons + " silent=" + silentBars.size());
    }

    /** The two numbers for the 09-25 10:06 LONG, from the runner's own geometry. */
    private static void reproduce(StdvOteRunnerStrategy runner, SetupContext ctx, AccountState account,
                                  RiskLimits limits, Candle c) throws Exception {
        java.lang.reflect.Field coreF = StdvOteRunnerStrategy.class.getDeclaredField("core");
        coreF.setAccessible(true);
        StdvOteStrategy core = (StdvOteStrategy) coreF.get(runner);
        TradeableInstrument.Spec spec = TradeableInstrument.of(TradeableInstrument.resolve("MNQ").orElseThrow());
        int buffer = StdvOteRunnerStrategy.DEFAULT_STOP_BUFFER_TICKS;
        OteZone z = ctx.ote;
        double stop = core.plannedStopForSizing(spec.tickSize(), buffer);
        double dllRoom = limits.getMaxDailyLoss() + account.getNetDailyPnl();
        double mllRoom = limits.getMaxLossLimit() - (account.getHighestEndOfDayBalance() - account.getEquity());
        double budget = StdvOteSizer.riskBudget(limits.getRiskPerTrade(), dllRoom, mllRoom);
        repro.append("[A-05.4] 2026-09-25 10:06 ET reproduction (cfg A)\n")
             .append("  state=").append(ctx.state).append(" bias=").append(ctx.htfBias)
             .append(" sweep=").append(ctx.sweep == null ? "none" : ctx.sweep.getSweptLevel())
             .append(" raidScore=").append(ctx.raidScore).append(" close=").append(c.getClose())
             .append(" entryModel=").append(ctx.oteEntryModel).append(" anchor=").append(ctx.oteAnchorMode)
             .append('\n')
             .append("  band [0.786,0.618]=[").append(z == null ? "?" : z.f79()).append(',')
             .append(z == null ? "?" : z.f62()).append("] 0.705=").append(z == null ? "?" : z.f705())
             .append(" 1.0=").append(z == null ? "?" : z.one00()).append('\n')
             .append("  pdArrayInOte=").append(ctx.pdArrayInOte).append(" pdArrayFarEdge=").append(ctx.pdArrayFarEdge)
             .append(" sweepExtreme=").append(ctx.sweepExtreme).append(" stopMode=").append(OteConfig.stopMode())
             .append('\n')
             .append("  planned stop (plannedStopForSizing, ").append(buffer).append(" tick buffer)=").append(stop)
             .append('\n')
             .append("  budget=min(riskPerTrade ").append(limits.getRiskPerTrade()).append(", DLL room ")
             .append(dllRoom).append(", MLL room ").append(mllRoom).append(") = $").append(budget).append('\n');
        if (z != null) {
            for (double[] e : new double[][] {{z.f62(), 0.618}, {z.f705(), 0.705}, {c.getClose(), -1}, {z.f79(), 0.786}}) {
                StdvOteSizer.RiskSize rs = StdvOteSizer.riskDerived(budget, e[0], stop,
                        spec.tickSize(), spec.tickValue(), 1, Math.min(20, limits.getMaxContracts()));
                repro.append(String.format("  if entry %s %.2f: stop dist %.2f pts = $%.2f/micro vs $%.2f -> %s%n",
                        e[1] < 0 ? "close" : "@" + e[1], e[0], Math.abs(e[0] - stop), rs.perContract(), budget,
                        rs.denied() ? rs.reason() : rs.contracts() + " micros"));
            }
        }
        repro.append("  lastGateFailed=").append(ctx.lastGateFailed).append('\n');
    }

    @AfterAll
    static void clear() {
        System.clearProperty("bias.range.window");
        StdvOteRegistry.unregister("MNQ");
    }

    @Test
    void noOteArmedBarIsSilent() {
        assertThat(armedNotEmittedBars).as("the tape has armed bars to check").isGreaterThan(0);
        assertThat(silentBars).as("OTE_ARMED bars with lastGateFailed == null and no emission").isEmpty();
    }

    /**
     * V5 Agent 05.5: with the default {@code ote.pdArraySource=ICT_OB} the
     * 09-25 10:06 LONG no longer stalls - the 10:05 down-close bar is its
     * order block and the setup emits on the raid bar. The 05.4 reason
     * ({@code ALARM: impulse-no-pd-array-at-sweep ...}) is still asserted
     * under {@code SWEEP_BAR} in {@link IctOrderBlockAtSweepTest}.
     */
    @Test
    void the0925LongNowEmitsOnTheIctOrderBlock() {
        assertThat(state1006).isEqualTo(SetupState.IN_TRADE);
        assertThat(reason1006).isNull();
        assertThat(armedGates).noneSatisfy(g ->
                assertThat(g.getCandleTime()).isEqualTo(et("2026-09-25T10:06")));
    }
}
