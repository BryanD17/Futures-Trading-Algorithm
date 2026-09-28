package com.topstep.trading.strategy.stdvote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.chartstate.CandleSeries;
import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.LiquiditySweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 Agent 03 — task 4 (session-aware manipulation leg on G1) and task 7
 * (the documented starved-pipeline fallback).
 */
@DisplayName("V5 Agent 03 — manipulation leg (G1) + starved fallback")
class Agent03SweepLegTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    @AfterEach
    void cleanup() {
        StdvOteRegistry.unregister("MNQ");
        System.clearProperty("raid.starvedScore");
    }

    private static List<Candle> tape() throws Exception {
        try (InputStream in = Agent03SweepLegTest.class.getResourceAsStream("/tape/real_MNQ_1m.json")) {
            JsonNode arr = new ObjectMapper().readTree(in);
            List<Candle> out = new ArrayList<>(arr.size());
            for (JsonNode b : arr) {
                out.add(new Candle("MNQ", java.time.OffsetDateTime.parse(b.get("t").asText()).toInstant(),
                        b.get("o").asDouble(), b.get("h").asDouble(), b.get("l").asDouble(),
                        b.get("c").asDouble(), b.get("v").asLong()));
            }
            return out;
        }
    }

    @Test
    @DisplayName("G1: bearish manipulation leg found inside the NY PM session (retrace up into 30640)")
    void g1ManipulationLeg() throws Exception {
        SessionLegLocator loc = new SessionLegLocator();
        LevelEngine levels = new LevelEngine("MNQ", new CandleSeries("MNQ", 5000));
        Instant end = LocalDateTime.parse("2026-09-28T14:54").atZone(ET).toInstant();
        for (Candle c : tape()) {
            if (!c.getTimestamp().isBefore(end)) break;
            levels.processCandle(c);
            loc.onCandle(c);
        }
        assertThat(loc.currentSession()).isEqualTo("NY_PM");
        Optional<SessionLegLocator.Located> leg = loc.locate(false, levels.getAllLevels(), 0.25,
                StdvProjectionEngine.DEFAULT_MIN_LEG_TICKS);
        System.out.println("[A-03] G1 manipulation leg @14:53 (NY_PM session, bias BEARISH): " + leg);
        assertThat(leg).isPresent();
        // The counter-bias (upward) excursion of the session reaches the
        // 30640 London high the owner sells (14:53 high 30650).
        assertThat(leg.get().legHigh()).isGreaterThanOrEqualTo(30640.0);
        assertThat(leg.get().legLow()).isLessThan(leg.get().legHigh());
    }

    @Test
    @DisplayName("session windows: the locator uses Agent 02's SessionClassifier (always a session)")
    void alwaysASession() {
        SessionLegLocator loc = new SessionLegLocator();
        loc.onCandle(new Candle("MNQ", LocalDateTime.parse("2026-09-28T14:53").atZone(ET).toInstant(),
                1, 2, 0.5, 1.5, 1));
        assertThat(loc.currentSession()).isEqualTo("NY_PM");
        loc.onCandle(new Candle("MNQ", LocalDateTime.parse("2026-09-28T21:00").atZone(ET).toInstant(),
                1, 2, 0.5, 1.5, 1));
        assertThat(loc.currentSession()).isEqualTo("ASIA");
        assertThat(loc.size()).isEqualTo(1); // buffer restarts at each session boundary
    }

    @Test
    @DisplayName("task 7: an unscoreable sweep gets the DOCUMENTED base (floor - 1), never silently the floor")
    void starvedFallbackIsDocumentedAndBelowFloor() {
        StdvOteRunnerStrategy r = new StdvOteRunnerStrategy("MNQ", "MES", new EventBus());
        r.initialize();
        // A sweep whose candle is not in the series (pipeline starved).
        LiquiditySweep ghost = new LiquiditySweep(true, 20000, Instant.parse("2026-09-28T14:00:00Z"), false);
        assertThat(r.currentRaidScore(ghost)).isEqualTo(TradeableInstrument.of(
                TradeableInstrument.Symbol.MNQ).raidMinQuality() - 1);
        System.setProperty("raid.starvedScore", "3");
        assertThat(r.currentRaidScore(ghost)).isEqualTo(3);
        r.shutdown();
    }
}
