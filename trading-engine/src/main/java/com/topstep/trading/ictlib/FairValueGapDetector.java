package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;

import java.util.List;

/**
 * §S2 — FAIR VALUE GAP (FVG) / INVERSE (IFVG), with the full lifecycle.
 *
 * <p>Named {@code FairValueGapDetector} to sit beside — never on top of — the
 * gate-side {@code com.topstep.trading.strategy.FvgDetector}, which stays
 * exactly as it is (V4 anti-pattern C3). The two differ in ONE decisive way:
 *
 * <pre>
 *   legacy : bullish gap  ⇔  l[i] &gt; h[i-2]
 *   §S2    : bullish gap  ⇔  l[i] &gt; h[i-2]  AND  displacementUp[i-1]
 * </pre>
 *
 * The displacement requirement is the whole point (Appendix E3): naive
 * three-candle gaps fire constantly on quiet tape, while a gap left behind by
 * an energetic candle is the one institutional flow actually leaves. ictlib
 * will therefore report STRICTLY FEWER gaps than the legacy detector, and
 * {@link IctLibDiffStats} measures by how many. That is the feature, not a bug
 * — see Appendix J.
 *
 * <p>Lifecycle (bullish; bearish mirrors on highs):
 * ACTIVE → TOUCHED (l[j] &lt; zoneTop) → FILLED (l[j] &lt; zoneBottom, terminal).
 * Monotonic; the right edge freezes on FILLED.
 *
 * <p>SPEC DECISION (§S2, IFVG zone geometry): the spec inverts the gap
 * comparison for IFVG mode but does not restate the zone. Both modes therefore
 * build the zone from the SAME two levels — h[i-2] and l[i] for a bullish
 * read — taking min as bottom and max as top. In FVG mode that reproduces the
 * spec's [h[i-2], l[i]] exactly; in IFVG mode it yields the overlap region the
 * spec describes. One formula, no second definition to drift.
 */
public final class FairValueGapDetector implements FamilyDetector {

    private final IctLibConfig config;

    public FairValueGapDetector(IctLibConfig config) {
        this.config = config;
    }

    @Override
    public DetectionType family() {
        return DetectionType.FVG;
    }

    @Override
    public void onBar(TimeframeSeries series, DetectionRegistry registry) {
        advanceLifecycles(series, registry);
        detect(series, registry);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LIFECYCLE — runs first, and only on zones created by EARLIER bars
    // ═══════════════════════════════════════════════════════════════════════

    private void advanceLifecycles(TimeframeSeries series, DetectionRegistry registry) {
        Candle c = series.at(0);
        if (c == null) return;
        long bar = series.barIndex();

        for (MutableDetection d : registry.mutableView(DetectionType.FVG, series.timeframe())) {
            if (d.terminal() || d.createdAtBar() >= bar) continue;

            if (d.direction().isBullish()) {
                // Bullish gap sits below price; it is consumed from above.
                if (c.getLow() < d.priceBottom()) {
                    d.advanceTo(DetectionState.FILLED, c.getTimestamp(), bar);
                } else if (c.getLow() < d.priceTop()) {
                    d.advanceTo(DetectionState.TOUCHED, c.getTimestamp(), bar);
                }
            } else {
                // Bearish gap sits above price; it is consumed from below.
                if (c.getHigh() > d.priceTop()) {
                    d.advanceTo(DetectionState.FILLED, c.getTimestamp(), bar);
                } else if (c.getHigh() > d.priceBottom()) {
                    d.advanceTo(DetectionState.TOUCHED, c.getTimestamp(), bar);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DETECTION — the three-candle pattern (i-2, i-1, i)
    // ═══════════════════════════════════════════════════════════════════════

    private void detect(TimeframeSeries series, DetectionRegistry registry) {
        Candle c0 = series.at(0);
        Candle c2 = series.at(2);
        if (c0 == null || c2 == null) return;

        boolean inverse = config.gapMode == IctLibConfig.GapMode.IFVG;

        boolean dispUp = DisplacementRule.isDisplacementUp(
                series, 1, config.displacementMeanLen, config.displacementWickRatioMax);
        if (dispUp) {
            boolean gap = inverse ? (c0.getLow() < c2.getHigh()) : (c0.getLow() > c2.getHigh());
            if (gap) {
                register(series, registry, DetectionDirection.BULLISH,
                        c2.getHigh(), c0.getLow());
            }
        }

        boolean dispDown = DisplacementRule.isDisplacementDown(
                series, 1, config.displacementMeanLen, config.displacementWickRatioMax);
        if (dispDown) {
            boolean gap = inverse ? (c0.getHigh() > c2.getLow()) : (c0.getHigh() < c2.getLow());
            if (gap) {
                register(series, registry, DetectionDirection.BEARISH,
                        c0.getHigh(), c2.getLow());
            }
        }
    }

    /**
     * Store the gap — or, when a same-direction gap was created on the
     * IMMEDIATELY preceding bar and is still live, widen that one to the union
     * of both zones (§S2 consecutive-gap merge). Merging keeps one continuous
     * imbalance as one fact instead of a stack of adjacent slivers the chart
     * would have to draw on top of each other.
     */
    private void register(TimeframeSeries series, DetectionRegistry registry,
                          DetectionDirection direction, double levelA, double levelB) {
        Candle c = series.at(0);
        long bar = series.barIndex();
        double bottom = Math.min(levelA, levelB);
        double top = Math.max(levelA, levelB);

        List<MutableDetection> existing =
                registry.mutableView(DetectionType.FVG, series.timeframe());
        for (int i = existing.size() - 1; i >= 0; i--) {
            MutableDetection prev = existing.get(i);
            if (prev.direction() != direction) continue;
            if (prev.createdAtBar() != bar - 1) continue;
            if (prev.terminal()) continue;
            prev.widenTo(bottom, top);
            prev.putMeta("merged", Boolean.TRUE);
            prev.putMeta("mergedAtBar", bar);
            return;
        }

        MutableDetection d = registry.create(DetectionType.FVG, series.timeframe(),
                direction, bottom, top, bar, c.getTimestamp(), DetectionState.ACTIVE);
        d.putMeta("mode", config.gapMode.name());
    }
}
