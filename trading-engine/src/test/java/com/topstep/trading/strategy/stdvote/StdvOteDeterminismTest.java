package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.OrderType;
import com.topstep.trading.event.Event;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.EventHandler;
import com.topstep.trading.event.EventType;
import com.topstep.trading.event.PositionClosedEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SA5 determinism proof: the full candle-driven pipeline (detectors → state
 * machine → validator → emission) is a pure function of the candle sequence.
 * The SAME fixture, fed to two FRESH runner instances, must produce
 * byte-identical signal sequences — same entries/stops/targets to the tick,
 * same sizes, same event order.
 *
 * <p>Covers both modes:
 * <ul>
 *   <li>LEGACY: the SA2 wiring-integration fixture
 *       ({@link StdvOteGoldenFixture}) run twice.</li>
 *   <li>SCALP: the SA4 two-trade frequency fixture
 *       ({@link StdvOteScalpFrequencyFixture}) run twice through the REAL
 *       {@link ExecutionEngine} close funnel (fill → target →
 *       closePosition → PositionClosedEvent → re-arm), asserting the
 *       interleaved signal/close event order is identical.</li>
 * </ul>
 */
@DisplayName("StdvOteDeterminismTest (two fresh runs — identical output)")
class StdvOteDeterminismTest {

    @org.junit.jupiter.api.BeforeEach
    void pinDetectorTimeframe() {
        // The fixtures encode 1m entry anatomy (displacement/FVG/MSS built
        // candle-by-candle at 1m) — pin the detector timeframe so this
        // suite keeps testing the wiring. LIVE default is 5m (field fix
        // 2026-07-09).
        System.setProperty("stdvote.detectorTimeframe", "1");
    }

    @AfterEach
    void cleanup() {
        System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        System.clearProperty("stdvote.detectorTimeframe");
        StdvOteRegistry.unregister(StdvOteGoldenFixture.SYMBOL);
    }

    /** Synchronous bus that records an ordered event trace. */
    static final class TraceBus extends EventBus {
        final Map<EventType, List<EventHandler<Event>>> subs = new EnumMap<>(EventType.class);
        final List<StrategySignalEvent> signals = new ArrayList<>();
        final List<String> trace = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Event> void subscribe(EventType type, EventHandler<T> handler) {
            subs.computeIfAbsent(type, k -> new ArrayList<>()).add((EventHandler<Event>) handler);
        }

        @Override
        public void publish(Event event) {
            if (event instanceof StrategySignalEvent sig) {
                signals.add(sig);
                trace.add(describe(sig));
            }
            if (event instanceof PositionClosedEvent closed) {
                trace.add("CLOSE " + closed.getSymbol()
                        + " win=" + closed.isWin()
                        + " pnl=" + closed.getPnl());
            }
            for (EventHandler<Event> h : subs.getOrDefault(event.getType(), List.of())) {
                h.handle(event);
            }
        }
    }

    /** Exact textual footprint of a signal — any drift fails the equality. */
    private static String describe(StrategySignalEvent sig) {
        return sig.getSignalType() + " " + sig.getSymbol()
                + " side=" + sig.getSide()
                + " entry=" + sig.getEntryPrice()
                + " stop=" + sig.getStopPrice()
                + " target=" + sig.getTargetPrice()
                + " qty=" + sig.getQuantity()
                + " tier=" + sig.getTier()
                + " rr=" + sig.getActualRR();
    }

    /** One fresh legacy-mode run over the SA2 wiring fixture. */
    private static TraceBus runLegacyOnce() {
        TraceBus bus = new TraceBus();
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(
                StdvOteGoldenFixture.SYMBOL, "MES", bus);
        s.initialize();
        DefaultStrategyContext ctx = new DefaultStrategyContext(new AccountState(50_000.0));
        for (Candle c : StdvOteGoldenFixture.fullFixture()) {
            s.onCandle(c, ctx);
        }
        StdvOteRegistry.unregister(StdvOteGoldenFixture.SYMBOL);
        return bus;
    }

    /** One fresh scalp-mode run over the two-trade fixture with the real close funnel. */
    private static TraceBus runScalpOnce() {
        TraceBus bus = new TraceBus();
        AccountState account = new AccountState(50_000.0);
        ExecutionEngine exec = new ExecutionEngine(account);
        exec.setEventBus(bus);
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(
                StdvOteScalpFrequencyFixture.SYMBOL, "MES", bus);
        s.initialize();
        DefaultStrategyContext ctx = new DefaultStrategyContext(account);
        int submitted = 0;
        for (Candle c : StdvOteScalpFrequencyFixture.fullTwoTradeFixture()) {
            s.onCandle(c, ctx);
            if (bus.signals.size() > submitted && submitted == 0) {
                StrategySignalEvent sig = bus.signals.get(submitted);
                submitted++;
                Order order = Order.builder()
                        .symbol(sig.getSymbol())
                        .side(sig.getSide())
                        .type(OrderType.LIMIT)
                        .quantity(sig.getQuantity())
                        .limitPrice(sig.getEntryPrice())
                        .build();
                exec.submitOrderEnhanced(order, sig.getStopPrice(),
                        StdvOteScalpFrequencyFixture.ONE_R_TARGET,
                        TradeTier.TIER_1, new double[0][]);
            }
            exec.onNewCandle(c);
        }
        StdvOteRegistry.unregister(StdvOteScalpFrequencyFixture.SYMBOL);
        return bus;
    }

    @Test
    @DisplayName("LEGACY: two fresh runs over the SA2 wiring fixture produce identical signal sequences")
    void legacyRunsAreDeterministic() {
        System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        TraceBus first = runLegacyOnce();
        TraceBus second = runLegacyOnce();

        // The fixture emits — determinism over an empty sequence proves nothing.
        assertThat(first.signals).isNotEmpty();
        assertThat(first.trace).isEqualTo(second.trace);

        // Belt and braces: prices identical to the tick, not just formatted alike.
        for (int i = 0; i < first.signals.size(); i++) {
            StrategySignalEvent a = first.signals.get(i);
            StrategySignalEvent b = second.signals.get(i);
            assertThat(a.getEntryPrice()).isEqualTo(b.getEntryPrice());
            assertThat(a.getStopPrice()).isEqualTo(b.getStopPrice());
            assertThat(a.getTargetPrice()).isEqualTo(b.getTargetPrice());
            assertThat(a.getQuantity()).isEqualTo(b.getQuantity());
        }
    }

    @Test
    @DisplayName("SCALP: two fresh runs over the two-trade fixture produce identical signal+close event order")
    void scalpRunsAreDeterministic() {
        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "true");
        TraceBus first = runScalpOnce();
        TraceBus second = runScalpOnce();

        // Two emissions + one close — the full re-arm sequence must replay
        // identically, in the same order.
        assertThat(first.signals).hasSize(2);
        assertThat(first.trace).isEqualTo(second.trace);
        assertThat(first.trace.stream().filter(t -> t.startsWith("CLOSE")).count())
                .isEqualTo(1);

        for (int i = 0; i < first.signals.size(); i++) {
            StrategySignalEvent a = first.signals.get(i);
            StrategySignalEvent b = second.signals.get(i);
            assertThat(a.getEntryPrice()).isEqualTo(b.getEntryPrice());
            assertThat(a.getStopPrice()).isEqualTo(b.getStopPrice());
            assertThat(a.getTargetPrice()).isEqualTo(b.getTargetPrice());
            assertThat(a.getQuantity()).isEqualTo(b.getQuantity());
        }
    }
}
