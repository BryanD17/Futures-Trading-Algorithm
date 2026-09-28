package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.connector.MarketDataListener;
import com.topstep.trading.connector.OrderListener;
import com.topstep.trading.connector.TradingConnector;
import com.topstep.trading.domain.*;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.event.StrategySignalEvent.SignalType;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGENT-05 (V5): MNQ + MES trade independently.
 * <ul>
 *   <li>each runner receives ONLY its own symbol as a primary candle, the
 *       other as its SMT feed (routing counters);</li>
 *   <li>a throwing runner never stops the other runner;</li>
 *   <li>an open MNQ position does not block an MES entry unless the TOTAL
 *       contract cap says so;</li>
 *   <li>both symbols fill / carry P&amp;L independently in one ExecutionEngine.</li>
 * </ul>
 */
class MultiInstrumentIndependenceTest {

    private static final Instant T0 = Instant.parse("2026-09-28T14:00:00Z"); // 09:00 CT

    /** Records subscriptions; no network. */
    static final class StubConnector implements TradingConnector {
        final Map<String, MarketDataListener> subs = new LinkedHashMap<>();
        public void connect() {}
        public void disconnect() {}
        public boolean isConnected() { return true; }
        public void subscribeMarketData(String s, MarketDataListener l) { subs.put(s, l); }
        public void unsubscribeMarketData(String s) { subs.remove(s); }
        public String submitOrder(Order o, OrderListener l) { return o.getOrderId(); }
        public void cancelOrder(String id) {}
        public double getAccountBalance() { return 50_000; }
        public String getName() { return "stub"; }
    }

    private static Candle bar(String sym, int minute, double px) {
        return new Candle(sym, T0.plusSeconds(60L * minute), px, px + 1, px - 1, px, 100);
    }

    @Test
    void mnqAndMesRouteAndTradeIndependently() {
        StubConnector conn = new StubConnector();
        EventBus bus = new EventBus();
        AccountState acct = new AccountState(50_000.0);
        DefaultStrategyContext ctx = new DefaultStrategyContext(acct);
        Map<String, String> smt = new LinkedHashMap<>();
        smt.put("MNQ", "MES");
        smt.put("MES", "MNQ");
        StdvOteMultiInstrumentEngine engine = new StdvOteMultiInstrumentEngine(
                conn, bus, ctx, List.of("MNQ", "MES"), smt);
        engine.start();
        assertThat(conn.subs.keySet()).containsExactly("MNQ", "MES");

        for (int i = 0; i < 5; i++) {
            engine.dispatchCandle(bar("MNQ", i, 20000 + i));
            engine.dispatchCandle(bar("MES", i, 6000 + i));
        }
        System.out.println("routing: MNQ primary=" + engine.routedPrimaryCount("MNQ")
                + " smt=" + engine.routedSmtCount("MNQ") + " | MES primary=" + engine.routedPrimaryCount("MES")
                + " smt=" + engine.routedSmtCount("MES") + " | dispatchErrors=" + engine.getDispatchErrorCount());
        assertThat(engine.routedPrimaryCount("MNQ")).isEqualTo(5);
        assertThat(engine.routedPrimaryCount("MES")).isEqualTo(5);
        assertThat(engine.routedSmtCount("MNQ")).isEqualTo(5); // MES bars as MNQ's SMT
        assertThat(engine.routedSmtCount("MES")).isEqualTo(5); // MNQ bars as MES's SMT
        assertThat(engine.getDispatchErrorCount()).isZero();

        // A throwing tap is contained: routing continues for both runners.
        engine.setCandleTap(c -> { throw new IllegalStateException("tap boom"); });
        engine.dispatchCandle(bar("MNQ", 5, 20005));
        engine.dispatchCandle(bar("MES", 5, 6005));
        assertThat(engine.getDispatchErrorCount()).isEqualTo(2);
        assertThat(engine.routedPrimaryCount("MNQ")).isEqualTo(6);
        assertThat(engine.routedPrimaryCount("MES")).isEqualTo(6);
        engine.stop();

        // ── Risk + execution independence (legacy topstep50k: max 5 / total 10) ──
        PropFirmRiskEngine risk = new PropFirmRiskEngine();
        RiskLimits limits = RiskLimits.topstep50k();
        ExecutionEngine exec = new ExecutionEngine(acct);
        StrategySignalEvent mnq = new StrategySignalEvent(SignalType.LONG_ENTRY, "MNQ", OrderSide.BUY,
                20000, 19980, 20040, "t", TradeTier.TIER_1, 5, 2.0, null, false, T0);
        RiskDecision dm = risk.evaluate(mnq, acct, limits);
        assertThat(dm.isAllowed()).as(dm.getReason()).isTrue();
        exec.submitOrder(dm.getOrder(), 19980, 20040, T0);
        exec.onNewCandle(new Candle("MNQ", T0.plusSeconds(60), 20001, 20002, 19999, 20001, 100));
        assertThat(acct.hasPosition("MNQ")).isTrue();

        // MES entry while MNQ is open: allowed (5 + 2 <= total 10).
        StrategySignalEvent mes = new StrategySignalEvent(SignalType.SHORT_ENTRY, "MES", OrderSide.SELL,
                6000, 6010, 5980, "t", TradeTier.TIER_1, 2, 2.0, null, false, T0);
        RiskDecision de = risk.evaluate(mes, acct, limits);
        System.out.println("MES with MNQ open: " + de.getReason());
        assertThat(de.isAllowed()).as(de.getReason()).isTrue();
        exec.submitOrder(de.getOrder(), 6010, 5980, T0);
        exec.onNewCandle(new Candle("MES", T0.plusSeconds(60), 5999, 6001, 5998, 5999, 100));
        assertThat(acct.hasPosition("MES")).isTrue();
        assertThat(acct.getTotalContracts()).isEqualTo(7);

        // Unrealized P&L keeps BOTH symbols (MNQ +2pt x5 x$2 = +20; MES -1pt x2 x$5 = -10... at last prices).
        exec.onNewCandle(new Candle("MNQ", T0.plusSeconds(120), 20003, 20004, 20002, 20003, 100));
        assertThat(acct.getUnrealizedPnL()).isEqualTo(3 * 5 * 2.0 + (6000 - 5999) * 2 * 5.0);

        // Only the TOTAL cap blocks: a third entry needing 5 more -> 12 > 10.
        StrategySignalEvent mnq2 = new StrategySignalEvent(SignalType.LONG_ENTRY, "MGC", OrderSide.BUY,
                2400, 2399, 2402, "t", TradeTier.TIER_1, 5, 2.0, null, false, T0);
        RiskDecision capped = risk.evaluate(mnq2, acct, limits);
        assertThat(capped.isAllowed()).isFalse();
        assertThat(capped.getReason()).contains("max total contracts");
    }
}
