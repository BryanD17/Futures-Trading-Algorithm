package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.MarketStructureShiftDetector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * §S8 — MARKET STRUCTURE: MSS (the regime flipped) and BOS (the regime
 * continued), over a zigzag of confirmed pivots.
 *
 * <pre>
 *   dir ∈ {-1, 0, +1}, initially 0
 *   MSS_BULL : close above the most recent confirmed swing HIGH while dir &lt;= 0
 *              → dir := +1, emitted at that swing's price/bar
 *   BOS_BULL : while dir == +1, a close above a NEWER confirmed swing high
 *              → emitted, unless the level equals the previous MSS/BOS level
 *                for that side (dedup)
 *   bearish mirrors
 * </pre>
 *
 * <h2>This is NOT the gate's MSS</h2>
 * The 2022-model gate (M6) uses {@link MarketStructureShiftDetector}, a
 * stricter construct that also demands displacement through the level. That
 * detector is untouched and remains the only thing entries read. This engine is
 * the chart/confluence-grade read: a pure zigzag regime.
 *
 * <p>Two structure truths are only tolerable if their divergence is MEASURED
 * (anti-pattern C3), so this class runs a SHADOW instance of the gate detector
 * over the same bars — the real class, constructed exactly as the strategy
 * constructs it ({@code new MarketStructureShiftDetector(50, 2)}), because it
 * is silent and cheap, so there is no reason to mirror its rule and risk
 * drift. The counters feed {@code [ICTLIB-DIFF <sym>] mss: ...}.
 *
 * <p>{@code agreeWindow} pairs each ictlib MSS with a gate MSS of the SAME
 * direction within ±{@code mssAgreeWindow} bars, greedily and at most once
 * each. Order does not matter — either detector may lead — which is the whole
 * question the number answers: do these two see the same turn, a few bars
 * apart, or genuinely different markets?
 */
public final class StructureEngine implements FamilyDetector {

    /** One side's zigzag entry. */
    private record Node(long bar, double price, boolean high) {}

    /** An unmatched structure event awaiting a counterpart. */
    private static final class Pending {
        final long bar;
        final boolean bullish;
        boolean matched;

        Pending(long bar, boolean bullish) {
            this.bar = bar;
            this.bullish = bullish;
        }
    }

    private final IctLibConfig config;
    private final IctLibDiffStats stats;
    private final MarketStructureShiftDetector shadowGate;

    private final Deque<Node> zigzag = new ArrayDeque<>();
    private int dir = 0;
    private Double lastBullLevel;
    private Double lastBearLevel;

    private final Deque<Pending> pendingIctlib = new ArrayDeque<>();
    private final Deque<Pending> pendingGate = new ArrayDeque<>();

    public StructureEngine(IctLibConfig config, IctLibDiffStats stats) {
        this.config = config;
        this.stats = stats;
        // Same construction the stdvote strategy uses, so the comparison is
        // against the gate that actually runs (StdvOteRunnerStrategy.java:405).
        this.shadowGate = new MarketStructureShiftDetector(50, 2);
    }

    @Override
    public DetectionType family() {
        return DetectionType.MSS;
    }

    @Override
    public void onBar(TimeframeSeries series, DetectionRegistry registry) {
        Candle c = series.at(0);
        if (c == null) return;
        long bar = series.barIndex();

        // Shadow the gate detector over the identical bar stream.
        MarketStructureShiftDetector.MSS gateMss = shadowGate.update(c);
        if (gateMss != null && stats != null) {
            stats.recordGateMss();
            offer(pendingGate, pendingIctlib, new Pending(bar, gateMss.isBullish));
        }

        updateZigzag(series);

        Node lastHigh = newest(true);
        Node lastLow = newest(false);

        if (lastHigh != null && c.getClose() > lastHigh.price()) {
            if (dir <= 0) {
                emit(series, registry, DetectionType.MSS, DetectionDirection.BULLISH, lastHigh);
                dir = 1;
                lastBullLevel = lastHigh.price();
                if (stats != null) {
                    stats.recordIctlibMss();
                    offer(pendingIctlib, pendingGate, new Pending(bar, true));
                }
            } else if (lastBullLevel == null || lastBullLevel != lastHigh.price()) {
                emit(series, registry, DetectionType.BOS, DetectionDirection.BULLISH, lastHigh);
                lastBullLevel = lastHigh.price();
            }
        }

        if (lastLow != null && c.getClose() < lastLow.price()) {
            if (dir >= 0) {
                emit(series, registry, DetectionType.MSS, DetectionDirection.BEARISH, lastLow);
                dir = -1;
                lastBearLevel = lastLow.price();
                if (stats != null) {
                    stats.recordIctlibMss();
                    offer(pendingIctlib, pendingGate, new Pending(bar, false));
                }
            } else if (lastBearLevel == null || lastBearLevel != lastLow.price()) {
                emit(series, registry, DetectionType.BOS, DetectionDirection.BEARISH, lastLow);
                lastBearLevel = lastLow.price();
            }
        }
    }

    /** Current regime: -1 bearish, 0 undecided, +1 bullish. */
    public int direction() {
        return dir;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ZIGZAG
    // ═══════════════════════════════════════════════════════════════════════

    private void updateZigzag(TimeframeSeries series) {
        SwingPivots.Pivot high =
                SwingPivots.confirmHigh(series, config.structurePivotLeft, config.structurePivotRight);
        if (high != null) addNode(new Node(high.bar(), high.price(), true));

        SwingPivots.Pivot low =
                SwingPivots.confirmLow(series, config.structurePivotLeft, config.structurePivotRight);
        if (low != null) addNode(new Node(low.bar(), low.price(), false));
    }

    /**
     * Consecutive same-side pivots collapse to the more extreme one — that is
     * what makes it a zigzag rather than a list of every wiggle.
     */
    private void addNode(Node n) {
        Node last = zigzag.peekLast();
        if (last != null && last.high() == n.high()) {
            boolean moreExtreme = n.high() ? n.price() > last.price() : n.price() < last.price();
            if (!moreExtreme) return;
            zigzag.removeLast();
        }
        zigzag.addLast(n);
        while (zigzag.size() > config.structureHistoryCap) zigzag.removeFirst();
    }

    private Node newest(boolean high) {
        List<Node> nodes = new ArrayList<>(zigzag);
        for (int i = nodes.size() - 1; i >= 0; i--) {
            if (nodes.get(i).high() == high) return nodes.get(i);
        }
        return null;
    }

    private void emit(TimeframeSeries series, DetectionRegistry registry,
                      DetectionType type, DetectionDirection direction, Node level) {
        Candle c = series.at(0);
        MutableDetection d = registry.create(type, series.timeframe(), direction,
                level.price(), level.price(), series.barIndex(), c.getTimestamp(),
                DetectionState.POINT);
        d.putMeta("level", level.price());
        d.putMeta("levelBar", level.bar());
        d.putMeta("close", c.getClose());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AGREEMENT MATCHING (measurement only)
    // ═══════════════════════════════════════════════════════════════════════

    private void offer(Deque<Pending> own, Deque<Pending> other, Pending event) {
        prune(own, event.bar);
        prune(other, event.bar);
        for (Pending p : other) {
            if (!p.matched && p.bullish == event.bullish
                    && Math.abs(p.bar - event.bar) <= config.mssAgreeWindow) {
                p.matched = true;
                event.matched = true;
                stats.recordMssAgreement();
                break;
            }
        }
        own.addLast(event);
    }

    private void prune(Deque<Pending> q, long bar) {
        while (!q.isEmpty() && bar - q.peekFirst().bar > config.mssAgreeWindow) {
            q.removeFirst();
        }
    }
}
