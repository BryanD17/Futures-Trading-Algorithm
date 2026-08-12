package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * §S6 — LIQUIDITY POOLS: clusters of near-equal confirmed swings, where resting
 * stops pool up. This is the family the raid pipeline already half-knows about,
 * which is why a confirmed pool does NOT stay locked inside ictlib — it is
 * published into {@code LevelEngine} through {@link PoolListener} so the whole
 * engine keeps ONE level universe (Appendix E8).
 *
 * <pre>
 *   tolerance  = ATR(atrPeriod) / toleranceDiv
 *   cluster    = the CONTIGUOUS run of same-side swings, newest → oldest,
 *                all within ±tolerance of the new pivot
 *   poolPrice  = (minClusterPrice + maxClusterPrice) / 2
 *   zone       = [poolPrice - tolerance, poolPrice + tolerance]
 *   anchor     = the OLDEST swing in the cluster
 * </pre>
 *
 * <p>SPEC DECISION (§S6 cluster contiguity): the spec says to walk newest to
 * oldest and "stop at the first one farther than tolerance from y ON THE FAR
 * SIDE", then states plainly that the cluster must be contiguous in recency.
 * The trailing clause is ambiguous, so the operative rule is the plain one: the
 * walk stops at the first same-side swing more than {@code tolerance} away in
 * EITHER direction. First principles — a pool is a run of swings that stopped
 * at the same price; a swing that broke well past them ended that run,
 * regardless of which way it broke.
 *
 * <p>SPEC DECISION (direction encoding): §S6 speaks of BUYSIDE and SELLSIDE.
 * Those are mapped onto {@link DetectionDirection} by WHERE the pool sits —
 * BULLISH = buyside (built from swing highs, resting above), BEARISH =
 * sellside — and the unambiguous word is also carried in {@code meta.side} so
 * no consumer has to guess the convention.
 *
 * <p>Lifecycle (buyside; sellside mirrors): ACTIVE → PARTIAL (one boundary
 * closed through — the raid is under way) → SWEPT (both boundaries closed
 * through; terminal — the pool is consumed).
 *
 * <p>Retention: 4 per side.
 */
public final class LiquidityPoolDetector implements FamilyDetector {

    /** Notified whenever a pool is confirmed or its zone is updated. */
    public interface PoolListener {
        void onPoolConfirmed(String symbol, Detection pool);
    }

    private static final String META_ANCHOR_BAR = "anchorBar";
    private static final String META_BROKEN_LOW = "brokenLow";
    private static final String META_BROKEN_HIGH = "brokenHigh";

    private final IctLibConfig config;
    private final PoolListener listener;

    private final Deque<SwingPivots.Pivot> highs = new ArrayDeque<>();
    private final Deque<SwingPivots.Pivot> lows = new ArrayDeque<>();

    public LiquidityPoolDetector(IctLibConfig config, PoolListener listener) {
        this.config = config;
        this.listener = listener;
    }

    @Override
    public DetectionType family() {
        return DetectionType.LIQUIDITY_POOL;
    }

    @Override
    public void onBar(TimeframeSeries series, DetectionRegistry registry) {
        advanceLifecycles(series, registry);

        // Swings are recorded FIRST and unconditionally. Abstaining from
        // clustering while ATR is still cold is correct; throwing away the
        // pivots that happened during warmup is not — those early swings are
        // exactly the oldest members of the first real cluster, and losing
        // them silently delays every pool by a full scan window.
        SwingPivots.Pivot high = SwingPivots.confirmHigh(series, config.poolSwingLen, 1);
        if (high != null) push(highs, high);
        SwingPivots.Pivot low = SwingPivots.confirmLow(series, config.poolSwingLen, 1);
        if (low != null) push(lows, low);

        double tolerance = SwingPivots.atr(series, config.poolAtrPeriod) / config.poolToleranceDiv;
        // ABSTAIN while ATR is undefined: no pools, no exception, no block.
        if (Double.isNaN(tolerance) || tolerance <= 0) return;

        if (high != null) {
            evaluate(series, registry, highs, DetectionDirection.BULLISH, tolerance);
        }
        if (low != null) {
            evaluate(series, registry, lows, DetectionDirection.BEARISH, tolerance);
        }
    }

    private void push(Deque<SwingPivots.Pivot> store, SwingPivots.Pivot p) {
        store.addLast(p);
        while (store.size() > config.poolScanDepth) store.removeFirst();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // THE CLUSTER ARITHMETIC — pure, so Appendix W4 is a direct unit test
    // ═══════════════════════════════════════════════════════════════════════

    /** A qualifying cluster: its price extremes and the swing it anchors to. */
    record Cluster(int size, double minPrice, double maxPrice, SwingPivots.Pivot oldest) {
        double poolPrice() {
            return (minPrice + maxPrice) / 2.0;
        }
    }

    /**
     * @param sameSide confirmed same-side swings, OLDEST first, the new pivot last
     * @return the cluster the new pivot completes, or null when it completes none
     */
    static Cluster cluster(List<SwingPivots.Pivot> sameSide, double tolerance, int minCluster) {
        if (sameSide.isEmpty()) return null;
        SwingPivots.Pivot p = sameSide.get(sameSide.size() - 1);
        double y = p.price();

        List<SwingPivots.Pivot> members = new ArrayList<>();
        members.add(p);
        for (int i = sameSide.size() - 2; i >= 0; i--) {
            SwingPivots.Pivot q = sameSide.get(i);
            if (Math.abs(q.price() - y) > tolerance) break;   // contiguity ends
            members.add(q);
        }
        if (members.size() < minCluster) return null;

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        SwingPivots.Pivot oldest = members.get(members.size() - 1);
        for (SwingPivots.Pivot m : members) {
            min = Math.min(min, m.price());
            max = Math.max(max, m.price());
        }
        return new Cluster(members.size(), min, max, oldest);
    }

    private void evaluate(TimeframeSeries series, DetectionRegistry registry,
                          Deque<SwingPivots.Pivot> store, DetectionDirection direction,
                          double tolerance) {
        Cluster c = cluster(new ArrayList<>(store), tolerance, config.poolMinCluster);
        if (c == null) return;

        double poolPrice = c.poolPrice();
        double bottom = poolPrice - tolerance;
        double top = poolPrice + tolerance;

        // A pool already anchored at this swing is UPDATED, never duplicated —
        // the same resting liquidity must be one fact on the chart.
        for (MutableDetection d : registry.mutableView(
                DetectionType.LIQUIDITY_POOL, series.timeframe())) {
            if (d.terminal() || d.direction() != direction) continue;
            Object anchor = d.meta().get(META_ANCHOR_BAR);
            if (anchor instanceof Number n && n.longValue() == c.oldest().bar()) {
                d.resetZone(bottom, top);
                d.putMeta("poolPrice", poolPrice);
                d.putMeta("clusterSize", c.size());
                d.putMeta("tolerance", tolerance);
                publish(series, d);
                return;
            }
        }

        Candle bar = series.at(0);
        MutableDetection d = registry.create(DetectionType.LIQUIDITY_POOL,
                series.timeframe(), direction, bottom, top,
                series.barIndex(), bar.getTimestamp(), DetectionState.ACTIVE);
        d.putMeta("side", direction.isBullish() ? "BUYSIDE" : "SELLSIDE");
        d.putMeta(META_ANCHOR_BAR, c.oldest().bar());
        d.putMeta("poolPrice", poolPrice);
        d.putMeta("clusterSize", c.size());
        d.putMeta("tolerance", tolerance);
        d.putMeta(META_BROKEN_LOW, Boolean.FALSE);
        d.putMeta(META_BROKEN_HIGH, Boolean.FALSE);
        publish(series, d);
    }

    private void publish(TimeframeSeries series, MutableDetection d) {
        if (listener != null) {
            listener.onPoolConfirmed(d.symbol(), DetectionSnapshot.of(d));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LIFECYCLE — the two-phase break
    // ═══════════════════════════════════════════════════════════════════════

    private void advanceLifecycles(TimeframeSeries series, DetectionRegistry registry) {
        Candle c = series.at(0);
        if (c == null) return;
        long bar = series.barIndex();

        for (MutableDetection d : registry.mutableView(
                DetectionType.LIQUIDITY_POOL, series.timeframe())) {
            if (d.terminal() || d.createdAtBar() >= bar) continue;

            boolean brokenLow = Boolean.TRUE.equals(d.meta().get(META_BROKEN_LOW));
            boolean brokenHigh = Boolean.TRUE.equals(d.meta().get(META_BROKEN_HIGH));

            if (d.direction().isBullish()) {          // buyside: taken from below
                if (c.getClose() > d.priceBottom()) brokenLow = true;
                if (c.getClose() > d.priceTop()) brokenHigh = true;
            } else {                                  // sellside: taken from above
                if (c.getClose() < d.priceTop()) brokenHigh = true;
                if (c.getClose() < d.priceBottom()) brokenLow = true;
            }
            d.putMeta(META_BROKEN_LOW, brokenLow);
            d.putMeta(META_BROKEN_HIGH, brokenHigh);

            if (brokenLow && brokenHigh) {
                d.advanceTo(DetectionState.SWEPT, c.getTimestamp(), bar);
            } else if (brokenLow || brokenHigh) {
                d.advanceTo(DetectionState.PARTIAL, c.getTimestamp(), bar);
            }
        }
    }
}
