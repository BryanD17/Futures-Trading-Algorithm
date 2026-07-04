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
import com.topstep.trading.event.StrategySignalEvent.SignalType;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SA4 frequency integration tests: multiple trades per killzone become
 * structurally possible in scalp mode — with the close funnel, the re-arm
 * cooldown, and the no-overlap rule all exercised end to end on a
 * deterministic candle fixture.
 *
 * <p>The close funnel is the REAL one: signals are turned into
 * {@link ExecutionEngine} orders; the engine fills them off candle prices,
 * closes them at the bracket target, counts the trade
 * ({@code AccountState.recordTradeCompleted}) and publishes
 * {@link PositionClosedEvent} — which the runner's re-arm logic consumes.
 */
@DisplayName("StdvOteScalpFrequencyIntegrationTest (SA4 re-arm + no-overlap)")
class StdvOteScalpFrequencyIntegrationTest {

    @BeforeEach
    void enableScalpMode() {
        // Default scalp configuration on purpose — the fixture's raids score
        // exactly the MNQ instrument base (5), which is indistinguishable
        // from the starved-pipeline fallback and therefore bypasses the
        // binary floor by design (see SA4_frequency_gates.md). The floor
        // itself is pinned by StdvOteScalpRaidGateTest.
        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "true");
    }

    @AfterEach
    void cleanup() {
        System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        System.clearProperty(ScalpConfig.MIN_RAID_SCORE_PROPERTY);
        System.clearProperty(ScalpConfig.REARM_COOLDOWN_BARS_PROPERTY);
        StdvOteRegistry.unregister(StdvOteScalpFrequencyFixture.SYMBOL);
    }

    /**
     * Synchronous event bus: publish dispatches to subscribed handlers on
     * the calling thread — deterministic close-notification for tests (the
     * production bus dispatches the identical events asynchronously).
     */
    static final class SyncBus extends EventBus {
        final Map<EventType, List<EventHandler<Event>>> subs = new EnumMap<>(EventType.class);
        final List<StrategySignalEvent> signals = new ArrayList<>();
        final List<PositionClosedEvent> closes = new ArrayList<>();
        /** Signal count at the time each close was observed (ordering proof). */
        final List<Integer> signalCountAtClose = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Event> void subscribe(EventType type, EventHandler<T> handler) {
            subs.computeIfAbsent(type, k -> new ArrayList<>()).add((EventHandler<Event>) handler);
        }

        @Override
        public void publish(Event event) {
            if (event instanceof StrategySignalEvent sig) {
                signals.add(sig);
            }
            if (event instanceof PositionClosedEvent closed) {
                closes.add(closed);
                signalCountAtClose.add(signals.size());
            }
            for (EventHandler<Event> h : subs.getOrDefault(event.getType(), List.of())) {
                h.handle(event);
            }
        }
    }

    /** Harness: runner + real ExecutionEngine close funnel on one sync bus. */
    private static final class Harness {
        final SyncBus bus = new SyncBus();
        final AccountState account = new AccountState(50_000.0);
        final ExecutionEngine exec = new ExecutionEngine(account);
        final StdvOteRunnerStrategy strategy;
        final DefaultStrategyContext context = new DefaultStrategyContext(account);
        int submitted = 0;
        final double bracketTarget;

        Harness(double bracketTarget) {
            this.bracketTarget = bracketTarget;
            exec.setEventBus(bus);
            strategy = new StdvOteRunnerStrategy(
                    StdvOteScalpFrequencyFixture.SYMBOL, "MES", bus);
            strategy.initialize();
        }

        void feed(List<Candle> candles) {
            for (Candle c : candles) {
                strategy.onCandle(c, context);
                // Turn the FIRST emitted signal into a real order so the
                // funnel (fill → target → closePosition → event) runs.
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
                    exec.submitOrderEnhanced(order, sig.getStopPrice(), bracketTarget,
                            TradeTier.TIER_1, new double[0][]);
                }
                exec.onNewCandle(c);
            }
        }
    }

    @Test
    @DisplayName("(a) TWO complete trades in one NY AM killzone: emit, close via funnel, cooldown, re-arm, emit again")
    void twoTradesInOneKillzone() {
        Harness h = new Harness(StdvOteScalpFrequencyFixture.ONE_R_TARGET);
        h.feed(StdvOteScalpFrequencyFixture.fullTwoTradeFixture());

        // Two emissions, identical legitimate geometry (the second setup
        // passed every sequential gate afresh: new sweep, new displacement,
        // new MSS, new OTE reaction).
        assertThat(h.bus.signals).hasSize(2);
        for (StrategySignalEvent sig : h.bus.signals) {
            assertThat(sig.getSignalType()).isEqualTo(SignalType.LONG_ENTRY);
            assertThat(sig.getSymbol()).isEqualTo(StdvOteScalpFrequencyFixture.SYMBOL);
            assertThat(sig.getEntryPrice()).isEqualTo(StdvOteScalpFrequencyFixture.EXPECTED_ENTRY);
            assertThat(sig.getStopPrice()).isEqualTo(StdvOteScalpFrequencyFixture.EXPECTED_STOP);
        }

        // The close funnel really ran: one PositionClosedEvent, a win at the
        // 1R target, counted once by the frequency counters.
        assertThat(h.bus.closes).hasSize(1);
        assertThat(h.bus.closes.get(0).getSymbol()).isEqualTo(StdvOteScalpFrequencyFixture.SYMBOL);
        assertThat(h.bus.closes.get(0).isWin()).isTrue();
        assertThat(h.account.getTradesToday()).isEqualTo(1);
        assertThat(h.account.getConsecutiveLosses()).isZero();

        // Ordering: trade 1 CLOSED before signal 2 was emitted.
        assertThat(h.bus.signalCountAtClose.get(0)).isEqualTo(1);

        // Sizer wiring: with account state present, the StdvOteSizer decided
        // the size — $150 risk budget / $24 per-contract risk = 6 micros,
        // inside [5, 20] and under maxContracts 20.
        assertThat(h.bus.signals.get(0).getQuantity()).isEqualTo(6);
        assertThat(h.bus.signals.get(1).getQuantity()).isEqualTo(6);
    }

    @Test
    @DisplayName("(c) NO re-arm while the position is still open (no-overlap rule)")
    void noRearmWhilePositionOpen() {
        // Bracket target far away: the position fills but NEVER closes, so
        // no PositionClosedEvent fires. The same two-act fixture must then
        // produce exactly ONE emission — act 2's fresh setup may not arm
        // while the symbol has an open position.
        Harness h = new Harness(/* unreachable target */ 21_200.0);
        h.feed(StdvOteScalpFrequencyFixture.fullTwoTradeFixture());

        assertThat(h.bus.closes).isEmpty();
        assertThat(h.account.hasPosition(StdvOteScalpFrequencyFixture.SYMBOL)).isTrue();
        assertThat(h.bus.signals).hasSize(1);
        assertThat(h.strategy.getSetupContext().state).isEqualTo(SetupState.IN_TRADE);
    }

    @Test
    @DisplayName("(d) NO re-arm inside the cooldown window")
    void noRearmInsideCooldown() {
        // Same fixture, same funnel, but a cooldown longer than the rest of
        // the feed (50 bars vs ~30 remaining): the re-arm may not fire, so
        // only ONE emission can exist. Together with (a) — identical feed,
        // cooldown 5 → two emissions — this pins the cooldown gate exactly.
        System.setProperty(ScalpConfig.REARM_COOLDOWN_BARS_PROPERTY, "50");
        Harness h = new Harness(StdvOteScalpFrequencyFixture.ONE_R_TARGET);
        h.feed(StdvOteScalpFrequencyFixture.fullTwoTradeFixture());

        // Trade 1 emitted and CLOSED (funnel ran)...
        assertThat(h.bus.closes).hasSize(1);
        assertThat(h.account.getTradesToday()).isEqualTo(1);
        // ...but no second setup armed inside the cooldown.
        assertThat(h.bus.signals).hasSize(1);
    }
}
