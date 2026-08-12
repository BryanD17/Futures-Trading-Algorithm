package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * §S3 — BALANCED PRICE RANGE: the price region where an active bullish gap and
 * an active bearish gap overlap. Two opposing imbalances covering the same
 * prices is a much stronger magnet than either gap alone, which is why it gets
 * its own family rather than being derived at render time.
 *
 * <pre>
 *   low  = max(bullZoneBottom, bearZoneBottom)
 *   high = min(bullZoneTop,    bearZoneTop)      valid iff low &lt; high
 * </pre>
 *
 * <p>SPEC DECISION 1 (orientation). §S3 gives a geometric rule ("the bullish
 * gap sits above the bearish gap's bottom with the bullish gap created LATER")
 * and then an explicit fallback ("if both orderings are ambiguous, orientation
 * = direction of the LATER-created gap"). The two agree wherever the geometric
 * rule decides anything, so this implementation uses the fallback as the single
 * rule: the LATER gap is the one doing the repricing, and one rule cannot drift
 * from itself.
 *
 * <p>SPEC DECISION 2 (pos = 0 at creation). The spec derives the invalidating
 * side from where price sat when the region formed, but does not say which side
 * is "far" when price formed INSIDE the region (pos = 0). Decision: fall back to
 * the orientation — a bullish BPR is invalidated by a close below its low, a
 * bearish one by a close above its high. First principles: the region's job is
 * to hold in the direction it was created for, so failure means closing through
 * it against that direction. Such a detection starts at TOUCHED, since price is
 * already inside it.
 *
 * <p>Lifecycle: ACTIVE → TOUCHED (price re-enters from the pos side) → BROKEN
 * (a close fully through to the far side; terminal, right edge frozen).
 */
public final class BprDetector implements FamilyDetector {

    private static final String META_POS = "pos";
    private static final String META_BULL_GAP = "bullGapId";
    private static final String META_BEAR_GAP = "bearGapId";

    /** Newest gap pairs already turned into a region — prevents a BPR per bar. */
    private final Set<String> pairsUsed = new LinkedHashSet<>() {
        @Override
        public boolean add(String key) {
            boolean added = super.add(key);
            // Bounded: gap ids are monotonic, so the oldest keys can never
            // recur and holding them forever would be a slow leak (C7).
            if (size() > 256) {
                java.util.Iterator<String> it = iterator();
                it.next();
                it.remove();
            }
            return added;
        }
    };

    @Override
    public DetectionType family() {
        return DetectionType.BPR;
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

        for (MutableDetection d : registry.mutableView(DetectionType.BPR, series.timeframe())) {
            if (d.terminal() || d.createdAtBar() >= bar) continue;
            int pos = ((Number) d.meta().getOrDefault(META_POS, 0)).intValue();
            boolean farSideIsBelow = (pos != 0)
                    ? pos > 0
                    : d.direction().isBullish();

            if (farSideIsBelow) {
                if (c.getClose() < d.priceBottom()) {
                    d.advanceTo(DetectionState.BROKEN, c.getTimestamp(), bar);
                } else if (c.getLow() <= d.priceTop()) {
                    d.advanceTo(DetectionState.TOUCHED, c.getTimestamp(), bar);
                }
            } else {
                if (c.getClose() > d.priceTop()) {
                    d.advanceTo(DetectionState.BROKEN, c.getTimestamp(), bar);
                } else if (c.getHigh() >= d.priceBottom()) {
                    d.advanceTo(DetectionState.TOUCHED, c.getTimestamp(), bar);
                }
            }
        }
    }

    private void detect(TimeframeSeries series, DetectionRegistry registry) {
        Candle c = series.at(0);
        if (c == null) return;

        MutableDetection bull = newestUntouchedGap(series, registry, DetectionDirection.BULLISH);
        MutableDetection bear = newestUntouchedGap(series, registry, DetectionDirection.BEARISH);
        if (bull == null || bear == null) return;

        String pairKey = series.timeframe() + "|" + bull.id() + "|" + bear.id();
        if (pairsUsed.contains(pairKey)) return;

        double low = Math.max(bull.priceBottom(), bear.priceBottom());
        double high = Math.min(bull.priceTop(), bear.priceTop());
        // Only a pair that actually PRODUCED a region is retired. A pair that
        // does not overlap yet may overlap later — §S2's consecutive-gap merge
        // widens a zone in place, keeping its id — so it must stay eligible.
        if (!(low < high)) return;
        pairsUsed.add(pairKey);

        DetectionDirection orientation = (bull.createdAtBar() >= bear.createdAtBar())
                ? DetectionDirection.BULLISH : DetectionDirection.BEARISH;

        int pos = c.getClose() > high ? 1 : (c.getClose() < low ? -1 : 0);
        DetectionState initial = (pos == 0) ? DetectionState.TOUCHED : DetectionState.ACTIVE;

        long bar = series.barIndex();
        MutableDetection d = registry.create(DetectionType.BPR, series.timeframe(),
                orientation, low, high, bar, c.getTimestamp(), initial);
        d.putMeta(META_POS, pos);
        d.putMeta(META_BULL_GAP, bull.id());
        d.putMeta(META_BEAR_GAP, bear.id());
    }

    /**
     * Most recently created gap of one direction that is still literally
     * ACTIVE. §S3 says ACTIVE, not "not terminal": a gap price has already
     * traded back into is being consumed, and pairing it would draw regions
     * around imbalances that are half spent.
     */
    private MutableDetection newestUntouchedGap(TimeframeSeries series,
                                                DetectionRegistry registry,
                                                DetectionDirection direction) {
        List<MutableDetection> gaps =
                registry.mutableView(DetectionType.FVG, series.timeframe());
        for (int i = gaps.size() - 1; i >= 0; i--) {
            MutableDetection g = gaps.get(i);
            if (g.direction() == direction && g.state() == DetectionState.ACTIVE) return g;
        }
        return null;
    }
}
