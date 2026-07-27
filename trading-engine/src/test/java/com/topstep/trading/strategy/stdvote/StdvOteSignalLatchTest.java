package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.PositionClosedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-07-27 no-trade fix — the post-emission latch must always release.
 *
 * <p>The core enters IN_TRADE (and the scalp runner sets positionOpen) at
 * EMISSION time, before the engine decides whether an order actually
 * reaches the market. Two release paths are tested here: the synthetic
 * PositionClosedEvent published by the engine runners for vetoed/suppressed
 * signals, and the runner's own entry-fill timeout for resting limits that
 * never fill. Without them, one dead signal parked the symbol until a
 * process restart.
 */
@DisplayName("Signal latch release (vetoed/unfilled emissions must re-arm)")
class StdvOteSignalLatchTest {

    @BeforeEach
    void scalpMode() {
        System.setProperty("scalpMode.enabled", "true");
    }

    @AfterEach
    void clearProps() {
        System.clearProperty("scalpMode.enabled");
        System.clearProperty("stdvOte.entryTimeoutBars");
    }

    /** In-killzone 1m candles (Tuesday 10:00 ET = 14:00 UTC in June). */
    private static Candle candleAt(int minuteOffset) {
        return new Candle("MNQ",
                Instant.parse("2026-06-23T14:00:00Z").plus(minuteOffset, ChronoUnit.MINUTES),
                20000.0, 20001.0, 19999.0, 20000.0, 100);
    }

    @Test
    @DisplayName("entry-fill timeout: IN_TRADE with no position releases and invalidates")
    void entryTimeoutReleasesLatch() {
        System.setProperty("stdvOte.entryTimeoutBars", "10"); // small for the test
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy("MNQ", "MES", new EventBus());
        // Simulate the post-emission latch (what tryEmit leaves behind).
        s.getSetupContext().state = SetupState.IN_TRADE;
        s.latchForTest();

        for (int i = 0; i < 12; i++) {
            s.onCandle(candleAt(i), null); // null context = no position visible
        }
        assertThat(s.isPositionOpenForTest()).isFalse();
        assertThat(s.getSetupContext().lastGateFailed).contains("entry not filled");
        assertThat(s.getSetupContext().state).isNotEqualTo(SetupState.IN_TRADE);
        s.shutdown();
    }

    @Test
    @DisplayName("synthetic PositionClosedEvent (engine veto release) frees the latch")
    void syntheticCloseReleasesLatch() {
        EventBus bus = new EventBus();
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy("MNQ", "MES", bus);
        s.getSetupContext().state = SetupState.IN_TRADE;
        s.latchForTest();

        // What LiveEngineRunner/SimEngineRunner publish on a vetoed signal.
        bus.publish(new PositionClosedEvent("MNQ", 0.0, false, Instant.now()));

        // Give the async bus a beat, then let the candle thread apply it.
        long deadline = System.currentTimeMillis() + 3000;
        int i = 0;
        while (s.isPositionOpenForTest() && System.currentTimeMillis() < deadline) {
            s.onCandle(candleAt(i++), null);
        }
        assertThat(s.isPositionOpenForTest()).isFalse();
        s.shutdown();
    }

    @Test
    @DisplayName("timeout disabled (0) leaves the historical behavior in place")
    void timeoutDisableSwitch() {
        System.setProperty("stdvOte.entryTimeoutBars", "0");
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy("MNQ", "MES", new EventBus());
        s.getSetupContext().state = SetupState.IN_TRADE;
        s.latchForTest();
        for (int i = 0; i < 50; i++) {
            s.onCandle(candleAt(i), null);
        }
        assertThat(s.isPositionOpenForTest()).isTrue(); // latch intentionally held
        s.shutdown();
    }
}
