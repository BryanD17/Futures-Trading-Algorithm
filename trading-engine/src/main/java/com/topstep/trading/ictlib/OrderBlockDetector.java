package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;

/**
 * §S7 — ORDER BLOCKS WITH BREAKER LIFECYCLE.
 *
 * <p>An order block is the ORIGIN of the move that broke structure: when price
 * finally closes through an uncrossed swing high, the candle that launched that
 * move — the lowest one between the swing and the break — is where the bid sat.
 * Not "the last down candle" (the shortcut most implementations take), which is
 * only the same candle sometimes.
 *
 * <pre>
 *   Bullish, on the first close c[i] &gt; top.price with top not yet crossed:
 *     mark top.crossed
 *     scan bars top.bar+1 .. i-1 for the LOWEST low-ref
 *       low-ref  = bodyBot if useBody else l
 *       high-ref = bodyTop if useBody else h
 *     zone = [low-ref, high-ref] of that candle, anchored at it
 * </pre>
 *
 * <p>Lifecycle — the part that makes this worth having over a plain zone:
 * <ul>
 *   <li>ACTIVE — fresh demand (bullish) / supply (bearish).</li>
 *   <li>TESTED — price traded in without a body-close through it. The zone
 *       held; it is now a level with a track record.</li>
 *   <li>BREAKER — a body-close went through the far edge. POLARITY FLIPS: the
 *       bullish block is now resistance. Still live, just inverted.</li>
 *   <li>REMOVED — after breaking, price closed back beyond the near edge in the
 *       original direction. The level is spent. Terminal.</li>
 * </ul>
 *
 * <p>SPEC DECISION (§S7 confirmation width): §S7 says "swingLen-bar
 * confirmation" without splitting it into left and right bars, while §S6 states
 * its convention explicitly (left = swingLen, right = 1). DECIDED: follow §S6 —
 * {@code left = swingLen}, {@code right = 1}. First principles: requiring
 * swingLen bars on the RIGHT would delay confirmation by ten bars, and the
 * §S7 rule fires on "the FIRST close through an uncrossed swing". Price
 * routinely breaks a swing high within ten bars of making it, so a symmetric
 * confirmation would silently drop exactly the fast, decisive breaks that leave
 * the best order blocks.
 *
 * <p>Retention: 5 per side, terminal evicted first.
 */
public final class OrderBlockDetector implements FamilyDetector {

    /** A tracked swing with the flag §S7 keys creation on. */
    private static final class Extreme {
        final SwingPivots.Pivot pivot;
        boolean crossed;

        Extreme(SwingPivots.Pivot pivot) {
            this.pivot = pivot;
        }
    }

    private final IctLibConfig config;
    private Extreme top;
    private Extreme bottom;

    public OrderBlockDetector(IctLibConfig config) {
        this.config = config;
    }

    @Override
    public DetectionType family() {
        return DetectionType.ORDER_BLOCK;
    }

    @Override
    public void onBar(TimeframeSeries series, DetectionRegistry registry) {
        advanceLifecycles(series, registry);

        SwingPivots.Pivot newHigh = SwingPivots.confirmHigh(series, config.obSwingLen, 1);
        if (newHigh != null) top = new Extreme(newHigh);
        SwingPivots.Pivot newLow = SwingPivots.confirmLow(series, config.obSwingLen, 1);
        if (newLow != null) bottom = new Extreme(newLow);

        Candle c = series.at(0);
        if (c == null) return;

        if (top != null && !top.crossed && c.getClose() > top.pivot.price()) {
            top.crossed = true;
            createFromOrigin(series, registry, DetectionDirection.BULLISH, top.pivot);
        }
        if (bottom != null && !bottom.crossed && c.getClose() < bottom.pivot.price()) {
            bottom.crossed = true;
            createFromOrigin(series, registry, DetectionDirection.BEARISH, bottom.pivot);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // THE ORIGIN SCAN
    // ═══════════════════════════════════════════════════════════════════════

    private void createFromOrigin(TimeframeSeries series, DetectionRegistry registry,
                                  DetectionDirection direction, SwingPivots.Pivot swing) {
        long here = series.barIndex();
        // Scan the bars strictly between the swing and the breaking bar.
        int oldestBack = (int) (here - (swing.bar() + 1));
        if (oldestBack < 1) return;                    // nothing in between
        if (series.at(oldestBack) == null) {
            // The swing has aged out of the window. Scan what remains rather
            // than abandon the block — a truncated scan is still the lowest
            // candle we can see, and reporting nothing would be worse.
            oldestBack = series.size() - 1;
            if (oldestBack < 1) return;
        }

        int bestBack = -1;
        double best = direction.isBullish() ? Double.MAX_VALUE : -Double.MAX_VALUE;
        for (int back = oldestBack; back >= 1; back--) {
            Candle c = series.at(back);
            if (c == null) continue;
            double ref = direction.isBullish() ? lowRef(c) : highRef(c);
            if (direction.isBullish() ? ref < best : ref > best) {
                best = ref;
                bestBack = back;
            }
        }
        if (bestBack < 0) return;

        Candle origin = series.at(bestBack);
        MutableDetection d = registry.create(DetectionType.ORDER_BLOCK,
                series.timeframe(), direction,
                lowRef(origin), highRef(origin),
                series.barIndex(), series.at(0).getTimestamp(), DetectionState.ACTIVE);
        d.putMeta("originBar", series.barIndexOf(bestBack));
        d.putMeta("originTime", origin.getTimestamp().toString());
        d.putMeta("brokenSwing", swing.price());
        d.putMeta("useBody", config.obUseBody);
    }

    private double lowRef(Candle c) {
        return config.obUseBody ? TimeframeSeries.bodyBot(c) : c.getLow();
    }

    private double highRef(Candle c) {
        return config.obUseBody ? TimeframeSeries.bodyTop(c) : c.getHigh();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════

    private void advanceLifecycles(TimeframeSeries series, DetectionRegistry registry) {
        Candle c = series.at(0);
        if (c == null) return;
        long bar = series.barIndex();

        for (MutableDetection d : registry.mutableView(
                DetectionType.ORDER_BLOCK, series.timeframe())) {
            if (d.terminal() || d.createdAtBar() >= bar) continue;

            boolean bullish = d.direction().isBullish();
            if (d.state() == DetectionState.BREAKER) {
                boolean reclaimed = bullish
                        ? c.getClose() > d.priceTop()
                        : c.getClose() < d.priceBottom();
                if (reclaimed) d.advanceTo(DetectionState.REMOVED, c.getTimestamp(), bar);
                continue;
            }

            boolean broken = bullish
                    ? TimeframeSeries.bodyBot(c) < d.priceBottom()
                    : TimeframeSeries.bodyTop(c) > d.priceTop();
            if (broken) {
                d.advanceTo(DetectionState.BREAKER, c.getTimestamp(), bar);
                continue;
            }

            if (d.state() == DetectionState.ACTIVE
                    && c.getLow() <= d.priceTop() && c.getHigh() >= d.priceBottom()) {
                d.advanceTo(DetectionState.TESTED, c.getTimestamp(), bar);
            }
        }
    }
}
