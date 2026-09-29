package com.topstep.trading.strategy.stdvote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.LiveEngineRunner;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.domain.Trade;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.PositionClosedEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.risk.LiveRiskPath;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.strategy.DefaultStrategyContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V5 Agent 05.10 (d) — harness / LIVE handler parity on the REAL 7-day tape
 * (the live config: range.ltf.enabled=true), runner + PropFirmRiskEngine + SIM
 * ExecutionEngine wired as FunnelAutopsyHarness wires them.
 *
 * <p>Two risk paths are built with {@link LiveEngineRunner#newLiveRiskPath()}
 * (the LIVE runner's own factory): the HARNESS instance (lifecycle fed where the
 * harness feeds it; its budget is the one the strategy sizes from) and the LIVE
 * instance (fed at the LIVE runner's call sites: candleHousekeeping before the
 * execution candle, notifyPositionClosed on every close). For every signal the
 * two decide identically (allowed, reason, quantity, budget, zone). STATIC also
 * equals the plain proven call {@code risk.evaluate(signal, account, limits)};
 * PHASE_AWARE never produces "requested N micros ... &gt; risk budget" because
 * the strategy sized the request from the same budget.
 */
@DisplayName("V5 Agent 05.10 (d) - harness and LIVE handler decide identically on the real tape (STATIC and PHASE_AWARE)")
class LiveRiskPathTapeTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static Replay stat;
    private static Replay phase;

    @BeforeAll
    static void replay() throws Exception {
        stat = Replay.run("STATIC", Map.of("range.ltf.enabled", "true"));
        phase = Replay.run("PHASE_AWARE", Map.of("range.ltf.enabled", "true", LiveRiskPath.PHASE_AWARE_KEY, "true"));
        System.out.println(stat.log);
        System.out.println(phase.log);
    }

    @AfterAll
    static void clear() {
        StdvOteRegistry.unregister("MNQ");
    }

    @Test
    @DisplayName("STATIC: every signal - harness = LIVE handler = risk.evaluate(signal, account, limits); tape = Main (15 closed, +$1,308.30)")
    void staticIsTheProvenPath() {
        assertThat(stat.signals).isNotEmpty();
        for (Replay.Row r : stat.signals) {
            assertThat(r.live()).as("%s", r).isEqualTo(r.harness());
            assertThat(r.harness().reason()).as("%s", r).isEqualTo(r.proven());
            assertThat(r.harness().path()).isEqualTo("STATIC");
        }
        assertThat(stat.trades).hasSize(15);
        assertThat(stat.trades.stream().mapToDouble(Trade::getRealizedPnL).sum()).isCloseTo(1308.30, within(1e-6));
    }

    @Test
    @DisplayName("PHASE_AWARE: harness = LIVE handler for every signal; no stale-budget 'requested > max' deny; allowed $ risk <= the phase-aware budget")
    void phaseAwareParityAndNoStaleBudget() {
        assertThat(phase.signals).isNotEmpty();
        assertThat(phase.signals).anyMatch(r -> r.harness().allowed());
        assertThat(phase.signals).anyMatch(r -> r.harness().deniedBy() == LiveRiskPath.DeniedBy.PHASE_AWARE_GATE);
        for (Replay.Row r : phase.signals) {
            assertThat(r.live()).as("%s", r).isEqualTo(r.harness());
            assertThat(r.harness().path()).isEqualTo("PHASE_AWARE");
            assertThat(r.harness().reason()).as("%s", r).doesNotContain("RISK: requested").doesNotContain("> risk budget");
            if (r.harness().allowed()) {
                assertThat(r.qty() * r.perMicro()).as("%s", r).isLessThanOrEqualTo(r.harness().budget() + 1e-9);
            }
        }
    }

    static final class Replay {
        /** The comparable part of a decision. */
        record D(boolean allowed, String reason, String path, LiveRiskPath.DeniedBy deniedBy, int qty,
                 double budget, String zone) { }

        record Row(Instant at, String tier, int requested, double perMicro, int qty, D harness, D live, String proven) { }

        final List<Row> signals = new ArrayList<>();
        List<Trade> trades = List.of();
        final StringBuilder log = new StringBuilder();

        static D d(LiveRiskPath.Decision x) {
            RiskDecision rd = x.riskDecision();
            return new D(x.allowed(), x.reason(), x.path(), x.deniedBy(),
                    rd != null && rd.isAllowed() ? rd.getOrder().getQuantity() : 0,
                    x.effectiveBudget(), String.valueOf(x.zone()));
        }

        static Replay run(String tag, Map<String, String> props) throws Exception {
            props.forEach(System::setProperty);
            Replay r = new Replay();
            List<Candle> mnq = load("/tape/real_MNQ_1m.json", "MNQ");
            List<Candle> mes = load("/tape/real_MES_1m.json", "MES");
            EventBus bus = new EventBus();
            AccountState account = new AccountState(50_000.0);
            RiskLimits limits = ScalpConfig.activeRiskLimits();
            PropFirmRiskEngine risk = new PropFirmRiskEngine();
            LiveRiskPath harnessPath = LiveEngineRunner.newLiveRiskPath();
            LiveRiskPath livePath = LiveEngineRunner.newLiveRiskPath();
            ExecutionEngine exec = new ExecutionEngine(account);
            exec.setEventBus(bus);
            exec.setExecutionListener(new ExecutionEngine.ExecutionListener() {
                @Override public void onPositionOpened(String s, com.topstep.trading.domain.OrderSide side, double px, int q) { }
                @Override public void onPositionClosed(String s, double pnl, boolean win) {
                    harnessPath.onPositionClosed(pnl);   // FunnelAutopsyHarness
                    livePath.onPositionClosed(pnl);      // LiveEngineRunner.notifyPositionClosed
                }
            });
            DefaultStrategyContext context = new DefaultStrategyContext(account);
            context.setRiskBudgetProvider(harnessPath.budgetProviderOrNull());
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
                    harnessPath.onCandle(now, account);   // harness
                    livePath.onCandle(now, account);      // LIVE candleHousekeeping (before executionEngine.onNewCandle)
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
                    if (sig == null) continue;
                    // The LIVE instance's budget for this tier must be the one the strategy sized from.
                    assertThat(livePath.perTradeBudget("MNQ", sig.getTier(), limits.getRiskPerTrade()))
                            .isEqualTo(harnessPath.perTradeBudget("MNQ", sig.getTier(), limits.getRiskPerTrade()));
                    String proven = risk.evaluate(sig, account, limits).getReason();
                    LiveRiskPath.Decision live = livePath.evaluate(sig, account, limits, risk);
                    LiveRiskPath.Decision harness = harnessPath.evaluate(sig, account, limits, risk);
                    double perMicro = Math.abs(sig.getEntryPrice() - sig.getStopPrice()) / 0.25 * 0.50;
                    D hd = d(harness);
                    Row row = new Row(now, sig.getTier().name(), sig.getQuantity(), perMicro, hd.qty(), hd,
                            d(live), proven);
                    r.signals.add(row);
                    r.log.append(String.format("[A-05.10 d] %s %s %s req=%d $%.2f/micro | harness %s %s | live %s %s%n",
                            tag, now.atZone(ET).toLocalDateTime(), sig.getTier(), sig.getQuantity(), perMicro,
                            hd.allowed() ? "ALLOW qty=" + hd.qty() : "DENY", hd.reason(),
                            row.live().allowed() ? "ALLOW qty=" + row.live().qty() : "DENY",
                            row.live().equals(hd) ? "(identical)" : "(DIFFERENT) " + row.live()));
                    RiskDecision rd = harness.riskDecision();
                    if (harness.allowed()) {
                        exec.submitOrder(rd.getOrder(), sig.getStopPrice(), sig.getTargetPrice());
                    } else {
                        bus.publish(new PositionClosedEvent("MNQ", 0.0, false, now));
                    }
                    bus.awaitIdle(5_000);
                }
                bus.awaitIdle(5_000);
            } finally {
                runner.shutdown();
                bus.stop();
                StdvOteRegistry.unregister("MNQ");
                props.keySet().forEach(System::clearProperty);
            }
            r.trades = new ArrayList<>(exec.getCompletedTrades());
            r.log.append(String.format("[A-05.10 d] %s signals=%d allowed=%d closed=%d pnl=%.2f final %s%n", tag,
                    r.signals.size(), r.signals.stream().filter(x -> x.harness().allowed()).count(), r.trades.size(),
                    r.trades.stream().mapToDouble(Trade::getRealizedPnL).sum(), harnessPath.lifecycle().zoneInputs()));
            return r;
        }
    }

    private static List<Candle> load(String res, String symbol) throws Exception {
        try (InputStream in = LiveRiskPathTapeTest.class.getResourceAsStream(res)) {
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
