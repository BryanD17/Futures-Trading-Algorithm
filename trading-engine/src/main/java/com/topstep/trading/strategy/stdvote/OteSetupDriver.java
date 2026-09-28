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
    /** V5 Agent 05.2: IMPULSE_LEG | POST_SWEEP (read at construction, like the other OTE keys). */
    private final String entryModel;
    private final double minSweepFib;
    /** V5 Agent 05.5: ICT_OB | SWEEP_BAR (read at construction, like entryModel). */
    private final String pdArraySource;
    private final int obLookbackBars;
    /** Last 1m feed candles (for the ICT order block before the sweep bar). */
    private final Deque<Candle> feedBars = new ArrayDeque<>();
    private static final int FEED_BUFFER = 32;
    /** Snapshot of the {@link #obLookbackBars} feed bars BEFORE {@link #sweepBar}, oldest first. */
    private List<Candle> sweepPrior = List.of();
    /** Human-readable pick of the last impulse alarm (transcript / OteAlarmEvent reaction). */
    private String lastPdPick;
    /** Minimum upper (short) / lower (long) wick share for the rejection-wick PD array. */
    static final double REJECTION_WICK_MIN = 0.5;

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
    // V5 Agent 05.2 - impulse-leg setup state.
    private boolean impulseMode;
    private double impulseSweptLevel = Double.NaN;
    private Candle sweepBar;
    /** Retrace extreme since the FIRST sweep of this SWEEP_DONE episode (survives sweep refreshes). */
    private double episodeHigh = Double.NaN;
    private double episodeLow = Double.NaN;

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
        this.entryModel = OteConfig.entryModel();
        this.minSweepFib = OteConfig.impulseMinSweepFib();
        this.pdArraySource = OteConfig.pdArraySource();
        this.obLookbackBars = OteConfig.obLookbackBars();
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
                newSweep(ctx);
                sweepTs = ts;
                postSweepHigh = Math.max(ctx.sweep.getSweptLevel(), c.getHigh());
                postSweepLow = Math.min(ctx.sweep.getSweptLevel(), c.getLow());
                setSweepBar(c);
            } else {
                postSweepHigh = Math.max(postSweepHigh, c.getHigh());
                postSweepLow = Math.min(postSweepLow, c.getLow());
            }
            trackEpisode(ctx.sweep.getSweptLevel(), c);
        }
        feedBars.addLast(c);
        while (feedBars.size() > FEED_BUFFER) feedBars.removeFirst();
    }

    /**
     * Record the raid bar and snapshot the {@link #obLookbackBars} 1m feed
     * bars printed BEFORE it (the ICT order block is read from these).
     */
    private void setSweepBar(Candle c) {
        sweepBar = c;
        java.util.ArrayList<Candle> prior = new java.util.ArrayList<>();
        Iterator<Candle> it = feedBars.descendingIterator();
        while (it.hasNext() && prior.size() < obLookbackBars) {
            Candle b = it.next();
            if (c.getTimestamp() != null && b.getTimestamp() != null
                    && !b.getTimestamp().isBefore(c.getTimestamp())) continue;
            prior.add(0, b);
        }
        sweepPrior = List.copyOf(prior);
    }

    /**
     * Every completed detector-timeframe candle, AFTER the displacement / FVG
     * / MSS detectors have been updated with it.
     */
    public void onAnatomyCandle(Candle c, MSS observed) {
        long idx = pd.onBar(c);
        if (observed != null) {
            mssEvents.addLast(new MssEvent(observed.isBullish, observed.breakLevel, c.getTimestamp(), idx));
            // 200: the impulse-leg model (05.2) looks back to the leg that
            // created the dealing range (G1: 09:30-10:45 ET, read at 14:53).
            while (mssEvents.size() > 200) mssEvents.removeFirst();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Step 10 — SWEEP_DONE → DISPLACED (M5)
    // ══════════════════════════════════════════════════════════════════════

    /** @return null on success (or no-op), else the stall reason. */
    public String tryRecordDisplacement(StdvOteStrategy core, MarketBias bias) {
        return tryRecordDisplacement(core, bias, null);
    }

    /**
     * Step 10. V5 Agent 05.2: with {@code ote.entryModel=IMPULSE_LEG} the
     * impulse-leg model is tried first ({@link #tryImpulseLegEntry}); when it
     * does not apply the POST_SWEEP sequence runs exactly as before.
     */
    public String tryRecordDisplacement(StdvOteStrategy core, MarketBias bias, Candle candle) {
        SetupContext ctx = core.getSetupContext();
        if (ctx.state != SetupState.SWEEP_DONE) return null;
        if (candle != null && OteConfig.ENTRY_MODEL_IMPULSE_LEG.equals(entryModel)) {
            if (tryImpulseLegEntry(core, bias, candle)) return null;
        }
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

    // ══════════════════════════════════════════════════════════════════════
    // V5 Agent 05.2 — IMPULSE_LEG entry model
    // ══════════════════════════════════════════════════════════════════════

    /**
     * The OTE model: the displacement and the structure break are the IMPULSE
     * LEG that created the dealing range (G1: HH 30759.25 09:30 ET bar -> LL
     * 30356.75 10:45 ET bar); the retrace INTO the range's OTE band with a
     * liquidity sweep and a PD-array rejection IS the entry. When the recorded
     * sweep's level lies inside the band (and its extreme reaches
     * {@code ote.impulseLeg.minSweepFib}, not beyond the 1.0), M5 and M6 are
     * PROVEN on the leg with the same detectors - never skipped:
     * <ul>
     *   <li>M5: at least one displacement of THE detector (same rule, same
     *       thresholds) in the bias direction on a leg bar, AND a
     *       same-direction FVG whose middle candle is inside the leg
     *       ({@code IMPULSE_FVG});</li>
     *   <li>M6: an MSS of THE MSS detector on a leg bar at/after that
     *       displacement: the leg closed beyond the last opposing swing;</li>
     *   <li>M7: the zone is the dealing-range band; ARM on this bar (price is
     *       already in the band); ALARM in {@link #alarm} on the rejection.</li>
     * </ul>
     * Records every number in {@link SetupContext} (validator + CSV) and, on
     * success, walks SWEEP_DONE -> DISPLACED -> MSS_CONFIRMED -> OTE_ARMED on
     * this candle. Returns false (verdict in {@code ctx.impulseLegVerdict})
     * when the model does not apply - the caller then runs POST_SWEEP.
     */
    boolean tryImpulseLegEntry(StdvOteStrategy core, MarketBias bias, Candle candle) {
        SetupContext ctx = core.getSetupContext();
        if (bias == MarketBias.NEUTRAL || ctx.sweep == null) return false;
        boolean bullish = bias == MarketBias.BULLISH;
        if (anchorMode != OteAnchorMode.DEALING_RANGE) {
            ctx.impulseLegVerdict = "n/a: anchorMode " + anchorMode;
            return false;
        }
        // A short needs a buyside (HIGH) sweep, a long a sellside (LOW) sweep.
        if (ctx.sweep.isBullish() != bullish) {
            ctx.impulseLegVerdict = "sweep-direction-mismatch";
            return false;
        }
        Optional<OteAnchorRangeTracker.Leg> legOpt = OteAnchorRangeTracker.fromContext(ctx);
        if (legOpt.isEmpty()) {
            ctx.impulseLegVerdict = "no-dealing-range";
            return false;
        }
        OteAnchorRangeTracker.Leg leg = legOpt.get();
        Optional<OteZone> zoneOpt = ote.buildZone(leg.low(), leg.high(), bullish, tick);
        if (zoneOpt.isEmpty()) {
            ctx.impulseLegVerdict = "no-zone";
            return false;
        }
        OteZone z = zoneOpt.get();
        double lo = Math.min(z.f62(), z.f79());
        double hi = Math.max(z.f62(), z.f79());
        double level = ctx.sweep.getSweptLevel();
        // Keep the post-sweep extreme in step with a sweep recorded /
        // refreshed on THIS candle (onFeedCandle ran before step 9).
        Instant ts = ctx.sweep.getTimestamp();
        if (ts != null && (sweepTs == null || !sweepTs.equals(ts))) {
            newSweep(ctx);
            sweepTs = ts;
            postSweepHigh = Math.max(level, candle.getHigh());
            postSweepLow = Math.min(level, candle.getLow());
            setSweepBar(candle);
        }
        trackEpisode(level, candle);
        // The retrace's extreme since the episode's first sweep: a retrace
        // that already traded beyond the 0.786 is not an OTE retrace.
        double ext = bullish ? episodeLow : episodeHigh;
        if (level < lo - 1e-9 || level > hi + 1e-9) {
            ctx.impulseLegVerdict = "sweep " + level + " not in OTE band [" + lo + "," + hi + "]";
            return false;
        }
        if (Double.isNaN(ext) || ext < lo - 1e-9 || ext > hi + 1e-9) {
            ctx.impulseLegVerdict = "retrace extreme " + ext + " outside the OTE band [" + lo + "," + hi + "]";
            return false;
        }
        double size = leg.high() - leg.low();
        double need = bullish ? leg.high() - minSweepFib * size : leg.low() + minSweepFib * size;
        if (Double.isNaN(ext) || (bullish ? ext > need + 1e-9 : ext < need - 1e-9)) {
            ctx.impulseLegVerdict = "sweep extreme " + ext + " short of " + minSweepFib + " (" + roundTick(need) + ")";
            return false;
        }
        if (closedBeyondExtreme(z, candle)) {
            ctx.impulseLegVerdict = "close beyond the range 1.0";
            return false;
        }
        // The impulse leg on the detector timeframe.
        long now = pd.lastIndex();
        long startIdx;
        long endIdx;
        if (bullish) {      // LL -> HH
            endIdx = pd.lastBarAt(leg.high(), true, now, tick);
            startIdx = endIdx < 0 ? -1 : pd.lastBarAt(leg.low(), false, endIdx, tick);
        } else {            // HH -> LL
            endIdx = pd.lastBarAt(leg.low(), false, now, tick);
            startIdx = endIdx < 0 ? -1 : pd.lastBarAt(leg.high(), true, endIdx, tick);
        }
        if (startIdx < 0 || endIdx < 0 || startIdx >= endIdx) {
            ctx.impulseLegVerdict = "impulse leg not in the detector buffer / not " + (bullish ? "LL->HH" : "HH->LL");
            return false;
        }
        Instant legStart = pd.barAt(startIdx).getTimestamp();
        Instant legEnd = pd.barAt(endIdx).getTimestamp();
        // M5 - displacement(s) of THE detector on leg bars.
        List<DisplacementDetector.Scored> disps = displacement.between(legStart, legEnd, bullish);
        if (disps.isEmpty()) {
            ctx.impulseLegVerdict = "M5: no " + (bullish ? "bullish" : "bearish") + " displacement on the impulse leg";
            return false;
        }
        // M6 - an MSS of THE MSS detector on a leg bar at/after a displacement.
        MssEvent mss = null;
        DisplacementDetector.Scored disp = null;
        for (MssEvent e : mssEvents) {
            if (e.bullish() != bullish || e.idx() < startIdx || e.idx() > endIdx) continue;
            DisplacementDetector.Scored before = null;
            for (DisplacementDetector.Scored d : disps) {
                if (!d.displacement().getTimestamp().isAfter(e.at())) before = d;
            }
            if (before != null) {
                mss = e;
                disp = before;
                break;
            }
        }
        if (mss == null) {
            ctx.impulseLegVerdict = "M6: no structure break on the impulse leg at/after its displacement";
            return false;
        }
        long dIdx = pd.indexOf(disp.displacement().getTimestamp());
        // M5 FVG created inside the leg: the displacement's own gap first.
        Optional<PdArray> fvg = dIdx < 0 ? Optional.empty() : pd.linkedFvg(dIdx, bullish, linkBars)
                .filter(g -> !g.at().isBefore(legStart) && !g.at().isAfter(legEnd));
        if (fvg.isEmpty()) fvg = pd.gapInWindow(startIdx + 1, endIdx, bullish);
        if (fvg.isEmpty()) {
            ctx.impulseLegVerdict = "M5: no " + (bullish ? "bullish" : "bearish") + " FVG created inside the impulse leg";
            return false;
        }
        Candle mssBar = pd.barAt(mss.idx());
        // Record the proof, then walk the machine to OTE_ARMED.
        ctx.impulseLegStart = legStart;
        ctx.impulseLegEnd = legEnd;
        ctx.impulseDispRangeAtr = disp.rangeOverAtr();
        ctx.impulseDispBody = disp.bodyRatio();
        ctx.impulseDispAtrMult = displacement.getDisplacementMultiplier();
        ctx.impulseDispBodyMin = displacement.getMinBodyRatio();
        ctx.impulseMssSwing = mss.level();
        ctx.impulseMssClose = mssBar == null ? Double.NaN : mssBar.getClose();
        ctx.impulseSweptLevel = level;
        PdArray impulseFvg = fvg.get();
        core.recordDisplacement(impulseFvg.asFairValueGap(), "IMPULSE_FVG", disp.displacement().getTimestamp());
        core.recordMss(mss.at());
        if (ctx.state != SetupState.MSS_CONFIRMED
                || !core.recordOtePlan(z, OteAnchorMode.DEALING_RANGE.name(), leg.source(), ext)
                || !core.armOte(candle.getTimestamp())) {
            // Cannot happen with a consistent bias; never leave a half-walked setup.
            core.invalidate("impulse-leg arm failed in state " + ctx.state);
            return true;
        }
        impulseMode = true;
        impulseSweptLevel = level;
        dispIdx = dIdx;
        mssIdx = mss.idx();
        linked = impulseFvg;
        orderBlock = null;
        ctx.oteEntryModel = OteConfig.ENTRY_MODEL_IMPULSE_LEG;
        ctx.impulseLegVerdict = "ARMED: sweep " + level + " in band [" + lo + "," + hi + "] ext " + ext
                + " | leg " + legStart + ".." + legEnd
                + " | M5 disp " + disp.displacement().getTimestamp()
                + String.format(" range/ATR %.2f>=%.2f body %.2f>=%.2f", disp.rangeOverAtr(),
                        displacement.getDisplacementMultiplier(), disp.bodyRatio(), displacement.getMinBodyRatio())
                + " FVG [" + impulseFvg.bottom() + "," + impulseFvg.top() + "]@" + impulseFvg.at()
                + " | M6 close " + ctx.impulseMssClose + (bullish ? " > " : " < ") + "swing " + mss.level()
                + " @" + mss.at();
        System.out.println("[" + symbol + "] IMPULSE_LEG " + ctx.impulseLegVerdict);
        publish(new OteArmedEvent(symbol, candle.getTimestamp(), z.bullish(), ctx.oteAnchorMode,
                z.legLow(), z.legHigh(), z.f62(), z.f705(), z.f79(), z.eq50(), ext));
        return true;
    }

    /**
     * IMPULSE_LEG PD arrays at the raid: the sweep's OB (the opposite-close
     * detector bar that took the level), the sweep bar's rejection wick, and
     * band-overlapping FVG / IFVG / BREAKER - each must REACH the swept level
     * (the array the retrace rejected from, not one it never touched).
     */
    private List<PdArray> impulseCandidates(boolean bullish) {
        List<PdArray> out = new java.util.ArrayList<>();
        double level = impulseSweptLevel;
        if (sweepTs != null) {
            long secs = tfMinutes * 60L;
            Instant barStart = Instant.ofEpochSecond(Math.floorDiv(sweepTs.getEpochSecond(), secs) * secs);
            long sweepBarIdx = pd.indexOf(barStart);
            if (sweepBarIdx >= 0) pd.sweepOrderBlock(sweepBarIdx, bullish, level).ifPresent(out::add);
        }
        if (OteConfig.PD_SOURCE_ICT_OB.equals(pdArraySource)) {
            ictOrderBlock(bullish).ifPresent(out::add);
        }
        if (sweepBar != null) {
            double range = sweepBar.getHigh() - sweepBar.getLow();
            double bodyTop = Math.max(sweepBar.getOpen(), sweepBar.getClose());
            double bodyBot = Math.min(sweepBar.getOpen(), sweepBar.getClose());
            // The rejection: the raid bar closed back inside (short: below the level).
            boolean rejected = bullish ? sweepBar.getClose() > level : sweepBar.getClose() < level;
            double wick = bullish ? bodyBot - sweepBar.getLow() : sweepBar.getHigh() - bodyTop;
            if (rejected && range > 0 && wick / range >= REJECTION_WICK_MIN) {
                out.add(bullish
                        ? new PdArray("WICK", true, sweepBar.getLow(), bodyBot, sweepBar.getTimestamp())
                        : new PdArray("WICK", false, bodyTop, sweepBar.getHigh(), sweepBar.getTimestamp()));
            }
        }
        for (PdArray p : pd.candidates(bullish)) {
            if (bullish ? p.bottom() <= level : p.top() >= level) out.add(p);
        }
        return out;
    }

    /**
     * V5 Agent 05.5 - the ICT order block of the raid, on the 1m FEED
     * timeframe (the timeframe the sweep and its rejection wick are read on):
     * the NEWEST opposite-close candle (down-close for a long, up-close for a
     * short) among the {@code ote.obLookbackBars} bars BEFORE the sweep bar,
     * i.e. the last opposite candle before the move that swept the level
     * reversed. Only that newest candle is the OB; the alarm keeps it only
     * when its range overlaps the OTE band. 09-25 10:06 long: the 10:05 bar
     * [30755.00, 30803.50] (down-close) - the up-closing 10:06 raid bar is the
     * rejection. G1 14:53 short: the 14:52 bar [30632.00, 30640.00] (up-close).
     */
    Optional<PdArray> ictOrderBlock(boolean bullish) {
        for (int i = sweepPrior.size() - 1; i >= 0; i--) {
            Candle c = sweepPrior.get(i);
            boolean opposite = bullish ? c.getClose() < c.getOpen() : c.getClose() > c.getOpen();
            if (!opposite) continue;
            return Optional.of(new PdArray("OB", bullish, c.getLow(), c.getHigh(), c.getTimestamp()));
        }
        return Optional.empty();
    }

    /**
     * IMPULSE_LEG reaction: the candle traded into the band and CLOSED back
     * beyond the swept level toward the trade (short: below it) with a close
     * in the trade direction (down-close for a short) - the rejection.
     */
    static String impulseReaction(OteZone z, Candle c, double sweptLevel) {
        double lo = Math.min(z.f62(), z.f79());
        double hi = Math.max(z.f62(), z.f79());
        if (z.bullish()) {
            if (c.getLow() > hi) return null;
            if (c.getClose() > sweptLevel && c.getClose() > c.getOpen()) {
                return "rejection: close back above swept " + sweptLevel;
            }
            return null;
        }
        if (c.getHigh() < lo) return null;
        if (c.getClose() < sweptLevel && c.getClose() < c.getOpen()) {
            return "rejection: close back below swept " + sweptLevel;
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
            ctx.oteEntryModel = OteConfig.ENTRY_MODEL_POST_SWEEP;
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
        boolean impulse = impulseMode
                && OteConfig.ENTRY_MODEL_IMPULSE_LEG.equals(ctx.oteEntryModel);
        if (impulse) {
            // Stop side follows the retrace's extreme (05.2 stop rule).
            ctx.sweepExtreme = z.bullish() ? episodeLow : episodeHigh;
        }
        List<PdArray> candidates = impulse
                ? impulseCandidates(z.bullish())
                : pd.candidates(z.bullish(), linked, orderBlock);
        boolean ictOb = impulse && OteConfig.PD_SOURCE_ICT_OB.equals(pdArraySource);
        // Selection is Agent 04's rule in both sources (entry nearest the
        // 0.705; tie -> OB). 05.5 measured the brief's strict OB > WICK > FVG
        // priority on the tape: it dropped the 09-28 12:32 NY_LUNCH fill (the
        // sweep OB's entry clamps to the 0.618, RR(T1) 0.40 < 1.0) and moved
        // 09-23 01:42 ASIA off its FVG entry - see A-05.5 section 2.
        Optional<PdArray> best = PdArrayLocator.bestInBand(candidates, z);
        lastPdPick = best.map(p -> p.kind() + " [" + p.bottom() + "," + p.top() + "]@" + p.at()
                + (impulse ? " src=" + pdArraySource : "")).orElse(null);
        if (best.isEmpty()) {
            lastStall = impulse ? "impulse-no-pd-array-at-sweep" : "no-pd-array-overlapping-band";
            return false;
        }
        String reaction = impulse ? impulseReaction(z, candle, impulseSweptLevel) : reaction(z, candle);
        if (reaction == null) {
            lastStall = impulse ? "impulse-awaiting-rejection" : "no-reaction-at-band";
            return false;
        }
        double entry = roundTick(PdArrayLocator.entryLevel(best.get(), z));
        if (core.recordOteAlarm(entry, best.get().kind(), best.get().farEdge(), candle.getTimestamp())) {
            publish(new OteAlarmEvent(symbol, candle.getTimestamp(), z.bullish(), best.get().kind(),
                    best.get().bottom(), best.get().top(), entry, z.f62(), z.f79(),
                    ictOb ? reaction + " | pd " + lastPdPick : reaction));
            return true;
        }
        return false;
    }

    /** The PD array the last {@link #alarm} call picked (kind [bottom,top]@bar), or null. */
    public String lastPdPick() {
        return lastPdPick;
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
            impulseMode = false;
            impulseSweptLevel = Double.NaN;
        }
        if (ctx.sweep == null) {
            sweepTs = null;
            postSweepHigh = Double.NaN;
            postSweepLow = Double.NaN;
            sweepBar = null;
            sweepPrior = List.of();
            episodeHigh = Double.NaN;
            episodeLow = Double.NaN;
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
        impulseMode = false;
        impulseSweptLevel = Double.NaN;
        sweepBar = null;
        sweepPrior = List.of();
        feedBars.clear();
        lastPdPick = null;
        episodeHigh = Double.NaN;
        episodeLow = Double.NaN;
    }

    /**
     * A new sweep timestamp: a REFRESH inside a running SWEEP_DONE episode
     * keeps the retrace extreme; any other new sweep starts a new episode
     * (the previous setup died / traded, even within this same candle).
     */
    private void newSweep(SetupContext ctx) {
        if (prevState != SetupState.SWEEP_DONE || ctx.state != SetupState.SWEEP_DONE) {
            episodeHigh = Double.NaN;
            episodeLow = Double.NaN;
        }
    }

    /** Fold a candle (and the swept level) into the episode's retrace extreme. */
    private void trackEpisode(double level, Candle c) {
        episodeHigh = Double.isNaN(episodeHigh) ? Math.max(level, c.getHigh())
                : Math.max(episodeHigh, Math.max(level, c.getHigh()));
        episodeLow = Double.isNaN(episodeLow) ? Math.min(level, c.getLow())
                : Math.min(episodeLow, Math.min(level, c.getLow()));
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
