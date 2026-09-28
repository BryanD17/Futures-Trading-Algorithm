package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.OteAlarmEvent;
import com.topstep.trading.event.OteArmedEvent;
import com.topstep.trading.event.OteInvalidatedEvent;
import com.topstep.trading.strategy.DisplacementDetector;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.MarketStructureShiftDetector.MSS;
import com.topstep.trading.strategy.stdvote.PdArrayLocator.PdArray;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * V5 Agent 04 — the post-sweep half of the STDV+OTE funnel, extracted from
 * {@link StdvOteRunnerStrategy} so the same code is driven by the live runner
 * AND by tape-driven golden-case tests (G1 injects the correct bias/sweep).
 *
 * <pre>
 *   SWEEP_DONE    ── displacement (ONE detector, calibrated) + linked FVG/OB ──▶ DISPLACED       (M5)
 *   DISPLACED     ── close beyond the most recent opposite swing (ONE MSS)   ──▶ MSS_CONFIRMED  (M6)
 *   MSS_CONFIRMED ── zone fixed on the ANCHORED leg; price first trades into
 *                    [0.618, 0.786]                                           ──▶ OTE_ARMED      (ARM)
 *   OTE_ARMED     ── PD array OVERLAPS the band AND price reacts             ──▶ alarm → tryEmit (M7)
 *   any of the two ── CLOSE beyond the range extreme (the 1.0)               ──▶ INVALIDATED    (INVALIDATE)
 *   OTE_ARMED     ── runner's ote.windowBars                                 ──▶ INVALIDATED    (EXPIRE)
 * </pre>
 *
 * Every transition publishes an {@link OteArmedEvent} / {@link OteAlarmEvent}
 * / {@link OteInvalidatedEvent} with the zone numbers (and records it in
 * {@link OteEventLog}). All times are CANDLE times.
 */
public final class OteSetupDriver {

    /** One MSS observation on the detector timeframe. */
    record MssEvent(boolean bullish, double level, Instant at, long idx) {}

    private final String symbol;
    private final double tick;
    private final int tfMinutes;
    private final DisplacementDetector displacement;
    private final EventBus bus;
    private final OteEntryCalculator ote = new OteEntryCalculator();
    private final PdArrayLocator pd = new PdArrayLocator();
    private final OteAnchorRangeTracker range = new OteAnchorRangeTracker();
    private final Deque<MssEvent> mssEvents = new ArrayDeque<>();

    private final int recentBars;
    private final int linkBars;
    private final int mssFreshBars;
    private final OteAnchorMode anchorMode;

    // Never re-consume a displacement across setups.
    private Instant consumedDisplacementTs;

    // Per-setup state (cleared when the machine falls back before SWEEP_DONE).
    private long dispIdx = -1;
    private PdArray linked;
    private PdArray orderBlock;
    private long mssIdx = -1;
    private Instant sweepTs;
    private double postSweepHigh = Double.NaN;
    private double postSweepLow = Double.NaN;
    private SetupState prevState = SetupState.IDLE;
    private boolean invalidationPublished;
    private String lastStall;

    public OteSetupDriver(String symbol, double tickSize, DisplacementDetector displacement,
                          EventBus bus, int detectorTfMinutes) {
        this.symbol = symbol;
        this.tick = tickSize;
        this.displacement = displacement;
        this.bus = bus;
        this.tfMinutes = Math.max(1, detectorTfMinutes);
        this.recentBars = OteConfig.displacementRecentBars();
        this.linkBars = OteConfig.fvgLinkBars();
        this.mssFreshBars = OteConfig.mssFreshBars();
        this.anchorMode = OteConfig.anchorMode();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Feeds
    // ══════════════════════════════════════════════════════════════════════

    /** Every 1m feed candle (before the state steps run). */
    public void onFeedCandle(Candle c, SetupContext ctx) {
        range.onCandle(c);
        if (ctx != null && ctx.sweep != null) {
            Instant ts = ctx.sweep.getTimestamp();
            if (sweepTs == null || !sweepTs.equals(ts)) {
                sweepTs = ts;
                postSweepHigh = Math.max(ctx.sweep.getSweptLevel(), c.getHigh());
                postSweepLow = Math.min(ctx.sweep.getSweptLevel(), c.getLow());
            } else {
                postSweepHigh = Math.max(postSweepHigh, c.getHigh());
                postSweepLow = Math.min(postSweepLow, c.getLow());
            }
        }
    }

    /**
     * Every completed detector-timeframe candle, AFTER the displacement / FVG
     * / MSS detectors have been updated with it.
     */
    public void onAnatomyCandle(Candle c, MSS observed) {
        long idx = pd.onBar(c);
        if (observed != null) {
            mssEvents.addLast(new MssEvent(observed.isBullish, observed.breakLevel, c.getTimestamp(), idx));
            while (mssEvents.size() > 50) mssEvents.removeFirst();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Step 10 — SWEEP_DONE → DISPLACED (M5)
    // ══════════════════════════════════════════════════════════════════════

    /** @return null on success (or no-op), else the stall reason. */
    public String tryRecordDisplacement(StdvOteStrategy core, MarketBias bias) {
        SetupContext ctx = core.getSetupContext();
        if (ctx.state != SetupState.SWEEP_DONE) return null;
        boolean bullish = bias == MarketBias.BULLISH;
        // The displacement bar must END after the sweep (the bar containing
        // the sweep qualifies: sweep-and-displace candle).
        Instant notBefore = ctx.sweep == null || ctx.sweep.getTimestamp() == null ? null
                : ctx.sweep.getTimestamp().minus(Duration.ofMinutes(tfMinutes - 1L));
        DisplacementDetector.Displacement d = displacement.findRecent(recentBars, bullish, notBefore);
        if (d == null) {
            return displacement.anyRecent(recentBars, notBefore)
                    ? "displacement-wrong-direction" : "no-recent-displacement";
        }
        if (d.getTimestamp() != null && d.getTimestamp().equals(consumedDisplacementTs)) {
            return "displacement-already-consumed";
        }
        long idx = pd.indexOf(d.getTimestamp());
        if (idx < 0) return "no-displacement-object";
        Optional<PdArray> link = pd.linkedFvg(idx, bullish, linkBars);
        String kind = "FVG";
        if (link.isEmpty()) {
            if (!pd.linkWindowClosed(idx, linkBars)) return "fvg-link-pending";
            link = pd.orderBlock(idx, bullish, linkBars);
            kind = "OB";
        }
        if (link.isEmpty()) return "no-fvg-for-displacement";
        core.recordDisplacement(link.get().asFairValueGap(), kind, d.getTimestamp());
        if (ctx.state == SetupState.DISPLACED) {
            consumedDisplacementTs = d.getTimestamp();
            dispIdx = idx;
            linked = link.get();
            orderBlock = pd.orderBlock(idx, bullish, linkBars).orElse(null);
        }
        return null;
    }

    /** The FVG the displacement at {@code dispTs} links to (evidence helper). */
    Optional<PdArray> linkedFvgFor(Instant dispTs, boolean bullish) {
        long idx = pd.indexOf(dispTs);
        return idx < 0 ? Optional.empty() : pd.linkedFvg(idx, bullish, linkBars);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Step 11 — DISPLACED → MSS_CONFIRMED (M6)
    // ══════════════════════════════════════════════════════════════════════

    public String tryRecordMss(StdvOteStrategy core, MarketBias bias) {
        SetupContext ctx = core.getSetupContext();
        if (ctx.state != SetupState.DISPLACED) return null;
        boolean bullish = bias == MarketBias.BULLISH;
        long now = pd.lastIndex();
        Iterator<MssEvent> it = mssEvents.descendingIterator();
        while (it.hasNext()) {
            MssEvent e = it.next();
            if (e.idx() < dispIdx) break;
            if (now - e.idx() > mssFreshBars) break;
            if (e.bullish() != bullish) {
                // A counter-bias shift kills the setup only when it closes
                // through the swept extreme (the thesis is then wrong).
                double ext = bullish ? postSweepLow : postSweepHigh;
                boolean through = !Double.isNaN(ext) && (bullish ? e.level() < ext : e.level() > ext);
                if (through) {
                    core.invalidate("counter-bias MSS through the sweep extreme " + ext);
                    return null;
                }
                continue;
            }
            core.recordMss(e.at());
            if (ctx.state == SetupState.MSS_CONFIRMED) {
                mssIdx = e.idx();
            }
            return null;
        }
        return "no-MSS";
    }

    // ══════════════════════════════════════════════════════════════════════
    // Step 12 — plan the zone, INVALIDATE, ARM (MSS_CONFIRMED → OTE_ARMED)
    // ══════════════════════════════════════════════════════════════════════

    public String tryArmOte(StdvOteStrategy core, MarketBias bias, Candle candle) {
        SetupContext ctx = core.getSetupContext();
        if (ctx.state != SetupState.MSS_CONFIRMED) return null;
        boolean bullish = bias == MarketBias.BULLISH;
        // Re-anchor every bar until ARM: while the leg is still extending
        // (new LL after the MSS) the zone follows it; from ARM on it is
        // frozen (D-19 — a PD array must never fall out of a moving band).
        if (!plan(core, bullish, candle.getTimestamp()) && ctx.ote == null) return "no-anchor-leg";
        OteZone z = ctx.ote;
        if (closedBeyondExtreme(z, candle)) {
            core.invalidate("OTE invalidated: close " + candle.getClose()
                    + " beyond range extreme " + z.one00());
            return null;
        }
        if (mssIdx >= 0 && pd.lastIndex() - mssIdx > mssFreshBars) {
            core.invalidate("MSS stale before price reached the OTE band ("
                    + mssFreshBars + " detector bars)");
            return null;
        }
        if (!tradedIntoBand(z, candle)) return "awaiting-band-touch";
        if (core.armOte(candle.getTimestamp())) {
            publish(new OteArmedEvent(symbol, candle.getTimestamp(), z.bullish(), ctx.oteAnchorMode,
                    z.legLow(), z.legHigh(), z.f62(), z.f705(), z.f79(), z.eq50(),
                    z.bullish() ? candle.getLow() : candle.getHigh()));
        }
        return null;
    }

    /** Fix the zone on the anchored leg; false when no leg can be anchored. */
    private boolean plan(StdvOteStrategy core, boolean bullish, Instant now) {
        SetupContext ctx = core.getSetupContext();
        double atr = displacement.getLastAtr();
        double minLeg = Math.max(StdvProjectionEngine.DEFAULT_MIN_LEG_TICKS * 5 * tick,
                Double.isNaN(atr) ? 0.0 : 4.0 * atr);
        OteAnchorRangeTracker.Leg leg = null;
        OteAnchorMode used = anchorMode;
        if (anchorMode == OteAnchorMode.DEALING_RANGE) {
            leg = OteAnchorRangeTracker.fromContext(ctx)
                    .or(() -> range.leg(now, bullish, OteAnchorMode.DEALING_RANGE, minLeg))
                    .orElse(null);
        } else if (anchorMode == OteAnchorMode.TRADING_DAY) {
            leg = range.leg(now, bullish, OteAnchorMode.TRADING_DAY, minLeg).orElse(null);
        }
        if (leg == null || !legUsable(leg, bullish)) {
            leg = impulseLeg(bullish);
            used = OteAnchorMode.IMPULSE;
        }
        if (leg == null) return false;
        Optional<OteZone> zone = ote.buildZone(leg.low(), leg.high(), bullish, tick);
        if (zone.isEmpty()) return false;
        double ext = bullish ? postSweepLow : postSweepHigh;
        return core.recordOtePlan(zone.get(), used.name(), leg.source(), ext);
    }

    /**
     * The dealing range is the right anchor only when the setup is a
     * retracement INTO it: the leg must contain the post-sweep extreme, and
     * that extreme must sit on the leg's premium side for a short (at or
     * above equilibrium) / discount side for a long. A sweep in the wrong
     * half means the setup belongs to a smaller, local leg — the anchor then
     * falls back to the post-sweep impulse (IMPULSE).
     */
    private boolean legUsable(OteAnchorRangeTracker.Leg leg, boolean bullish) {
        if (!(leg.size() > 0)) return false;
        double eq = (leg.low() + leg.high()) / 2.0;
        if (bullish) {
            return Double.isNaN(postSweepLow)
                    || (postSweepLow >= leg.low() - tick && postSweepLow <= eq);
        }
        return Double.isNaN(postSweepHigh)
                || (postSweepHigh <= leg.high() + tick && postSweepHigh >= eq);
    }

    /** Pre-V5 anchor: sweep extreme → furthest price since (IMPULSE mode). */
    private OteAnchorRangeTracker.Leg impulseLeg(boolean bullish) {
        if (Double.isNaN(postSweepHigh) || Double.isNaN(postSweepLow)
                || !(postSweepHigh > postSweepLow)) return null;
        return new OteAnchorRangeTracker.Leg(postSweepLow, postSweepHigh, null, null, "IMPULSE");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Step 13 — ALARM (OTE_ARMED): PD array overlaps + reaction
    // ══════════════════════════════════════════════════════════════════════

    /**
     * @return true when the alarm has fired (this candle or earlier) and the
     *         runner should attempt emission; false otherwise (stall recorded
     *         in {@link #lastStall()}).
     */
    public boolean alarm(StdvOteStrategy core, Candle candle) {
        SetupContext ctx = core.getSetupContext();
        lastStall = null;
        if (ctx.state != SetupState.OTE_ARMED || ctx.ote == null) return false;
        // R4 defence-in-depth: never ALARM (→ emit) inside the SACRED daily
        // 14:45–17:00 CT no-entry / flatten block or the weekend gap, whatever
        // the session gate (M3, Agent 02) says. The legacy NY-PM killzone
        // runs to 16:00 ET = 15:00 CT and would otherwise admit 14:45–15:00 CT.
        if (!StdvOteRunnerStrategy.allSessionEntryWindow(
                candle.getTimestamp().atZone(java.time.ZoneId.of("America/Chicago")))) {
            lastStall = "no-entry-block-14:45-17:00CT";
            return false;
        }
        if (ctx.oteAnchorMode == null) return true;          // legacy recordOteImpulse path
        if (ctx.oteAlarmAt != null) return true;             // re-plan / retry on later bars
        OteZone z = ctx.ote;
        if (closedBeyondExtreme(z, candle)) {
            core.invalidate("OTE invalidated: close " + candle.getClose()
                    + " beyond range extreme " + z.one00());
            return false;
        }
        List<PdArray> candidates = pd.candidates(z.bullish(), linked, orderBlock);
        Optional<PdArray> best = PdArrayLocator.bestInBand(candidates, z);
        if (best.isEmpty()) {
            lastStall = "no-pd-array-overlapping-band";
            return false;
        }
        String reaction = reaction(z, candle);
        if (reaction == null) {
            lastStall = "no-reaction-at-band";
            return false;
        }
        double entry = roundTick(PdArrayLocator.entryLevel(best.get(), z));
        if (core.recordOteAlarm(entry, best.get().kind(), best.get().farEdge(), candle.getTimestamp())) {
            publish(new OteAlarmEvent(symbol, candle.getTimestamp(), z.bullish(), best.get().kind(),
                    best.get().bottom(), best.get().top(), entry, z.f62(), z.f79(), reaction));
            return true;
        }
        return false;
    }

    /** Why the last {@link #alarm} call did not fire (null when it did / n.a.). */
    public String lastStall() {
        return lastStall;
    }

    /**
     * Reaction at the band: the candle traded into the band AND either closed
     * back toward the trade (short: below the 0.618 edge or a down-close) or
     * reached the 0.705 limit (a resting limit there would have filled).
     */
    static String reaction(OteZone z, Candle c) {
        double lo = Math.min(z.f62(), z.f79());
        double hi = Math.max(z.f62(), z.f79());
        if (z.bullish()) {
            if (c.getLow() > hi) return null;
            if (c.getLow() <= z.f705()) return "limit@0.705";
            if (c.getClose() > hi) return "close-back-above-0.618";
            if (c.getClose() > c.getOpen()) return "up-close-in-band";
            return null;
        }
        if (c.getHigh() < lo) return null;
        if (c.getHigh() >= z.f705()) return "limit@0.705";
        if (c.getClose() < lo) return "close-back-below-0.618";
        if (c.getClose() < c.getOpen()) return "down-close-in-band";
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Housekeeping — invalidation events + per-setup reset
    // ══════════════════════════════════════════════════════════════════════

    /** Call once per feed candle after all state steps ran. */
    public void afterCandle(SetupContext ctx, Candle candle) {
        SetupState s = ctx.state;
        if (s == SetupState.INVALIDATED && !invalidationPublished && ctx.ote != null
                && (prevState == SetupState.MSS_CONFIRMED || prevState == SetupState.OTE_ARMED)) {
            invalidationPublished = true;
            OteZone z = ctx.ote;
            publish(new OteInvalidatedEvent(symbol, candle.getTimestamp(), z.bullish(),
                    ctx.lastGateFailed, candle.getClose(), z.one00(), z.f62(), z.f79()));
        }
        if (s == SetupState.IDLE || s == SetupState.BIAS_SET || s == SetupState.MANIP_DONE) {
            dispIdx = -1;
            linked = null;
            orderBlock = null;
            mssIdx = -1;
            invalidationPublished = false;
        }
        if (ctx.sweep == null) {
            sweepTs = null;
            postSweepHigh = Double.NaN;
            postSweepLow = Double.NaN;
        }
        prevState = s;
    }

    public void reset() {
        pd.reset();
        range.reset();
        mssEvents.clear();
        consumedDisplacementTs = null;
        dispIdx = -1;
        linked = null;
        orderBlock = null;
        mssIdx = -1;
        sweepTs = null;
        postSweepHigh = Double.NaN;
        postSweepLow = Double.NaN;
        prevState = SetupState.IDLE;
        invalidationPublished = false;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static boolean closedBeyondExtreme(OteZone z, Candle c) {
        return z.bullish() ? c.getClose() < z.one00() : c.getClose() > z.one00();
    }

    private static boolean tradedIntoBand(OteZone z, Candle c) {
        double lo = Math.min(z.f62(), z.f79());
        double hi = Math.max(z.f62(), z.f79());
        return z.bullish() ? c.getLow() <= hi : c.getHigh() >= lo;
    }

    private double roundTick(double p) {
        return tick > 0 ? Math.round(p / tick) * tick : p;
    }

    private void publish(com.topstep.trading.event.BaseEvent e) {
        java.util.Map<String, Object> m =
                e instanceof OteArmedEvent a ? a.toMap()
                        : e instanceof OteAlarmEvent al ? al.toMap()
                        : e instanceof OteInvalidatedEvent iv ? iv.toMap() : null;
        OteEventLog.record(symbol, m);
        System.out.println("[OTE " + symbol + "] " + e);
        if (bus != null) bus.publish(e);
    }

    // Test / evidence accessors.
    PdArrayLocator pdArrays() { return pd; }
    OteAnchorRangeTracker dealingRange() { return range; }
    double postSweepHigh() { return postSweepHigh; }
    double postSweepLow() { return postSweepLow; }
}
