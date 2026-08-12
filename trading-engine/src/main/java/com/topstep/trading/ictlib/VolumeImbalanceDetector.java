package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;

/**
 * §S4 — VOLUME IMBALANCE: a gap between the BODIES of two consecutive candles
 * whose WICKS still overlap. It is the small inefficiency that is not a full
 * opening gap — price left a body-void behind but never actually gapped.
 *
 * <pre>
 *   Bullish (between i-1 and i), all five must hold:
 *     (1) o[i]   &gt; c[i-1]        opens above prior close
 *     (2) c[i]   &gt; c[i-1]        closes above prior close
 *     (3) o[i]   &gt; o[i-1]        opens above prior open
 *     (4) h[i-1] &gt; l[i]          wicks overlap — NOT an opening gap
 *     (5) h[i-1] &lt; bodyBot[i]    prior wick high below the current body
 *   Zone = [bodyTop[i-1], bodyBot[i]]
 * </pre>
 *
 * <p>Condition (4) is the one that is easy to lose: without it, a genuine
 * session gap would be misfiled as a volume imbalance. Every inequality above
 * has a dedicated near-miss negative test, because five chained comparisons is
 * exactly where a silent sign error survives review (risk G-R4).
 *
 * <p>Lifecycle: ACTIVE → FILLED (terminal) when a later candle's RANGE fully
 * covers the zone. "Fully covers" and not "touches": a body-void is repaired
 * when price has traded through all of it, and a wick poking in has not done
 * that.
 *
 * <p>Retention: 6 (§S4, not per side).
 */
public final class VolumeImbalanceDetector implements FamilyDetector {

    private final IctLibConfig config;

    public VolumeImbalanceDetector(IctLibConfig config) {
        this.config = config;
    }

    @Override
    public DetectionType family() {
        return DetectionType.VOLUME_IMBALANCE;
    }

    @Override
    public void onBar(TimeframeSeries series, DetectionRegistry registry) {
        advanceLifecycles(series, registry);
        detect(series, registry);
    }

    private void advanceLifecycles(TimeframeSeries series, DetectionRegistry registry) {
        Candle c = series.at(0);
        if (c == null) return;
        long bar = series.barIndex();
        for (MutableDetection d : registry.mutableView(
                DetectionType.VOLUME_IMBALANCE, series.timeframe())) {
            if (d.terminal() || d.createdAtBar() >= bar) continue;
            if (c.getLow() <= d.priceBottom() && c.getHigh() >= d.priceTop()) {
                d.advanceTo(DetectionState.FILLED, c.getTimestamp(), bar);
            }
        }
    }

    private void detect(TimeframeSeries series, DetectionRegistry registry) {
        Candle cur = series.at(0);
        Candle prev = series.at(1);
        if (cur == null || prev == null) return;

        if (isBullish(prev, cur)) {
            register(series, registry, DetectionDirection.BULLISH,
                    TimeframeSeries.bodyTop(prev), TimeframeSeries.bodyBot(cur));
        } else if (isBearish(prev, cur)) {
            register(series, registry, DetectionDirection.BEARISH,
                    TimeframeSeries.bodyTop(cur), TimeframeSeries.bodyBot(prev));
        }
    }

    /** §S4 bullish — each conjunct is one numbered condition, in spec order. */
    static boolean isBullish(Candle prev, Candle cur) {
        return cur.getOpen() > prev.getClose()                       // (1)
                && cur.getClose() > prev.getClose()                  // (2)
                && cur.getOpen() > prev.getOpen()                    // (3)
                && prev.getHigh() > cur.getLow()                     // (4)
                && prev.getHigh() < TimeframeSeries.bodyBot(cur);    // (5)
    }

    /** §S4 bearish — the exact mirror. */
    static boolean isBearish(Candle prev, Candle cur) {
        return cur.getOpen() < prev.getClose()                       // (1)
                && cur.getClose() < prev.getClose()                  // (2)
                && cur.getOpen() < prev.getOpen()                    // (3)
                && prev.getLow() < cur.getHigh()                     // (4)
                && prev.getLow() > TimeframeSeries.bodyTop(cur);     // (5)
    }

    private void register(TimeframeSeries series, DetectionRegistry registry,
                          DetectionDirection direction, double bottom, double top) {
        Candle c = series.at(0);
        MutableDetection d = registry.create(DetectionType.VOLUME_IMBALANCE,
                series.timeframe(), direction, bottom, top,
                series.barIndex(), c.getTimestamp(), DetectionState.ACTIVE);
        // Display-only hint (§S4 projectBars): how far right the marker is
        // drawn. Carried as meta so the renderer never invents its own number.
        d.putMeta("projectBars", config.viProjectBars);
    }
}
