package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.CandleSeries;
import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.chartstate.EqualLevelDetector;
import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.chartstate.LiquidityRaid;
import com.topstep.trading.chartstate.RaidDetector;
import com.topstep.trading.chartstate.RaidDirection;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.BarAggregationManager;
import com.topstep.trading.strategy.BarAggregationManager.Timeframe;
import com.topstep.trading.strategy.CorrelationTracker;
import com.topstep.trading.strategy.DisplacementDetector;
import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.FvgDetector;
import com.topstep.trading.strategy.HtfTrendAnalyzer;
import com.topstep.trading.strategy.HtfTrendAnalyzer.HtfTrendState;
import com.topstep.trading.strategy.IctStructureDetector;
import com.topstep.trading.strategy.ImpulseExtensionAnalyzer;
import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.strategy.KillzoneClock;
import com.topstep.trading.strategy.LiquidityDetector;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.LiquidityTargetIdentifier;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.MarketStructureShiftDetector;
import com.topstep.trading.strategy.MarketStructureShiftDetector.MSS;
import com.topstep.trading.strategy.SilverBulletClock;
import com.topstep.trading.strategy.StrategyContext;
import com.topstep.trading.strategy.TradeTier;
import com.topstep.trading.strategy.TradingStrategy;
import com.topstep.trading.validation.MandatoryConfluenceValidator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The runtime-ready STDV+OTE strategy that runners actually instantiate.
 *
 * <p>Wraps a pure {@link StdvOteStrategy} (the state machine + emission core,
 * which is what the unit tests exercise) with the full detector orchestration
 * needed to make it advance under a live candle feed. Implements
 * {@link TradingStrategy} with the same 3-arg constructor shape as the legacy
 * {@code IctHighConfluenceStrategy} so the runner-side swap is a one-line
 * change.
 *
 * <h2>Detector poll order (per onCandle)</h2>
 *
 * <ol>
 *   <li>Timestamp monotonicity guard — duplicate / out-of-order candles are
 *       dropped before they can touch any detector.</li>
 *   <li>Update every LTF detector with the new candle, and feed the raid
 *       pipeline ({@code RaidDetector.processCandle}) so raid quality scores
 *       are real 1–10 values, not the instrument base.</li>
 *   <li>{@link BarAggregationManager} aggregates the 1m feed;
 *       {@link HtfTrendAnalyzer} re-evaluates ONLY on completed 15m/30m bars
 *       and its state maps to the {@code recordHtfBias} hook (1m noise never
 *       thrashes the bias).</li>
 *   <li>{@link KillzoneClock} + {@link SilverBulletClock} →
 *       {@code ctx.killzoneOpen}; killzone-open candles are buffered for the
 *       {@link ManipulationLegDetector} (Judas swing).</li>
 *   <li>{@code recordManipulationLeg} ← Judas leg while a killzone is open;
 *       most-recent swing pair only as an out-of-killzone fallback.</li>
 *   <li>{@link LiquidityDetector#getLastSweep()} + the raid pipeline's
 *       direction-matched quality score → {@code recordSweep} (same sweep is
 *       never consumed twice — timestamp identity is tracked).</li>
 *   <li>{@link DisplacementDetector#getDisplacementFvgZone()} — the FVG the
 *       displacement itself created — → {@code recordDisplacement}; the
 *       newest same-direction unfilled FVG is only a fallback.</li>
 *   <li>{@link MarketStructureShiftDetector#update} returns an MSS →
 *       {@code recordMss}; the MSS arms the {@link ImpulseLegTracker}.</li>
 *   <li>{@link ImpulseLegTracker} supplies the post-MSS impulse extremes and
 *       the observable rejection-reaction boolean →
 *       {@code recordOteImpulse}.</li>
 *   <li>When state reaches {@code OTE_ARMED}: compute the tier from
 *       confluence factors and call {@code tryEmit} with the configured
 *       stop buffer.</li>
 * </ol>
 *
 * <h2>Configuration (system properties, {@code stdvOte.*} pattern)</h2>
 *
 * <ul>
 *   <li>{@code stdvOte.stopBufferTicks} — stop buffer beyond the OTE 1.0, in
 *       ticks. Default {@code 4} (legacy behaviour preserved exactly; scalp
 *       mode chooses its own value in SA3).</li>
 *   <li>{@code stdvOte.reactionWickTicks} — minimum rejection-wick length at
 *       the OTE zone, in ticks, for {@code reactionConfirmed}. Default
 *       {@code 2}.</li>
 * </ul>
 *
 * <h2>What is intentionally deferred</h2>
 *
 * <ul>
 *   <li>Sizing uses a tier-driven fixed size in the {@code [5, 20]} band
 *       rather than the full buffer-based MLL calculation. {@link StdvOteSizer}
 *       stays unwired until SA3's risk-profile work exposes equity + MLL floor
 *       — wiring it here would change live sizing.</li>
 *   <li>Scalp mode, re-arm logic, and killzone-window changes (SA3/SA4).</li>
 *   <li>SMT cross-feed: the strategy accepts an SMT candle via
 *       {@link #onSmtCandle(Candle)}; downstream runner code routes the
 *       correlate symbol there.</li>
 *   <li>One active setup per instrument; no concurrent setups.</li>
 * </ul>
 *
 * <h2>Safety</h2>
 *
 * <p>The instrument is validated against the {@link TradeableInstrument}
 * registry at construction; non-tradeable symbols (full-size NQ/ES/GC, or
 * anything outside MNQ/MES/MGC) throw immediately. The {@code [5, 20]} size
 * band is enforced by {@link StdvOteStrategy#tryEmit} via the validator's
 * M8 gate. No order is emitted while {@code ctx.lastGateFailed} is set.
 */
public final class StdvOteRunnerStrategy implements TradingStrategy {

    /** System property: stop buffer beyond the OTE 1.0, in ticks (default 4). */
    public static final String STOP_BUFFER_TICKS_PROPERTY = "stdvOte.stopBufferTicks";
    /** Legacy stop buffer — preserved as the default. */
    public static final int DEFAULT_STOP_BUFFER_TICKS = 4;

    /** System property: minimum OTE rejection-wick length, in ticks (default 2). */
    public static final String REACTION_WICK_TICKS_PROPERTY = "stdvOte.reactionWickTicks";
    public static final int DEFAULT_REACTION_WICK_TICKS = 2;

    private final String symbol;
    private final String smtSymbol;
    private final TradeableInstrument.Spec spec;
    private final EventBus eventBus;

    private final StdvOteStrategy core;

    // Detectors (LTF / per-bar).
    private final IctStructureDetector structureDetector;
    private final LiquidityDetector liquidityDetector;
    private final FvgDetector fvgDetector;
    private final DisplacementDetector displacementDetector;
    private final MarketStructureShiftDetector mssDetector;
    private final KillzoneClock killzoneClock;
    private final SilverBulletClock silverBulletClock;
    private final ImpulseExtensionAnalyzer impulseAnalyzer;
    private final CorrelationTracker correlationTracker;

    // HTF aggregation + trend (the real recordHtfBias source).
    private final BarAggregationManager barManager;
    private HtfTrendAnalyzer htfTrend;

    // Raid scoring (uses CandleSeries + LevelEngine + EqualLevelDetector).
    private final CandleSeries candleSeries;
    private final LevelEngine levelEngine;
    private final EqualLevelDetector equalLevelDetector;
    private final RaidDetector raidDetector;
    private final ChartStateQueryAPI chartState;

    // Scalp mode (SA3): read once at construction, stdvOte.enabled pattern.
    // When on, the core targets via ScalpTargetCalculator and this runner
    // feeds it the nearest opposing liquidity level each candle.
    private final boolean scalpMode;
    private final LiquidityTargetIdentifier liquidityTargets;

    // Tier-driven fixed size table (first-cut sizing; replaced by the full
    // buffer-based formula in StdvOteSizer once the runner exposes equity —
    // SA3 scope, see class javadoc).
    private static final int SIZE_TIER_4 = 18;
    private static final int SIZE_TIER_3 = 14;
    private static final int SIZE_TIER_2 = 10;
    private static final int SIZE_TIER_1 = 6;

    /** Bars-since-MSS window in which we still consider the impulse fresh. */
    private static final int MSS_FRESH_BARS = 30;

    /** Maximum bars allowed in OTE_ARMED before the setup invalidates. */
    private static final int MAX_BARS_IN_OTE = 8;

    /** Manipulation-leg snap tolerance in ticks (projection-level snapping). */
    private static final int MANIP_SNAP_TOL_TICKS = 3;

    /** Cap on the killzone candle buffer (longest window: London 9h = 540m). */
    private static final int KILLZONE_BUFFER_MAX = 600;

    // Config (read once at construction; stdvOte.* system properties).
    private final int stopBufferTicks;
    private final int reactionWickTicks;

    // Per-bar state — recomputed each onCandle.
    private MarketBias lastBias = MarketBias.NEUTRAL;
    private MSS lastObservedMss;
    private int barsSinceMss = Integer.MAX_VALUE;
    private int barsInOte = 0;

    // Idempotency guards.
    private Instant lastPrimaryTimestamp;
    private Instant lastSmtTimestamp;
    private Instant lastConsumedSweepTs;
    private Instant lastConsumedDisplacementTs;

    // Post-sweep extremes (impulse-leg origin candidates).
    private double lowSinceSweep = Double.NaN;
    private double highSinceSweep = Double.NaN;

    // Post-MSS impulse leg (OTE input).
    private final ImpulseLegTracker impulseTracker = new ImpulseLegTracker();

    // Killzone-open anchoring for the manipulation-leg detector.
    private final List<Candle> killzoneCandles = new ArrayList<>();
    private boolean killzoneActive = false;

    /**
     * Construct a runner-ready STDV+OTE strategy for the given symbol.
     *
     * @param symbol     instrument symbol; MUST be one of MNQ/MES/MGC
     * @param smtSymbol  SMT correlate (e.g. MES for MNQ); may be null
     * @param eventBus   event bus to publish StrategySignalEvent on
     * @throws IllegalArgumentException if {@code symbol} is not in the
     *         {@link TradeableInstrument} registry
     */
    public StdvOteRunnerStrategy(String symbol, String smtSymbol, EventBus eventBus) {
        Optional<TradeableInstrument.Symbol> resolved = TradeableInstrument.resolve(symbol);
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException(
                    "StdvOteRunnerStrategy rejects non-tradeable symbol: " + symbol
                            + ". Allowed: MNQ, MES, MGC.");
        }
        this.symbol = symbol;
        this.smtSymbol = smtSymbol;
        this.spec = TradeableInstrument.of(resolved.get());
        this.eventBus = eventBus;

        this.stopBufferTicks = intProperty(STOP_BUFFER_TICKS_PROPERTY, DEFAULT_STOP_BUFFER_TICKS);
        this.reactionWickTicks = intProperty(REACTION_WICK_TICKS_PROPERTY, DEFAULT_REACTION_WICK_TICKS);

        // Detectors with sensible defaults from the legacy strategy.
        this.structureDetector = new IctStructureDetector(50);
        this.liquidityDetector = new LiquidityDetector(30);
        this.fvgDetector = new FvgDetector(20);
        this.displacementDetector = new DisplacementDetector(20);
        this.mssDetector = new MarketStructureShiftDetector(50, 2);
        this.killzoneClock = new KillzoneClock();
        this.silverBulletClock = new SilverBulletClock();
        this.impulseAnalyzer = new ImpulseExtensionAnalyzer(symbol, 30);
        this.correlationTracker = new CorrelationTracker(50);

        // True HTF bias source: 1m → 15m/30m aggregation → trend analyzer.
        this.barManager = new BarAggregationManager(symbol, 500);
        this.htfTrend = new HtfTrendAnalyzer(symbol, barManager);

        // Chart-state pipeline for raid quality scoring.
        this.candleSeries = new CandleSeries(symbol, 5000);
        this.levelEngine = new LevelEngine(symbol, candleSeries);
        this.equalLevelDetector = new EqualLevelDetector(symbol, candleSeries);
        this.raidDetector = new RaidDetector(symbol, levelEngine, equalLevelDetector, candleSeries);
        this.chartState = buildChartStateAdapter();

        // Core state machine + validator + projection / OTE engines.
        StdvProjectionEngine projectionEngine = new StdvProjectionEngine(chartState, impulseAnalyzer);
        OteEntryCalculator oteCalc = new OteEntryCalculator();
        MandatoryConfluenceValidator validator =
                new MandatoryConfluenceValidator(null, displacementDetector, chartState);
        // The M7 RR band comes from the ACTIVE RiskLimits' signal band:
        // legacy → topstep50k() carries [2.0, +inf) (identical to the old
        // hardcoded floor); scalp → topstep50kScalp() carries [0.8, 1.5].
        validator.setActiveRiskLimits(ScalpConfig.activeRiskLimits());
        this.core = new StdvOteStrategy(symbol, projectionEngine, oteCalc, validator,
                eventBus, /* expiryBars */ 40L);

        // Scalp mode (SA3): swap the emission target model only. All other
        // gates/state machinery run exactly as in legacy mode.
        this.scalpMode = ScalpConfig.isEnabled();
        this.liquidityTargets = new LiquidityTargetIdentifier(symbol, levelEngine);
        if (scalpMode) {
            core.enableScalpMode(ScalpConfig.targetCalculator());
            System.out.println("[StdvOteRunnerStrategy] SCALP MODE ACTIVE for " + symbol
                    + " (1R-capped targets, band ["
                    + ScalpConfig.activeRiskLimits().getSignalMinRr() + ", "
                    + ScalpConfig.activeRiskLimits().getSignalMaxRr() + "])");
        }
    }

    /** Read-only access to the underlying setup context (used by the API + tests). */
    public SetupContext getSetupContext() {
        return core.getSetupContext();
    }

    /** Receive a candle from the SMT correlate (e.g. MES when the primary is MNQ). */
    public void onSmtCandle(Candle candle) {
        if (candle == null) return;
        Instant ts = candle.getTimestamp();
        if (ts != null) {
            // Same monotonicity guard as the primary feed: drop stale/dupe.
            if (lastSmtTimestamp != null && !ts.isAfter(lastSmtTimestamp)) return;
            lastSmtTimestamp = ts;
        }
        liquidityDetector.updateSmt(candle);
        correlationTracker.update(candle);
    }

    @Override
    public String getName() {
        return "STDV_OTE";
    }

    @Override
    public void onCandle(Candle candle, StrategyContext context) {
        if (candle == null) return;

        // Route SMT candles to the SMT path.
        if (smtSymbol != null && smtSymbol.equals(candle.getSymbol())) {
            onSmtCandle(candle);
            return;
        }
        // Only process candles for our primary symbol.
        if (!symbol.equals(candle.getSymbol())) {
            return;
        }

        // Idempotency: drop duplicate and out-of-order candles before any
        // detector sees them (timestamps must be strictly increasing).
        Instant now = candle.getTimestamp();
        if (now == null) return;
        if (lastPrimaryTimestamp != null && !now.isAfter(lastPrimaryTimestamp)) {
            return;
        }
        lastPrimaryTimestamp = now;

        // 1. Update every LTF detector.
        structureDetector.update(candle);
        liquidityDetector.updatePrimary(candle);
        fvgDetector.update(candle);
        displacementDetector.update(candle);
        MSS observedMss = mssDetector.update(candle);
        if (observedMss != null) {
            lastObservedMss = observedMss;
            barsSinceMss = 0;
        } else if (barsSinceMss < Integer.MAX_VALUE) {
            barsSinceMss++;
        }
        candleSeries.addCandle(candle);
        levelEngine.processCandle(candle);
        equalLevelDetector.ageAndCleanupLevels();
        impulseAnalyzer.update(candle);
        correlationTracker.update(candle);

        // 2. HTF aggregation. The trend analyzer re-evaluates ONLY when a
        // 15m/30m bar completes — the recordHtfBias hook must never be fed
        // from 1m noise (it invalidates on bias flips).
        Map<Timeframe, Candle> completedHtf = barManager.processCandle(candle);
        boolean htfBarClosed = completedHtf.containsKey(Timeframe.M15)
                || completedHtf.containsKey(Timeframe.M30);
        if (htfBarClosed) {
            htfTrend.update(completedHtf);
        }

        // 3. Feed the raid pipeline so active raids exist and carry real
        // 1-10 quality scores (previously never called — scores were stuck
        // at the instrument base).
        boolean hasSmt = smtSymbol != null
                && correlationTracker.hasSMTDivergence(symbol, smtSymbol, 20);
        Boolean htfBullish = (lastBias == MarketBias.BULLISH) ? Boolean.TRUE
                : (lastBias == MarketBias.BEARISH) ? Boolean.FALSE : null;
        boolean displacementEntry = lastBias != MarketBias.NEUTRAL
                && displacementDetector.hasLayer3EntryTrigger(5, lastBias == MarketBias.BULLISH);
        raidDetector.processCandle(candle, RaidDetector.RaidDetectionContext.fullWithCascade(
                hasSmt, htfBullish, htfTrend.getTrendState().isStrong(),
                /* zoneConfluenceScore */ 0, displacementEntry, /* targetAlignmentBonus */ 0));

        // 4. Post-sweep extremes + post-MSS impulse tracking.
        if (!Double.isNaN(lowSinceSweep)) {
            lowSinceSweep = Math.min(lowSinceSweep, candle.getLow());
            highSinceSweep = Math.max(highSinceSweep, candle.getHigh());
        }
        impulseTracker.onCandle(candle.getHigh(), candle.getLow());

        // 5. Killzone bookkeeping: buffer candles from the killzone open so
        // the manipulation-leg detector can anchor the Judas swing there.
        boolean inKillzone = isInstrumentKillzone(now);
        if (inKillzone && !killzoneActive) {
            killzoneCandles.clear();
        }
        killzoneActive = inKillzone;
        if (inKillzone) {
            killzoneCandles.add(candle);
            if (killzoneCandles.size() > KILLZONE_BUFFER_MAX) {
                killzoneCandles.remove(0);
            }
        }

        // Let the core do its own bar-counting + expiry.
        core.onCandle(candle, context);

        SetupContext ctx = core.getSetupContext();
        ctx.killzoneOpen = inKillzone;

        // 6. HTF bias hook — completed HTF bar close ONLY, and only on change.
        if (htfBarClosed) {
            MarketBias bias = mapTrendToBias(htfTrend.getTrendState());
            if (bias != lastBias) {
                core.recordHtfBias(bias);
                lastBias = bias;
            }
        }

        // 7. SMT state for the context (informational, doesn't gate).
        ctx.smtState = hasSmt ? "DIVERGENT" : "NEUTRAL";

        // Track time in OTE.
        if (ctx.state == SetupState.OTE_ARMED) {
            barsInOte++;
            if (barsInOte > MAX_BARS_IN_OTE) {
                core.invalidate("OTE window expired (" + MAX_BARS_IN_OTE + " bars)");
                barsInOte = 0;
            }
        } else {
            barsInOte = 0;
        }

        // 8. From BIAS_SET, look for a manipulation leg.
        if (ctx.state == SetupState.BIAS_SET) {
            tryRecordManipulationLeg(ctx);
        }

        // 9. From MANIP_DONE, look for the sweep.
        if (ctx.state == SetupState.MANIP_DONE) {
            tryRecordSweep(candle);
        }

        // 10. From SWEEP_DONE, look for displacement + FVG in bias direction.
        if (ctx.state == SetupState.SWEEP_DONE) {
            tryRecordDisplacement();
        }

        // 11. From DISPLACED, look for an MSS in the bias direction.
        if (ctx.state == SetupState.DISPLACED) {
            tryRecordMss(candle);
        }

        // 12. From MSS_CONFIRMED, build the OTE zone from the post-MSS impulse.
        if (ctx.state == SetupState.MSS_CONFIRMED) {
            tryArmOte(candle);
        }

        // 13. From OTE_ARMED, build the order and try to emit. In scalp mode
        // the core's target Candidate A (nearest opposing liquidity in the
        // trade direction) is pre-computed here each candle so the core
        // stays detector-free.
        if (ctx.state == SetupState.OTE_ARMED) {
            if (scalpMode) {
                core.setNearestOpposingLiquidity(
                        nearestOpposingLiquidity(candle.getClose()));
            }
            tryEmitOrder();
        }
    }

    /**
     * Candidate A for the scalp target: the nearest opposing liquidity level
     * in the bias direction (above price for longs, below for shorts).
     * Primary source: {@link LiquidityTargetIdentifier#findAllTargets}
     * (unraided, significance-filtered, nearest first). Fallback: the
     * {@link LevelEngine} nearest-unraided-level query. Null when the level
     * pipeline has no opposing level yet — the calculator then considers
     * only the FVG origin / 1R fallback.
     */
    private Double nearestOpposingLiquidity(double referencePrice) {
        if (lastBias == MarketBias.NEUTRAL) return null;
        boolean bullish = (lastBias == MarketBias.BULLISH);
        List<LiquidityTargetIdentifier.LiquidityTarget> targets =
                liquidityTargets.findAllTargets(referencePrice, bullish);
        if (!targets.isEmpty()) {
            return targets.get(0).getTargetPrice(); // sorted nearest-first
        }
        Optional<KnownLevel> level = bullish
                ? levelEngine.getNearestUnraidedLevelAbove(referencePrice)
                : levelEngine.getNearestUnraidedLevelBelow(referencePrice);
        return level.map(KnownLevel::getPrice).orElse(null);
    }

    @Override
    public void initialize() {
        // Reset every stateful collaborator that supports it, rebuild the
        // ones that do not, and return the core to IDLE. Idempotent; safe to
        // call before the first candle or between backtest runs.
        structureDetector.reset();
        liquidityDetector.reset();
        fvgDetector.reset();
        displacementDetector.reset();
        mssDetector.reset();
        raidDetector.reset();
        candleSeries.clear();
        correlationTracker.resetAll();
        barManager.reset();
        htfTrend = new HtfTrendAnalyzer(symbol, barManager);
        resetTransientState();
        lastPrimaryTimestamp = null;
        lastSmtTimestamp = null;
        lastBias = MarketBias.NEUTRAL;
        core.resetForNextWindow();
    }

    @Override
    public void onSessionEnd() {
        // Core-level invalidation of an in-flight setup must still fire.
        core.onSessionEnd();
        // Session-scoped wiring state must not leak into the next session.
        // The HTF aggregation and level engine intentionally survive: HTF
        // structure and prior-day levels are cross-session context.
        resetTransientState();
    }

    @Override
    public void shutdown() {
        core.shutdown();
    }

    /** Clear per-setup / per-session wiring state (not the HTF context). */
    private void resetTransientState() {
        lastObservedMss = null;
        barsSinceMss = Integer.MAX_VALUE;
        barsInOte = 0;
        lowSinceSweep = Double.NaN;
        highSinceSweep = Double.NaN;
        lastConsumedSweepTs = null;
        lastConsumedDisplacementTs = null;
        impulseTracker.reset();
        killzoneCandles.clear();
        killzoneActive = false;
    }

    // ──────────────────────────────────────────────────────────────────────
    // State-machine input helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Map the 5-state HTF trend to the 3-state bias the core consumes. */
    private static MarketBias mapTrendToBias(HtfTrendState state) {
        if (state == null) return MarketBias.NEUTRAL;
        switch (state) {
            case STRONG_BULLISH:
            case WEAK_BULLISH:
                return MarketBias.BULLISH;
            case STRONG_BEARISH:
            case WEAK_BEARISH:
                return MarketBias.BEARISH;
            case RANGING:
            default:
                return MarketBias.NEUTRAL;
        }
    }

    private boolean isInstrumentKillzone(Instant now) {
        // MGC trades the London window in addition to NY; MNQ/MES use NY only.
        boolean ny = killzoneClock.isInKillzone(now) || silverBulletClock.isInSilverBulletWindow(now);
        if ("MGC".equals(symbol) && killzoneClock.isInLondonSession(now)) {
            return true;
        }
        return ny;
    }

    /**
     * Manipulation-leg precedence: while a killzone is open, the
     * {@link ManipulationLegDetector} (Judas swing anchored at the killzone
     * open) is authoritative — we wait for a real leg rather than falling
     * back. Outside any killzone the detector can never have an anchor, so
     * the legacy most-recent-swing-pair input applies (documented fallback).
     */
    private void tryRecordManipulationLeg(SetupContext ctx) {
        boolean biasBullish = (ctx.htfBias == MarketBias.BULLISH);
        if (killzoneActive && !killzoneCandles.isEmpty()) {
            Optional<ManipulationLegDetector.Leg> leg = ManipulationLegDetector.detect(
                    killzoneCandles, biasBullish, spec.tickSize(),
                    StdvProjectionEngine.DEFAULT_MIN_LEG_TICKS);
            leg.ifPresent(l -> core.recordManipulationLeg(
                    l.legLow(), l.legHigh(), spec.tickSize(), MANIP_SNAP_TOL_TICKS));
            return;
        }
        // Fallback (no killzone anchor available): most recent swing pair.
        Double swingHigh = structureDetector.getLastSwingHigh();
        Double swingLow = structureDetector.getLastSwingLow();
        if (swingHigh == null || swingLow == null) return;
        if (!(swingHigh > swingLow)) return;
        core.recordManipulationLeg(swingLow, swingHigh, spec.tickSize(), MANIP_SNAP_TOL_TICKS);
    }

    private void tryRecordSweep(Candle candle) {
        if (!liquidityDetector.hasRecentSweep(3)) return;
        LiquiditySweep sweep = liquidityDetector.getLastSweep();
        if (sweep == null) return;

        // Idempotency: never consume the same sweep event twice.
        if (sweep.getTimestamp() != null && sweep.getTimestamp().equals(lastConsumedSweepTs)) {
            return;
        }

        // Bias-direction match: bullish setup wants a sellside (low) sweep,
        // which LiquiditySweep encodes with isBullish() == true.
        boolean wantBullishSweep = (lastBias == MarketBias.BULLISH);
        if (sweep.isBullish() != wantBullishSweep) return;

        int score = currentRaidScore(sweep);
        core.recordSweep(sweep, score);
        if (core.getSetupContext().state == SetupState.SWEEP_DONE) {
            lastConsumedSweepTs = sweep.getTimestamp();
            // Begin tracking the reversal-leg origin from the swept extreme.
            lowSinceSweep = Math.min(sweep.getSweptLevel(), candle.getLow());
            highSinceSweep = Math.max(sweep.getSweptLevel(), candle.getHigh());
        }
    }

    /**
     * Raid quality for the M4 gate: the real 1–10 score of the
     * direction-matched active raid when the raid pipeline has one;
     * otherwise the instrument base (keeps M4 satisfiable exactly at the
     * floor when the pipeline is starved of known levels, matching the
     * pre-wiring fallback behaviour).
     */
    private int currentRaidScore(LiquiditySweep sweep) {
        RaidDirection want = sweep.isBullish() ? RaidDirection.LOW_SWEEP : RaidDirection.HIGH_SWEEP;
        Optional<LiquidityRaid> raid = raidDetector.getActiveRaidByDirection(want);
        return raid.map(LiquidityRaid::getQualityScore).orElse(spec.raidMinQuality());
    }

    private void tryRecordDisplacement() {
        boolean bullish = (lastBias == MarketBias.BULLISH);
        if (!displacementDetector.hasRecentDisplacement(5, bullish)) return;
        DisplacementDetector.Displacement d = displacementDetector.getLastDisplacement();
        if (d == null) return;

        // Idempotency: never consume the same displacement event twice.
        if (d.getTimestamp() != null && d.getTimestamp().equals(lastConsumedDisplacementTs)) {
            return;
        }

        // Displacement→FVG linkage: prefer the FVG the displacement itself
        // created (exact 3-candle window recorded by the detector) over the
        // newest same-direction FVG from FvgDetector, which may be unrelated.
        FairValueGap fvg = null;
        double[] zone = displacementDetector.getDisplacementFvgZone();
        if (d.createdFvg() && zone != null && zone[1] > zone[0]) {
            fvg = new FairValueGap(bullish, /* top */ zone[1], /* bottom */ zone[0],
                    d.getTimestamp());
        } else {
            fvg = pickFvgFor(bullish);
        }
        if (fvg == null) return;
        core.recordDisplacement(fvg);
        if (core.getSetupContext().state == SetupState.DISPLACED) {
            lastConsumedDisplacementTs = d.getTimestamp();
        }
    }

    /** Fallback FVG pick: newest same-direction unfilled FVG. */
    private FairValueGap pickFvgFor(boolean bullish) {
        List<FairValueGap> fvgs = fvgDetector.getUnfilledFvgs();
        if (fvgs == null || fvgs.isEmpty()) return null;
        // Walk from newest to oldest to find a same-direction FVG.
        for (int i = fvgs.size() - 1; i >= 0; i--) {
            FairValueGap f = fvgs.get(i);
            if (f.isBullish() == bullish) return f;
        }
        return null;
    }

    private void tryRecordMss(Candle candle) {
        if (lastObservedMss == null || barsSinceMss > MSS_FRESH_BARS) return;
        boolean biasBullish = (lastBias == MarketBias.BULLISH);
        if (lastObservedMss.isBullish != biasBullish) {
            // Counter-bias MSS — invalidates the setup per the spec.
            core.invalidate("counter-bias MSS observed");
            return;
        }
        core.recordMss();
        if (core.getSetupContext().state == SetupState.MSS_CONFIRMED) {
            // Arm the impulse tracker on the true post-MSS leg: origin at the
            // post-sweep extreme, terminus at the MSS candle extreme (extends
            // bar by bar from here).
            double origin;
            double terminus;
            if (biasBullish) {
                origin = !Double.isNaN(lowSinceSweep)
                        ? lowSinceSweep : lastObservedMss.displacementLow;
                terminus = Math.max(lastObservedMss.displacementHigh, candle.getHigh());
            } else {
                origin = !Double.isNaN(highSinceSweep)
                        ? highSinceSweep : lastObservedMss.displacementHigh;
                terminus = Math.min(lastObservedMss.displacementLow, candle.getLow());
            }
            impulseTracker.arm(biasBullish, origin, terminus);
        }
    }

    private void tryArmOte(Candle candle) {
        if (!impulseTracker.isArmed()) return;
        if (impulseTracker.isViolated()) {
            // Price took out the impulse origin (the OTE 1.0 invalidation)
            // before any entry — the leg is dead.
            core.invalidate("impulse origin violated before OTE entry");
            return;
        }
        if (!impulseTracker.hasValidLeg()) return;

        // Reaction is derived from observable price action — a rejection
        // wick at the OTE zone — never a hardcoded literal.
        boolean reactionConfirmed = impulseTracker.isRejectionReaction(
                candle, spec.tickSize(), reactionWickTicks);
        core.recordOteImpulse(impulseTracker.impulseLow(), impulseTracker.impulseHigh(),
                spec.tickSize(), reactionConfirmed);
    }

    private void tryEmitOrder() {
        SetupContext ctx = core.getSetupContext();
        TradeTier tier = computeTier(ctx);
        if (tier == null) {
            core.invalidate("no qualifying tier");
            return;
        }
        int size = sizeForTier(tier);
        // tryEmit runs the validator; if it passes, a signal is published.
        // On failure the OTE window simply keeps counting in onCandle — the
        // previous extra barsInOte++ here double-counted and halved the
        // window (SA1 audit finding).
        core.tryEmit(spec.tickSize(), stopBufferTicks, tier, size);
    }

    private TradeTier computeTier(SetupContext ctx) {
        // Optional confluence count (O1..O8 from STDV_OTE_MODEL.md §5).
        int opt = 0;
        if (ctx.killzoneOpen) opt++;                                // O1
        if ("DIVERGENT".equals(ctx.smtState)) opt++;                // O2
        if (ctx.sweep != null) opt++;                               // O3 (sweep present is M4, but
                                                                    //     swept-level type is the O3 hook)
        if (ctx.fvg != null && ctx.ote != null && ctx.ote.contains(ctx.fvg.getTop())) opt++; // O4

        boolean indexPair = "MNQ".equals(symbol) || "MES".equals(symbol);
        boolean smtRequired = indexPair;

        int rs = ctx.raidScore;
        if (rs >= 8 && opt >= 4 && (!smtRequired || "DIVERGENT".equals(ctx.smtState))) {
            return TradeTier.TIER_4;
        }
        if (rs >= 7 && opt >= 3) return TradeTier.TIER_3;
        if (rs >= 6 && opt >= 2) return TradeTier.TIER_2;
        if (rs >= spec.raidMinQuality()) return TradeTier.TIER_1;
        return null;
    }

    private int sizeForTier(TradeTier tier) {
        int s;
        switch (tier) {
            case TIER_4: s = SIZE_TIER_4; break;
            case TIER_3: s = SIZE_TIER_3; break;
            case TIER_2: s = SIZE_TIER_2; break;
            case TIER_1: s = SIZE_TIER_1; break;
            default:     s = spec.minMicros();
        }
        if (s < spec.minMicros()) s = spec.minMicros();
        if (s > spec.maxMicros()) s = spec.maxMicros();
        return s;
    }

    /** Read an int system property with a safe fallback (stdvOte.* pattern). */
    private static int intProperty(String name, int defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            System.out.println("[StdvOteRunnerStrategy] WARN: invalid " + name
                    + "='" + raw + "', using default " + defaultValue);
            return defaultValue;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // ChartStateQueryAPI adapter (minimal — only the methods the projection
    // engine + raid scoring path call).
    // ──────────────────────────────────────────────────────────────────────

    private ChartStateQueryAPI buildChartStateAdapter() {
        return new ChartStateQueryAPI() {
            @Override public String getSymbol() { return symbol; }
            @Override public com.topstep.trading.chartstate.InstrumentRaidConfig getConfig() {
                return null;
            }
            @Override public java.util.List<LiquidityRaid> getActiveRaids() {
                return raidDetector.getActiveRaids();
            }
            @Override public java.util.List<LiquidityRaid> getEntryValidRaids() {
                return raidDetector.getActiveRaids();
            }
            @Override public java.util.List<LiquidityRaid> getConfirmedRaids() {
                return raidDetector.getActiveRaids();
            }
            @Override public java.util.Optional<LiquidityRaid> getBestActiveRaid() {
                List<LiquidityRaid> a = raidDetector.getActiveRaids();
                if (a == null || a.isEmpty()) return java.util.Optional.empty();
                return java.util.Optional.of(a.get(a.size() - 1));
            }
            @Override public java.util.Optional<LiquidityRaid> getActiveBullishRaid() {
                return java.util.Optional.empty();
            }
            @Override public java.util.Optional<LiquidityRaid> getActiveBearishRaid() {
                return java.util.Optional.empty();
            }
            @Override public boolean hasActiveRaidForDirection(boolean expectBullish) { return false; }
            @Override public java.util.Optional<LiquidityRaid> getRaidById(String raidId) {
                return java.util.Optional.empty();
            }
            @Override public java.util.Optional<Double> getPDH() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<Double> getPDL() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<Double> getPWH() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<Double> getPWL() { return java.util.Optional.empty(); }
            @Override public java.util.List<com.topstep.trading.chartstate.KnownLevel> getAllLevels() {
                return levelEngine.getAllLevels();
            }
            @Override public java.util.List<com.topstep.trading.chartstate.KnownLevel> getUnraidedLevels() {
                return levelEngine.getAllLevels();
            }
            @Override public java.util.List<com.topstep.trading.chartstate.KnownLevel> getLevelsNearPrice(double price) {
                return levelEngine.getAllLevels();
            }
            @Override public java.util.Optional<com.topstep.trading.chartstate.KnownLevel> getNearestLevelAbove(double price) {
                return java.util.Optional.empty();
            }
            @Override public java.util.Optional<com.topstep.trading.chartstate.KnownLevel> getNearestLevelBelow(double price) {
                return java.util.Optional.empty();
            }
            @Override public java.util.Optional<com.topstep.trading.chartstate.KnownLevel> getLevel(
                    com.topstep.trading.chartstate.LevelType type) {
                return java.util.Optional.empty();
            }
            @Override public java.util.List<EqualLevelDetector.EqualLevel> getEqualHighs() {
                return java.util.List.of();
            }
            @Override public java.util.List<EqualLevelDetector.EqualLevel> getEqualLows() {
                return java.util.List.of();
            }
            @Override public java.util.Optional<EqualLevelDetector.EqualLevel> getStrongestEqualHigh() {
                return java.util.Optional.empty();
            }
            @Override public java.util.Optional<EqualLevelDetector.EqualLevel> getStrongestEqualLow() {
                return java.util.Optional.empty();
            }
            @Override public java.util.List<EqualLevelDetector.EqualLevel> getEqualHighsAbove(double price) {
                return java.util.List.of();
            }
            @Override public java.util.List<EqualLevelDetector.EqualLevel> getEqualLowsBelow(double price) {
                return java.util.List.of();
            }
            @Override public java.util.Optional<Double> getLatestClose() { return java.util.Optional.empty(); }
            @Override public double getHighest(int lookback) { return 0; }
            @Override public double getLowest(int lookback) { return 0; }
            @Override public double getAverageRange(int lookback) { return 0; }
            @Override public boolean hasMinimumData(int required) { return candleSeries.size() >= required; }
            @Override public boolean isInAsia() { return false; }
            @Override public boolean isInLondon() { return false; }
            @Override public boolean isInNY() { return false; }
            @Override public String getLevelsSummary() { return ""; }
            @Override public String getRaidsSummary() { return ""; }
        };
    }
}
