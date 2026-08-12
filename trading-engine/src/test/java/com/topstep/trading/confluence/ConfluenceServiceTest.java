package com.topstep.trading.confluence;

import com.topstep.trading.chart.ChartEngine;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.ictlib.IctLibConfig;
import com.topstep.trading.ictlib.IctLibEngine;
import com.topstep.trading.strategy.MarketBias;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The confluence stack: aggregation only, tri-state arithmetic, configurable
 * weights, determinism and serialisation.
 */
class ConfluenceServiceTest {

    private static final String SYM = "MNQ";
    private static final Instant T = Instant.parse("2026-08-11T14:00:00Z");

    private static EngineFacts allTrue() {
        return new EngineFacts(T, 21000.0, true, MarketBias.BULLISH, MarketBias.BULLISH,
                "vote=BULLISH(3/1/0) agree=true", true, "entry at discount",
                true, 7, "ARMED");
    }

    // ── TRI-STATE ARITHMETIC ───────────────────────────────────────────────

    @Test
    @DisplayName("A wholly cold stack is UNKNOWN everywhere: score 0, maxScore 0, ratio 0 — not NaN")
    void coldStackIsVisiblyCold() {
        ConfluenceService s = new ConfluenceService();
        ConfluenceSnapshot snap = s.snapshot(SYM, true, EngineFacts.cold(T), T);

        assertThat(snap.knownCount()).isZero();
        assertThat(snap.maxScore()).isZero();
        assertThat(snap.score()).isZero();
        assertThat(snap.ratio()).isZero();      // never NaN, never a divide by zero
        assertThat(snap.values().values()).allMatch(v -> v == Tri.UNKNOWN);
    }

    @Test
    @DisplayName("UNKNOWN is excluded from BOTH score and maxScore — it never counts against a direction")
    void unknownIsExcludedFromBothSides() {
        ConfluenceService s = new ConfluenceService();

        // Only the killzone answers; everything else is cold.
        EngineFacts onlyKillzone = new EngineFacts(T, null, true, null, null, null,
                null, null, null, null, null);
        ConfluenceSnapshot snap = s.snapshot(SYM, true, onlyKillzone, T);

        double kz = ConfluenceField.IN_TRADING_KILLZONE.weight();
        assertThat(snap.score()).isEqualTo(kz, within(1e-9));
        assertThat(snap.maxScore()).isEqualTo(kz, within(1e-9));
        assertThat(snap.ratio()).isEqualTo(1.0, within(1e-9));
        assertThat(snap.knownCount()).isEqualTo(1);

        // The same single TRUE with a FALSE beside it scores the same but out
        // of a LARGER maximum — that is the whole difference UNKNOWN makes.
        EngineFacts killzoneAndFlatBias = new EngineFacts(T, null, true,
                MarketBias.NEUTRAL, null, null, null, null, null, null, null);
        ConfluenceSnapshot snap2 = s.snapshot(SYM, true, killzoneAndFlatBias, T);
        assertThat(snap2.score()).isEqualTo(kz, within(1e-9));
        assertThat(snap2.maxScore())
                .isEqualTo(kz + ConfluenceField.HTF_BIAS_ALIGNED.weight(), within(1e-9));
        assertThat(snap2.ratio()).isLessThan(1.0);
    }

    @Test
    @DisplayName("Bias alignment is direction-aware; NEUTRAL is a real FALSE, not UNKNOWN")
    void biasAlignment() {
        ConfluenceService s = new ConfluenceService();
        EngineFacts bull = new EngineFacts(T, null, null, MarketBias.BULLISH, null, null,
                null, null, null, null, null);

        assertThat(s.snapshot(SYM, true, bull, T).values()
                .get(ConfluenceField.HTF_BIAS_ALIGNED)).isEqualTo(Tri.TRUE);
        assertThat(s.snapshot(SYM, false, bull, T).values()
                .get(ConfluenceField.HTF_BIAS_ALIGNED)).isEqualTo(Tri.FALSE);

        EngineFacts flat = new EngineFacts(T, null, null, MarketBias.NEUTRAL, null, null,
                null, null, null, null, null);
        assertThat(s.snapshot(SYM, true, flat, T).values()
                .get(ConfluenceField.HTF_BIAS_ALIGNED)).isEqualTo(Tri.FALSE);
    }

    @Test
    @DisplayName("The raid score is compared against a floor and the raw value is reported")
    void raidScoreFloor() {
        ConfluenceService s = new ConfluenceService();
        EngineFacts weak = new EngineFacts(T, null, null, null, null, null,
                null, null, true, 3, null);
        EngineFacts strong = new EngineFacts(T, null, null, null, null, null,
                null, null, true, 7, null);

        assertThat(s.snapshot(SYM, true, weak, T).values()
                .get(ConfluenceField.RAID_SCORE)).isEqualTo(Tri.FALSE);
        assertThat(s.snapshot(SYM, true, strong, T).values()
                .get(ConfluenceField.RAID_SCORE)).isEqualTo(Tri.TRUE);
        assertThat(s.snapshot(SYM, true, strong, T).details()
                .get(ConfluenceField.RAID_SCORE)).contains("7/10");
    }

    // ── AGGREGATION FROM THE REAL SOURCES ──────────────────────────────────

    /** A feed with enough shape to populate several ictlib families. */
    private static List<Candle> feed(int bars) {
        List<Candle> out = new ArrayList<>(bars);
        long state = 42L;
        double price = 21000.0;
        for (int i = 0; i < bars; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            int step = (int) ((state >>> 33) % 41) - 20;
            double open = price;
            double close = open + step * 0.25;
            double up = 0.25 + ((state >>> 41) % 17) * 0.25;
            double dn = 0.25 + ((state >>> 47) % 17) * 0.25;
            out.add(new Candle(SYM, T.plusSeconds(60L * i), open,
                    Math.max(open, close) + up, Math.min(open, close) - dn, close, 100L));
            price = close;
        }
        return out;
    }

    @Test
    @DisplayName("ictlib fields are read from the REGISTRY's stored state, not recomputed")
    void ictLibFactsComeFromTheRegistry() {
        IctLibEngine lib = new IctLibEngine(IctLibConfig.defaults());
        for (Candle c : feed(600)) lib.onCandle(c);

        ConfluenceService s = new ConfluenceService();
        s.setIctLibEngine(lib);
        s.registerInstrument(SYM, 0.25);

        ConfluenceSnapshot snap = s.snapshot(SYM, true, allTrue(), T);

        // Something in the ICT block must now be answerable — the registry has
        // hundreds of detections in it.
        assertThat(snap.values().get(ConfluenceField.ACTIVE_FVG_IN_DIRECTION)).isNotNull();
        assertThat(snap.values().get(ConfluenceField.STRUCTURE_STATE)).isIn(Tri.TRUE, Tri.FALSE);
        assertThat(snap.knownCount()).isGreaterThan(7);
        assertThat(snap.details().get(ConfluenceField.ACTIVE_FVG_IN_DIRECTION))
                .contains("gap(s)");
    }

    @Test
    @DisplayName("A cold ictlib reports UNKNOWN for every ICT field, never FALSE")
    void coldIctLibAbstains() {
        ConfluenceService s = new ConfluenceService();
        s.setIctLibEngine(new IctLibEngine(IctLibConfig.defaults()));   // never fed
        ConfluenceSnapshot snap = s.snapshot(SYM, true, allTrue(), T);

        assertThat(snap.values().get(ConfluenceField.ACTIVE_FVG_IN_DIRECTION)).isEqualTo(Tri.UNKNOWN);
        assertThat(snap.values().get(ConfluenceField.NEAREST_OB_ZONE)).isEqualTo(Tri.UNKNOWN);
        assertThat(snap.values().get(ConfluenceField.POOL_SWEPT_RECENTLY)).isEqualTo(Tri.UNKNOWN);
        assertThat(snap.details().get(ConfluenceField.ACTIVE_FVG_IN_DIRECTION))
                .isEqualTo("ictlib cold for MNQ");
        // …and the engine facts still count, so the stack is not silenced.
        assertThat(snap.values().get(ConfluenceField.IN_TRADING_KILLZONE)).isEqualTo(Tri.TRUE);
    }

    @Test
    @DisplayName("With no ChartEngine wired the chart fact is UNKNOWN, not FALSE")
    void chartFactAbstainsWhenUnwired() {
        ConfluenceService s = new ConfluenceService();
        ConfluenceSnapshot snap = s.snapshot(SYM, true, allTrue(), T);
        assertThat(snap.values().get(ConfluenceField.CHART_OTE_STATE)).isEqualTo(Tri.UNKNOWN);

        s.setChartEngine(new ChartEngine());     // wired but with no zone yet
        assertThat(s.snapshot(SYM, true, allTrue(), T).values()
                .get(ConfluenceField.CHART_OTE_STATE)).isEqualTo(Tri.UNKNOWN);
    }

    // ── WEIGHTS, TELEMETRY, SERIALISATION, DETERMINISM ─────────────────────

    @Test
    @DisplayName("Weights are configurable per field and a zero weight removes a field's influence")
    void weightsAreConfigurable() {
        String key = "confluence.weight." + ConfluenceField.IN_TRADING_KILLZONE.key();
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "0");
            assertThat(ConfluenceField.IN_TRADING_KILLZONE.weight()).isZero();

            ConfluenceService s = new ConfluenceService();
            EngineFacts onlyKillzone = new EngineFacts(T, null, true, null, null, null,
                    null, null, null, null, null);
            ConfluenceSnapshot snap = s.snapshot(SYM, true, onlyKillzone, T);
            assertThat(snap.score()).isZero();
            assertThat(snap.maxScore()).isZero();
            // The FIELD is still known and reported — only its weight is zero.
            assertThat(snap.values().get(ConfluenceField.IN_TRADING_KILLZONE)).isEqualTo(Tri.TRUE);
            assertThat(snap.knownCount()).isEqualTo(1);

            System.setProperty(key, "not-a-number");
            assertThat(ConfluenceField.IN_TRADING_KILLZONE.weight())
                    .isEqualTo(ConfluenceField.IN_TRADING_KILLZONE.defaultWeight());
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    @Test
    @DisplayName("[CONFLUENCE] line carries both directions and the heaviest TRUE fields")
    void logLineShape() {
        ConfluenceService s = new ConfluenceService();
        s.publish(SYM, allTrue());

        String line = s.logLine(SYM);
        assertThat(line).startsWith("[CONFLUENCE MNQ] long ");
        assertThat(line).contains(" short ");
        assertThat(line).contains(" top: ");
        assertThat(line).containsPattern("long \\d+/\\d+w=\\d\\.\\d\\d");
        // The long side is fully aligned here, so its heaviest TRUE fields lead.
        assertThat(line).contains("inTradingKillzone");
    }

    @Test
    @DisplayName("Serialisation exposes every field with its owner, weight, glyph and detail")
    void apiSerialisation() {
        ConfluenceService s = new ConfluenceService();
        Map<String, Object> m = s.snapshot(SYM, true, allTrue(), T).toApiMap();

        assertThat(m).containsKeys("symbol", "direction", "at", "score", "maxScore",
                "ratio", "trueCount", "knownCount", "fieldCount", "top", "fields");
        assertThat(m).containsEntry("direction", "LONG");
        assertThat(m).containsEntry("fieldCount", ConfluenceField.values().length);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) m.get("fields");
        assertThat(fields).hasSize(ConfluenceField.values().length);
        assertThat(fields.get(0)).containsKeys("key", "value", "glyph", "weight", "owner", "detail");
        assertThat(fields).allSatisfy(f ->
                assertThat(f.get("owner")).as("every field names its owner").isNotNull());
    }

    @Test
    @DisplayName("Determinism: the same inputs produce the same snapshot")
    void determinism() {
        IctLibEngine lib = new IctLibEngine(IctLibConfig.defaults());
        for (Candle c : feed(400)) lib.onCandle(c);

        ConfluenceService a = new ConfluenceService();
        a.setIctLibEngine(lib);
        a.registerInstrument(SYM, 0.25);
        ConfluenceService b = new ConfluenceService();
        b.setIctLibEngine(lib);
        b.registerInstrument(SYM, 0.25);

        ConfluenceSnapshot s1 = a.snapshot(SYM, true, allTrue(), T);
        ConfluenceSnapshot s2 = b.snapshot(SYM, true, allTrue(), T);

        assertThat(s2.values()).isEqualTo(s1.values());
        assertThat(s2.details()).isEqualTo(s1.details());
        assertThat(s2.score()).isEqualTo(s1.score());
        assertThat(s2.maxScore()).isEqualTo(s1.maxScore());
    }

    @Test
    @DisplayName("Long and short are computed independently — one direction never leaks into the other")
    void directionsAreIndependent() {
        ConfluenceService s = new ConfluenceService();
        ConfluenceSnapshot longs = s.snapshot(SYM, true, allTrue(), T);
        ConfluenceSnapshot shorts = s.snapshot(SYM, false, allTrue(), T);

        assertThat(longs.values().get(ConfluenceField.HTF_BIAS_ALIGNED)).isEqualTo(Tri.TRUE);
        assertThat(shorts.values().get(ConfluenceField.HTF_BIAS_ALIGNED)).isEqualTo(Tri.FALSE);
        assertThat(longs.score()).isGreaterThan(shorts.score());
        // Direction-free facts agree across both.
        assertThat(shorts.values().get(ConfluenceField.IN_TRADING_KILLZONE))
                .isEqualTo(longs.values().get(ConfluenceField.IN_TRADING_KILLZONE));
    }
}
