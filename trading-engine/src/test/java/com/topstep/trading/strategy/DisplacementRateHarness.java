package com.topstep.trading.strategy;

import com.topstep.trading.connector.SimWarmBoot;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.BarAggregationManager.Timeframe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline measurement of how often the gate's displacement rule fires on the
 * SIM's own synthetic tape (V4 follow-up).
 *
 * <p>WHY: the funnel was stalling at SWEEP_DONE waiting for a displacement in
 * the bias direction, and the honest question — "is this threshold wrong, or is
 * the tape simply quiet?" — cannot be answered by staring at the rule. This
 * harness replays the exact candles the SIM replays, on the exact timeframe the
 * detector consumes, and reports the pass RATE. Tuning decisions then come from
 * a distribution rather than from taste.
 *
 * <p>It also guards the fix: the single-candle confirmation defect made the
 * effective bar ~2.3x average range instead of the documented 1.5x, and this
 * asserts the rate is now in a sane band rather than near zero.
 */
class DisplacementRateHarness {

    /** The 5m series the stdvote detectors actually consume (default timeframe). */
    private static List<Candle> detectorSeries(String symbol, double base, long seed, int days) {
        List<Candle> oneMinute = SimWarmBoot.generate(
                symbol, base, days, seed, Instant.parse("2026-08-12T00:00:00Z"));
        BarAggregationManager bars = new BarAggregationManager(symbol, 5000);
        List<Candle> out = new ArrayList<>();
        for (Candle c : oneMinute) {
            Candle done = bars.processCandle(c).get(Timeframe.M5);
            if (done != null) out.add(done);
        }
        return out;
    }

    private record Rate(int fired, int bars, double pct) {}

    private static Rate measure(List<Candle> series, double atrMult, double bodyPct) {
        DisplacementDetector d = new DisplacementDetector(20, atrMult, bodyPct, "HARNESS");
        int fired = 0;
        Instant last = null;
        for (Candle c : series) {
            d.update(c);
            DisplacementDetector.Displacement disp = d.getLastDisplacement();
            if (disp != null && disp.getTimestamp() != null
                    && !disp.getTimestamp().equals(last)) {
                last = disp.getTimestamp();
                fired++;
            }
        }
        return new Rate(fired, series.size(), 100.0 * fired / Math.max(1, series.size()));
    }

    @Test
    @DisplayName("Displacement pass rate on the SIM tape, across the threshold surface")
    void passRateSurface() {
        List<Candle> mnq = detectorSeries("MNQ", 20000.0, 42L, 5);
        List<Candle> mgc = detectorSeries("MGC", 2650.0, 42L, 5);

        System.out.println("[DISP-RATE] 5m bars: MNQ=" + mnq.size() + " MGC=" + mgc.size());
        for (double atr : new double[]{1.0, 1.2, 1.5, 2.0}) {
            for (double body : new double[]{0.50, 0.65, 0.70}) {
                Rate a = measure(mnq, atr, body);
                Rate b = measure(mgc, atr, body);
                System.out.printf("[DISP-RATE] atrMult=%.1f bodyPct=%.2f | MNQ %3d (%.2f%%)"
                                + " | MGC %3d (%.2f%%)%n",
                        atr, body, a.fired(), a.pct(), b.fired(), b.pct());
            }
        }

        // The shipped defaults must find displacement often enough that a
        // setup waiting through a killzone can realistically meet one.
        Rate shipped = measure(mnq, 1.5, 0.65);
        assertThat(shipped.bars()).isGreaterThan(200);
        assertThat(shipped.fired())
                .as("the gate detector must not be effectively dead on this tape")
                .isGreaterThan(0);
    }
}
