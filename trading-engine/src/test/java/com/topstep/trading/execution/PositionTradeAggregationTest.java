package com.topstep.trading.execution;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.OrderType;
import com.topstep.trading.domain.Trade;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.GateDecisionEvent;
import com.topstep.trading.event.PositionClosedEvent;
import com.topstep.trading.event.SetupCancelledEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V5 Agent 05.3 — items 2 and 3 at the execution layer.
 * <ul>
 *   <li>Every position that goes flat produces EXACTLY ONE completed Trade
 *       (partial exits aggregated: quantity, VWAP exit, P&amp;L, R on the
 *       initial $ risk) and ONE PositionClosedEvent — including a position
 *       flattened by a partial take-profit (09-28: no Trade, closedTrades
 *       stayed 3, R printed 0.00).</li>
 *   <li>A SetupCancelledEvent cancels the still-unfilled SIM entry with a
 *       GateDecisionEvent "ORDER: cancelled — setup &lt;reason&gt;" and no
 *       synthetic PositionClosedEvent.</li>
 * </ul>
 */
class PositionTradeAggregationTest {

    private static final Instant T0 = Instant.parse("2026-09-28T18:53:00Z"); // 14:53 ET
    private EventBus bus;
    private AccountState account;
    private ExecutionEngine exec;
    private final List<PositionClosedEvent> closes = new CopyOnWriteArrayList<>();
    private final List<GateDecisionEvent> gates = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        bus = new EventBus();
        account = new AccountState(50_000.0);
        exec = new ExecutionEngine(account);
        exec.setEventBus(bus);
        exec.setOrderTtlBars(0);
        bus.subscribe(PositionClosedEvent.class, closes::add);
        bus.subscribe(GateDecisionEvent.class, gates::add);
        bus.start();
    }

    @AfterEach
    void tearDown() {
        bus.stop();
    }

    private static Candle bar(int minute, double o, double h, double l, double c) {
        return new Candle("MNQ", T0.plusSeconds(60L * minute), o, h, l, c, 100);
    }

    private void step(Candle c) {
        exec.onNewCandle(c);
        assertThat(bus.awaitIdle(5_000)).isTrue();
    }

    @Test
    void g1ShortClosedByTwoPartialsIsOneTradeWithR() {
        // G1: SELL 3 @ 30634.75, stop 30674.00 (39.25 pts = $78.50/micro), target 30510.50.
        exec.submitOrder(new Order("MNQ", OrderSide.SELL, OrderType.LIMIT, 3, 30634.75), 30674.0, 30510.5);
        step(bar(1, 30634.0, 30640.0, 30622.5, 30630.0));   // fill
        assertThat(account.hasPosition("MNQ")).isTrue();
        step(bar(2, 30600.0, 30601.0, 30595.0, 30598.0));   // 1R partial: 2 @ 30595.50
        assertThat(account.hasPosition("MNQ")).isTrue();
        assertThat(exec.getCompletedTrades()).isEmpty();
        step(bar(3, 30560.0, 30562.0, 30555.0, 30557.0));   // 2R partial: 1 @ 30556.25 -> FLAT

        assertThat(account.hasPosition("MNQ")).isFalse();
        List<Trade> trades = exec.getCompletedTrades();
        assertThat(trades).hasSize(1);
        Trade t = trades.get(0);
        assertThat(t.getQuantity()).isEqualTo(3);
        assertThat(t.getSide()).isEqualTo(OrderSide.SELL);
        assertThat(t.getEntryPrice()).isEqualTo(30634.75);
        assertThat(t.getExitPrice()).isCloseTo((2 * 30595.50 + 30556.25) / 3.0, within(1e-9));
        assertThat(t.getRealizedPnL()).isCloseTo(314.0, within(1e-9));       // 157 + 157
        assertThat(t.getRiskAmount()).isCloseTo(235.5, within(1e-9));        // 3 x $78.50
        assertThat(t.getRMultiple()).isCloseTo(314.0 / 235.5, within(1e-9)); // 1.33, not 0.00
        assertThat(closes).hasSize(1);
        assertThat(closes.get(0).getPnl()).isCloseTo(314.0, within(1e-9));
        assertThat(account.getTradesToday()).isEqualTo(1);
    }

    @Test
    void oneMicroFlattenedByTheFirstPartialIsATrade() {
        // 09-28 12:32: SELL 1 @ 30645.00, stop 30723.00 — the 1R partial closes the only micro.
        exec.submitOrder(new Order("MNQ", OrderSide.SELL, OrderType.LIMIT, 1, 30645.0), 30723.0, 30356.75);
        step(bar(1, 30640.0, 30650.0, 30625.75, 30645.75));
        step(bar(2, 30570.0, 30572.5, 30564.25, 30571.0));   // 1R = 30567.00
        List<Trade> trades = exec.getCompletedTrades();
        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).getQuantity()).isEqualTo(1);
        assertThat(trades.get(0).getRealizedPnL()).isCloseTo(156.0, within(1e-9));
        assertThat(trades.get(0).getRMultiple()).isCloseTo(1.0, within(1e-9));
        assertThat(closes).hasSize(1);
        assertThat(account.getTradesToday()).isEqualTo(1);
    }

    @Test
    void partialThenBreakevenStopIsOneAggregatedTrade() {
        // 09-23 01:26 ASIA: SELL 5 @ 31036.00 stop 31044.25 -> 3 @ 1R, then 2 at the BE stop.
        exec.submitOrder(new Order("MNQ", OrderSide.SELL, OrderType.LIMIT, 5, 31036.0), 31044.25, 31011.5);
        step(bar(1, 31030.0, 31037.0, 31029.0, 31033.0));   // fill
        step(bar(2, 31030.0, 31031.0, 31027.0, 31028.0));   // 1R = 31027.75 -> 3 closed, stop -> BE
        for (int m = 3; m < 30 && account.hasPosition("MNQ"); m++) {
            step(bar(m, 31040.0, 31045.0, 31039.0, 31044.0)); // rally -> remaining 2 stopped
        }
        assertThat(account.hasPosition("MNQ")).isFalse();
        List<Trade> trades = exec.getCompletedTrades();
        assertThat(trades).hasSize(1);
        Trade t = trades.get(0);
        assertThat(t.getQuantity()).isEqualTo(5);
        assertThat(t.getNotes()).startsWith("2 exits:");
        assertThat(t.getRiskAmount()).isCloseTo(5 * 16.5, within(1e-9));
        assertThat(t.getRMultiple()).isCloseTo(t.getRealizedPnL() / 82.5, within(1e-9));
        assertThat(closes).hasSize(1);
        assertThat(account.getTradesToday()).isEqualTo(1);
    }

    @Test
    void setupCancelledEventCancelsTheUnfilledEntryWithAGateEvent() {
        // 09-24 14:03 LONG 3 @ 30607.00 never filled; the setup expired at 15:07.
        exec.submitOrder(new Order("MNQ", OrderSide.BUY, OrderType.LIMIT, 3, 30607.0), 30567.75, 30703.25);
        step(bar(1, 30650.0, 30655.0, 30640.0, 30645.0));   // above the limit: resting
        assertThat(exec.getActiveOrdersList("MNQ")).hasSize(1);

        bus.publish(new SetupCancelledEvent("MNQ", "expired (200 bars without progress)", T0.plusSeconds(3840)));
        assertThat(bus.awaitIdle(5_000)).isTrue();

        assertThat(exec.getActiveOrdersList("MNQ")).isEmpty();
        assertThat(exec.getSetupCancelCount()).isEqualTo(1);
        assertThat(gates).anySatisfy(g -> {
            assertThat(g.getGate()).isEqualTo("ORDER");
            assertThat(g.getReason()).isEqualTo("ORDER: cancelled — setup expired (200 bars without progress)");
        });
        assertThat(closes).as("no synthetic release: the strategy released its own latch").isEmpty();
        // A later bar through the old limit must NOT fill anything.
        step(bar(2, 30610.0, 30612.0, 30590.0, 30600.0));
        assertThat(account.hasPosition("MNQ")).isFalse();
    }

    @Test
    void setupCancelledEventNeverTouchesAFilledPositionOrAnotherSymbol() {
        exec.submitOrder(new Order("MNQ", OrderSide.SELL, OrderType.LIMIT, 1, 30645.0), 30723.0, 30356.75);
        step(bar(1, 30640.0, 30650.0, 30625.75, 30645.75));  // filled
        exec.submitOrder(new Order("MES", OrderSide.BUY, OrderType.LIMIT, 1, 6500.0), 6490.0, 6530.0);
        bus.publish(new SetupCancelledEvent("MNQ", "re-armed", T0));
        assertThat(bus.awaitIdle(5_000)).isTrue();
        assertThat(account.hasPosition("MNQ")).isTrue();
        assertThat(exec.getActiveOrdersList("MES")).hasSize(1);
        assertThat(exec.getSetupCancelCount()).isZero();
        assertThat(gates).noneMatch(g -> "ORDER".equals(g.getGate()));
    }

    @Test
    void liveExternalLegsMergeIntoOneTrade() {
        Trade leg1 = Trade.builder().symbol("MNQ").side(OrderSide.SELL).quantity(2).entryPrice(30634.75)
                .exitPrice(30595.5).entryTime(T0).exitTime(T0.plusSeconds(600)).realizedPnL(157.0)
                .riskAmount(157.0).notes("Partial take profit (1.0R)").build();
        Trade leg2 = Trade.builder().symbol("MNQ").side(OrderSide.SELL).quantity(1).entryPrice(30634.75)
                .exitPrice(30556.25).entryTime(T0).exitTime(T0.plusSeconds(2700)).realizedPnL(157.0)
                .riskAmount(78.5).notes("Partial take profit (2.0R)").build();
        exec.recordExternalPartial(leg1);
        exec.recordExternalPartial(leg2);
        assertThat(exec.getCompletedTrades()).isEmpty();
        exec.finalizeExternalTrade("MNQ");
        List<Trade> trades = exec.getCompletedTrades();
        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).getQuantity()).isEqualTo(3);
        assertThat(trades.get(0).getRealizedPnL()).isCloseTo(314.0, within(1e-9));
        assertThat(trades.get(0).getRMultiple()).isCloseTo(314.0 / 235.5, within(1e-9));
    }
}
