package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.domain.Candle;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * V5 Agent 03 (task 4) — the manipulation leg in SCORING mode, where there
 * is ALWAYS a session.
 *
 * <p>Pre-V5 the runner only looked for the Judas leg inside an OPEN
 * killzone and waited there without falling back (cfg B on the real tape:
 * {@code MANIP-no-leg} held 195–232 bars per session). This locator keeps
 * its own buffer of the CURRENT session window (ET clock, no dependency on
 * the killzone clock) and resolves the leg in priority order:
 * <ol>
 *   <li>{@code JUDAS} — {@link ManipulationLegDetector} anchored at the
 *       session open (counter-bias excursion + reclaim of the open);</li>
 *   <li>{@code LEVEL_SWEEP} — the session's counter-bias excursion that
 *       carried price from the session open through a KNOWN level (bearish
 *       bias: open &lt; level &lt; session high; leg = the session low before
 *       that high → the high);</li>
 *   <li>fallback (caller) — the most recent swing pair, IMMEDIATELY: the
 *       leg only seeds the STDV ladder, it must never stall the funnel.</li>
 * </ol>
 * Session windows come from Agent 02's {@code SessionClassifier} (ASIA,
 * LONDON, PRE_NY, NY_AM, NY_LUNCH, NY_PM, NO_ENTRY, PRE_ASIA, WEEKEND).
 */
public final class SessionLegLocator {

    private static final int BUFFER_MAX = 600;

    /** A resolved leg and how it was found. */
    public record Located(double legLow, double legHigh, String kind) { }

    private final List<Candle> buffer = new ArrayList<>();
    private String session;

    public void onCandle(Candle c) {
        if (c == null || c.getTimestamp() == null) return;
        // V5: the ONE session classifier (Agent 02) — candle time, DST-proof.
        String s = com.topstep.trading.strategy.session.SessionClassifier
                .classify(c.getTimestamp()).name();
        if (!s.equals(session)) {
            session = s;
            buffer.clear();
        }
        buffer.add(c);
        if (buffer.size() > BUFFER_MAX) buffer.remove(0);
    }

    public String currentSession() {
        return session;
    }

    public int size() {
        return buffer.size();
    }

    public void reset() {
        buffer.clear();
        session = null;
    }

    /**
     * Resolve the session's manipulation leg for the given bias, or empty
     * (the caller then falls back to the swing pair).
     */
    public Optional<Located> locate(boolean biasBullish, List<KnownLevel> levels,
                                    double tickSize, int minLegTicks) {
        if (buffer.size() < 2) return Optional.empty();
        Optional<ManipulationLegDetector.Leg> judas =
                ManipulationLegDetector.detect(buffer, biasBullish, tickSize, minLegTicks);
        if (judas.isPresent()) {
            return Optional.of(new Located(judas.get().legLow(), judas.get().legHigh(), "JUDAS"));
        }
        return levelSweepLeg(biasBullish, levels, tickSize, minLegTicks);
    }

    private Optional<Located> levelSweepLeg(boolean biasBullish, List<KnownLevel> levels,
                                            double tickSize, int minLegTicks) {
        if (levels == null || levels.isEmpty()) return Optional.empty();
        // Counter-bias excursion: bearish bias → the session HIGH; bullish → LOW.
        int idx = 0;
        for (int i = 1; i < buffer.size(); i++) {
            Candle c = buffer.get(i);
            Candle best = buffer.get(idx);
            if (biasBullish ? c.getLow() < best.getLow() : c.getHigh() > best.getHigh()) idx = i;
        }
        Candle ext = buffer.get(idx);
        double extreme = biasBullish ? ext.getLow() : ext.getHigh();
        // The excursion must carry price from the SESSION OPEN through a
        // known level (for a bearish bias: open < level < session high).
        double before = buffer.get(0).getOpen();
        double counter = biasBullish ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int i = 0; i <= idx; i++) {
            Candle c = buffer.get(i);
            counter = biasBullish ? Math.max(counter, c.getHigh()) : Math.min(counter, c.getLow());
        }
        boolean took = false;
        for (KnownLevel l : levels) {
            if (l.getType().name().contains("OPEN")) continue;
            double p = l.getPrice();
            if (biasBullish && !l.getType().isHigh() && p < before && p > extreme) took = true;
            if (!biasBullish && l.getType().isHigh() && p > before && p < extreme) took = true;
        }
        if (!took) return Optional.empty();
        double lo = biasBullish ? extreme : counter;
        double hi = biasBullish ? counter : extreme;
        if (!(hi > lo)) return Optional.empty();
        if (tickSize > 0 && (hi - lo) / tickSize < Math.max(0, minLegTicks)) return Optional.empty();
        return Optional.of(new Located(lo, hi, "LEVEL_SWEEP"));
    }
}
