package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.PositionClosedEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.strategy.DefaultStrategyContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGENT-05 (V5 RC-16): the LEGACY path subscribes PositionClosedEvent, so a
 * signal that never executed (risk deny, warmup drop, order failure, SIM
 * TTL) releases the latch — the setup is invalidated and the normal legacy
 * re-arm applies. A signal that DID execute keeps IN_TRADE terminal (legacy
 * one-trade discipline unchanged). The signal carries its CANDLE time.
 */
class LegacyLatchReleaseTest {

    @BeforeEach
    void legacy() {
        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "false");
        System.setProperty("stdvote.detectorTimeframe", "1");
    }

    @AfterEach
    void cleanup() {
        System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        System.clearProperty("stdvote.detectorTimeframe");
        StdvOteRegistry.unregister(StdvOteGoldenFixture.SYMBOL);
    }

    private static Candle after(Candle last, int minutes) {
        return new Candle(last.getSymbol(), last.getTimestamp().plusSeconds(60L * minutes),
                last.getClose(), last.getClose() + 0.5, last.getClose() - 0.5, last.getClose(), 100);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier c) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < deadline) TimeUnit.MILLISECONDS.sleep(10);
    }

    @Test
    void riskDenialReleasesTheLegacyLatch() throws Exception {
        EventBus bus = new EventBus();
        CopyOnWriteArrayList<StrategySignalEvent> signals = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<PositionClosedEvent> closes = new CopyOnWriteArrayList<>();
        bus.subscribe(StrategySignalEvent.class, signals::add);
        bus.subscribe(PositionClosedEvent.class, closes::add);
        bus.start();
        try {
            StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(StdvOteGoldenFixture.SYMBOL, "MES", bus);
            s.initialize();
            DefaultStrategyContext ctx = new DefaultStrategyContext(new AccountState(50_000.0));
            List<Candle> fixture = StdvOteGoldenFixture.fullFixture();
            for (Candle c : fixture) s.onCandle(c, ctx);
            assertThat(s.getSetupContext().state).isEqualTo(SetupState.IN_TRADE);
            awaitTrue(() -> !signals.isEmpty());
            assertThat(signals).hasSize(1);
            // RC-15: the signal carries the emitting candle's MARKET time.
            assertThat(signals.get(0).getCandleTime()).isNotNull();
            assertThat(fixture.stream().map(Candle::getTimestamp)).contains(signals.get(0).getCandleTime());

            // SIM/LIVE release after a risk deny (synthetic close, pnl 0).
            Candle last = fixture.get(fixture.size() - 1);
            bus.publish(new PositionClosedEvent("MNQ", 0.0, false, last.getTimestamp()));
            awaitTrue(() -> !closes.isEmpty());
            TimeUnit.MILLISECONDS.sleep(50);
            s.onCandle(after(last, 1), ctx);
            assertThat(s.getSetupContext().state)
                    .as("legacy latch released -> INVALIDATED for re-arm (was stuck IN_TRADE for 200 min)")
                    .isEqualTo(SetupState.INVALIDATED);
            assertThat(s.getSetupContext().lastGateFailed).contains("signal not executed");
        } finally {
            bus.stop();
        }
    }

    @Test
    void anExecutedLegacyTradeStaysTerminal() throws Exception {
        EventBus bus = new EventBus();
        CopyOnWriteArrayList<PositionClosedEvent> closes = new CopyOnWriteArrayList<>();
        bus.subscribe(PositionClosedEvent.class, closes::add);
        bus.start();
        try {
            StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(StdvOteGoldenFixture.SYMBOL, "MES", bus);
            s.initialize();
            AccountState acct = new AccountState(50_000.0);
            DefaultStrategyContext ctx = new DefaultStrategyContext(acct);
            List<Candle> fixture = StdvOteGoldenFixture.fullFixture();
            for (Candle c : fixture) s.onCandle(c, ctx);
            assertThat(s.getSetupContext().state).isEqualTo(SetupState.IN_TRADE);
            Candle last = fixture.get(fixture.size() - 1);
            // The order filled and a position existed for a bar...
            acct.updatePosition("MNQ", 5, 21023.0);
            s.onCandle(after(last, 1), ctx);
            // ...then closed at the stop (a REAL trade).
            acct.updatePosition("MNQ", -5, 21011.0);
            acct.recordTradeCompleted(-120.0);
            bus.publish(new PositionClosedEvent("MNQ", -120.0, false, last.getTimestamp()));
            awaitTrue(() -> !closes.isEmpty());
            TimeUnit.MILLISECONDS.sleep(50);
            s.onCandle(after(last, 2), ctx);
            assertThat(s.getSetupContext().state).isEqualTo(SetupState.IN_TRADE);
        } finally {
            bus.stop();
        }
    }
}
