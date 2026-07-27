package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.CandleSeries;
import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.chartstate.EqualLevelDetector;
import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.chartstate.LiquidityRaid;
import com.topstep.trading.chartstate.RaidDetector;
import com.topstep.trading.chartstate.RaidDirection;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.PositionClosedEvent;
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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;

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

    // ── Scalp frequency / gate state (SA4) ────────────────────────────────
    /** Active risk limits (scalp or legacy profile — single selection point). */
    private final RiskLimits activeRiskLimits;
    /** Re-arm cooldown length in feed bars ({@code scalp.rearmCooldownBars}). */
    private final int rearmCooldownBars;
    /** London prime window (ET) gating MGC scalp entries. */
    private final LocalTime londonPrimeStartEt;
    private final LocalTime londonPrimeEndEt;

    // ── All-sessions trading + killzone size boost (owner directive
    //    2026-07-08) ─────────────────────────────────────────────────────
    /** {@code scalp.allSessions}: entries allowed any time the market is
     *  open except the daily 14:45–17:00 CT no-entry block (flatten
     *  guarantee + Globex halt) and the weekend gap. */
    private final boolean allSessions;
    /** {@code scalp.killzoneSizeBoost}: sizer multiplier INSIDE prime
     *  killzones; clamped [1.0, 2.0]; every existing size cap still binds. */
    private final double killzoneSizeBoost;
    /** True while the CURRENT candle is inside a prime killzone (NY AM/PM,
     *  MGC London prime). Drives the O1 tier confluence and the size boost
     *  — deliberately NOT the widened M3 entry window, so widening the
     *  trading hours does not inflate tier quality. */
    private boolean primeKillzoneNow;
    /** Timestamp of the candle being processed (candle time, not wall clock). */
    private Instant lastCandleInstant;

    /** V2 Agent 06: identity (taggedAt) of the last REACTED zone already
     *  counted as chartReacted_machineSilent — one count per zone. */
    private Instant lastChartOnlyZoneTag;
    /** Buffer-based sizer, wired in scalp mode when account state is available. */
    private final StdvOteSizer sizer = new StdvOteSizer();
    private final double sizerSafetyCushion;
    /** OTE entry math (same instance the core uses; pure). */
    private final OteEntryCalculator oteCalculator;

    /** M2b premium/discount evaluator (V3 Agent 02); never null after ctor. */
    private final PremiumDiscountEvaluator pdEvaluator;

    /** 3-of-4 bias vote engine (V3 Agent 03); never null after ctor. */
    private final BiasVoteEngine biasVoteEngine;

    /** AMD cycle tracker feeding the V2 vote (previously legacy-only). */
    private com.topstep.trading.strategy.DailyAmdCycleTracker amdTracker;

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

    /**
     * Set asynchronously by the PositionClosedEvent handler (EventBus worker
     * threads); consumed on the candle thread — the SetupContext is
     * thread-confined, so all state mutation happens flag-and-apply style.
     */
    private final AtomicBoolean pendingPositionClosed = new AtomicBoolean(false);
    /** True from signal emission until a PositionClosedEvent for this symbol. */
    private volatile boolean positionOpen = false;
    /** Bars left before a re-arm may fire; -1 = no re-arm pending. */
    private int rearmCooldownRemaining = -1;
    /** Previous candle's state, to detect INVALIDATED transitions. */
    private SetupState lastSeenState = SetupState.IDLE;

    // Tier-driven fixed size table (first-cut sizing; replaced by the full
    // buffer-based formula in StdvOteSizer once the runner exposes equity —
    // SA3 scope, see class javadoc).
    private static final int SIZE_TIER_4 = 18;
    private static final int SIZE_TIER_3 = 14;
    private static final int SIZE_TIER_2 = 10;
    private static final int SIZE_TIER_1 = 6;

    /**
     * Timeframe on which the ENTRY ANATOMY (displacement, FVG, MSS/CHoCH)
     * is measured — {@code -Dstdvote.detectorTimeframe} in minutes
     * (1|3|5|15), DEFAULT 5. Field fix 2026-07-09: on raw 1m, MNQ never
     * registers the displacement a human sees on the 5m chart. Structure,
     * sweeps, and levels remain 1m regardless.
     */
    private final Timeframe detectorTimeframe = resolveDetectorTimeframe();

    static Timeframe resolveDetectorTimeframe() {
        int minutes = Integer.getInteger("stdvote.detectorTimeframe", 5);
        switch (minutes) {
            case 1:  return Timeframe.M1;
            case 3:  return Timeframe.M3;
            case 15: return Timeframe.M15;
            case 5:
            default: return Timeframe.M5;
        }
    }

    /**
     * FUNNEL CALIBRATION (2026-07-27 no-trade diagnosis): the 2026-07-09
     * field fix moved the entry anatomy (displacement/FVG/MSS) from 1m to
     * 5m bars but left every window around it calibrated in 1m FEED bars —
     * giving the whole funnel 40 minutes on a 5x slower clock. A 12h LIVE
     * session died of exactly this: 144/173 invalidations were
     * "expired (40 bars without progress)"; an offline replay of the same
     * real tape reproduced 0 emissions with 96 expiry deaths. The windows
     * below now scale by the DETECTOR timeframe, restoring the original
     * design durations (40/8/30 DETECTOR bars). On a 1m detector timeframe
     * the values are numerically identical to the historical constants.
     * Overrides (in detector bars): stdvOte.setupExpiryBars,
     * stdvOte.oteWindowBars, stdvOte.mssFreshBars.
     */
    private final int setupExpiryFeedBars =
            intProperty("stdvOte.setupExpiryBars", 40) * detectorTimeframe.getMinutes();

    /** Maximum feed bars allowed in OTE_ARMED before the setup invalidates. */
    private final int maxBarsInOte =
            intProperty("stdvOte.oteWindowBars", 8) * detectorTimeframe.getMinutes();

    /** Feed bars since MSS in which the impulse is still fresh (see the
     *  FUNNEL CALIBRATION note — 30 DETECTOR bars, scaled to the feed). */
    private final int mssFreshBars =
            intProperty("stdvOte.mssFreshBars", 30) * detectorTimeframe.getMinutes();

    /**
     * OBSERVABILITY ONLY (chart-in-memory rollout): the runner's ChartEngine,
     * whose 30m OTE screenshot-pattern signal is logged NEXT TO the existing
     * gate result so a week of SIM logs can be compared before any gating
     * decision. Nothing in this class gates on it. May be null (tests,
     * legacy wiring) — every use is null-guarded.
     */
    private volatile com.topstep.trading.chart.ChartEngine chartEngine;

    /** Install the observability-only ChartEngine reference (may be null). */
    public void setChartEngine(com.topstep.trading.chart.ChartEngine engine) {
        this.chartEngine = engine;
        // M7b reads the SAME chart the log comparison uses (V3 Agent 06).
        ote30mGate.setChartEngine(engine);
    }

    /** M7b 30m-OTE confluence gate (V3 Agent 06); never null after ctor. */
    private final Ote30mConfluenceGate ote30mGate;

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
        this.displacementDetector = new DisplacementDetector(20, 1.5, 0.65, symbol);
        this.mssDetector = new MarketStructureShiftDetector(50, 2);
        System.out.println("[StdvOteRunnerStrategy] " + symbol
                + " entry-anatomy detectors (displacement/FVG/MSS) on "
                + detectorTimeframe.getLabel()
                + " (stdvote.detectorTimeframe; structure/sweeps/levels stay 1m)");
        this.killzoneClock = new KillzoneClock();
        this.silverBulletClock = new SilverBulletClock();
        this.impulseAnalyzer = new ImpulseExtensionAnalyzer(symbol, 30);
        this.correlationTracker = new CorrelationTracker(50);

        // True HTF bias source: 1m → 15m/30m aggregation → trend analyzer.
        this.barManager = new BarAggregationManager(symbol, 500);
        this.htfTrend = new HtfTrendAnalyzer(symbol, barManager);
        // V3 Agent 04: publish THE authoritative aggregation manager for
        // this symbol so the connector's TIER-2 HTF seed and the /api/chart
        // ?tf= reads target the same instance the strategy trades from.
        com.topstep.trading.strategy.HtfSeriesRegistry.register(symbol, barManager);

        // Chart-state pipeline for raid quality scoring.
        this.candleSeries = new CandleSeries(symbol, 5000);
        this.levelEngine = new LevelEngine(symbol, candleSeries);
        this.equalLevelDetector = new EqualLevelDetector(symbol, candleSeries);
        this.raidDetector = new RaidDetector(symbol, levelEngine, equalLevelDetector, candleSeries);
        this.chartState = buildChartStateAdapter();

        // Core state machine + validator + projection / OTE engines.
        StdvProjectionEngine projectionEngine = new StdvProjectionEngine(chartState, impulseAnalyzer);
        this.oteCalculator = new OteEntryCalculator();
        MandatoryConfluenceValidator validator =
                new MandatoryConfluenceValidator(null, displacementDetector, chartState);
        // The M7 RR band comes from the ACTIVE RiskLimits' signal band:
        // legacy → topstep50k() carries [2.0, +inf) (identical to the old
        // hardcoded floor); scalp → topstep50kScalp() carries [0.8, 1.5].
        this.activeRiskLimits = ScalpConfig.activeRiskLimits();
        validator.setActiveRiskLimits(activeRiskLimits);
        // M2b premium/discount gate (V3 Agent 02): evaluator reads the
        // governing range from THIS runner's LevelEngine at gate time;
        // default mode is LOG (counts, never blocks).
        this.pdEvaluator = PremiumDiscountEvaluator.install(
                symbol, spec.tickSize(), levelEngine);
        validator.setPremiumDiscountEvaluator(pdEvaluator);
        // 3-of-4 bias vote (V3 Agent 03): V2's AMD tracker joins the live
        // path (it previously fed only the legacy strategy); default mode
        // LOG — the vote runs and counts agreement, legacy still decides.
        this.biasVoteEngine = BiasVoteEngine.install(symbol, spec.tickSize());
        this.amdTracker = new com.topstep.trading.strategy.DailyAmdCycleTracker(symbol);
        // M7b 30m-OTE confluence gate (V3 Agent 06): default LOG — the
        // V2 log-only comparison, formalized through counters; GATE is one
        // flag away once the promote criteria are met.
        this.ote30mGate = Ote30mConfluenceGate.install(symbol);
        validator.setOte30mConfluenceGate(ote30mGate);
        this.core = new StdvOteStrategy(symbol, projectionEngine, oteCalculator, validator,
                eventBus, /* expiryBars, feed bars (see FUNNEL CALIBRATION) */
                setupExpiryFeedBars);
        System.out.println("[StdvOteRunnerStrategy] " + symbol
                + " funnel windows (feed bars): expiry=" + setupExpiryFeedBars
                + " oteWindow=" + maxBarsInOte + " mssFresh=" + mssFreshBars
                + " (detector " + detectorTimeframe.getLabel() + ")");

        // Scalp mode (SA3 target model + SA4 frequency/gates). All the
        // sequential mandatory gates run exactly as in legacy mode.
        this.scalpMode = ScalpConfig.isEnabled();
        this.liquidityTargets = new LiquidityTargetIdentifier(symbol, levelEngine);
        this.rearmCooldownBars = ScalpConfig.rearmCooldownBars();
        this.londonPrimeStartEt = ScalpConfig.londonPrimeStartEt();
        this.londonPrimeEndEt = ScalpConfig.londonPrimeEndEt();
        this.allSessions = ScalpConfig.allSessions();
        this.killzoneSizeBoost = ScalpConfig.killzoneSizeBoost();
        this.sizerSafetyCushion = ScalpConfig.sizerSafetyCushion();
        if (scalpMode) {
            core.enableScalpMode(ScalpConfig.targetCalculator(), ScalpConfig.minRaidScore());
            // Re-arm trigger (SA4): observe the position-close funnels via
            // the bus. The handler only flips a flag — all state mutation
            // happens on the candle thread (SetupContext is thread-confined).
            if (eventBus != null) {
                eventBus.subscribe(PositionClosedEvent.class, evt -> {
                    if (this.symbol.equals(evt.getSymbol())) {
                        pendingPositionClosed.set(true);
                    }
                });
            }
            System.out.println("[StdvOteRunnerStrategy] SCALP MODE ACTIVE for " + symbol
                    + " (1R-capped targets, band ["
                    + activeRiskLimits.getSignalMinRr() + ", "
                    + activeRiskLimits.getSignalMaxRr() + "], minRaidScore="
                    + ScalpConfig.minRaidScore() + ", rearmCooldownBars="
                    + rearmCooldownBars + ")");
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

        // 1a. Aggregate FIRST so the entry-anatomy detectors below can be
        // fed completed higher-timeframe candles (field fix 2026-07-09).
        Map<Timeframe, Candle> completedHtf = barManager.processCandle(candle);

        // 1b. Structure / liquidity / levels stay on raw 1m — sweeps and
        // level touches ARE 1m events.
        structureDetector.update(candle);
        liquidityDetector.updatePrimary(candle);
        candleSeries.addCandle(candle);
        levelEngine.processCandle(candle);
        equalLevelDetector.ageAndCleanupLevels();
        impulseAnalyzer.update(candle);
        correlationTracker.update(candle);
        // V2-vote input (V3 Agent 03): mirror the legacy strategy's feed —
        // per 1m candle, with the level engine and the latest displacement.
        amdTracker.update(candle, levelEngine, displacementDetector.getLastDisplacement());

        // 1c. ENTRY ANATOMY — displacement, FVG, MSS/CHoCH — is evaluated
        // on the DETECTOR TIMEFRAME (default 5m, -Dstdvote.detectorTimeframe
        // = 1|3|5|15). FIELD FIX 2026-07-09: fed raw 1m candles, MNQ's
        // displacement detector fired ZERO times across an entire LIVE
        // session (a 5m-obvious displacement is five unremarkable 1m
        // candles: no single 1m bar clears range >= 1.5x ATR(1m) with a 65%
        // body) while MGC's thin 1m bars tripped it. The same granularity
        // starved 1m FVGs (too small to overlap the OTE band at M7) and 1m
        // MSS. The gates are UNCHANGED — displacement + FVG (M5) and MSS
        // (M6) are still mandatory — they are now measured on the timeframe
        // the model (and the owner's chart) actually uses.
        Candle anatomyCandle = (detectorTimeframe == Timeframe.M1)
                ? candle
                : completedHtf.get(detectorTimeframe);
        MSS observedMss = null;
        if (anatomyCandle != null) {
            fvgDetector.update(anatomyCandle);
            displacementDetector.update(anatomyCandle);
            observedMss = mssDetector.update(anatomyCandle);
        }
        if (observedMss != null) {
            lastObservedMss = observedMss;
            barsSinceMss = 0;
        } else if (barsSinceMss < Integer.MAX_VALUE) {
            barsSinceMss++; // freshness stays counted in 1m feed bars
        }

        // 2. HTF trend. The analyzer re-evaluates ONLY when a 15m/30m bar
        // completes — the recordHtfBias hook must never be fed 1m noise.
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
        lastCandleInstant = now;
        boolean inKillzone = isInstrumentKillzone(now);
        // Prime-killzone flag for tier confluence (O1) and the size boost.
        // In legacy (non-scalp) mode it equals the legacy killzone check, so
        // legacy behavior is unchanged.
        primeKillzoneNow = scalpMode ? isPrimeKillzone(now) : inKillzone;
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

        // 5b. SCALP re-arm engine (SA4). Legacy mode: none of this runs —
        // IN_TRADE / INVALIDATED stay terminal (one-move discipline).
        if (scalpMode) {
            processScalpRearm(ctx, context, inKillzone);
        }

        // 6. HTF bias hook — completed HTF bar close ONLY. V2 Agent 04:
        // record EVERY completed-bar evaluation (not just changes) — the
        // hysteresis grace counts CONSECUTIVE NEUTRAL 15m evaluations, and
        // repeated same-bias records are idempotent in the core (INVALIDATED
        // sits above IN_TRADE, so a dead setup ignores repeats).
        if (htfBarClosed) {
            MarketBias legacyBias = mapTrendToBias(htfTrend.getTrendState());
            // 3-of-4 bias vote (V3 Agent 03). LEGACY: not evaluated at all.
            // LOG (default): evaluated + counted, legacy still decides.
            // VOTE: the vote replaces legacy AT THIS ONE SEAM (B12) — the
            // only place core.recordHtfBias is fed on the live path.
            BiasVoteEngine.BiasVoteResult voteResult = null;
            if (biasVoteEngine.mode() != BiasVoteEngine.VoteMode.LEGACY) {
                voteResult = biasVoteEngine.evaluate(
                        new BiasVoteEngine.VoteInputs(
                                htfTrend.getTrendState(),
                                amdTracker.getCurrentPhase(),
                                levelEngine.getLevel(
                                        com.topstep.trading.chartstate.LevelType.MIDNIGHT_OPEN)
                                        .map(com.topstep.trading.chartstate.KnownLevel::getPrice),
                                candle.getClose(),
                                levelEngine.getLevel(com.topstep.trading.chartstate.LevelType.PDH),
                                levelEngine.getLevel(com.topstep.trading.chartstate.LevelType.PDL),
                                // V3 Agent 05: weekly tapped-state context
                                // (detail-only) + H4 series for the optional
                                // V1 consult (copied only when enabled).
                                levelEngine.getLevel(com.topstep.trading.chartstate.LevelType.PWH),
                                levelEngine.getLevel(com.topstep.trading.chartstate.LevelType.PWL),
                                biasVoteEngine.includeH4()
                                        ? barManager.getCandlesSnapshot(Timeframe.H4, 120)
                                        : java.util.List.of()),
                        legacyBias);
            }
            MarketBias bias = BiasVoteEngine.effectiveBias(
                    biasVoteEngine.mode(), legacyBias, voteResult);
            core.recordHtfBias(bias);
            lastBias = bias;
        }

        // 6b. GATE TELEMETRY — one line per completed 15m bar so "why is it
        // not trading" is answerable from the log in one glance (the same
        // fields /api/setup serves). Placed after the bias hook so the line
        // reflects the bias this bar just produced.
        if (completedHtf.containsKey(Timeframe.M15)) {
            String oteState = "NONE";
            OteAgreementStats stats = OteAgreementStats.forSymbol(symbol);
            com.topstep.trading.chart.ChartEngine ce = chartEngine;
            if (ce != null) {
                java.util.Optional<com.topstep.trading.chart.OteZoneSnapshot> zone =
                        ce.getActiveOteZone(symbol);
                oteState = zone
                        .map(z -> z.state().name() + (z.bullish() ? "/BULL" : "/BEAR"))
                        .orElse("NONE");
                // V2 Agent 06: chart REACTED while the machine is silent
                // (no armed setup) — counted ONCE per zone (identity =
                // taggedAt), sampled on the 15m tick. Counting only.
                if (zone.isPresent()
                        && zone.get().state() == com.topstep.trading.chart.OteState.REACTED
                        && zone.get().taggedAt() != null
                        && !zone.get().taggedAt().equals(lastChartOnlyZoneTag)
                        && ctx.state != SetupState.OTE_ARMED
                        && ctx.state != SetupState.IN_TRADE
                        && ctx.state != SetupState.MANAGING) {
                    lastChartOnlyZoneTag = zone.get().taggedAt();
                    stats.recordChartReactedMachineSilent(candle.getTimestamp());
                }
            }
            // Append-only format: existing fields keep their names/order;
            // the oteStats rollup is appended at the end (V2 Agent 06).
            System.out.println("[GATES " + symbol + "] state=" + ctx.state
                    + " bias=" + ctx.htfBias
                    + " lastGateFailed=" + ctx.lastGateFailed
                    + " kzActive=" + killzoneActive
                    + " chart30mOte=" + oteState
                    + " " + pdEvaluator.gatesToken(candle.getClose())
                    + " " + biasVoteEngine.gatesToken()
                    + " " + ote30mGate.gatesToken()
                    + " " + stats.rollup());
        }

        // 6c. Crash-safe agreement-stats checkpoint every completed 30m bar
        // (V3 Agent 06) — the loader collapses same-session lines last-wins,
        // so re-checkpointing can never double count.
        if (completedHtf.containsKey(Timeframe.M30)) {
            OteAgreementStatsStore.checkpoint(symbol, candle.getTimestamp());
        }

        // 7. SMT state for the context (informational, doesn't gate).
        ctx.smtState = hasSmt ? "DIVERGENT" : "NEUTRAL";

        // Track time in OTE.
        if (ctx.state == SetupState.OTE_ARMED) {
            barsInOte++;
            if (barsInOte > maxBarsInOte) {
                core.invalidate("OTE window expired (" + maxBarsInOte + " bars)");
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
            // LOG-ONLY comparison (chart-in-memory rollout): show the 30m
            // ChartEngine's screenshot-pattern verdict side by side with the
            // live M7/OTE gate path. DO NOT gate on this — the owner reviews
            // a week of SIM logs and decides when to switch the gate over.
            if (chartEngine != null && lastBias != MarketBias.NEUTRAL) {
                boolean screenshotPattern = chartEngine.hasReactedOte(
                        symbol, lastBias == MarketBias.BULLISH);
                System.out.println("[" + symbol + "] OTE_ARMED (live gate path)"
                        + " | chart30m.hasReactedOte=" + screenshotPattern
                        + " | bias=" + lastBias);
            }
            if (scalpMode) {
                core.setNearestOpposingLiquidity(
                        nearestOpposingLiquidity(candle.getClose()));
            }
            // V2 Agent 06: capture the chart verdict BEFORE the emission
            // attempt so the agreement count reflects what the 30m chart
            // said at decision time. COUNTING ONLY — nothing gates on it.
            boolean chartAgreedAtEmission = chartEngine != null
                    && lastBias != MarketBias.NEUTRAL
                    && chartEngine.hasReactedOte(symbol, lastBias == MarketBias.BULLISH);
            tryEmitOrder(context);
            if (ctx.state == SetupState.IN_TRADE) {
                OteAgreementStats stats = OteAgreementStats.forSymbol(symbol);
                if (chartAgreedAtEmission) {
                    stats.recordMachineEmittedChartAgreed(candle.getTimestamp());
                } else {
                    stats.recordMachineEmittedChartDisagreed(candle.getTimestamp());
                }
            }
        }

        // Remember the state for INVALIDATED-transition detection (SA4).
        lastSeenState = ctx.state;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Scalp re-arm engine (SA4) — multiple setups per killzone
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Applies pending position-close notifications, runs the re-arm cooldown
     * and, when every gate passes, resets the core for the next setup in the
     * same killzone. Called once per primary candle, on the candle thread,
     * in scalp mode only.
     *
     * <p>Re-arm requires ALL of:
     * <ol>
     *   <li>the setup is terminal: {@code INVALIDATED}, or {@code IN_TRADE}
     *       with the position confirmed closed (PositionClosedEvent);</li>
     *   <li>{@code scalp.rearmCooldownBars} feed bars have elapsed since the
     *       close/invalidation was observed;</li>
     *   <li>a killzone is open for this instrument right now;</li>
     *   <li>NO-OVERLAP: no open position on this symbol (event-tracked flag
     *       AND the account's live position map when available);</li>
     *   <li>the PropFirmRiskEngine frequency gates would pass — same fields
     *       the engine blocks on ({@code maxTradesPerDay},
     *       {@code maxConsecutiveLosses} vs the account counters).</li>
     * </ol>
     */
    private void processScalpRearm(SetupContext ctx, StrategyContext context,
                                   boolean inKillzone) {
        // Apply the async close notification on the candle thread. The
        // cooldown starts on the detection candle and counts FULL bars —
        // the decrement below is skipped on the detection candle itself.
        boolean detectedThisBar = false;
        if (pendingPositionClosed.compareAndSet(true, false)) {
            positionOpen = false;
            if (ctx.state == SetupState.IN_TRADE && rearmCooldownRemaining < 0) {
                rearmCooldownRemaining = rearmCooldownBars;
                detectedThisBar = true;
                System.out.println("[" + symbol + "] SCALP: position closed — re-arm in "
                        + rearmCooldownBars + " bars");
            }
        }
        // An INVALIDATED setup with no pending cooldown starts one. FIELD
        // BUG FIX (2026-07-09 LIVE, 7.5h dead in NY AM): this used to
        // require a lastSeenState EDGE (!= INVALIDATED), but invalidations
        // fired AFTER this step within the same candle — the HTF bias hook
        // (step 6), counter-bias MSS, impulse-origin violation, OTE-window
        // expiry — were written into lastSeenState at end-of-candle before
        // this detector ever saw the edge, leaving the machine INVALIDATED
        // forever (the core's bar-count expiry at step 5a was the ONLY
        // source it could see). The rearmCooldownRemaining < 0 guard alone
        // already guarantees the cooldown starts exactly once per episode.
        if (ctx.state == SetupState.INVALIDATED
                && rearmCooldownRemaining < 0) {
            rearmCooldownRemaining = rearmCooldownBars;
            detectedThisBar = true;
            System.out.println("[" + symbol + "] SCALP: setup invalidated ("
                    + ctx.lastGateFailed + ") — re-arm in " + rearmCooldownBars + " bars");
        }
        if (detectedThisBar) {
            return;
        }

        if (rearmCooldownRemaining > 0) {
            rearmCooldownRemaining--;
        } else if (rearmCooldownRemaining == 0 && canRearm(ctx, context, inKillzone)) {
            rearmCooldownRemaining = -1;
            rearm(ctx);
        }
    }

    /** All re-arm gates outside the cooldown itself. */
    private boolean canRearm(SetupContext ctx, StrategyContext context, boolean inKillzone) {
        boolean terminal = ctx.state == SetupState.INVALIDATED
                || (ctx.state == SetupState.IN_TRADE && !positionOpen);
        if (!terminal) return false;
        if (!inKillzone) return false;
        // NO-OVERLAP: never arm a new setup while a position is open on this
        // symbol — the event-tracked flag plus the live account map.
        if (positionOpen) return false;
        if (context != null && context.hasPosition(symbol)) return false;
        // Mirror the PropFirmRiskEngine frequency gates (3b in evaluate()):
        // arming a setup the engine would block is pointless and would burn
        // the killzone window.
        AccountState account = (context != null) ? context.getAccountState() : null;
        if (account != null) {
            if (activeRiskLimits.getMaxTradesPerDay() > 0
                    && account.getTradesToday() >= activeRiskLimits.getMaxTradesPerDay()) {
                return false;
            }
            if (activeRiskLimits.getMaxConsecutiveLosses() > 0
                    && account.getConsecutiveLosses() >= activeRiskLimits.getMaxConsecutiveLosses()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reset the core + per-setup runner state for the next setup in the same
     * killzone. The sweep/displacement consumption markers are deliberately
     * KEPT so the new setup can never re-consume the previous setup's
     * events; the killzone candle buffer is KEPT (same killzone anchor).
     */
    private void rearm(SetupContext ctx) {
        core.resetForNextWindow();
        lastObservedMss = null;
        barsSinceMss = Integer.MAX_VALUE;
        barsInOte = 0;
        lowSinceSweep = Double.NaN;
        highSinceSweep = Double.NaN;
        impulseTracker.reset();
        // Re-seed the HTF bias (resetForNextWindow clears it to NEUTRAL and
        // the bias hook only fires on CHANGE at HTF closes).
        if (lastBias != MarketBias.NEUTRAL) {
            core.recordHtfBias(lastBias);
        }
        System.out.println("[" + symbol + "] SCALP: re-armed for next setup"
                + " (state=" + ctx.state + ", bias=" + lastBias + ")");
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
        amdTracker = new com.topstep.trading.strategy.DailyAmdCycleTracker(symbol);
        resetTransientState();
        lastPrimaryTimestamp = null;
        lastSmtTimestamp = null;
        lastBias = MarketBias.NEUTRAL;
        // Scalp re-arm state (SA4) starts clean.
        pendingPositionClosed.set(false);
        positionOpen = false;
        rearmCooldownRemaining = -1;
        lastSeenState = SetupState.IDLE;
        core.resetForNextWindow();
    }

    @Override
    public void onSessionEnd() {
        // Persist the session's agreement counters (V3 Agent 06). Candle
        // time, not wall clock; a session with no candles has nothing new.
        if (lastCandleInstant != null) {
            OteAgreementStatsStore.checkpoint(symbol, lastCandleInstant);
        }
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
        if (scalpMode) {
            return isScalpWindow(now);
        }
        // LEGACY (unchanged): NY killzones ∪ Silver Bullet windows; MGC also
        // trades the full London session 3:00–12:00 ET.
        boolean ny = killzoneClock.isInKillzone(now) || silverBulletClock.isInSilverBulletWindow(now);
        if ("MGC".equals(symbol) && killzoneClock.isInLondonSession(now)) {
            return true;
        }
        return ny;
    }

    /**
     * SCALP time windows (SA4). Full KillzoneClock killzones replace the
     * Silver-Bullet union:
     * <ul>
     *   <li>NY AM 9:45–12:30 ET and NY PM 13:45–16:00 ET for every
     *       instrument. The NY PM close (16:00 ET = 15:00 CT) precedes the
     *       Topstep flatten time (15:10 CT = 16:10 ET), so no scalp entry
     *       can slip between the killzone close and the flatten.</li>
     *   <li>MGC only: the London session restricted to its PRIME window
     *       ({@code scalp.londonPrimeStartEt}–{@code scalp.londonPrimeEndEt},
     *       default 3:00–5:00 ET). KillzoneClock's phase API covers NY AM/PM
     *       only (London returns OUTSIDE), so the prime restriction is
     *       config-driven per the SA4 fallback clause. This NARROWS the
     *       legacy 3:00–12:00 ET London window.</li>
     * </ul>
     * SilverBulletClock is no longer a hard gate in scalp mode — the 3:00–4:00
     * ET SB window alone no longer opens trading for MNQ/MES — but it REMAINS
     * a scoring input: RaidDetector.processCandle stamps the SB window on the
     * scoring context and RaidQualityScorer awards +1 inside it.
     */
    private boolean isScalpWindow(Instant now) {
        // Owner directive 2026-07-08 (scalp.allSessions, default on): the M3
        // time gate widens from "prime killzones only" to "any time the
        // market is open", MINUS the daily 14:45–17:00 CT no-entry block
        // (protects the 15:10 CT Topstep flatten and spans the Globex halt)
        // and the weekend gap. The prime killzones are OR-ed in unchanged so
        // their exact historical boundaries (e.g. NY PM entries until
        // 15:00 CT) are preserved.
        if (allSessions) {
            return isPrimeKillzone(now)
                    || allSessionEntryWindow(now.atZone(CT_ZONE));
        }
        return isPrimeKillzone(now);
    }

    /**
     * The ORIGINAL scalp windows — NY AM/PM for every instrument, plus the
     * London prime window for MGC. Still used verbatim for: (1) the M3 gate
     * when {@code scalp.allSessions=false}; (2) the O1 tier confluence;
     * (3) the killzone size boost.
     */
    private boolean isPrimeKillzone(Instant now) {
        LocalTime et = now.atZone(ET_ZONE).toLocalTime();
        boolean nyKillzone = killzoneClock.isInNyAmKillzone(et)
                || killzoneClock.isInNyPmKillzone(et);
        if ("MGC".equals(symbol)) {
            boolean londonPrime = killzoneClock.isInLondonSession(now)
                    && !et.isBefore(londonPrimeStartEt)
                    && et.isBefore(londonPrimeEndEt);
            return nyKillzone || londonPrime;
        }
        return nyKillzone;
    }

    /** Daily no-new-entries block start: 25 min before the 15:10 CT flatten. */
    static final LocalTime ENTRY_BLOCK_START_CT = LocalTime.of(14, 45);
    /** Globex reopen (and entry-block end): 17:00 CT. */
    static final LocalTime REOPEN_CT = LocalTime.of(17, 0);
    private static final ZoneId CT_ZONE = ZoneId.of("America/Chicago");

    /**
     * All-sessions entry window: any time the futures market is open EXCEPT
     * the daily no-entry block {@link #ENTRY_BLOCK_START_CT}–{@link #REOPEN_CT}
     * and the weekend gap (Friday 14:45 CT → Sunday 17:00 CT). Pure function
     * of the candle-time argument — package-private for direct unit testing.
     */
    static boolean allSessionEntryWindow(ZonedDateTime ct) {
        DayOfWeek day = ct.getDayOfWeek();
        LocalTime t = ct.toLocalTime();
        if (day == DayOfWeek.SATURDAY) return false;
        if (day == DayOfWeek.SUNDAY) return !t.isBefore(REOPEN_CT);
        if (day == DayOfWeek.FRIDAY) return t.isBefore(ENTRY_BLOCK_START_CT);
        boolean inDailyBlock = !t.isBefore(ENTRY_BLOCK_START_CT) && t.isBefore(REOPEN_CT);
        return !inDailyBlock;
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

        // SA5 STRICT binary gate: the score passed to the core is subject to
        // scalp.minRaidScore in scalp mode regardless of provenance. When
        // the raid pipeline has no tracked raid the fallback score is the
        // instrument base (5 for MNQ/MES, 6 for MGC) — in scalp mode with
        // the default floor 6 that fallback is REJECTED for the index
        // instruments: a score that cannot be shown >= the floor does not
        // trade. The core keeps the machine in MANIP_DONE so a later,
        // pipeline-scored >= floor sweep can still arm inside the window.
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
        if (lastObservedMss == null || barsSinceMss > mssFreshBars) return;
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

    private void tryEmitOrder(StrategyContext context) {
        SetupContext ctx = core.getSetupContext();
        TradeTier tier = computeTier(ctx);
        if (tier == null) {
            if (scalpMode) {
                // SA4: the tier ladder must NOT block emission in scalp mode
                // — the binary quality gate is the raid-score floor (already
                // enforced at sweep time). Tier only informs sizing.
                tier = TradeTier.TIER_1;
            } else {
                core.invalidate("no qualifying tier");
                return;
            }
        }
        // NO-OVERLAP (scalp): never emit while a position is open on this
        // symbol. Legacy is single-shot by construction (IN_TRADE terminal).
        if (scalpMode && (positionOpen
                || (context != null && context.hasPosition(symbol)))) {
            return;
        }
        int size = scalpMode ? scalpSize(ctx, tier, context) : sizeForTier(tier);
        if (size <= 0) {
            // Sizer stand-down (never 1–4 micros): skip this bar; the OTE
            // window keeps counting and the setup expires/invalidates
            // normally if conditions do not improve.
            return;
        }
        // tryEmit runs the validator; if it passes, a signal is published.
        // On failure the OTE window simply keeps counting in onCandle — the
        // previous extra barsInOte++ here double-counted and halved the
        // window (SA1 audit finding).
        boolean emitted = core.tryEmit(spec.tickSize(), stopBufferTicks, tier, size);
        if (emitted && scalpMode) {
            // Track the open position for the no-overlap rule; cleared only
            // by this symbol's PositionClosedEvent.
            positionOpen = true;
        }
    }

    /**
     * Scalp-mode size selection (SA4): route through {@link StdvOteSizer}
     * when account/equity state is available from the {@link StrategyContext}.
     *
     * <p>Mapping onto the sizer's buffer-based formula:
     * <ul>
     *   <li>{@code equity} — live account equity;</li>
     *   <li>{@code mllFloor} — highest EOD balance − MLL (the Topstep bust
     *       line from the active {@link RiskLimits});</li>
     *   <li>{@code riskFraction} — capped so the risk budget never exceeds
     *       the profile's {@code riskPerTrade} ($150 on topstep50kScalp) NOR
     *       the sizer's canonical 12% of available room;</li>
     *   <li>{@code topstepMicroMax} — the risk engine's
     *       {@code maxContracts}, so runner sizing can never exceed it.</li>
     * </ul>
     * The sizer itself clamps to the instrument band [5, 20] and returns 0
     * (stand down) rather than 1–4 micros. Without account state (no
     * context), sizing falls back to the bounded tier table — flagged in
     * SA4_frequency_gates.md.
     */
    private int scalpSize(SetupContext ctx, TradeTier tier, StrategyContext context) {
        AccountState account = (context != null) ? context.getAccountState() : null;
        if (account == null || ctx.ote == null || Double.isNaN(ctx.pdArrayInOte)) {
            return sizeForTier(tier); // bounded fallback ([5, 20] clamp)
        }
        double entry = oteCalculator.chooseEntry(
                ctx.ote, OptionalDouble.of(ctx.pdArrayInOte), spec.tickSize());
        double stop = oteCalculator.stopPrice(ctx.ote, spec.tickSize(), stopBufferTicks);
        double equity = account.getEquity();
        double mllFloor = account.getHighestEndOfDayBalance()
                - activeRiskLimits.getMaxLossLimit();
        double availableRoom = equity - mllFloor - sizerSafetyCushion;
        double riskFraction = StdvOteSizer.DEFAULT_RISK_FRACTION;
        if (availableRoom > 0) {
            riskFraction = Math.min(riskFraction,
                    activeRiskLimits.getRiskPerTrade() / availableRoom);
        }
        // Killzone size boost (scalp.killzoneSizeBoost, clamp [1.0, 2.0]):
        // rides the sizer's multiplier slot, so the result is floored and
        // re-clamped against the tier cap, topstepMicroMax, and the [5, 20]
        // band — the boost can request more size, never bypass a cap. The
        // PropFirmRiskEngine still evaluates the final signal.
        double sizeMultiplier = 1.0;
        if (killzoneSizeBoost > 1.0 && lastCandleInstant != null
                && isPrimeKillzone(lastCandleInstant)) {
            sizeMultiplier = killzoneSizeBoost;
        }
        StdvOteSizer.SizingDecision decision = sizer.decide(
                new StdvOteSizer.SizeRequest(entry, stop, spec, tier),
                new StdvOteSizer.SizeContext(equity, mllFloor, sizerSafetyCushion,
                        riskFraction, /* multiplier: 1.0 or killzone boost */ sizeMultiplier,
                        activeRiskLimits.getMaxContracts()));
        if (!decision.shouldTrade()) {
            System.out.println("[" + symbol + "] SCALP sizer stand-down: "
                    + decision.reason() + " (" + decision.detail() + ")");
            return 0;
        }
        if (sizeMultiplier > 1.0) {
            System.out.println("[" + symbol + "] KILLZONE SIZE BOOST x" + sizeMultiplier
                    + " -> " + decision.contracts() + " micros (all caps still applied)");
        }
        return decision.contracts();
    }

    private TradeTier computeTier(SetupContext ctx) {
        // Optional confluence count (O1..O8 from STDV_OTE_MODEL.md §5).
        int opt = 0;
        // O1 counts the PRIME killzone, not the widened all-sessions entry
        // window — otherwise scalp.allSessions would hand every off-hours
        // setup a free confluence point and inflate tiers.
        if (primeKillzoneNow) opt++;                                // O1
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
