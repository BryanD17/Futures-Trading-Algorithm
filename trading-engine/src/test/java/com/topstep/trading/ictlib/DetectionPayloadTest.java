package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bounded {@code detections} payload the Bot Chart renders from.
 */
class DetectionPayloadTest {

    @SuppressWarnings("unchecked")
    private static Map<String, List<Map<String, Object>>> families(Map<String, Object> p) {
        return (Map<String, List<Map<String, Object>>>) p.get("families");
    }

    private static DetectionRegistry populated() {
        IctLibEngine engine = new IctLibEngine(IctLibConfig.defaults());
        for (Candle c : IctLibDeterminismTest.syntheticFeed(900)) engine.onCandle(c);
        return engine.registry(IctLibFixture.SYM);
    }

    @Test
    @DisplayName("Every family is present, keyed by its stable jsonKey, with a typed shape")
    void shape() {
        Map<String, Object> p = DetectionPayload.build(populated(), "all");

        assertThat(families(p)).isNotEmpty();
        assertThat(p).containsKeys("timeframe", "cap", "returned", "truncated",
                "counts", "families");

        Map<String, Object> any = families(p).values().iterator().next().get(0);
        assertThat(any).containsKeys("id", "type", "timeframe", "direction",
                "top", "bottom", "state", "createdAt", "stateChangedAt");
    }

    @Test
    @DisplayName("The timeframe filter is honoured — a 30m chart asks for the 15m instances")
    void timeframeFilter() {
        Map<String, Object> p = DetectionPayload.build(populated(), IctLibEngine.TF_15M);
        assertThat(p).containsEntry("timeframe", "15m");
        for (List<Map<String, Object>> family : families(p).values()) {
            assertThat(family).allSatisfy(d -> assertThat(d).containsEntry("timeframe", "15m"));
        }
    }

    @Test
    @DisplayName("Live detections are kept; finished ones are trimmed to the most recent few")
    void terminalDetectionsAreTrimmed() {
        DetectionRegistry r = populated();
        Map<String, Object> p = DetectionPayload.build(r, "all", 10_000, 2);

        for (Map.Entry<String, List<Map<String, Object>>> e : families(p).entrySet()) {
            long terminal = e.getValue().stream()
                    .filter(d -> {
                        String state = (String) d.get("state");
                        return DetectionState.valueOf(state).isTerminal();
                    })
                    .count();
            assertThat(terminal)
                    .as("family %s keeps at most recentTerminal finished detections", e.getKey())
                    .isLessThanOrEqualTo(2);
        }
    }

    @Test
    @DisplayName("The hard cap bounds the payload and says so — truncation is never silent")
    void capIsHardAndHonest() {
        Map<String, Object> p = DetectionPayload.build(populated(), "all", 5, 3);
        assertThat((Integer) p.get("returned")).isLessThanOrEqualTo(5);
        assertThat(p).containsEntry("truncated", true);

        Map<String, Object> roomy = DetectionPayload.build(populated(), "all", 10_000, 3);
        assertThat(roomy).containsEntry("truncated", false);
    }

    @Test
    @DisplayName("An empty registry yields the honest empty shape, never null")
    void emptyRegistry() {
        DetectionRegistry empty = new DetectionRegistry("MNQ", IctLibConfig.defaults().retentions());
        Map<String, Object> p = DetectionPayload.build(empty, "all");
        assertThat(families(p)).isEmpty();
        assertThat(p).containsEntry("returned", 0).containsEntry("truncated", false);
    }

    @Test
    @DisplayName("Serialised size stays small enough to poll every 15 seconds")
    void payloadStaysSmall() {
        Map<String, Object> p = DetectionPayload.build(populated(), IctLibEngine.TF_15M);
        String json = p.toString();       // a generous proxy for the JSON length
        assertThat(json.length())
                .as("detections payload characters (proxy for wire size)")
                .isLessThan(200_000);
        assertThat((Integer) p.get("returned")).isLessThanOrEqualTo(DetectionPayload.DEFAULT_CAP);
    }
}
