package com.topstep.trading.execution;

import com.topstep.trading.domain.*;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.GateDecisionEvent;
import com.topstep.trading.event.PositionClosedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGENT-05 (V5 RC-17): the SIM execution path — P&amp;L units, order TTL,
 * the async fill race, and the 14:45 CT flatten safety net.
 */
class ExecutionPathSafetyTest {

    // 2026-09-28 (CDT, UTC-5). 09:00 CT = 14:00Z.
    private static final Instant T0 = Instant.parse("2026-09-28T14:00:00Z");

    private static Candle bar(String sym, Instant t, double o, double h, double l, double c) {
        return new Candle(sym, t, o, h, l, c, 100);
    }

    private static Order limit(String sym, OrderSide side, int qty, double px) {
        return Order.builder().symbol(sym).side(side).type(OrderType.LIMIT).quantity(qty).limitPrice(px).build();
    }

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    @Test
    void pnlIsPointsTimesPointValueNotPointsTimesTickValue() {
        AccountState acct = new AccountState(50_000.0);
        ExecutionEngine exec = new ExecutionEngine(acct);
        exec.submitOrderEnhanced(limit("MNQ", OrderSide.BUY, 1, 20000.0), 19990.0, 20010.0,
                com.topstep.trading.strategy.TradeTier.TIER_2, null);
        exec.onNewCandle(bar("MNQ", T0, 20002, 20003, 19999, 20001));     // fill @ 20000
        assertThat(acct.hasPosition("MNQ")).isTrue();
        exec.onNewCandle(bar("MNQ", T0.plusSeconds(60), 20005, 20011, 20004, 20010)); // target 20010
        Trade t = exec.getCompletedTrades().get(0);
        // 10 points x 1 micro x $2/pt = $20 (old formula: 10 x $0.50 = $5).
        assertThat(t.getRealizedPnL()).isEqualTo(20.0);
        assertThat(acct.getRealizedPnL()).isEqualTo(20.0);
        // Unrealized uses the same units.
        Position p = new Position("MNQ", 2, 20000.0);
        assertThat(p.getUnrealizedPnL(20010.0, 0.50)).isEqualTo(40.0);
        assertThat(new Position("MGC", 1, 2400.0).getUnrealizedPnL(2401.0, 1.00)).isEqualTo(10.0);
    }

    @Test
    void simOrderTtlCancelsAndReleasesTheStrategyLatch() throws Exception {
        EventBus bus = new EventBus();
        CopyOnWriteArrayList<Object> events = new CopyOnWriteArrayList<>();
        bus.subscribe(GateDecisionEvent.class, events::add);
        bus.subscribe(PositionClosedEvent.class, events::add);
        bus.start();
        try {
            AccountState acct = new AccountState(50_000.0);
            ExecutionEngine exec = new ExecutionEngine(acct);
            exec.setEventBus(bus);
            exec.setOrderTtlBars(3);
            exec.submitOrder(limit("MNQ", OrderSide.BUY, 1, 19900.0), 19890.0, 19950.0);
            for (int i = 0; i < 3; i++) {
                exec.onNewCandle(bar("MNQ", T0.plusSeconds(60L * i), 20000, 20001, 19999, 20000));
            }
            assertThat(exec.getActiveOrdersList("MNQ")).hasSize(1);
            exec.onNewCandle(bar("MNQ", T0.plusSeconds(180), 20000, 20001, 19999, 20000));
            assertThat(exec.getActiveOrdersList("MNQ")).isEmpty();
            assertThat(exec.getTtlCancelCount()).isEqualTo(1);
            await(() -> events.size() >= 2);
            assertThat(events).anySatisfy(e -> {
                assertThat(e).isInstanceOf(GateDecisionEvent.class);
                assertThat(((GateDecisionEvent) e).getGate()).isEqualTo("ORDER_TTL");
            });
            assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOf(PositionClosedEvent.class));
            // Default TTL = OTE window x 2 = 8 x 5m x 2 = 80 feed bars.
            assertThat(new ExecutionEngine(new AccountState(1)).getOrderTtlBars()).isEqualTo(80);
        } finally {
            bus.stop();
        }
    }

    @Test
    void fillIsNotLostWhenTheNextCandleArrivesBeforeTheOrder() {
        AccountState acct = new AccountState(50_000.0);
        ExecutionEngine exec = new ExecutionEngine(acct);
        // Signal from candle T0; the async bus lets candle T0+1m (which
        // trades through the limit) reach the engine FIRST.
        exec.onNewCandle(bar("MNQ", T0, 20010, 20012, 20005, 20008));
        exec.onNewCandle(bar("MNQ", T0.plusSeconds(60), 20008, 20009, 19998, 20003));
        exec.submitOrder(limit("MNQ", OrderSide.BUY, 2, 20000.0), 19990.0, 20030.0, T0);
        assertThat(acct.hasPosition("MNQ")).as("fill replayed from the raced candle").isTrue();
        assertThat(acct.getPosition("MNQ").getQuantity()).isEqualTo(2);
    }

    @Test
    void activeOrdersAreThreadSafe() throws Exception {
        AccountState acct = new AccountState(50_000.0);
        ExecutionEngine exec = new ExecutionEngine(acct);
        exec.setOrderTtlBars(0);
        Thread producer = new Thread(() -> {
            for (int i = 0; i < 500; i++) {
                exec.submitOrder(limit("MES", OrderSide.BUY, 1, 1000.0), 990.0, 1100.0);
            }
        });
        producer.start();
        for (int i = 0; i < 500; i++) {
            exec.onNewCandle(bar("MES", T0.plusSeconds(i), 5000, 5001, 4999, 5000)); // never fills
        }
        producer.join();
        assertThat(exec.getActiveOrdersList("MES")).hasSize(500);
    }

    @Test
    void flattenSafetyNetAt1445CtClosesPositionsAndCancelsRestingEntries() throws Exception {
        EventBus bus = new EventBus();
        CopyOnWriteArrayList<GateDecisionEvent> gates = new CopyOnWriteArrayList<>();
        bus.subscribe(GateDecisionEvent.class, gates::add);
        bus.start();
        try {
            AccountState acct = new AccountState(50_000.0);
            ExecutionEngine exec = new ExecutionEngine(acct);
            exec.setEventBus(bus);
            exec.setFlattenSafetyNet(true);
            Instant t1440 = Instant.parse("2026-09-28T19:40:00Z"); // 14:40 CT
            exec.submitOrderEnhanced(limit("MNQ", OrderSide.SELL, 3, 30635.75), 30675.0, 30558.0,
                    com.topstep.trading.strategy.TradeTier.TIER_2, null);
            exec.onNewCandle(bar("MNQ", t1440, 30630, 30640, 30625, 30630)); // fills short
            assertThat(acct.hasPosition("MNQ")).isTrue();
            exec.submitOrder(limit("MES", OrderSide.BUY, 1, 6000.0), 5990.0, 6030.0);

            Instant t1445 = Instant.parse("2026-09-28T19:45:00Z"); // 14:45 CT
            exec.onNewCandle(bar("MNQ", t1445, 30620, 30622, 30610, 30615));
            assertThat(acct.hasPosition("MNQ")).as("flattened at 14:45 CT").isFalse();
            Trade t = exec.getCompletedTrades().get(0);
            assertThat(t.getExitPrice()).isEqualTo(30615.0);
            assertThat(t.getNotes()).contains("FLATTEN 14:45 CT");
            // 20.75 pt x 3 x $2 = $124.50
            assertThat(t.getRealizedPnL()).isEqualTo(124.5);

            // A resting MES entry cannot fill inside the block: cancelled.
            exec.onNewCandle(bar("MES", t1445, 6001, 6002, 5980, 5985));
            assertThat(acct.hasPosition("MES")).isFalse();
            assertThat(exec.getActiveOrdersList("MES")).isEmpty();
            assertThat(exec.getSafetyNetFlattenCount()).isEqualTo(1);
            await(() -> gates.size() >= 2);
            assertThat(gates).extracting(GateDecisionEvent::getGate).contains("FLATTEN");
        } finally {
            bus.stop();
        }
    }
}
