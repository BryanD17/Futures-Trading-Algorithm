package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;

/**
 * §S1 — registers DISPLACEMENT point detections.
 *
 * <p>A displacement has no zone and no lifecycle: it describes one candle, so
 * it is stored with {@link DetectionState#POINT} and the candle's own high/low
 * as its bounds (the chart needs somewhere to draw the marker; nothing treats
 * those bounds as a tradeable zone).
 *
 * <p>Retention: last 50 (§S1).
 *
 * <p>V5 Agent 04 (RC-09, "one displacement source"): this scanner is a CHART
 * MARKER only. It is NOT a gate input — the M5 displacement gate reads exactly
 * one detector, the calibrated
 * {@code com.topstep.trading.strategy.DisplacementDetector} constructed in
 * {@code StdvOteRunnerStrategy} (range &ge; displacement.atrMult &times; prior
 * ATR14, body &ge; displacement.bodyPct). The §S1 body-vs-mean-body rule here
 * is deliberately different and must never be wired into a gate
 * (listed under "UNPLUGGED PATHS" in docs/reports/autopsy_v5/A-04.md).
 */
public final class DisplacementScanner implements FamilyDetector {

    private final IctLibConfig config;

    public DisplacementScanner(IctLibConfig config) {
        this.config = config;
    }

    @Override
    public DetectionType family() {
        return DetectionType.DISPLACEMENT;
    }

    @Override
    public void onBar(TimeframeSeries series, DetectionRegistry registry) {
        Candle c = series.at(0);
        if (c == null) return;

        boolean up = DisplacementRule.isDisplacementUp(
                series, 0, config.displacementMeanLen, config.displacementWickRatioMax);
        boolean down = !up && DisplacementRule.isDisplacementDown(
                series, 0, config.displacementMeanLen, config.displacementWickRatioMax);
        if (!up && !down) return;

        MutableDetection d = registry.create(
                DetectionType.DISPLACEMENT, series.timeframe(),
                DetectionDirection.of(up),
                c.getLow(), c.getHigh(),
                series.barIndex(), c.getTimestamp(), DetectionState.POINT);
        d.putMeta("body", TimeframeSeries.body(c));
        d.putMeta("meanBody", DisplacementRule.meanBody(series, 0, config.displacementMeanLen));
        d.putMeta("close", c.getClose());
    }
}
