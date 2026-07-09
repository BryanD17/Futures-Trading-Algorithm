package com.topstep.trading.chart;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2 Agent 05: per-instrument tuning must change leg acceptance for THAT
 * symbol only — every other symbol keeps the engine's constructor
 * defaults, and with no overrides set behavior is identical to pre-V2
 * (which the UNCHANGED pre-existing ChartEngine tests prove).
 */
class ChartEnginePerInstrumentTuningTest {

    private static final Instant T0 = Instant.parse("2026-01-05T10:00:00Z");
    /** The V1 lifecycle price shape: dip origin 19990, leg to 20040 (50 pts). */
    private static final double[] BARS_30M = {
            20000, 19995, 19990, 19995, 20000, 20010, 20025, 20040, 20035, 20030
    };

    @AfterEach
    void cleanup() {
        System.clearProperty("chart.minLegTicks.MGC");
        System.clearProperty("chart.swingStrength.MGC");
        System.clearProperty("chart.zoneExpiryBars.MGC");
    }

    private static void feed(ChartEngine engine, String symbol) {
        for (int i = 0; i < BARS_30M.length; i++) {
            double p = BARS_30M[i];
            Instant ts = T0.plus(Duration.ofMinutes(30L * i));
            engine.onCandle(new Candle(symbol, ts, p, p, p, p, 1));
        }
        // Completing bar 9 triggers the rebuild that draws (or rejects) the zone.
        double last = 20030;
        Instant ts = T0.plus(Duration.ofMinutes(300));
        engine.onCandle(new Candle(symbol, ts, last, last, last, last, 1));
    }

    @Test
    void overrideChangesAcceptanceForThatSymbolOnly() {
        ChartEngine engine = new ChartEngine(); // defaults 2 / 40 / 32
        engine.registerInstrument("MNQ", 0.25);
        engine.registerInstrument("MES", 0.25);
        // Same 50-point leg; 200 ticks at 0.25. Raise ONLY MES's floor
        // above it (250 ticks = 62.5 pts) — MES must reject, MNQ must draw.
        engine.configureInstrument("MES", 250, 2, 32);

        feed(engine, "MNQ");
        feed(engine, "MES");

        assertTrue(engine.getActiveOteZone("MNQ").isPresent(),
                "default-tuned symbol must accept the 200-tick leg");
        assertTrue(engine.getActiveOteZone("MES").isEmpty(),
                "overridden symbol must reject the leg below ITS floor only");
    }

    @Test
    void systemPropertyTuningIsResolvedPerSymbol() {
        System.setProperty("chart.minLegTicks.MGC", "250");
        ChartEngine engine = new ChartEngine();
        engine.registerInstrument("MNQ", 0.25);
        engine.registerInstrument("MGC", 0.25); // same tick so the shape is comparable
        engine.applySystemPropertyTuning("MNQ"); // resolves to defaults (no props)
        engine.applySystemPropertyTuning("MGC"); // resolves the 250-tick override

        feed(engine, "MNQ");
        feed(engine, "MGC");

        assertTrue(engine.getActiveOteZone("MNQ").isPresent(),
                "no-property symbol keeps default acceptance");
        assertTrue(engine.getActiveOteZone("MGC").isEmpty(),
                "chart.minLegTicks.MGC must raise MGC's floor only");
    }
}
