package com.topstep.trading.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V5 Agent 01 (RC-17): GateDecisionEvent ring + counters. */
@DisplayName("EngineTelemetry / GateDecisionEvent (V5 Agent 01)")
class EngineTelemetryTest {

    @AfterEach
    void cleanup() {
        EngineTelemetry.resetForTests();
    }

    @Test
    @DisplayName("keeps the last 200 decisions, oldest first, and counts per gate")
    void ringKeepsLast200() {
        Instant t0 = Instant.parse("2026-09-28T13:30:00Z");
        for (int i = 0; i < 250; i++) {
            EngineTelemetry.record(new GateDecisionEvent("MNQ", t0.plusSeconds(60L * i),
                    "NY_AM", "SWEEP_DONE", i % 2 == 0 ? "SETUP" : "RISK", "r" + i, i, -i));
        }
        List<GateDecisionEvent> recent = EngineTelemetry.recent(EngineTelemetry.RING_CAPACITY);
        assertThat(recent).hasSize(200);
        assertThat(recent.get(0).getReason()).isEqualTo("r50");
        assertThat(recent.get(199).getReason()).isEqualTo("r249");
        assertThat(EngineTelemetry.gateCounts()).containsEntry("SETUP", 125L).containsEntry("RISK", 125L);
    }

    @Test
    @DisplayName("row carries symbol, candle time, session, state, gate, reason and two finite numbers")
    void rowShape() {
        GateDecisionEvent e = new GateDecisionEvent("MNQ", Instant.parse("2026-09-28T19:05:00Z"),
                EngineTelemetry.sessionOf(Instant.parse("2026-09-28T19:05:00Z")), "SIGNAL", "RISK",
                "R:R too low", Double.NaN, 3.0);
        assertThat(e.getSession()).isEqualTo("NY_PM"); // 15:05 ET
        assertThat(e.getNumberA()).isEqualTo(0.0);     // NaN never reaches the JSON
        assertThat(e.getType()).isEqualTo(EventType.GATE_DECISION);
        assertThat(e.toApiMap()).containsKeys("symbol", "candleTime", "session", "state",
                "gate", "reason", "numberA", "numberB");
    }

    @Test
    @DisplayName("publish on a running bus delivers the event to GateDecisionEvent subscribers")
    void publishDelivers() throws Exception {
        EventBus bus = new EventBus(1);
        java.util.concurrent.CountDownLatch got = new java.util.concurrent.CountDownLatch(1);
        bus.subscribe(GateDecisionEvent.class, ev -> got.countDown());
        bus.start();
        try {
            EngineTelemetry.publish(bus, new GateDecisionEvent("MGC", Instant.now(), "LONDON",
                    "SIGNAL", "WARMUP", "warmup suppression", 2.0, 5.0));
            assertThat(got.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            bus.stop();
        }
        assertThat(EngineTelemetry.recent(1).get(0).getGate()).isEqualTo("WARMUP");
    }
}
