package com.topstep.trading.execution;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.OrderType;
import com.topstep.trading.event.Event;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.PositionClosedEvent;
import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SA4: {@link ExecutionEngine#closePosition} is one of the two real
 * position-close funnels (the other is LiveEngineRunner's bracket close
 * handlers). This test pins that the funnel publishes exactly one
 * {@link PositionClosedEvent} per fully closed position — at the same spot
 * that counts the trade via {@code AccountState.recordTradeCompleted} — and
 * that the event carries the market exit timestamp (deterministic for
 * backtests).
 */
@DisplayName("PositionClosedEventFunnelTest (close funnel publishes the event)")
class PositionClosedEventFunnelTest {

    static final class RecordingBus extends EventBus {
        final List<PositionClosedEvent> closes = new ArrayList<>();
        @Override
        public void publish(Event event) {
            if (event instanceof PositionClosedEvent closed) {
                closes.add(closed);
            }
        }
    }

    private static Candle candle(Instant ts, double o, double h, double l, double c) {
        return new Candle("MNQ", ts, o, h, l, c, 100);
    }

    @Test
    @DisplayName("fill then target hit → ONE PositionClosedEvent with symbol, win flag, exit time")
    void closeFunnelPublishesEvent() {
        AccountState account = new AccountState(50_000.0);
        ExecutionEngine engine = new ExecutionEngine(account);
        RecordingBus bus = new RecordingBus();
        engine.setEventBus(bus);

        Order order = Order.builder()
                .symbol("MNQ").side(OrderSide.BUY).type(OrderType.LIMIT)
                .quantity(6).limitPrice(21023.0).build();
        engine.submitOrderEnhanced(order, 21011.0, 21035.0, TradeTier.TIER_1,
                new double[0][]);

        Instant t1 = Instant.parse("2026-06-15T14:00:00Z");
        Instant t2 = Instant.parse("2026-06-15T14:01:00Z");

        // Entry fill (low trades through the limit) — no close yet.
        engine.onNewCandle(candle(t1, 21030, 21031, 21022, 21028));
        assertThat(bus.closes).isEmpty();
        assertThat(account.hasPosition("MNQ")).isTrue();

        // Target hit → closePosition → counted + published, exactly once.
        engine.onNewCandle(candle(t2, 21028, 21040, 21027, 21038));
        assertThat(bus.closes).hasSize(1);
        PositionClosedEvent evt = bus.closes.get(0);
        assertThat(evt.getSymbol()).isEqualTo("MNQ");
        assertThat(evt.isWin()).isTrue();
        assertThat(evt.getPnl()).isGreaterThan(0.0);
        assertThat(evt.getClosedAt()).isEqualTo(t2); // market exit time, not wall clock
        assertThat(evt.getType()).isEqualTo(com.topstep.trading.event.EventType.POSITION_CLOSED);
        assertThat(account.getTradesToday()).isEqualTo(1);
        assertThat(account.hasPosition("MNQ")).isFalse();
    }

    @Test
    @DisplayName("no event bus set → close funnel still works (legacy behaviour, no publish)")
    void noBusMeansNoPublishButCloseStillWorks() {
        AccountState account = new AccountState(50_000.0);
        ExecutionEngine engine = new ExecutionEngine(account); // no setEventBus

        Order order = Order.builder()
                .symbol("MNQ").side(OrderSide.BUY).type(OrderType.LIMIT)
                .quantity(6).limitPrice(21023.0).build();
        engine.submitOrderEnhanced(order, 21011.0, 21035.0, TradeTier.TIER_1,
                new double[0][]);

        engine.onNewCandle(candle(Instant.parse("2026-06-15T14:00:00Z"),
                21030, 21031, 21022, 21028));
        engine.onNewCandle(candle(Instant.parse("2026-06-15T14:01:00Z"),
                21028, 21040, 21027, 21038));

        assertThat(account.getTradesToday()).isEqualTo(1);
        assertThat(account.hasPosition("MNQ")).isFalse();
    }
}
