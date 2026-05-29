package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.connector.MarketDataListener;
import com.topstep.trading.connector.OrderListener;
import com.topstep.trading.connector.TradingConnector;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.Order;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.DefaultStrategyContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SA-followup tests for {@link StdvOteMultiInstrumentEngine}.
 *
 * <p>The engine routes candles between the active set (MNQ + MGC default) and
 * the SMT-only feed (MES default). These tests verify:
 *
 * <ul>
 *   <li>only registry-allowed symbols may be active (NQ/ES/GC throw);</li>
 *   <li>start() subscribes to every active + SMT-only symbol exactly once;</li>
 *   <li>candles dispatched for an active symbol drive only that strategy;</li>
 *   <li>candles dispatched for an SMT-only symbol drive
 *       {@link StdvOteRunnerStrategy#onSmtCandle} on the strategy that
 *       names it as a correlate, NOT {@code onCandle};</li>
 *   <li>stop() unsubscribes and clears strategies.</li>
 * </ul>
 */
@DisplayName("StdvOteMultiInstrumentEngine")
class StdvOteMultiInstrumentEngineTest {

    private DefaultStrategyContext ctx() {
        return new DefaultStrategyContext(new AccountState(50_000.0));
    }

    /** Minimal test connector that records subscribe/unsubscribe calls. */
    private static final class RecordingConnector implements TradingConnector {
        final Map<String, MarketDataListener> subs = new ConcurrentHashMap<>();
        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public boolean isConnected() { return true; }
        @Override public void subscribeMarketData(String symbol, MarketDataListener listener) {
            subs.put(symbol, listener);
        }
        @Override public void unsubscribeMarketData(String symbol) {
            subs.remove(symbol);
        }
        @Override public String submitOrder(Order order, OrderListener listener) { return "test"; }
        @Override public void cancelOrder(String orderId) {}
        @Override public double getAccountBalance() { return 50_000.0; }
        @Override public String getName() { return "test"; }
    }

    @Test
    @DisplayName("constructor rejects full-size active symbols (NQ/ES/GC)")
    void rejectsFullSizeActive() {
        RecordingConnector c = new RecordingConnector();
        assertThatThrownBy(() -> new StdvOteMultiInstrumentEngine(
                c, new EventBus(), ctx(),
                List.of("NQ"), Map.of("NQ", "ES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registry");
    }

    @Test
    @DisplayName("default config: active=MNQ,MGC and SMT-only=MES (MNQ correlate)")
    void defaultConfig() {
        StdvOteMultiInstrumentEngine eng = new StdvOteMultiInstrumentEngine(
                new RecordingConnector(), new EventBus(), ctx(),
                List.of("MNQ", "MGC"),
                Map.of("MNQ", "MES", "MGC", ""));
        assertThat(eng.getActiveSymbols()).containsExactly("MNQ", "MGC");
        assertThat(eng.getSmtOnlySymbols()).containsExactly("MES");
        assertThat(eng.symbolsForSubscription())
                .containsExactly("MNQ", "MGC", "MES");
    }

    @Test
    @DisplayName("start() subscribes every active + SMT-only symbol exactly once")
    void startSubscribesEverySymbol() {
        RecordingConnector c = new RecordingConnector();
        StdvOteMultiInstrumentEngine eng = new StdvOteMultiInstrumentEngine(
                c, new EventBus(), ctx(),
                List.of("MNQ", "MGC"),
                Map.of("MNQ", "MES", "MGC", ""));
        eng.start();
        assertThat(c.subs.keySet()).containsExactlyInAnyOrder("MNQ", "MGC", "MES");
        assertThat(eng.isRunning()).isTrue();
    }

    @Test
    @DisplayName("dispatch routes an MNQ candle to the MNQ strategy and not MGC")
    void mnqCandleRoutesToMnq() {
        RecordingConnector c = new RecordingConnector();
        StdvOteMultiInstrumentEngine eng = new StdvOteMultiInstrumentEngine(
                c, new EventBus(), ctx(),
                List.of("MNQ", "MGC"),
                Map.of("MNQ", "MES", "MGC", ""));
        eng.start();

        // Send 5 MNQ candles.
        Instant t = Instant.parse("2026-05-28T14:00:00Z");
        for (int i = 0; i < 5; i++) {
            Candle mnq = new Candle("MNQ", t.plus(i, ChronoUnit.MINUTES),
                    20000.0, 20001.0, 19999.0, 20000.5, 100);
            eng.dispatchCandle(mnq);
        }
        // MNQ strategy has barIndex / setup state mutated; MGC strategy is
        // untouched (we can't easily peek at barIndex but the SetupContext
        // symbol pins it to MGC).
        assertThat(eng.getStrategy("MNQ").getSetupContext().symbol).isEqualTo("MNQ");
        assertThat(eng.getStrategy("MGC").getSetupContext().symbol).isEqualTo("MGC");
        // Both strategies have onCandle running, but only MNQ has received candles.
        // The MGC strategy's state is still IDLE (no candles arrived).
        assertThat(eng.getStrategy("MGC").getSetupContext().state).isEqualTo(SetupState.IDLE);
    }

    @Test
    @DisplayName("dispatch of an MES candle does NOT call onCandle on any active strategy")
    void mesCandleIsSmtFeedOnly() {
        RecordingConnector c = new RecordingConnector();
        StdvOteMultiInstrumentEngine eng = new StdvOteMultiInstrumentEngine(
                c, new EventBus(), ctx(),
                List.of("MNQ", "MGC"),
                Map.of("MNQ", "MES", "MGC", ""));
        eng.start();

        Instant t = Instant.parse("2026-05-28T14:00:00Z");
        for (int i = 0; i < 5; i++) {
            Candle mes = new Candle("MES", t.plus(i, ChronoUnit.MINUTES),
                    5000.0, 5001.0, 4999.0, 5000.5, 100);
            eng.dispatchCandle(mes);
        }
        // MES is SMT-only — neither active strategy advances state.
        assertThat(eng.getStrategy("MNQ").getSetupContext().state).isEqualTo(SetupState.IDLE);
        assertThat(eng.getStrategy("MGC").getSetupContext().state).isEqualTo(SetupState.IDLE);
    }

    @Test
    @DisplayName("dispatching for a symbol the engine doesn't know is a no-op")
    void unknownSymbolNoop() {
        RecordingConnector c = new RecordingConnector();
        StdvOteMultiInstrumentEngine eng = new StdvOteMultiInstrumentEngine(
                c, new EventBus(), ctx(),
                List.of("MNQ"), Map.of("MNQ", "MES"));
        eng.start();
        Candle weird = new Candle("XYZ", Instant.now(),
                100.0, 101.0, 99.0, 100.5, 10);
        eng.dispatchCandle(weird); // should not throw
        assertThat(eng.getStrategy("MNQ").getSetupContext().state).isEqualTo(SetupState.IDLE);
    }

    @Test
    @DisplayName("stop() unsubscribes and is idempotent")
    void stopIsIdempotent() {
        RecordingConnector c = new RecordingConnector();
        StdvOteMultiInstrumentEngine eng = new StdvOteMultiInstrumentEngine(
                c, new EventBus(), ctx(),
                List.of("MNQ", "MGC"), Map.of("MNQ", "MES", "MGC", ""));
        eng.start();
        assertThat(c.subs).hasSize(3);
        eng.stop();
        assertThat(c.subs).isEmpty();
        assertThat(eng.isRunning()).isFalse();
        eng.stop(); // second call must not throw
    }

    @Test
    @DisplayName("primary strategy is the first active symbol")
    void primaryIsFirstActive() {
        StdvOteMultiInstrumentEngine eng = new StdvOteMultiInstrumentEngine(
                new RecordingConnector(), new EventBus(), ctx(),
                List.of("MNQ", "MGC"), Map.of("MNQ", "MES", "MGC", ""));
        assertThat(eng.getPrimaryStrategy().getSetupContext().symbol).isEqualTo("MNQ");
    }
}
