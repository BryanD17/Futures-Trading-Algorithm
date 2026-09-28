package com.topstep.trading;

import com.topstep.trading.connector.MarketDataListener;
import com.topstep.trading.connector.OrderListener;
import com.topstep.trading.connector.TradingConnector;
import com.topstep.trading.domain.*;
import com.topstep.trading.event.GateDecisionEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.event.StrategySignalEvent.SignalType;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGENT-05 (V5 RC-17) — the SIM execution path end to end, through the REAL
 * SimEngineRunner wiring (EventBus → warmup guard → no-entry safety net →
 * PropFirmRiskEngine → ExecutionEngine → candle-driven fill → bracket levels):
 * a synthetic valid StrategySignalEvent becomes a FILLED bracket in &lt; 5 s.
 *
 * <p>The connector is scripted (deterministic candle times, 09:00 CT — never
 * the wall clock), so the test cannot fall into the 14:45–17:00 CT block.
 */
class SimSyntheticSignalFillIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-28T14:00:00Z"); // 09:00 CT Monday

    static final class ScriptedConnector implements TradingConnector {
        final Map<String, MarketDataListener> subs = new ConcurrentHashMap<>();
        public void connect() {}
        public void disconnect() { subs.clear(); }
        public boolean isConnected() { return true; }
        public void subscribeMarketData(String s, MarketDataListener l) { subs.put(s, l); }
        public void unsubscribeMarketData(String s) { subs.remove(s); }
        public String submitOrder(Order o, OrderListener l) { return o.getOrderId(); }
        public void cancelOrder(String id) {}
        public double getAccountBalance() { return 50_000; }
        public String getName() { return "scripted"; }
        void emit(Candle c) {
            MarketDataListener l = subs.get(c.getSymbol());
            if (l != null) l.onCandle(c);
        }
    }

    private static Candle bar(String sym, Instant t, double o, double h, double l, double c) {
        return new Candle(sym, t, o, h, l, c, 100);
    }

    private static boolean await(BooleanSupplier cond, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return cond.getAsBoolean();
    }

    private static double basePrice(String sym) {
        switch (sym) {
            case "MNQ": return 20000.0;
            case "MES": return 6000.0;
            default: return 2400.0;
        }
    }

    @Test
    void syntheticValidSignalBecomesFilledBracketInSimUnder5Seconds() throws Exception {
        ScriptedConnector conn = new ScriptedConnector();
        SimEngineRunner runner = new SimEngineRunner(50_000.0, RiskLimits.topstep50k(), conn);
        Thread t = new Thread(runner::start, "sim-runner-under-test");
        t.setDaemon(true);
        t.start();
        try {
            assertThat(await(runner::isRunning, 10_000)).as("runner started").isTrue();
            CopyOnWriteArrayList<GateDecisionEvent> gates = new CopyOnWriteArrayList<>();
            runner.getEventBusForTest().subscribe(GateDecisionEvent.class, gates::add);

            // One LIVE candle per required feed completes the warmup (candle time T0).
            for (String sym : conn.subs.keySet()) {
                double p = basePrice(sym);
                conn.emit(bar(sym, T0, p, p + 2, p - 2, p + 1));
            }
            assertThat(runner.isWarmupComplete()).as("warmup complete on live candles").isTrue();

            AccountState acct = runner.getAccountState();
            ExecutionEngine exec = runner.getExecutionEngine();
            assertThat(new com.topstep.trading.risk.PropFirmRiskEngine()
                    .isAccountInGoodStanding(acct, runner.getRiskLimits()))
                    .as("fresh SIM account in good standing").isTrue();

            // Synthetic, valid LONG: entry 20000, stop 19990 (10 pt = $20/micro), target 20020 (2R).
            StrategySignalEvent sig = new StrategySignalEvent(SignalType.LONG_ENTRY, "MNQ", OrderSide.BUY,
                    20000.0, 19990.0, 20020.0, "SYNTHETIC integration signal", TradeTier.TIER_1, 5,
                    2.0, null, false, T0);
            long startNs = System.nanoTime();
            runner.getEventBusForTest().publish(sig);

            // Next candle trades through the limit.
            assertThat(await(() -> !exec.getActiveOrdersList("MNQ").isEmpty(), 5_000))
                    .as("approved order resting").isTrue();
            conn.emit(bar("MNQ", T0.plusSeconds(60), 20001, 20003, 19998, 20002));

            boolean filled = await(() -> acct.hasPosition("MNQ") && exec.getOrderLevels("MNQ") != null, 5_000);
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            System.out.println("SIM synthetic signal -> FILLED bracket in " + elapsedMs + " ms"
                    + " | position=" + acct.getPosition("MNQ")
                    + " | bracket stop=" + exec.getOrderLevels("MNQ").getCurrentStopPrice()
                    + " target=" + exec.getOrderLevels("MNQ").getFinalTargetPrice()
                    + " | gateDecisions=" + gates.size());
            assertThat(filled).isTrue();
            assertThat(elapsedMs).isLessThan(5_000);
            assertThat(acct.getPosition("MNQ").getQuantity()).isEqualTo(5);
            assertThat(acct.getPosition("MNQ").getAvgEntryPrice()).isEqualTo(20000.0);
            assertThat(exec.getOrderLevels("MNQ").getCurrentStopPrice()).isEqualTo(19990.0);
            assertThat(exec.getOrderLevels("MNQ").getFinalTargetPrice()).isEqualTo(20020.0);
            // Agent 01's telemetry records the path (SIGNAL received, RISK-APPROVED);
            // no DENYING gate may appear for the valid signal.
            assertThat(gates).extracting(GateDecisionEvent::getGate)
                    .as("no gate denied the valid signal")
                    .allMatch(g -> g.equals("SIGNAL") || g.equals("RISK-APPROVED"));

            // A replay-era signal (candle BEFORE the warmup completion candle) is dropped + published.
            StrategySignalEvent stale = new StrategySignalEvent(SignalType.LONG_ENTRY, "MGC", OrderSide.BUY,
                    2400.0, 2399.0, 2402.0, "replay-era", TradeTier.TIER_1, 1, 2.0, null, false,
                    T0.minusSeconds(3600));
            runner.getEventBusForTest().publish(stale);
            assertThat(await(() -> gates.stream().anyMatch(g -> g.getGate().equals("WARMUP")), 3_000)).isTrue();
            assertThat(runner.getWarmupDroppedSignals()).isEqualTo(1);

            // An entry inside the 14:45–17:00 CT block is refused by the execution-path safety net.
            StrategySignalEvent late = new StrategySignalEvent(SignalType.LONG_ENTRY, "MGC", OrderSide.BUY,
                    2400.0, 2399.0, 2402.0, "no-entry block", TradeTier.TIER_1, 1, 2.0, null, false,
                    Instant.parse("2026-09-28T19:50:00Z"));
            runner.getEventBusForTest().publish(late);
            assertThat(await(() -> gates.stream().anyMatch(g -> g.getGate().equals("FLATTEN")), 3_000)).isTrue();
            assertThat(exec.getActiveOrdersList("MGC")).isEmpty();
        } finally {
            runner.stop();
        }
    }
}
