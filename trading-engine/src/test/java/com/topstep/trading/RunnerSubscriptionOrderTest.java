package com.topstep.trading;

import com.topstep.trading.event.EngineTelemetry;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.GateDecisionEvent;
import com.topstep.trading.event.StrategySignalEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V5 Agent 01, task 7 (D-14): every runner handler must be subscribed — and
 * the EventBus running — BEFORE the connector publishes its first candle;
 * a publish while the bus is stopped is an ERROR with a counter, not a WARN.
 */
@DisplayName("Runner wiring: handlers subscribed before the first candle (V5 Agent 01)")
class RunnerSubscriptionOrderTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("sim.warmBoot");
        System.clearProperty("backfill.days");
        System.clearProperty("mock.candleIntervalMs");
        EngineTelemetry.resetForTests();
    }

    @Test
    @DisplayName("SimEngineRunner: StrategySignalEvent handler subscribed and bus running when the first candle arrives")
    void handlersSubscribedBeforeFirstCandle() throws Exception {
        // Warm boot ON (1 day): the first candles are replayed SYNCHRONOUSLY
        // inside the connector subscription — the earliest possible moment a
        // candle can reach the runner.
        System.setProperty("sim.warmBoot", "true");
        System.setProperty("backfill.days", "1");
        System.setProperty("mock.candleIntervalMs", "600000"); // no live ticks during the test

        SimEngineRunner runner = new SimEngineRunner();
        // Constructed, not started: the handler is already on the bus, no candle yet.
        assertThat(runner.getEventBus().handlerCount(StrategySignalEvent.class)).isGreaterThanOrEqualTo(1);
        assertThat(runner.hasSeenFirstCandle()).isFalse();
        assertThat(runner.getEventBus().isRunning()).isFalse();

        Thread t = new Thread(runner::start, "test-sim-runner");
        t.setDaemon(true);
        t.start();
        long deadline = System.currentTimeMillis() + 120_000;
        while (!runner.hasSeenFirstCandle() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        try {
            assertThat(runner.hasSeenFirstCandle()).as("a first candle was delivered").isTrue();
            assertThat(runner.wasWiredBeforeFirstCandle())
                    .as("handler subscribed + EventBus running before the first candle").isTrue();
            assertThat(EngineTelemetry.errorCounts()).doesNotContainKey("SimEngineRunner.wiring");
        } finally {
            while (!runner.isRunning() && t.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            runner.stop();
            t.join(30_000);
        }
    }

    @Test
    @DisplayName("assertWiredForCandles throws when the bus is not running")
    void assertWiredThrowsWhenBusStopped() {
        System.setProperty("sim.warmBoot", "false");
        SimEngineRunner runner = new SimEngineRunner();
        assertThatThrownBy(runner::assertWiredForCandles)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EventBus not running");
    }

    @Test
    @DisplayName("EventBus.publish while stopped is an ERROR with a counter (not a silent WARN)")
    void publishWhileStoppedIsCounted() {
        EventBus bus = new EventBus(1);
        bus.publish(new GateDecisionEvent("MNQ", Instant.parse("2026-09-28T19:05:00Z"),
                "NY_PM", "SIGNAL", "RISK", "test", 1.0, 2.0));
        assertThat(bus.getDroppedNotRunning()).isEqualTo(1);
        assertThat(EngineTelemetry.errorCounts()).containsEntry("EventBus.publish.notRunning", 1L);
    }
}
