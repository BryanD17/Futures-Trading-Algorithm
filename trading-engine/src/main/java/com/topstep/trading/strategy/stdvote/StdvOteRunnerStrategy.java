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
import com.topstep.trading.strategy.session.RearmBiasGuard;
import com.topstep.trading.strategy.session.SessionClassifier;
import com.topstep.trading.strategy.session.SessionConfig;
import com.topstep.trading.strategy.session.SessionGateMode;
import com.topstep.trading.strategy.session.SessionWindow;
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

    /** The mandatory-gate validator; holds the V4 profile seam. */
    private final MandatoryConfluenceValidator validator;
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

    /**
     * {@code stdvOte.rearmOnInvalidated} — DEFAULT TRUE. Lets a LEGACY-mode
     * setup that died without trading arm again, under the same gates the
     * scalp re-arm already enforces (cooldown, killzone open, no open
     * position, risk frequency limits). Set false to restore the pre-fix
     * one-attempt-per-process behaviour.
     */
    private final boolean rearmOnInvalidated;
    /**
     * V5 Agent 05.3 — {@code setup.rearmAfterClose}, DEFAULT TRUE. In BOTH
     * target models a PositionClosedEvent for an EXECUTED signal (position
     * flat) moves IN_TRADE to a re-arm after {@code setup.rearmCooldownBars},
     * under every canRearm gate (no open position, frequency limits,
     * NO_ENTRY/WEEKEND). {@code false} restores the one-trade-per-window
     * discipline: IN_TRADE stays terminal until the setup expires (A/B).
     */
    private final boolean rearmAfterClose;
    /** V5 Agent 05.3: the executed trade of the current setup closed; re-arm pending. */
    private boolean closedAwaitingRearm = false;
    /** V5 Agent 05.3: the current setup emitted a signal and has not ended yet. */
    private boolean emittedSetupActive = false;
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
    /** V5 Agent 05.3: the StrategyContext of the candle being processed. */
    private StrategyContext lastStrategyContext;

    // ── V5 Agent 02: session domain (RC-02 / RC-03) ──────────────────────
    /** Effective M3 gate mode ({@code session.gateMode} x {@code session.allSessions}). */
    private final SessionGateMode gateMode = SessionConfig.effectiveGateMode();
    /** Session window of the current candle (SessionClassifier, candle time). */
    private SessionWindow sessionWindowNow;
    /** Session window of the previous candle (killzone-buffer anchor in SCORING). */
    private SessionWindow lastSessionWindow;
    /** "No double-invalidate on the same bias event" bookkeeping. */
    private final RearmBiasGuard rearmBiasGuard = new RearmBiasGuard();

    /** V2 Agent 06: identity (taggedAt) of the last REACTED zone already
     *  counted as chartReacted_machineSilent — one count per zone. */
    private Instant lastChartOnlyZoneTag;
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

    // AGENT-05.8: the opt-in counter-trend scalp (entry.counterTrendScalp).
    // NULL when the flag is off - nothing below runs and the engine is
    // byte-identical to the pre-05.8 runner. A PositionClosedEvent that
    // arrives while the scalp owns the symbol's order/position is routed
    // HERE, never to the with-trend latch (one position per symbol).
    private final CounterTrendScalp counterTrend;
    private final AtomicBoolean ctPendingClosed = new AtomicBoolean(false);

    // AGENT-05.9: the opt-in INDEPENDENT LTF dealing-range machine
    // (range.ltf.enabled). The HTF runner (the one the engine registers) builds
    // a second, complete runner in LTF mode and feeds it every candle after its
    // own step: own detectors, own StdvOteStrategy core + SetupContext, own
    // DealingRangeTracker (INTRADAY_SWINGS), own M2b evaluator, the same
    // validator chain. NULL when the flag is off - nothing below runs and the
    // engine is byte-identical to the pre-05.9 runner. One position per symbol:
    // whichever machine emits first holds it (the other may arm, never emit).
    /** "HTF" (the dealing-range machine) | "LTF" (the child). */
    private final String machine;
    private final boolean ltfMachine;
    /** LTF machine only: the HTF runner that owns and feeds it. */
    private final StdvOteRunnerStrategy htfParent;
    /** HTF machine only: the LTF child; null when range.ltf.enabled=false. */
    private final StdvOteRunnerStrategy ltf;
    private final LtfRangeConfig ltfConfig;
    /** LTF machine only: range.ltf.maxPerDay counter (CME trading day). */
    private final LtfRangeConfig.DailyQuota ltfQuota = new LtfRangeConfig.DailyQuota();
    /** Log / telemetry label: the symbol (HTF) or "SYM/LTF". */
    private final String logTag;
    private String lastLtfRangeEvent;
    private String lastLtfBiasNote;
    /** True from signal emission until a PositionClosedEvent for this symbol. */
    private volatile boolean positionOpen = false;
    /** AGENT-05 (RC-16): account trade count / day at emission — a release
     *  with no completed trade since then means the signal never executed. */
    private int tradesAtEmit = -1;
    private java.time.LocalDate tradingDayAtEmit;
    /** AGENT-05 (RC-16): a position for this symbol was observed after emission. */
    private boolean positionSeenSinceEmit = false;
    /** AGENT-05.4: the last risk-derived sizing outcome (SIZE deny numbers). */
    private StdvOteSizer.RiskSize lastRiskSize;
    /** Bars left before a re-arm may fire; -1 = no re-arm pending. */
    private int rearmCooldownRemaining = -1;
    /** Previous candle's state, to detect INVALIDATED transitions. */
    private SetupState lastSeenState = SetupState.IDLE;


    /**
     * Timeframe on which the ENTRY ANATOMY (displacement, FVG, MSS/CHoCH)
     * is measured — {@code -Dstdvote.detectorTimeframe} in minutes
     * (1|3|5|15), DEFAULT 5. Field fix 2026-07-09: on raw 1m, MNQ never
     * registers the displacement a human sees on the 5m chart. Structure,
     * sweeps, and levels remain 1m regardless.
     */
    private final Timeframe detectorTimeframe = resolveDetectorTimeframe();

    static Timeframe resolveDetectorTimeframe() {
        int minutes = com.topstep.trading.config.EngineConfig.current().getInt("stdvote.detectorTimeframe", 5);
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

    /** Maximum feed bars allowed in OTE_ARMED before the setup invalidates
     *  (V5 Agent 04 EXPIRE rule: {@code ote.windowBars}, legacy key
     *  {@code stdvOte.oteWindowBars}; detector bars scaled to the feed). */
    private final int maxBarsInOte =
            OteConfig.oteWindowBars() * detectorTimeframe.getMinutes();

    /** Feed bars since MSS in which the impulse is still fresh (see the
     *  FUNNEL CALIBRATION note — 30 DETECTOR bars, scaled to the feed;
     *  V5: {@code mss.freshBars}, legacy {@code stdvOte.mssFreshBars}). */
    private final int mssFreshBars =
            OteConfig.mssFreshBars() * detectorTimeframe.getMinutes();

    /**
     * Entry-fill timeout (2026-07-27 no-trade fix, scalp mode): feed bars
     * IN_TRADE may sit with the position latch set but NO actual position
     * before the runner declares the entry unexecuted (resting limit never
     * filled / order callback lost) and releases the latch so the re-arm
     * engine can hunt again. Default = 2x the OTE window — a limit that
     * has not filled by then belongs to a stale thesis anyway. 0 disables.
     */
    private final int entryTimeoutBars =
            intProperty("stdvOte.entryTimeoutBars", maxBarsInOte * 2);

    /** Consecutive IN_TRADE feed bars observed with no position. */
    private int entryPendingBars = 0;

    /**
     * OBSERVABILITY ONLY (chart-in-memory rollout): the runner's ChartEngine,
     * whose 30m OTE screenshot-pattern signal is logged NEXT TO the existing
     * gate result so a week of SIM logs can be compared before any gating
     * decision. Nothing in this class gates on it. May be null (tests,
     * legacy wiring) — every use is null-guarded.
     */
    private volatile com.topstep.trading.chart.ChartEngine chartEngine;

    /**
     * Publish this symbol's LevelEngine to the ICT library (V4 Agent 03) so
     * §S6 clustered liquidity pools register as equal-high/low levels in the
     * ONE level universe the raid pipeline already reads (Appendix E8).
     *
     * <p>One-directional and additive: ictlib writes levels, it never reads
     * raid state and never marks anything raided. Not calling this simply means
     * pools stay inside ictlib — no gate, detector or raid behaviour changes
     * either way.
     */
    public void setIctLibEngine(com.topstep.trading.ictlib.IctLibEngine engine) {
        if (engine != null) engine.attachLevelEngine(symbol, levelEngine);
    }

    /**
     * The confluence stack (V4 Agent 07). Null = not wired, and everything
     * simply goes unpublished — no gate reads this, so nothing changes.
     */
    private volatile com.topstep.trading.confluence.ConfluenceService confluenceService;

    /** Install the confluence stack (may be null). */
    public void setConfluenceService(
            com.topstep.trading.confluence.ConfluenceService service) {
        this.confluenceService = service;
        // V4 Agent 08: the validator scores the non-STRICT profiles against
        // this same stack, so both read one set of facts.
        if (validator != null) validator.setConfluenceService(service);
    }

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

    /** V5 Agent 04: displacement/FVG/MSS/OTE driver (steps 10-13). */
    private final OteSetupDriver oteDriver;

    // Killzone-open anchoring for the manipulation-leg detector.
    private final List<Candle> killzoneCandles = new ArrayList<>();
    private boolean killzoneActive = false;

    // ── V5 Agent 03: dealing-range bias, session-aware leg, scored sweeps ──
    /** The day's dealing range / impulse leg — the bias anchor (RC-06). */
    private final DealingRangeTracker dealingRange;
    /** True once the tracker has been offered the seeded H1 history. */
    private boolean dealingRangeWarmChecked = false;
    /** Current-session candle buffer for the manipulation leg (task 4). */
    private final SessionLegLocator sessionLegs = new SessionLegLocator();
    /** Scoring context of the latest raid-pipeline pass (reused to score
     *  swing sweeps with the SAME facts). */
    private RaidDetector.RaidDetectionContext lastRaidContext = RaidDetector.RaidDetectionContext.empty();
    /** Identity of the last LEVEL raid consumed as a sweep. */
    private String lastConsumedRaidId;
    /** Sweeps that could not be scored at all (documented fallback, task 7). */
    private long starvedSweeps = 0;
    /** Rollback switches (read once at construction). */
    private final boolean legacySweepMode = BiasConfig.legacySweepMode();
    private final boolean legacyManipLegMode = BiasConfig.legacyManipLegMode();

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
        this(symbol, smtSymbol, eventBus, null);
    }

    /**
     * AGENT-05.9: {@code htfParent != null} builds the LTF machine - a complete
     * runner whose dealing range is the intraday-swing range and whose bias is
     * that range's direction. Only an HTF runner constructs one (when
     * {@code range.ltf.enabled=true}); it is never registered anywhere.
     */
    private StdvOteRunnerStrategy(String symbol, String smtSymbol, EventBus eventBus,
                                  StdvOteRunnerStrategy htfParent) {
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
        // AGENT-05.9: which machine this runner is.
        this.htfParent = htfParent;
        this.ltfMachine = htfParent != null;
        this.machine = ltfMachine ? LtfRangeConfig.MACHINE_LTF : LtfRangeConfig.MACHINE_HTF;
        this.logTag = ltfMachine ? symbol + "/LTF" : symbol;
        this.ltfConfig = LtfRangeConfig.fromEngineConfig(symbol);
        // V5 Agent 05.6: bias.range.window (SESSION_DAY | RTH_FIRST | AUTO)
        // + bias.range.minLegTicks[.<SYM>] in this instrument's ticks.
        // AGENT-05.9: the LTF machine's range = the most recent confirmed 5m
        // swing leg >= range.ltf.minLegTicks[.<SYM>] (INTRADAY_SWINGS).
        this.dealingRange = ltfMachine
                ? DealingRangeTracker.intradaySwings(ltfConfig.minLegTicks() * spec.tickSize())
                : DealingRangeTracker.fromConfig(symbol, spec.tickSize());

        this.stopBufferTicks = intProperty(STOP_BUFFER_TICKS_PROPERTY, DEFAULT_STOP_BUFFER_TICKS);
        this.reactionWickTicks = intProperty(REACTION_WICK_TICKS_PROPERTY, DEFAULT_REACTION_WICK_TICKS);

        // Detectors with sensible defaults from the legacy strategy.
        this.structureDetector = new IctStructureDetector(50);
        this.liquidityDetector = new LiquidityDetector(30);
        this.fvgDetector = new FvgDetector(20);
        // V5 Agent 04 (RC-09) — the ONE displacement source, calibrated on the
        // real tape: range >= displacement.atrMult x ATR14 of the PRIOR bars
        // (true range) AND body >= displacement.bodyPct. Defaults 1.2 / 0.50
        // (see OteConfig for the tape numbers).
        double dispAtrMult = OteConfig.displacementAtrMult();
        double dispBodyPct = OteConfig.displacementBodyPct();
        this.displacementDetector = new DisplacementDetector(20, dispAtrMult, dispBodyPct, symbol)
                .usePriorTrueRangeAtr(OteConfig.DISPLACEMENT_ATR_LEN);
        // V5 Agent 04 — the ONE MSS source (M6): close beyond the most
        // recent opposite swing; ictlib's shadow uses the same factory.
        this.mssDetector = MarketStructureShiftDetector.forStdvOte();
        System.out.println(OteConfig.describe());
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
        if (!ltfMachine) com.topstep.trading.strategy.HtfSeriesRegistry.register(symbol, barManager);

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
        // Kept as a field (V4 Agent 08) so the confluence stack can be handed
        // to it after construction — the profile seam lives inside it.
        this.validator = validator;
        // The M7 RR band comes from the ACTIVE RiskLimits' signal band:
        // legacy → topstep50k() carries [2.0, +inf) (identical to the old
        // hardcoded floor); scalp → topstep50kScalp() carries [0.8, 1.5].
        this.activeRiskLimits = ScalpConfig.activeRiskLimits();
        validator.setActiveRiskLimits(activeRiskLimits);
        // M2b premium/discount gate (V3 Agent 02): evaluator reads the
        // governing range from THIS runner's LevelEngine at gate time;
        // default mode is LOG (counts, never blocks).
        this.pdEvaluator = PremiumDiscountEvaluator.install(
                symbol, spec.tickSize(), levelEngine, !ltfMachine);   // AGENT-05.9: LTF = unregistered
        validator.setPremiumDiscountEvaluator(pdEvaluator);
        // V5 Agent 03: M2b judges the entry against the day's DEALING RANGE
        // (the same range the bias is read from), ahead of R0/R1/R2.
        pdEvaluator.configureDealingRangeSource(() -> {
            DealingRangeTracker.Snapshot r = dealingRange.snapshot();
            return r.decisive() ? new double[] {r.high(), r.low()} : null;
        });
        // 3-of-4 bias vote (V3 Agent 03): V2's AMD tracker joins the live
        // path (it previously fed only the legacy strategy); default mode
        // LOG — the vote runs and counts agreement, legacy still decides.
        this.biasVoteEngine = BiasVoteEngine.install(symbol, spec.tickSize(), !ltfMachine);
        this.amdTracker = new com.topstep.trading.strategy.DailyAmdCycleTracker(symbol);
        // M7b 30m-OTE confluence gate (V3 Agent 06): default LOG — the
        // V2 log-only comparison, formalized through counters; GATE is one
        // flag away once the promote criteria are met.
        this.ote30mGate = Ote30mConfluenceGate.install(symbol, !ltfMachine);
        validator.setOte30mConfluenceGate(ote30mGate);
        this.core = new StdvOteStrategy(symbol, projectionEngine, oteCalculator, validator,
                eventBus, /* expiryBars, feed bars (see FUNNEL CALIBRATION) */
                setupExpiryFeedBars, /* AGENT-05.9: only the HTF core registers */ !ltfMachine);
        if (ltfMachine) {
            core.configureLtfMachine();
            SetupContext lc = core.getSetupContext();
            lc.ltfGating = ltfConfig.gating().name();
            lc.ltfMinLegTicks = ltfConfig.minLegTicks();
            lc.ltfRiskFraction = ltfConfig.riskFraction();
        }
        // AGENT-02 (RC-03): setup lifecycle — expiry anchor + budgets.
        SessionConfig.ExpiryAnchor expiryAnchor = SessionConfig.expiryAnchor(gateMode);
        int huntFeedBars = SessionConfig.expiryFeedBars(detectorTimeframe.getMinutes(), expiryAnchor);
        int preSweepFeedBars = SessionConfig.preSweepExpiryFeedBars();
        core.configureExpiry(expiryAnchor, huntFeedBars, preSweepFeedBars);
        // AGENT-05.1 (S2): PHASED anchor — one budget per post-sweep phase.
        SessionConfig.PhaseBudgets phaseBudgets = SessionConfig.phaseBudgets();
        core.configurePhaseBudgets(phaseBudgets.sweepToDisplacement(),
                phaseBudgets.displacementToMss(), phaseBudgets.mssToOte());
        System.out.println("[StdvOteRunnerStrategy] " + symbol + " SESSION GATE: " + gateMode
                + (gateMode == SessionGateMode.SCORING
                        ? " (entries allowed all sessions except 14:45-17:00 CT and the weekend; prime killzones score O1 + size)"
                        : " (legacy killzones block M3 and re-arm)")
                + " | expiry anchor=" + expiryAnchor
                + (expiryAnchor == SessionConfig.ExpiryAnchor.PHASED
                        ? " sweep→displacement=" + phaseBudgets.sweepToDisplacement()
                            + " displacement→MSS=" + phaseBudgets.displacementToMss()
                            + " MSS→OTE=" + phaseBudgets.mssToOte() + " min (feed bars)"
                            + " OTE→emit=ote.windowBars preSweep=" + preSweepFeedBars + " min"
                        : expiryAnchor == SessionConfig.ExpiryAnchor.SWEEP_DONE_TOTAL
                        ? " hunt=" + huntFeedBars + " feed bars (" + huntFeedBars + " min, "
                            + (huntFeedBars / Math.max(1, detectorTimeframe.getMinutes())) + " detector bars)"
                            + " preSweep=" + preSweepFeedBars + " min"
                        : " budget=" + setupExpiryFeedBars + " feed bars from BIAS_SET"));
        // V5 Agent 04: post-sweep funnel (displacement → FVG → MSS → OTE
        // arm / alarm / invalidate) — shared with the golden-case tests.
        this.oteDriver = new OteSetupDriver(logTag, spec.tickSize(), displacementDetector,
                ltfMachine ? null : eventBus, detectorTimeframe.getMinutes());
        System.out.println("[StdvOteRunnerStrategy] " + symbol
                + " funnel windows (feed bars): expiry=" + setupExpiryFeedBars
                + " oteWindow=" + maxBarsInOte + " mssFresh=" + mssFreshBars
                + " (detector " + detectorTimeframe.getLabel() + ")");

        // Scalp mode (SA3 target model + SA4 frequency/gates). All the
        // sequential mandatory gates run exactly as in legacy mode.
        this.scalpMode = ScalpConfig.isEnabled();
        this.liquidityTargets = new LiquidityTargetIdentifier(symbol, levelEngine);
        this.rearmCooldownBars = SessionConfig.rearmCooldownBars(); // AGENT-02: setup.rearmCooldownBars → scalp.rearmCooldownBars
        // DEFECT FIX (V4 follow-up): in LEGACY mode the re-arm engine never
        // ran, so an INVALIDATED setup was terminal for the LIFE OF THE
        // PROCESS — one dead setup per symbol and the engine was finished for
        // the day. Measured on SIM: 5 state transitions in an entire run, then
        // 265 consecutive samples sitting in INVALIDATED.
        //
        // "One-move discipline" is meant to bound TRADES, not ATTEMPTS. A
        // setup that died without trading has consumed no risk and used no
        // trade allowance, so refusing to look again is not discipline, it is
        // a stall. IN_TRADE stays terminal in legacy mode — that part IS the
        // discipline and is unchanged.
        this.rearmOnInvalidated = com.topstep.trading.config.EngineConfig.current().getBoolean(
                "stdvOte.rearmOnInvalidated", true);
        this.rearmAfterClose = SessionConfig.rearmAfterClose(); // AGENT-05.3
        this.londonPrimeStartEt = ScalpConfig.londonPrimeStartEt();
        this.londonPrimeEndEt = ScalpConfig.londonPrimeEndEt();
        this.allSessions = ScalpConfig.allSessions();
        this.killzoneSizeBoost = ScalpConfig.killzoneSizeBoost();
        this.sizerSafetyCushion = ScalpConfig.sizerSafetyCushion();
        if (!scalpMode && !BiasConfig.legacySweepMode()) {
            // V5 Agent 03 (RC-07): legacy mode applies M4's own number at
            // sweep time too — one floor, checked where the sweep is taken.
            core.setLegacyMinRaidScore(spec.raidMinQuality());
        }
        // AGENT-05 (V5 RC-16): observe the position-close funnels in BOTH
        // modes. Legacy used to subscribe only in scalp mode, so a legacy
        // signal the risk engine denied (released via a synthetic
        // PositionClosedEvent) left the machine IN_TRADE for 200 minutes.
        // The handler only flips a flag — all state mutation happens on the
        // candle thread (SetupContext is thread-confined).
        this.counterTrend = (CounterTrendScalp.Config.enabledInConfig() && !ltfMachine)
                ? new CounterTrendScalp(symbol, CounterTrendScalp.Config.fromEngineConfig(), spec.tickSize())
                : null;
        if (counterTrend != null) {
            System.out.println("[StdvOteRunnerStrategy] " + symbol + " COUNTER-TREND SCALP ON ("
                    + counterTrend.config().describe() + ")");
        }
        // AGENT-05.9: the LTF child (flag on, HTF runner only).
        this.ltf = (!ltfMachine && ltfConfig.enabled())
                ? new StdvOteRunnerStrategy(symbol, smtSymbol, eventBus, this) : null;
        if (ltf != null) {
            System.out.println("[StdvOteRunnerStrategy] " + symbol + " LTF MACHINE ON ("
                    + ltfConfig.describe() + ")");
        }
        if (eventBus != null && !ltfMachine) {
            eventBus.subscribe(PositionClosedEvent.class, evt -> {
                if (this.symbol.equals(evt.getSymbol())) {
                    CounterTrendScalp ct = this.counterTrend;
                    StdvOteRunnerStrategy lm = this.ltf;
                    if (ct != null && ct.ownsPosition()) {
                        ctPendingClosed.set(true);          // AGENT-05.8: the scalp's close / release
                    } else if (lm != null && lm.positionOpen) {
                        lm.pendingPositionClosed.set(true); // AGENT-05.9: the LTF machine's close / release
                    } else {
                        pendingPositionClosed.set(true);
                    }
                }
            });
        }
        if (scalpMode) {
            core.enableScalpMode(ScalpConfig.targetCalculator(), ScalpConfig.minRaidScore());
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

    /** AGENT-05.8: the counter-trend scalp's own SetupContext (null when entry.counterTrendScalp=false). */
    public SetupContext getCounterTrendContext() {
        return counterTrend == null ? null : counterTrend.context();
    }

    /** AGENT-05.9: the LTF machine's own SetupContext (null when range.ltf.enabled=false). */
    public SetupContext getLtfContext() {
        return ltf == null ? null : ltf.getSetupContext();
    }

    /** AGENT-05.9: the LTF machine's current dealing range (EMPTY when the flag is off). */
    public DealingRangeTracker.Snapshot getLtfRange() {
        return ltf == null ? DealingRangeTracker.Snapshot.EMPTY : ltf.dealingRange.snapshot();
    }

    /** AGENT-05.9: the LTF tracker's last rebuild / flip description (null when off / none yet). */
    public String getLtfRangeEvent() {
        return ltf == null || ltf.dealingRange.swingRange() == null ? null
                : ltf.dealingRange.swingRange().lastEvent();
    }

    /** AGENT-05.9: "HTF" | "LTF". */
    public String machine() {
        return machine;
    }

    /** AGENT-05.9 test hook: the LTF child (null when the flag is off). */
    StdvOteRunnerStrategy ltfForTest() {
        return ltf;
    }

    /** AGENT-05.9 test hook: LTF emissions on the trading day of {@code now}. */
    int ltfEmitsOnForTest(Instant now) {
        return ltfQuota.emitsOn(now);
    }

    /** AGENT-05.8 test hook: the scalp (null when the flag is off). */
    CounterTrendScalp counterTrendForTest() {
        return counterTrend;
    }

    // Test hooks for the entry-fill timeout (2026-07-27 no-trade fix).
    void latchForTest() { this.positionOpen = true; }
    boolean isPositionOpenForTest() { return positionOpen; }

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
            if (ltf != null) ltf.onSmtCandle(candle);   // AGENT-05.9
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

        // Funnel census (V4 follow-up): snapshot the setup state BEFORE ANY of
        // this candle's processing — including core.onCandle's expiry check,
        // which is where most invalidations actually happen. Bracketing only
        // the later funnel steps was the first version of this and it reported
        // zeros while the state was visibly changing: the transitions were
        // firing outside the bracket. Measure the whole candle or measure
        // nothing.
        final SetupState funnelStateBefore = core.getSetupContext().state;
        final FunnelTelemetry funnel = FunnelTelemetry.forSymbol(logTag);
        funnel.rollSessionIfNeeded(now);

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
            oteDriver.onAnatomyCandle(anatomyCandle, observedMss);
        }
        oteDriver.onFeedCandle(candle, core.getSetupContext());
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
        // V5 Agent 03 (RC-06/RC-07): the dealing range advances on every 1m
        // bar (warm-booted once from the seeded H1 ladder when present) and
        // is published for M2b / Agent 04 BEFORE the raid pass so the
        // premium/discount factor reads this bar's range.
        if (!dealingRangeWarmChecked) {
            dealingRangeWarmChecked = true;
            // AGENT-05.9: the LTF range is built from its own 5m swings only.
            List<Candle> seededH1 = ltfMachine ? null : barManager.getCandlesSnapshot(Timeframe.H1, 500);
            if (seededH1 != null && !seededH1.isEmpty() && !dealingRange.hasData()) {
                dealingRange.warm(seededH1);
                System.out.println("[BIAS " + symbol + "] dealing range warm-booted from "
                        + seededH1.size() + " seeded H1 bars -> " + dealingRange.snapshot());
            }
        }
        dealingRange.onCandle(candle);
        sessionLegs.onCandle(candle);
        DealingRangeTracker.Snapshot range = dealingRange.snapshot();
        SetupContext rangeCtx = core.getSetupContext();
        rangeCtx.rangeHigh = range.high();
        rangeCtx.rangeLow = range.low();
        rangeCtx.rangeEq = range.equilibrium();
        if (ltfMachine) stampLtfContext(rangeCtx, range);   // AGENT-05.9
        // The HTF-opposes penalty uses the SAME bias M2 judges: the setup's
        // direction while one is live, else the latest evaluation.
        MarketBias scoringBias = (rangeCtx.htfBias != MarketBias.NEUTRAL) ? rangeCtx.htfBias : lastBias;
        Boolean htfBullish = (scoringBias == MarketBias.BULLISH) ? Boolean.TRUE
                : (scoringBias == MarketBias.BEARISH) ? Boolean.FALSE : null;
        boolean displacementEntry = scoringBias != MarketBias.NEUTRAL
                && displacementDetector.hasLayer3EntryTrigger(5, scoringBias == MarketBias.BULLISH);
        lastRaidContext = RaidDetector.RaidDetectionContext.fullWithCascade(
                hasSmt, htfBullish, htfTrend.getTrendState().isStrong(),
                /* zoneConfluenceScore */ 0, displacementEntry, /* targetAlignmentBonus */ 0)
                .withRangeEquilibrium(range.decisive() ? range.equilibrium() : Double.NaN);
        raidDetector.processCandle(candle, lastRaidContext);

        // 4. Post-sweep extremes + post-MSS impulse tracking.
        if (!Double.isNaN(lowSinceSweep)) {
            lowSinceSweep = Math.min(lowSinceSweep, candle.getLow());
            highSinceSweep = Math.max(highSinceSweep, candle.getHigh());
        }
        impulseTracker.onCandle(candle.getHigh(), candle.getLow());

        // 5. Killzone bookkeeping: buffer candles from the killzone open so
        // the manipulation-leg detector can anchor the Judas swing there.
        lastCandleInstant = now;
        lastStrategyContext = context;   // AGENT-05.3 (rearm → endEmittedSetup)
        // V5 Agent 02: ONE classifier, candle time. SCORING: the "killzone"
        // (M3 gate + re-arm gate) is open whenever the window is not
        // NO_ENTRY / WEEKEND; BLOCKING: the legacy killzones, unchanged.
        lastSessionWindow = sessionWindowNow;
        sessionWindowNow = SessionClassifier.classify(now);
        boolean inKillzone = isInstrumentKillzone(now);
        boolean primeBefore = primeKillzoneNow;
        // Prime-killzone flag for tier confluence (O1) and the size boost.
        // SCORING: the SessionClassifier prime windows. BLOCKING: legacy —
        // scalp prime windows, or (legacy target model) the killzone itself.
        primeKillzoneNow = (gateMode == SessionGateMode.SCORING)
                ? SessionClassifier.isPrimeKillzone(now)
                : (scalpMode ? isPrimeKillzone(now) : inKillzone);
        // Buffer anchor: the killzone open (BLOCKING) or, in SCORING — where
        // the gate is open all session long — the most recent of (a) the
        // open of the CURRENT session window and (b) the open of a prime
        // killzone inside it (09:45 / 13:45 ET: the exact pre-V5 NY anchors),
        // so the Judas-swing detector anchors per session.
        boolean newAnchor = !killzoneActive
                || (gateMode == SessionGateMode.SCORING
                        && (sessionWindowNow != lastSessionWindow
                            || (primeKillzoneNow && !primeBefore)));
        if (inKillzone && newAnchor) {
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
        ctx.sessionWindow = sessionWindowNow.name();   // AGENT-02
        ctx.primeKillzone = primeKillzoneNow;         // AGENT-02

        // 5b. SCALP re-arm engine (SA4). Legacy mode: none of this runs —
        // IN_TRADE / INVALIDATED stay terminal (one-move discipline).
        if (scalpMode) {
            processScalpRearm(ctx, context, inKillzone);
        } else if (rearmOnInvalidated) {
            processLegacyRearm(ctx, context, inKillzone);
        }

        // 6. HTF bias hook — completed HTF bar close ONLY. V2 Agent 04:
        // record EVERY completed-bar evaluation (not just changes) — the
        // hysteresis grace counts CONSECUTIVE NEUTRAL 15m evaluations, and
        // repeated same-bias records are idempotent in the core (INVALIDATED
        // sits above IN_TRADE, so a dead setup ignores repeats).
        // AGENT-05.9: the LTF machine never votes - its bias is its own range's
        // direction, recorded on every completed detector (5m) bar below.
        if (ltfMachine && anatomyCandle != null) {
            recordLtfBias();
        }
        if (htfBarClosed && !ltfMachine) {
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
                        legacyBias,
                        // V5 Agent 03 (RC-06): the dealing-range anchor.
                        dealingRange.snapshot());
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
        if (completedHtf.containsKey(Timeframe.M15) && !ltfMachine) {
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

            // V4 Agent 07 — publish the facts this bar already computed and
            // emit the [CONFLUENCE] rollup on the SAME cadence as [GATES], so
            // the two lines always describe the same instant.
            com.topstep.trading.confluence.ConfluenceService cs = confluenceService;
            if (cs != null) {
                cs.publish(symbol, new com.topstep.trading.confluence.EngineFacts(
                        candle.getTimestamp(),
                        candle.getClose(),
                        killzoneActive,
                        ctx.htfBias,
                        biasVoteEngine.mode() == BiasVoteEngine.VoteMode.LEGACY
                                ? null : lastBias,
                        biasVoteEngine.gatesToken(),
                        pdEvaluator.gateCheck(candle.getClose(),
                                ctx.htfBias == MarketBias.BULLISH).passed(),
                        pdEvaluator.gatesToken(candle.getClose()),
                        ctx.sweep != null,
                        ctx.raidScore,
                        ctx.state == null ? null : ctx.state.name()));
                System.out.println(cs.logLine(symbol));
            }
            // V4 Agent 08 — sample every profile against the setup AS IT
            // STANDS, then print the "why no trade" number. The validator seam
            // covers real emission attempts; this covers the far more common
            // case where the funnel never gets that far.
            com.topstep.trading.trade.ProfileSimulator sim =
                    com.topstep.trading.trade.ProfileSimulator.forSymbol(symbol);
            if (cs != null) {
                sim.evaluate(ctx, ctx.lastGateFailed, false,
                        com.topstep.trading.trade.ProfileSimulator.snapshotFor(cs, symbol, ctx),
                        candle.getTimestamp(), false);
            }
            System.out.println(sim.logLine());
            System.out.println(funnel.logLine());
        }

        // 6c. Crash-safe agreement-stats checkpoint every completed 30m bar
        // (V3 Agent 06) — the loader collapses same-session lines last-wins,
        // so re-checkpointing can never double count.
        if (completedHtf.containsKey(Timeframe.M30) && !ltfMachine) {
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

        // 9. From MANIP_DONE, look for the sweep. V5 Agent 03: from
        // SWEEP_DONE (no displacement yet) a NEWER raid of a KNOWN level in
        // the same direction replaces a weaker sweep — the latest liquidity
        // grab before displacement is the one the model trades (G1: the
        // 14:52 raid of the 30640 London high).
        if (ctx.state == SetupState.MANIP_DONE) {
            tryRecordSweep(candle);
        } else if (ctx.state == SetupState.SWEEP_DONE && !legacySweepMode) {
            tryRefreshSweep(candle);
        }

        // 10. From SWEEP_DONE, look for displacement + FVG in bias direction.
        if (ctx.state == SetupState.SWEEP_DONE) {
            tryRecordDisplacement(candle);
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
                System.out.println("[" + logTag + "] OTE_ARMED (live gate path)"
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
            // V5 Agent 04 ALARM: emit only once a PD array overlaps the band
            // AND price reacted (or on retries after that).
            if (oteDriver.alarm(core, candle)) {
                tryEmitOrder(context);
            } else if (oteDriver.lastStall() != null) {
                funnel.recordStall("OTE_ARMED", oteDriver.lastStall());
                // AGENT-05.4: an ALARM stall is a reason, not silence.
                if (ctx.state == SetupState.OTE_ARMED) alarmStallDiagnostic(oteDriver.lastStall(), candle);
            } else if (ctx.state == SetupState.OTE_ARMED) {
                // AGENT-05.4: alarm refused without a stall (defensive).
                armedDiagnostic("ALARM", "ALARM: not fired (driver gave no stall reason)",
                        candle.getClose(), Double.NaN);
            }
            // AGENT-05.4 invariant: OTE_ARMED at the end of this step with no
            // emission ALWAYS carries a reason in SetupContext.
            if (ctx.state == SetupState.OTE_ARMED && ctx.lastGateFailed == null) {
                armedDiagnostic("EMIT", "EMIT: armed, attempt produced no signal and no reason",
                        candle.getClose(), Double.NaN);
            }
            if (ctx.state == SetupState.IN_TRADE && !ltfMachine) {
                OteAgreementStats stats = OteAgreementStats.forSymbol(symbol);
                if (chartAgreedAtEmission) {
                    stats.recordMachineEmittedChartAgreed(candle.getTimestamp());
                } else {
                    stats.recordMachineEmittedChartDisagreed(candle.getTimestamp());
                }
            }
        }

        // V5 Agent 04: OTE invalidation events + per-setup driver reset.
        oteDriver.afterCandle(ctx, candle);

        // Funnel census: one EVENT per real transition, with the reason when
        // the setup died. Measurement only.
        funnel.recordTransition(funnelStateBefore, ctx.state, ctx.lastGateFailed);

        // AGENT-05.3 (S-2): the emitting setup left IN_TRADE (any reason) —
        // cancel its unfilled entry.
        if (emittedSetupActive && ctx.state != SetupState.IN_TRADE) {
            endEmittedSetup(ctx.lastGateFailed, context);
        }

        // AGENT-05.4: a new OTE_ARMED episode republishes its diagnostics.
        if (ctx.state != SetupState.OTE_ARMED) lastArmedDiagnostic = null;

        // AGENT-05.8: the opt-in counter-trend scalp runs AFTER the with-trend
        // machine (the with-trend setup has priority on a shared bar).
        if (counterTrend != null) {
            stepCounterTrend(candle, context, range);
        }

        // Remember the state for INVALIDATED-transition detection (SA4).
        lastSeenState = ctx.state;

        // AGENT-05.9: the LTF machine runs AFTER this machine (and after the
        // counter-trend scalp) on the SAME candle - the HTF setup has priority
        // on a shared bar.
        if (ltf != null) {
            ltf.onCandle(candle, context);
        }
    }

    // ======================================================================
    // AGENT-05.9 - the LTF machine's own bias and context numbers
    // ======================================================================

    /** LTF machine: the range numbers every gate re-checks (M2 size, HTF_ALIGNED facts). */
    private void stampLtfContext(SetupContext ctx, DealingRangeTracker.Snapshot range) {
        ctx.machine = LtfRangeConfig.MACHINE_LTF;
        ctx.ltfGating = ltfConfig.gating().name();
        ctx.ltfMinLegTicks = ltfConfig.minLegTicks();
        ctx.ltfRiskFraction = ltfConfig.riskFraction();
        ctx.ltfRangeTicks = range.decisive()
                ? Math.round((range.high() - range.low()) / spec.tickSize()) : Double.NaN;
        SetupContext h = htfParent.core.getSetupContext();
        ctx.ltfHtfBias = htfParent.lastBias;
        ctx.ltfHtfRangeHigh = h.rangeHigh;
        ctx.ltfHtfRangeLow = h.rangeLow;
        ctx.ltfHtfEq = h.rangeEq;
        DealingRangeTracker.SwingRange sr = dealingRange.swingRange();
        String ev = sr == null ? null : sr.lastEvent();
        if (ev != null && !ev.equals(lastLtfRangeEvent)) {
            lastLtfRangeEvent = ev;
            System.out.println("[" + logTag + "] LTF RANGE " + ev + " eq " + range.equilibrium()
                    + " | HTF " + htfParent.lastBias + " [" + h.rangeLow + "," + h.rangeHigh + "]");
        }
    }

    /**
     * LTF machine: the bias = the LTF range's direction (NEUTRAL until a leg of
     * range.ltf.minLegTicks exists). INDEPENDENT: that is the bias. HTF_ALIGNED
     * (comparison): a direction that differs from the HTF machine's bias reads
     * NEUTRAL (the machine does not hunt it; M2/M2b re-check at emission).
     */
    private void recordLtfBias() {
        DealingRangeTracker.Snapshot r = dealingRange.snapshot();
        MarketBias b = r.decisive() ? r.direction() : MarketBias.NEUTRAL;
        MarketBias htf = htfParent.lastBias;
        String note = null;
        if (b != MarketBias.NEUTRAL && ltfConfig.gating() == LtfRangeConfig.Gating.HTF_ALIGNED && b != htf) {
            note = "LTF bias " + b + " != HTF bias " + htf + " (range.ltf.gating=HTF_ALIGNED) -> NEUTRAL";
            b = MarketBias.NEUTRAL;
        }
        if (note != null && !note.equals(lastLtfBiasNote)) {
            System.out.println("[" + logTag + "] " + note);
        }
        lastLtfBiasNote = note;
        core.recordHtfBias(b);
        lastBias = b;
    }

    /** True when the OTHER machine (or the counter-trend scalp) holds this symbol's order / position. */
    private boolean siblingOwnsPosition() {
        if (ltfMachine) {
            return htfParent.positionOpen
                    || (htfParent.counterTrend != null && htfParent.counterTrend.ownsPosition());
        }
        return ltf != null && ltf.positionOpen;
    }

    /**
     * LTF machine emission gates outside the validator: one position per
     * symbol, range.ltf.sessions, range.ltf.maxPerDay. Every refusal is a
     * reason (lastGateFailed + one GateDecisionEvent).
     */
    private boolean ltfEmitAllowed(StrategyContext context) {
        boolean accountPosition = context != null && context.hasPosition(symbol);
        if (siblingOwnsPosition() || accountPosition || positionOpen) {
            armedDiagnostic("POSITION", "POSITION: " + symbol + " held by the "
                    + (htfParent.positionOpen ? "HTF machine" : accountPosition ? "account" : "counter-trend scalp")
                    + " (one position per symbol; LTF emits once it is flat)",
                    htfParent.positionOpen ? 1 : 0, accountPosition ? 1 : 0);
            return false;
        }
        if (!ltfConfig.sessionAllowed(sessionWindowNow)) {
            armedDiagnostic("LTF", "LTF: session " + sessionWindowNow + " not in range.ltf.sessions "
                    + ltfConfig.sessions(), Double.NaN, Double.NaN);
            return false;
        }
        if (!ltfQuota.left(lastCandleInstant, ltfConfig.maxPerDay())) {
            armedDiagnostic("LTF", "LTF: range.ltf.maxPerDay " + ltfConfig.maxPerDay() + " reached ("
                    + ltfQuota.emitsOn(lastCandleInstant) + " today)",
                    ltfQuota.emitsOn(lastCandleInstant), ltfConfig.maxPerDay());
            return false;
        }
        return true;
    }

    // ======================================================================
    // AGENT-05.4 - no silent stand-down. Every OTE_ARMED bar that does not
    // emit writes its reason into SetupContext.lastGateFailed (self-written,
    // cleared at the top of the next attempt, so a later re-plan retries)
    // and publishes ONE GateDecisionEvent per distinct reason per armed
    // episode with the two numbers. /api/setup, the autopsy CSV and M9 all
    // see it. DIAGNOSIS_V5 D-07 can no longer happen silently.
    // ======================================================================

    /** Last diagnostic published for the current OTE_ARMED episode. */
    private String lastArmedDiagnostic;

    private void armedDiagnostic(String gate, String reason, double numberA, double numberB) {
        core.writeArmedDiagnostic(reason);
        publishArmedDecision(gate, reason, numberA, numberB);
    }

    /** Publish (deduplicated per episode) one GateDecisionEvent for an armed refusal. */
    private void publishArmedDecision(String gate, String reason, double numberA, double numberB) {
        if (reason == null || reason.equals(lastArmedDiagnostic)) return;
        lastArmedDiagnostic = reason;
        // AGENT-05.9: every LTF decision is tagged (reason starts with STDV_OTE_LTF:).
        if (ltfMachine) reason = LtfRangeConfig.REASON_PREFIX + " " + reason;
        System.out.println("[" + logTag + "] OTE_ARMED, not emitted: " + reason);
        if (eventBus != null) {
            com.topstep.trading.event.EngineTelemetry.publish(eventBus,
                    new com.topstep.trading.event.GateDecisionEvent(symbol, lastCandleInstant,
                            lastCandleInstant == null ? null
                                    : com.topstep.trading.event.EngineTelemetry.sessionOf(lastCandleInstant),
                            String.valueOf(core.getSetupContext().state), gate, reason, numberA, numberB));
        }
    }

    /** Gate token of a core-written reason ("M7: ..." -> "M7"). */
    private static String gateOf(String reason) {
        int colon = reason.indexOf(':');
        return colon > 0 && colon < 24 ? reason.substring(0, colon).trim() : "VALIDATOR";
    }

    /** The OTE driver's alarm stall, as a SetupContext reason with numbers. */
    private void alarmStallDiagnostic(String stall, Candle candle) {
        SetupContext ctx = core.getSetupContext();
        OteZone z = ctx.ote;
        double lo = z == null ? Double.NaN : Math.min(z.f62(), z.f79());
        double hi = z == null ? Double.NaN : Math.max(z.f62(), z.f79());
        String band = z == null ? "?" : "[" + lo + "," + hi + "]";
        double swept = ctx.sweep == null ? Double.NaN : ctx.sweep.getSweptLevel();
        switch (stall) {
            case "no-entry-block-14:45-17:00CT":
                armedDiagnostic("NO_ENTRY", "NO_ENTRY: 14:45-17:00 CT no-entry block (armed, band " + band + ")",
                        candle.getClose(), Double.NaN);
                return;
            case "impulse-no-pd-array-at-sweep":
                armedDiagnostic("ALARM", "ALARM: impulse-no-pd-array-at-sweep (no sweep/ICT OB, rejection wick or"
                        + " FVG/OB at swept " + swept + " overlaps band " + band + ")", swept, lo);
                return;
            case "no-pd-array-overlapping-band":
                armedDiagnostic("ALARM", "ALARM: no-pd-array-overlapping-band (band " + band + ")", lo, hi);
                return;
            case "impulse-awaiting-rejection":
                armedDiagnostic("ALARM", "ALARM: impulse-awaiting-rejection (need a close back beyond swept "
                        + swept + " in the trade direction, band " + band + ")", swept, lo);
                return;
            case "no-reaction-at-band":
                armedDiagnostic("ALARM", "ALARM: no-reaction-at-band (band " + band + ")", lo, hi);
                return;
            default:
                armedDiagnostic("ALARM", "ALARM: " + stall + " (band " + band + ")", lo, hi);
        }
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
        rearmBiasGuard.observeBias(lastBias); // AGENT-02: bias-event bookkeeping first
        // Apply the async close notification on the candle thread. The
        // cooldown starts on the detection candle and counts FULL bars —
        // the decrement below is skipped on the detection candle itself.
        boolean detectedThisBar = false;
        if (pendingPositionClosed.compareAndSet(true, false)) {
            positionOpen = false;
            // AGENT-05.3: setup.rearmAfterClose=false keeps IN_TRADE terminal (A/B).
            if (ctx.state == SetupState.IN_TRADE && rearmCooldownRemaining < 0 && rearmAfterClose) {
                rearmCooldownRemaining = rearmCooldownBars;
                detectedThisBar = true;
                System.out.println("[" + logTag + "] SCALP: position closed — re-arm in "
                        + rearmCooldownBars + " bars");
            }
        }
        // ── Entry-fill timeout (2026-07-27): emission happened but no
        // position ever materialized (unfilled resting limit, vetoed order
        // whose release event was lost, broken callback). Without this the
        // positionOpen latch blocks re-arm until process restart.
        if (ctx.state == SetupState.IN_TRADE && positionOpen && entryTimeoutBars > 0) {
            boolean hasPosition = context != null && context.hasPosition(symbol);
            if (hasPosition) {
                entryPendingBars = 0;
            } else if (++entryPendingBars > entryTimeoutBars) {
                entryPendingBars = 0;
                positionOpen = false;
                System.out.println("[" + logTag + "] SCALP: entry not filled within "
                        + entryTimeoutBars + " bars — releasing latch, setup invalidated");
                core.invalidate("entry not filled within " + entryTimeoutBars + " bars");
            }
        } else {
            entryPendingBars = 0;
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
            if (suppressDuplicateBiasInvalidation(ctx, context)) {
                return;
            }
            rearmCooldownRemaining = rearmCooldownBars;
            detectedThisBar = true;
            System.out.println("[" + logTag + "] SCALP: setup invalidated ("
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

    /**
     * LEGACY-mode re-arm (V4 follow-up defect fix). Only an INVALIDATED setup
     * — one that died WITHOUT trading — is re-armed; IN_TRADE remains terminal
     * so the one-trade-per-window discipline is untouched.
     *
     * <p>Every other gate is the scalp engine's, reused rather than
     * reimplemented: the cooldown, an open killzone, no open position on the
     * symbol, and the PropFirmRiskEngine frequency limits. A re-arm can
     * therefore never create a trade the risk engine would have refused.
     */
    private void processLegacyRearm(SetupContext ctx, StrategyContext context,
                                    boolean inKillzone) {
        rearmBiasGuard.observeBias(lastBias); // AGENT-02: bias-event bookkeeping first
        // AGENT-05 (V5 RC-16): the latch. A position observed after emission
        // marks the signal as EXECUTED (IN_TRADE stays terminal in legacy —
        // one-trade discipline). A release event (PositionClosedEvent) for a
        // signal that NEVER executed — risk deny, warmup drop, order failure,
        // SIM order TTL — invalidates the setup so the normal legacy re-arm
        // applies instead of sitting IN_TRADE until setupExpiryBars.
        if (ctx.state == SetupState.IN_TRADE && context != null && context.hasPosition(symbol)) {
            positionSeenSinceEmit = true;
        }
        if (pendingPositionClosed.compareAndSet(true, false)) {
            positionOpen = false;
            if (ctx.state == SetupState.IN_TRADE && !executedSinceEmit(context)) {
                System.out.println("[" + logTag + "] signal released without execution"
                        + " — legacy latch cleared, setup invalidated for re-arm");
                core.invalidate("signal not executed (released)");
            } else if (ctx.state == SetupState.IN_TRADE && rearmAfterClose
                    && !closedAwaitingRearm && rearmCooldownRemaining < 0) {
                // AGENT-05.3 (S-1): the EXECUTED trade closed (position flat).
                // Legacy used to sit IN_TRADE until "expired (200 bars)" and
                // missed the next setup of the window (09-28 14:53 G1).
                closedAwaitingRearm = true;
                rearmCooldownRemaining = rearmCooldownBars;
                System.out.println("[" + logTag + "] position closed — re-arm in "
                        + rearmCooldownBars + " bars (setup.rearmAfterClose=true)");
                return;
            }
        }
        if (ctx.state == SetupState.INVALIDATED && rearmCooldownRemaining < 0) {
            if (suppressDuplicateBiasInvalidation(ctx, context)) {
                return;
            }
            rearmCooldownRemaining = rearmCooldownBars;
            System.out.println("[" + logTag + "] setup invalidated ("
                    + ctx.lastGateFailed + ") — re-arm in " + rearmCooldownBars + " bars");
            return;
        }
        if (rearmCooldownRemaining > 0) {
            rearmCooldownRemaining--;
        } else if (rearmCooldownRemaining == 0
                && (ctx.state == SetupState.INVALIDATED
                    || (ctx.state == SetupState.IN_TRADE && closedAwaitingRearm))
                && canRearm(ctx, context, inKillzone)) {
            rearmCooldownRemaining = -1;
            rearm(ctx);
        }
    }

    /**
     * V5 Agent 05.3 (S-2): the setup that emitted a signal ended without an
     * executed position — INVALIDATED / EXPIRED for any reason, or re-armed.
     * Publish {@link com.topstep.trading.event.SetupCancelledEvent} so the
     * still-unfilled entry order is cancelled (SIM: ExecutionEngine, LIVE:
     * LiveEngineRunner at the broker), and release this runner's own latch
     * (no synthetic PositionClosedEvent follows these cancels). An executed
     * setup has no resting entry — nothing is published.
     */
    private void endEmittedSetup(String reason, StrategyContext context) {
        if (!emittedSetupActive) return;
        emittedSetupActive = false;
        if (executedSinceEmit(context) || (context != null && context.hasPosition(symbol))) return;
        positionOpen = false;
        entryPendingBars = 0;
        String why = (reason == null || reason.isBlank()) ? "invalidated" : reason;
        System.out.println("[" + logTag + "] setup ended before its entry filled (" + why
                + ") — cancelling the resting entry order");
        if (eventBus != null) {
            eventBus.publish(new com.topstep.trading.event.SetupCancelledEvent(symbol, why, lastCandleInstant));
        }
    }

    /**
     * V5 Agent 02 — "no double-invalidate on the same bias event": a
     * RE-ARMED setup that dies from a bias reason while no new bias event
     * happened since the re-arm (same {@code biasEpoch} / same observed
     * bias) is restored at once instead of paying a second death + cooldown
     * for an event it already paid for. Every other re-arm gate
     * (no position, risk frequency, NO_ENTRY/WEEKEND) still applies.
     *
     * @return true when the invalidation was suppressed (setup re-armed)
     */
    private boolean suppressDuplicateBiasInvalidation(SetupContext ctx, StrategyContext context) {
        if (!rearmBiasGuard.isDuplicateInvalidation(ctx, ctx.lastGateFailed)) return false;
        if (!canRearm(ctx, context, isInstrumentKillzone(lastCandleInstant))) return false;
        rearmBiasGuard.recordSuppressed();
        System.out.println("[" + logTag + "] duplicate invalidation on the SAME bias event suppressed ("
                + ctx.lastGateFailed + ", event key " + rearmBiasGuard.currentKey(ctx)
                + ") — setup restored without a second cooldown");
        rearmCooldownRemaining = -1;
        rearm(ctx);
        return true;
    }

    /** Test/telemetry hook: duplicate bias invalidations suppressed so far. */
    int suppressedDuplicateInvalidationsForTest() {
        return rearmBiasGuard.suppressedCount();
    }

    /**
     * AGENT-05 (RC-16): did the emitted signal become a real position? True
     * when a position was observed after emission or the account completed
     * a trade since emission (same trading day).
     */
    private boolean executedSinceEmit(StrategyContext context) {
        if (positionSeenSinceEmit) return true;
        AccountState account = (context != null) ? context.getAccountState() : null;
        if (account == null || tradesAtEmit < 0) return false;
        if (tradingDayAtEmit != null && !tradingDayAtEmit.equals(account.getCurrentTradingDay())) {
            return false;
        }
        return account.getTradesToday() > tradesAtEmit;
    }

    /** All re-arm gates outside the cooldown itself. */
    private boolean canRearm(SetupContext ctx, StrategyContext context, boolean inKillzone) {
        boolean terminal = ctx.state == SetupState.INVALIDATED
                || (ctx.state == SetupState.IN_TRADE && !positionOpen);
        if (!terminal) return false;
        // V5 Agent 02 (RC-02): SCORING needs no killzone — only the SACRED
        // NO_ENTRY / WEEKEND windows block a re-arm (candle time). BLOCKING
        // keeps the pre-V5 "killzone must be open" rule for A/B.
        if (gateMode == SessionGateMode.SCORING) {
            if (lastCandleInstant == null || SessionClassifier.blocksEntry(lastCandleInstant)) return false;
        } else if (!inKillzone) {
            return false;
        }
        // NO-OVERLAP: never arm a new setup while a position is open on this
        // symbol — the event-tracked flag plus the live account map.
        if (positionOpen) return false;
        // AGENT-05.8: the counter-trend scalp's position blocks with-trend
        // EMISSION (tryEmitOrder), not the with-trend hunt - it may arm.
        if (context != null && context.hasPosition(symbol)
                && !(counterTrend != null && counterTrend.ownsPosition())
                && !siblingOwnsPosition()) return false;   // AGENT-05.9: the other machine's position blocks EMISSION only
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
        // AGENT-05.3: a re-arm ends the previous setup — its unfilled entry
        // (if any) is cancelled first.
        endEmittedSetup("re-armed", lastStrategyContext);
        closedAwaitingRearm = false;
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
        // AGENT-02: the reset cleared the per-candle session fields; restore
        // them (candle time) and stamp the bias event this setup belongs to.
        // BLOCKING keeps the pre-V5 artefact (killzoneOpen left false by the
        // reset until the next candle) so the A/B baseline is byte-identical.
        if (gateMode == SessionGateMode.SCORING) ctx.killzoneOpen = killzoneActive;
        if (sessionWindowNow != null) ctx.sessionWindow = sessionWindowNow.name();
        ctx.primeKillzone = primeKillzoneNow;
        rearmBiasGuard.onRearm(ctx);
        System.out.println("[" + logTag + "] " + (scalpMode ? "SCALP: " : "")
                + "re-armed for next setup"
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
        dealingRange.reset();
        dealingRangeWarmChecked = false;
        sessionLegs.reset();
        resetTransientState();
        lastPrimaryTimestamp = null;
        lastSmtTimestamp = null;
        lastBias = MarketBias.NEUTRAL;
        // Scalp re-arm state (SA4) starts clean.
        pendingPositionClosed.set(false);
        positionOpen = false;
        rearmCooldownRemaining = -1;
        closedAwaitingRearm = false;     // AGENT-05.3
        emittedSetupActive = false;      // AGENT-05.3
        lastSeenState = SetupState.IDLE;
        rearmBiasGuard.reset();          // AGENT-02
        if (counterTrend != null) {      // AGENT-05.8
            counterTrend.reset();
            ctPendingClosed.set(false);
        }
        sessionWindowNow = null;         // AGENT-02
        lastSessionWindow = null;        // AGENT-02
        core.resetForNextWindow();
        ltfQuota.reset();                // AGENT-05.9
        lastLtfRangeEvent = null;
        lastLtfBiasNote = null;
        if (ltf != null) ltf.initialize();
    }

    @Override
    public void onSessionEnd() {
        // Persist the session's agreement counters (V3 Agent 06). Candle
        // time, not wall clock; a session with no candles has nothing new.
        if (lastCandleInstant != null && !ltfMachine) {
            OteAgreementStatsStore.checkpoint(symbol, lastCandleInstant);
        }
        // Core-level invalidation of an in-flight setup must still fire.
        core.onSessionEnd();
        // Session-scoped wiring state must not leak into the next session.
        // The HTF aggregation and level engine intentionally survive: HTF
        // structure and prior-day levels are cross-session context.
        resetTransientState();
        if (ltf != null) ltf.onSessionEnd();   // AGENT-05.9
    }

    @Override
    public void shutdown() {
        core.shutdown();
        if (ltf != null) ltf.shutdown();       // AGENT-05.9 (its core never registered)
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
        lastConsumedRaidId = null;
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
        // V5 Agent 02 (RC-02): decoupled from scalp mode. SCORING — in BOTH
        // the legacy and the scalp target model — opens the gate in every
        // SessionClassifier window except the SACRED NO_ENTRY / WEEKEND.
        if (gateMode == SessionGateMode.SCORING) {
            return !SessionClassifier.blocksEntry(now);
        }
        // BLOCKING (pre-V5, A/B): the legacy windows below, unchanged.
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
        // V5 Agent 02: SCORING uses the SessionClassifier prime windows for
        // every instrument; BLOCKING keeps the pre-V5 scalp prime windows.
        if (gateMode == SessionGateMode.SCORING) {
            return SessionClassifier.isPrimeKillzone(now);
        }
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
        // V5 Agent 02: routed through the single classifier. 14:45 CT =
        // 15:45 ET and 17:00 CT = 18:00 ET on every date (CT and ET switch
        // DST together), so this is the pre-V5 CT rule exactly.
        return !SessionClassifier.blocksEntry(ct.toInstant());
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
        if (legacyManipLegMode) {
            // Rollback (manip.legMode=KILLZONE): the pre-V5 behaviour.
            if (killzoneActive && !killzoneCandles.isEmpty()) {
                Optional<ManipulationLegDetector.Leg> kzLeg = ManipulationLegDetector.detect(
                        killzoneCandles, biasBullish, spec.tickSize(),
                        StdvProjectionEngine.DEFAULT_MIN_LEG_TICKS);
                kzLeg.ifPresent(l -> core.recordManipulationLeg(
                        l.legLow(), l.legHigh(), spec.tickSize(), MANIP_SNAP_TOL_TICKS));
                return;
            }
            Double sh = structureDetector.getLastSwingHigh();
            Double sl = structureDetector.getLastSwingLow();
            if (sh == null || sl == null || !(sh > sl)) return;
            core.recordManipulationLeg(sl, sh, spec.tickSize(), MANIP_SNAP_TOL_TICKS);
            return;
        }
        // V5 Agent 03 (task 4): with SCORING there is ALWAYS a session. The
        // leg = the counter-bias excursion of the CURRENT session window
        // (Judas off the session open, else the excursion that took a known
        // level); otherwise the most-recent swing pair IMMEDIATELY — the
        // pre-V5 killzone buffer waited with no fallback (cfg B:
        // MANIP-no-leg 195–232 bars per session).
        Optional<SessionLegLocator.Located> leg = sessionLegs.locate(
                biasBullish, levelEngine.getAllLevels(), spec.tickSize(),
                StdvProjectionEngine.DEFAULT_MIN_LEG_TICKS);
        if (leg.isPresent()) {
            core.recordManipulationLeg(leg.get().legLow(), leg.get().legHigh(),
                    spec.tickSize(), MANIP_SNAP_TOL_TICKS);
            if (ctx.state == SetupState.MANIP_DONE) {
                System.out.println("[" + logTag + "] MANIP leg (" + leg.get().kind() + ", session "
                        + sessionLegs.currentSession() + "): " + leg.get().legLow()
                        + " - " + leg.get().legHigh());
                return;
            }
        }
        // Fallback: most recent swing pair.
        Double swingHigh = structureDetector.getLastSwingHigh();
        Double swingLow = structureDetector.getLastSwingLow();
        if (swingHigh == null || swingLow == null) return;
        if (!(swingHigh > swingLow)) return;
        core.recordManipulationLeg(swingLow, swingHigh, spec.tickSize(), MANIP_SNAP_TOL_TICKS);
    }

    /** A sweep resolved for the setup: the event, its pipeline score and
     *  the scored raid behind it (null only on the starved fallback). */
    private record ResolvedSweep(LiquiditySweep sweep, int score, LiquidityRaid raid,
                                 String raidId, Instant swingTs) { }

    /** Bars a raid / swing sweep stays consumable after it printed. */
    private static final int SWEEP_RECENCY_BARS = 3;

    private void tryRecordSweep(Candle candle) {
        if (legacySweepMode) {
            tryRecordSweepLegacy(candle);
            return;
        }
        SetupContext ctx = core.getSetupContext();
        // Direction from the SETUP's bias (the one M2 judges): a bullish
        // setup wants a sellside (LOW) sweep = LiquiditySweep.isBullish().
        if (ctx.htfBias == MarketBias.NEUTRAL) return;
        boolean wantLow = (ctx.htfBias == MarketBias.BULLISH);
        ResolvedSweep r = resolveSweep(wantLow, false);
        if (r == null) return;
        // One floor per mode, enforced in the core (scalp.minRaidScore in
        // scalp mode, the instrument minimum in legacy): a sub-floor sweep
        // keeps the machine in MANIP_DONE for a better one.
        core.recordSweep(r.sweep(), r.score());
        if (ctx.state == SetupState.SWEEP_DONE) {
            markConsumed(r);
            // Begin tracking the reversal-leg origin from the swept extreme.
            lowSinceSweep = Math.min(r.sweep().getSweptLevel(), candle.getLow());
            highSinceSweep = Math.max(r.sweep().getSweptLevel(), candle.getHigh());
            System.out.println("[" + logTag + "] SWEEP recorded: "
                    + (r.raid() != null ? r.raid() : "starved-fallback score " + r.score()));
        }
    }

    /**
     * V5 Agent 03: in SWEEP_DONE (before any displacement) a NEWER raid of
     * a KNOWN level in the setup's direction scoring at least the recorded
     * sweep replaces it. Only level raids refresh (a swing sweep never
     * displaces a level sweep); the floor still applies.
     */
    private void tryRefreshSweep(Candle candle) {
        SetupContext ctx = core.getSetupContext();
        if (ctx.htfBias == MarketBias.NEUTRAL || ctx.sweep == null || ctx.displacement) return;
        boolean wantLow = (ctx.htfBias == MarketBias.BULLISH);
        ResolvedSweep r = resolveSweep(wantLow, true);
        if (r == null || r.raid() == null) return;
        int floor = scalpMode ? ScalpConfig.minRaidScore() : spec.raidMinQuality();
        if (r.score() < floor || r.score() < ctx.raidScore) return;
        if (ctx.sweep.getTimestamp() != null && r.sweep().getTimestamp() != null
                && !r.sweep().getTimestamp().isAfter(ctx.sweep.getTimestamp())) return;
        ctx.sweep = r.sweep();
        ctx.raidScore = r.score();
        markConsumed(r);
        lowSinceSweep = Math.min(r.sweep().getSweptLevel(), candle.getLow());
        highSinceSweep = Math.max(r.sweep().getSweptLevel(), candle.getHigh());
        System.out.println("[" + logTag + "] SWEEP refreshed (newer level raid): " + r.raid());
    }

    /** Rollback (raid.sweepMode=LEGACY): the pre-V5 sweep path, verbatim. */
    private void tryRecordSweepLegacy(Candle candle) {
        if (!liquidityDetector.hasRecentSweep(3)) return;
        LiquiditySweep sweep = liquidityDetector.getLastSweep();
        if (sweep == null) return;
        if (sweep.getTimestamp() != null && sweep.getTimestamp().equals(lastConsumedSweepTs)) {
            return;
        }
        boolean wantBullishSweep = (lastBias == MarketBias.BULLISH);
        if (sweep.isBullish() != wantBullishSweep) return;
        RaidDirection want = sweep.isBullish() ? RaidDirection.LOW_SWEEP : RaidDirection.HIGH_SWEEP;
        int score = raidDetector.getActiveRaidByDirection(want)
                .map(LiquidityRaid::getQualityScore).orElse(spec.raidMinQuality());
        core.recordSweep(sweep, score);
        if (core.getSetupContext().state == SetupState.SWEEP_DONE) {
            lastConsumedSweepTs = sweep.getTimestamp();
            lowSinceSweep = Math.min(sweep.getSweptLevel(), candle.getLow());
            highSinceSweep = Math.max(sweep.getSweptLevel(), candle.getHigh());
        }
    }

    private void markConsumed(ResolvedSweep r) {
        if (r.raidId() != null) lastConsumedRaidId = r.raidId();
        if (r.swingTs() != null) lastConsumedSweepTs = r.swingTs();
    }

    /**
     * V5 Agent 03 (RC-07): EVERY sweep is scored by the raid pipeline —
     * the pre-V5 silent fallback to the instrument base (5 = the floor, so
     * M4 "passed" by coincidence; scalp floor 6 rejected 2,031 sweeps) is
     * gone. Sources, best score wins:
     * <ol>
     *   <li>a LEVEL raid — {@link RaidDetector#getRecentRaid}: a known level
     *       (PDH/PDL, session high/low, equal level) swept with rejection in
     *       the last {@link #SWEEP_RECENCY_BARS} bars;</li>
     *   <li>the LiquidityDetector SWING sweep — the sweep candle scored via
     *       {@link RaidDetector#scoreSweep} against every level it took (or
     *       the prior swing extreme it took).</li>
     * </ol>
     */
    private ResolvedSweep resolveSweep(boolean wantLow, boolean levelOnly) {
        return resolveSweep(wantLow, levelOnly, lastConsumedRaidId, lastConsumedSweepTs);
    }

    /** AGENT-05.8: the same resolution against a caller's own consumption markers. */
    private ResolvedSweep resolveSweep(boolean wantLow, boolean levelOnly,
                                       String consumedRaidId, Instant consumedSweepTs) {
        RaidDirection want = wantLow ? RaidDirection.LOW_SWEEP : RaidDirection.HIGH_SWEEP;
        ResolvedSweep best = null;
        Optional<LiquidityRaid> raid = raidDetector.getRecentRaid(want, SWEEP_RECENCY_BARS);
        if (raid.isPresent() && !raid.get().getId().equals(consumedRaidId)) {
            LiquidityRaid lr = raid.get();
            best = new ResolvedSweep(
                    new LiquiditySweep(wantLow, lr.getTargetLevel().getPrice(), lr.getRaidTime(),
                            "DIVERGENT".equals(core.getSetupContext().smtState)),
                    lr.getQualityScore(), lr, lr.getId(), null);
        }
        if (levelOnly) return best;
        if (liquidityDetector.hasRecentSweep(SWEEP_RECENCY_BARS)) {
            LiquiditySweep sweep = liquidityDetector.getLastSweep();
            if (sweep != null && sweep.isBullish() == wantLow
                    && !(sweep.getTimestamp() != null && sweep.getTimestamp().equals(consumedSweepTs))) {
                ResolvedSweep swing = scoreSwingSweep(sweep);
                if (swing != null && (best == null || swing.score() > best.score())) best = swing;
            }
        }
        return best;
    }

    /** Score a LiquidityDetector sweep through the pipeline (task 6/7);
     *  null when the event is not a sweep at all (see currentRaidScore). */
    private ResolvedSweep scoreSwingSweep(LiquiditySweep sweep) {
        int score = currentRaidScore(sweep);
        if (score == NOT_A_SWEEP) return null;
        LiquidityRaid scored = lastScoredSwingRaid;
        return new ResolvedSweep(sweep, score, scored, null, sweep.getTimestamp());
    }

    /** currentRaidScore result: the event did not take the prior extreme
     *  with a rejection — a breakdown/breakout, not a liquidity sweep. */
    private static final int NOT_A_SWEEP = Integer.MIN_VALUE;

    /** The raid object behind the last {@link #currentRaidScore} (null = starved). */
    private LiquidityRaid lastScoredSwingRaid;

    /**
     * Raid quality for the M4 gate — V5 Agent 03: the sweep candle is
     * scored by {@link RaidDetector#scoreSweep} (known levels it took, else
     * the prior swing extreme). An event that did not take the prior
     * extreme with a rejection returns {@link #NOT_A_SWEEP} (ignored). ONLY
     * when the pipeline cannot score it at all (sweep candle no longer in
     * the candle series) does the DOCUMENTED starved fallback apply:
     * {@code raid.starvedScore}, default instrument floor − 1 — an
     * unscoreable sweep never passes M4. Counted and logged, never silent.
     */
    int currentRaidScore(LiquiditySweep sweep) {
        lastScoredSwingRaid = null;
        boolean lowSweep = sweep.isBullish();
        // CandleSeries.getLast is NEWEST-first; walk it oldest-first.
        List<Candle> recent = new ArrayList<>(candleSeries.getLast(SWEEP_RECENCY_BARS + 12));
        java.util.Collections.reverse(recent);
        int at = -1;
        for (int i = recent.size() - 1; i >= 0; i--) {
            if (recent.get(i).getTimestamp().equals(sweep.getTimestamp())) {
                at = i;
                break;
            }
        }
        if (at >= 1) {
            double prior = lowSweep ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            for (int i = Math.max(0, at - 9); i < at; i++) {
                prior = lowSweep ? Math.min(prior, recent.get(i).getLow())
                        : Math.max(prior, recent.get(i).getHigh());
            }
            Optional<LiquidityRaid> scored = raidDetector.scoreSweep(
                    recent.get(at), prior, lowSweep, lastRaidContext);
            if (scored.isPresent()) {
                lastScoredSwingRaid = scored.get();
                return scored.get().getQualityScore();
            }
            // The candle is in the series but did not take the prior
            // extreme with a rejection: LiquidityDetector's "<=" test fires
            // on equal lows and on closes below — not a sweep. Ignored.
            return NOT_A_SWEEP;
        }
        starvedSweeps++;
        int fallback = BiasConfig.starvedScore(spec.raidMinQuality());
        System.out.println("[" + logTag + "] RAID starved-pipeline fallback: sweep @ "
                + sweep.getSweptLevel() + " could not be scored -> documented base "
                + fallback + " (raid.starvedScore; count=" + starvedSweeps + ")");
        return fallback;
    }

    /**
     * Step 10 (M5) — V5 Agent 04: ONE displacement detector (calibrated),
     * linked to the FVG it created (or one within fvg.linkBars, or its OB).
     * Delegates to {@link OteSetupDriver}; stall reasons keep the historical
     * names so funnel histograms stay comparable.
     */
    private void tryRecordDisplacement(Candle candle) {
        // V5 Agent 05.2: the candle lets the driver try the IMPULSE_LEG entry
        // model first (M5/M6 proven on the dealing range's impulse leg, ARM
        // on the OTE-band sweep); POST_SWEEP otherwise, unchanged.
        String stall = oteDriver.tryRecordDisplacement(core, lastBias, candle);
        if (stall != null) {
            FunnelTelemetry.forSymbol(logTag).recordStall("SWEEP_DONE", stall);
        }
    }

    /**
     * Step 11 (M6) — the ONE MSS source ({@code MarketStructureShiftDetector
     * .forStdvOte()}): a close beyond the most recent opposite swing at/after
     * the displacement bar, within {@code mss.freshBars}.
     */
    private void tryRecordMss(Candle candle) {
        oteDriver.tryRecordMss(core, lastBias);
    }

    /**
     * Step 12 (M7 ARM) — zone fixed on the anchored leg (dealing range by
     * default, 0.618/0.705/0.786), INVALIDATE on a close beyond the range
     * extreme, ARM when price first trades into the band.
     */
    private void tryArmOte(Candle candle) {
        String stall = oteDriver.tryArmOte(core, lastBias, candle);
        if (stall != null) {
            FunnelTelemetry.forSymbol(logTag).recordStall("MSS_CONFIRMED", stall);
        }
    }

    // ======================================================================
    // AGENT-05.8 - OPT-IN COUNTER-TREND SCALP (entry.counterTrendScalp)
    // A premium-OTE-band sweep of a BULLISH range is shorted back to the
    // range equilibrium (T1) / the top of the discount band (final); mirror
    // for longs in a BEARISH range. Its own SetupContext, its own sweep
    // consumption, the same validator (M2 = the counter-trend rule, every
    // other gate in the trade's direction), half the $ budget, maxPerDay,
    // bounded sessions, one position per symbol. Every refusal writes the
    // scalp context's lastGateFailed and ONE GateDecisionEvent "CT".
    // ======================================================================

    private void stepCounterTrend(Candle candle, StrategyContext context, DealingRangeTracker.Snapshot range) {
        CounterTrendScalp ct = counterTrend;
        ct.onFeedCandle(candle);
        SetupContext cctx = ct.context();
        Instant now = candle.getTimestamp();
        cctx.sessionWindow = sessionWindowNow == null ? null : sessionWindowNow.name();
        cctx.primeKillzone = primeKillzoneNow;
        cctx.killzoneOpen = killzoneActive;
        cctx.rangeHigh = range.high();
        cctx.rangeLow = range.low();
        cctx.rangeEq = range.equilibrium();
        boolean accountPos = context != null && context.hasPosition(symbol);

        // 1. Lifecycle of an emitted scalp: close / release -> flat -> hunt.
        if (ctPendingClosed.compareAndSet(true, false) && ct.ownsPosition()) {
            ct.onFlat();
            ctDecision(ct, "CT: scalp flat (closed / released) - back to hunting the with-trend setup",
                    candle.getClose(), Double.NaN);
        }
        if (ct.phase() == CounterTrendScalp.Phase.WORKING) {
            if (accountPos) {
                ct.onFilled();
            } else {
                CounterTrendScalp.Plan p = ct.plan();
                String why = null;
                double a = Double.NaN;
                double b = Double.NaN;
                if (entryTimeoutBars > 0 && ct.workingBars() > entryTimeoutBars) {
                    why = "CT: entry not filled within " + entryTimeoutBars + " bars";
                    a = ct.workingBars();
                    b = entryTimeoutBars;
                } else if (p != null && ct.workingBars() > 0
                        && (p.tradeBullish() ? candle.getHigh() >= p.t1() : candle.getLow() <= p.t1())) {
                    why = "CT: equilibrium " + p.t1() + " traded before the entry " + p.entry() + " filled";
                    a = p.t1();
                    b = p.entry();
                } else if (SessionClassifier.blocksEntry(now)) {
                    why = "CT: " + sessionWindowNow + " - resting scalp entry cancelled";
                }
                if (why != null) {
                    System.out.println("[" + logTag + "] " + why + " - cancelling the resting scalp entry");
                    if (eventBus != null) {
                        eventBus.publish(new com.topstep.trading.event.SetupCancelledEvent(symbol, why, now));
                    }
                    ct.onFlat();
                    ctDecision(ct, why, a, b);
                }
            }
        }
        if (ct.ownsPosition()) return;   // one position per symbol: nothing to hunt while it is open

        // 2. ARMED episode housekeeping.
        if (ct.phase() == CounterTrendScalp.Phase.ARMED) {
            OteZone z = ct.zone();
            boolean tradeBullish = z.bullish();
            MarketBias armedRangeDir = tradeBullish ? MarketBias.BEARISH : MarketBias.BULLISH;
            if (tradeBullish ? candle.getClose() < z.one00() : candle.getClose() > z.one00()) {
                ctDisarm(ct, "CT: close " + candle.getClose() + " beyond the range 1.0 " + z.one00(),
                        candle.getClose(), z.one00());
            } else if (ct.barsArmed() > maxBarsInOte) {
                ctDisarm(ct, "CT: armed window expired (" + maxBarsInOte + " bars)", ct.barsArmed(), maxBarsInOte);
            } else if (!range.decisive() || range.direction() != armedRangeDir) {
                ctDisarm(ct, "CT: range now " + range.direction() + " (armed against " + armedRangeDir + ")",
                        range.high(), range.low());
            }
        }

        // 3. HUNT: a fresh sweep in the scalp's direction (its own consumption markers).
        if (range.decisive() && range.direction() != MarketBias.NEUTRAL) {
            boolean wantLow = range.direction() == MarketBias.BEARISH;   // long scalp in a bearish range
            ResolvedSweep r = resolveSweep(wantLow, false, ct.consumedRaidId(), ct.consumedSweepTs());
            if (r != null) {
                ct.markConsumed(r.raidId(), r.swingTs());
                double level = r.sweep().getSweptLevel();
                double ext = extremeSince(r.sweep().getTimestamp(), !wantLow, level, candle);
                int floor = scalpMode ? ScalpConfig.minRaidScore() : spec.raidMinQuality();
                // M4 for the scalp: the SAME pipeline and the SAME floor, scored
                // HTF-NEUTRAL - the scorer's "opposing HTF trend -4" (and the
                // +2/+3 alignment bonus) is the with-trend rule M2 already
                // adjudicates; for a counter-trend entry M2 is the CT rule, so
                // the HTF term is excluded instead of counted twice.
                int ctScore = ctNeutralScore(r.sweep(), wantLow, r.score());
                CounterTrendScalp.Verdict v = CounterTrendScalp.qualify(ct.config(), spec.tickSize(),
                        range.high(), range.low(), range.direction(), range.decisive(), lastBias,
                        r.sweep(), ctScore, floor, ext, OteConfig.impulseMinSweepFib(),
                        sessionWindowNow, candle.getClose());
                if (v.armed()) {
                    ct.arm(v, r.sweep(), ctScore, candle, now);
                    OteZone z = v.zone();
                    cctx.htfBias = range.direction();
                    cctx.ctRangeTicks = Math.round((range.high() - range.low()) / spec.tickSize() * 1e6) / 1e6;
                    cctx.ctMinRangeTicks = ct.config().minRangeTicks();
                    cctx.ctSweptLevel = level;
                    cctx.ctBandLo = Math.min(z.f62(), z.f79());
                    cctx.ctBandHi = Math.max(z.f62(), z.f79());
                    cctx.ctRejectionOpen = Double.NaN;
                    cctx.ctRejectionClose = Double.NaN;
                    ctDecision(ct, "CT: ARMED " + (z.bullish() ? "long" : "short") + " vs " + range.direction()
                            + " range [" + range.low() + "," + range.high() + "] sweep " + level + " ext " + ext
                            + " score " + ctScore + " (HTF-neutral; with-trend context " + r.score() + ")"
                            + " in band [" + cctx.ctBandLo + "," + cctx.ctBandHi + "]",
                            level, ext);
                } else if (ct.phase() != CounterTrendScalp.Phase.ARMED) {
                    ctDecision(ct, v.reason(), v.a(), v.b());
                    if (v.reason().startsWith("CT: raid score")) {
                        // Evidence: the M4 scoring factors of an in-band scalp sweep below the floor.
                        System.out.println("[" + logTag + "] CT sweep scored " + ctScore + " HTF-neutral; with-trend context: "
                                + (r.raid() != null ? r.raid() : "starved-fallback " + r.score()));
                    }
                }
            }
        }

        // 4. ALARM (PD array at the sweep + rejection close) and emission.
        if (ct.phase() != CounterTrendScalp.Phase.ARMED) return;
        OteZone z = ct.zone();
        boolean tradeBullish = z.bullish();
        double level = ct.sweep().getSweptLevel();
        if (!ct.alarmed()) {
            Optional<PdArrayLocator.PdArray> sweepBarOb = Optional.empty();
            Instant sweepTs = ct.sweep().getTimestamp();
            PdArrayLocator pd = oteDriver.pdArrays();
            if (sweepTs != null) {
                long secs = detectorTimeframe.getMinutes() * 60L;
                Instant barStart = Instant.ofEpochSecond(Math.floorDiv(sweepTs.getEpochSecond(), secs) * secs);
                long idx = pd.indexOf(barStart);
                if (idx >= 0) sweepBarOb = pd.sweepOrderBlock(idx, tradeBullish, level);
            }
            List<PdArrayLocator.PdArray> cands = CounterTrendScalp.candidates(tradeBullish, level,
                    ct.sweepBar(), ct.sweepPrior(), sweepBarOb, pd.candidates(tradeBullish));
            Optional<PdArrayLocator.PdArray> best = PdArrayLocator.bestInBand(cands, z);
            double lo = Math.min(z.f62(), z.f79());
            double hi = Math.max(z.f62(), z.f79());
            if (best.isEmpty()) {
                ctDecision(ct, "CT: no PD array at the sweep " + level + " overlaps the band [" + lo + "," + hi + "]",
                        level, lo);
                return;
            }
            String reaction = OteSetupDriver.impulseReaction(z, candle, level);
            if (reaction == null) {
                ctDecision(ct, "CT: awaiting the rejection close back " + (tradeBullish ? "above" : "below")
                        + " swept " + level, level, candle.getClose());
                return;
            }
            CounterTrendScalp.Plan p = CounterTrendScalp.plan(z, best.get(), ct.sweepExtreme(),
                    spec.tickSize(), stopBufferTicks, OteConfig.rrCeiling());
            ct.markAlarmed(p, now);
            cctx.ctRejectionOpen = candle.getOpen();
            cctx.ctRejectionClose = candle.getClose();
            ctDecision(ct, "CT: ALARM " + reaction + " | pd " + best.get().kind() + " [" + best.get().bottom()
                    + "," + best.get().top() + "] entry " + p.entry() + " stop " + p.stop()
                    + " T1(eq) " + p.t1() + " final " + p.finalTarget(), p.entry(), p.stop());
        }
        tryEmitCounterTrend(ct, candle, context, accountPos);
    }

    /** Emission attempt of an alarmed scalp: every refusal is reasoned + published. */
    private void tryEmitCounterTrend(CounterTrendScalp ct, Candle candle, StrategyContext context,
                                     boolean accountPos) {
        SetupContext cctx = ct.context();
        CounterTrendScalp.Plan p = ct.plan();
        OteZone z = ct.zone();
        Instant now = candle.getTimestamp();
        MarketBias rangeDir = z.bullish() ? MarketBias.BEARISH : MarketBias.BULLISH;
        if (!ct.config().sessionAllowed(sessionWindowNow)) {
            ctDecision(ct, "CT: session " + sessionWindowNow + " not in entry.counterTrend.sessions "
                    + ct.config().sessions(), Double.NaN, Double.NaN);
            return;
        }
        if (!ct.quotaLeft(now)) {
            ctDecision(ct, "CT: entry.counterTrend.maxPerDay " + ct.config().maxPerDay() + " reached ("
                    + ct.emitsOn(now) + " today)", ct.emitsOn(now), ct.config().maxPerDay());
            return;
        }
        if (lastBias != rangeDir) {
            ctDecision(ct, "CT: bias now " + lastBias + " (scalp armed against " + rangeDir + ")",
                    Double.NaN, Double.NaN);
            return;
        }
        boolean ltfHolds = ltf != null && ltf.positionOpen;   // AGENT-05.9
        if (positionOpen || accountPos || ltfHolds) {
            ctDecision(ct, "CT: " + symbol + " not flat (with-trend latch=" + positionOpen + ", account="
                    + accountPos + (ltfHolds ? ", LTF machine=true" : "") + ") - one position per symbol",
                    positionOpen ? 1 : 0, accountPos ? 1 : 0);
            return;
        }
        double rrFloor = OteConfig.rrFloor(scalpMode);
        if (p.rrT1() < rrFloor - 1e-9) {
            ctDecision(ct, "CT: RR to equilibrium " + String.format("%.2f", p.rrT1()) + " < floor " + rrFloor
                    + " (entry " + p.entry() + " stop " + p.stop() + " T1 " + p.t1() + ")", p.rrT1(), rrFloor);
            return;
        }
        // Size: the SAME risk-derived sizer on budget x maxRiskFraction.
        int cap = Math.min(Math.min(com.topstep.trading.risk.RiskConfig.maxMicros(), spec.maxMicros()),
                activeRiskLimits.getMaxContracts());
        double dllRoom = Double.NaN;
        double mllRoom = Double.NaN;
        AccountState account = (context != null) ? context.getAccountState() : null;
        if (account != null) {
            dllRoom = activeRiskLimits.getMaxDailyLoss() + account.getNetDailyPnl();
            mllRoom = activeRiskLimits.getMaxLossLimit()
                    - (account.getHighestEndOfDayBalance() - account.getEquity());
        }
        double budget = StdvOteSizer.riskBudget(activeRiskLimits.getRiskPerTrade(), dllRoom, mllRoom);
        double ctBudget = CounterTrendScalp.scaledBudget(budget, ct.config().maxRiskFraction());
        StdvOteSizer.RiskSize rs = StdvOteSizer.riskDerived(ctBudget, p.entry(), p.stop(),
                spec.tickSize(), spec.tickValue(), com.topstep.trading.risk.RiskConfig.minMicros(), cap);
        if (rs.denied()) {
            ctDecision(ct, "CT: " + rs.reason() + " [x" + ct.config().maxRiskFraction() + " budget]",
                    rs.needDollars(), rs.haveDollars());
            return;
        }
        int size = rs.contracts();
        // The validator: M2 = the counter-trend rule, every other gate in the trade's direction.
        cctx.state = SetupState.OTE_ARMED;
        cctx.htfBias = rangeDir;
        cctx.biasEpoch = core.getSetupContext().biasEpoch;
        cctx.displacement = true;
        cctx.fvg = p.pd().asFairValueGap();
        cctx.m5LinkKind = "CT_REJECTION+" + p.pd().kind();
        cctx.displacementAt = ct.sweep().getTimestamp();
        cctx.mss = true;
        cctx.mssAt = ct.context().oteAlarmAt;
        cctx.ote = z;
        cctx.oteAnchorMode = OteAnchorMode.DEALING_RANGE.name();
        cctx.oteAnchorSource = "COUNTER_TREND";
        cctx.pdArrayInOte = p.entry();
        cctx.pdArrayKind = p.pd().kind();
        cctx.pdArrayFarEdge = p.pd().farEdge();
        cctx.sweepExtreme = ct.sweepExtreme();
        cctx.entry = p.entry();
        cctx.stop = p.stop();
        cctx.t1 = p.t1();
        cctx.t2 = p.finalTarget();
        cctx.t3 = 0.0;
        cctx.finalTarget = p.target();
        cctx.rr = p.rrFinal();
        cctx.rrT1 = p.rrT1();
        cctx.scalpProfile = scalpMode;
        cctx.tier = TradeTier.TIER_1;
        cctx.sizeRequest = size;
        cctx.ctRiskFraction = ct.config().maxRiskFraction();
        cctx.lastGateFailed = null;
        com.topstep.trading.validation.ValidationResult res = validator.validateStdvOte(cctx);
        if (!res.passed()) {
            ctDecision(ct, "CT: validator " + (res.getFailures().isEmpty() ? res.getSummary()
                    : String.join("; ", res.getFailures())), p.entry(), p.stop());
            return;
        }
        boolean bullish = z.bullish();
        double[][] ladder = (p.target() == p.t1())
                ? new double[][] {{ p.rrT1(), 1.0 }}
                : new double[][] {{ p.rrT1(), 0.5 }, { p.rrFinal(), 0.5 }};
        com.topstep.trading.event.StrategySignalEvent signal = new com.topstep.trading.event.StrategySignalEvent(
                bullish ? com.topstep.trading.event.StrategySignalEvent.SignalType.LONG_ENTRY
                        : com.topstep.trading.event.StrategySignalEvent.SignalType.SHORT_ENTRY,
                symbol,
                bullish ? com.topstep.trading.domain.OrderSide.BUY : com.topstep.trading.domain.OrderSide.SELL,
                p.entry(), p.stop(), p.target(),
                CounterTrendScalp.REASON_PREFIX + " " + (bullish ? "long" : "short") + " vs " + rangeDir
                        + " range [" + z.legLow() + "," + z.legHigh() + "] sweep " + ct.sweep().getSweptLevel()
                        + " pd=" + p.pd().kind() + " size=" + size + " (risk x" + ct.config().maxRiskFraction() + ")"
                        + " T1(eq)=" + p.t1() + " final=" + p.finalTarget()
                        + " RR(T1)=" + String.format("%.2f", p.rrT1())
                        + " RR=" + String.format("%.2f", p.rrFinal()),
                TradeTier.TIER_1, size, p.rrFinal(), ladder, false, lastCandleInstant);
        if (eventBus != null) {
            eventBus.publish(signal);
        }
        ct.onEmitted(now);
        cctx.sizeFilled = size;
        ctDecision(ct, "CT: EMITTED " + signal.getReason(), p.entry(), p.stop());
    }

    /**
     * AGENT-05.8: the scalp sweep's M4 score through THE pipeline
     * ({@link RaidDetector#scoreSweep}, pure: registers nothing) with the HTF
     * term neutral (htfBullish = null: no alignment bonus, no opposing-trend
     * penalty), no displacement-entry bonus, this bar's SMT flag and range
     * equilibrium. Falls back to the with-trend-context score when the raid
     * candle has left the series or the pipeline cannot score it.
     */
    private int ctNeutralScore(LiquiditySweep sweep, boolean lowSweep, int fallback) {
        if (sweep == null || sweep.getTimestamp() == null) return fallback;
        List<Candle> recent = new ArrayList<>(candleSeries.getLast(SWEEP_RECENCY_BARS + 12));
        java.util.Collections.reverse(recent);
        int at = -1;
        for (int i = recent.size() - 1; i >= 0; i--) {
            if (sweep.getTimestamp().equals(recent.get(i).getTimestamp())) { at = i; break; }
        }
        if (at < 1) return fallback;
        double prior = lowSweep ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        for (int i = Math.max(0, at - 9); i < at; i++) {
            prior = lowSweep ? Math.min(prior, recent.get(i).getLow()) : Math.max(prior, recent.get(i).getHigh());
        }
        RaidDetector.RaidDetectionContext neutral = RaidDetector.RaidDetectionContext.fullWithCascade(
                lastRaidContext.hasSmtDivergence(), null, false, 0, false, 0)
                .withRangeEquilibrium(lastRaidContext.getRangeEquilibrium());
        Optional<LiquidityRaid> scored = raidDetector.scoreSweep(recent.get(at), prior, lowSweep, neutral);
        return scored.map(LiquidityRaid::getQualityScore).orElse(fallback);
    }

    /** Retrace extreme since the sweep (short: highest high incl. the swept level). */
    private double extremeSince(Instant sweepTs, boolean wantHigh, double level, Candle current) {
        double ext = wantHigh ? Math.max(level, current.getHigh()) : Math.min(level, current.getLow());
        if (sweepTs == null) return ext;
        for (Candle c : candleSeries.getLast(SWEEP_RECENCY_BARS + 2)) {
            if (c.getTimestamp() == null || c.getTimestamp().isBefore(sweepTs)) continue;
            ext = wantHigh ? Math.max(ext, c.getHigh()) : Math.min(ext, c.getLow());
        }
        return ext;
    }

    private void ctDisarm(CounterTrendScalp ct, String reason, double a, double b) {
        ct.disarm();
        ctDecision(ct, reason, a, b);
    }

    /** Write the scalp's lastGateFailed and publish ONE GateDecisionEvent "CT" per distinct reason. */
    private void ctDecision(CounterTrendScalp ct, String reason, double a, double b) {
        if (!ct.decide(reason)) return;
        System.out.println("[" + logTag + "] " + reason);
        if (eventBus != null) {
            com.topstep.trading.event.EngineTelemetry.publish(eventBus,
                    new com.topstep.trading.event.GateDecisionEvent(symbol, lastCandleInstant,
                            lastCandleInstant == null ? null
                                    : com.topstep.trading.event.EngineTelemetry.sessionOf(lastCandleInstant),
                            String.valueOf(ct.phase()), CounterTrendScalp.GATE, reason, a, b));
        }
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
                // AGENT-05.4: the death is reasoned (lastGateFailed) AND published.
                publishArmedDecision("TIER", "TIER: no qualifying tier", Double.NaN, Double.NaN);
                return;
            }
        }
        // AGENT-05 (V5 RC-16) / AGENT-05.4: M9 is cleared PER ATTEMPT - a
        // diagnostic left by an earlier bar must never veto this bar's
        // attempt (D-06); every early return below writes its own reason.
        ctx.lastGateFailed = null;
        // AGENT-05.8: one position per symbol - a with-trend setup may ARM
        // while the counter-trend scalp's order / position is open, but it
        // emits only once the scalp is flat.
        if (counterTrend != null && counterTrend.ownsPosition()) {
            armedDiagnostic("POSITION", "POSITION: " + symbol + " counter-trend scalp "
                    + counterTrend.phase() + " (one position per symbol; with-trend emits once it is flat)",
                    1, Double.NaN);
            return;
        }
        // AGENT-05.9: one position per symbol across the two machines.
        if (ltf != null && ltf.positionOpen) {
            armedDiagnostic("POSITION", "POSITION: " + symbol + " held by the LTF machine"
                    + " (one position per symbol; HTF emits once it is flat)", 1, Double.NaN);
            return;
        }
        if (ltfMachine && !ltfEmitAllowed(context)) {
            return;
        }
        // NO-OVERLAP (scalp): never emit while a position is open on this
        // symbol. Legacy is single-shot by construction (IN_TRADE terminal).
        boolean accountPosition = context != null && context.hasPosition(symbol);
        if (scalpMode && (positionOpen || accountPosition)) {
            armedDiagnostic("POSITION", "POSITION: " + symbol + " position still open (latch="
                    + positionOpen + ", account=" + accountPosition + ")",
                    positionOpen ? 1 : 0, accountPosition ? 1 : 0);
            return;
        }
        lastRiskSize = null;
        int size = scalpMode ? scalpSize(ctx, tier, context) : sizeForTier(ctx, tier, context);
        if (size <= 0) {
            // SIZE deny (stop too wide for the risk budget even at
            // size.minMicros): the reason and both $ numbers go into
            // SetupContext + one GateDecisionEvent (AGENT-05.4); the OTE
            // window keeps counting and a later re-plan retries.
            StdvOteSizer.RiskSize rs = lastRiskSize;
            armedDiagnostic("SIZE", rs != null ? rs.reason() : "SIZE: sizer returned 0 micros",
                    rs != null ? rs.needDollars() : Double.NaN, rs != null ? rs.haveDollars() : Double.NaN);
            return;
        }
        // tryEmit runs the validator; if it passes, a signal is published.
        // On failure the OTE window simply keeps counting in onCandle — the
        // previous extra barsInOte++ here double-counted and halved the
        // window (SA1 audit finding). The signal carries the CANDLE time
        // (SignalCandleClock) for the deterministic warmup guard (RC-15).
        java.time.Instant prevClock = com.topstep.trading.event.SignalCandleClock.current();
        if (lastCandleInstant != null) {
            com.topstep.trading.event.SignalCandleClock.set(lastCandleInstant);
        }
        boolean emitted;
        try {
            emitted = core.tryEmit(spec.tickSize(), stopBufferTicks, tier, size);
        } finally {
            com.topstep.trading.event.SignalCandleClock.set(prevClock);
        }
        if (!emitted && ctx.state == SetupState.OTE_ARMED) {
            // AGENT-05.4: the core wrote its reason (validator / SCALP /
            // BIAS) - publish it; never leave the refusal silent.
            if (ctx.lastGateFailed == null) {
                armedDiagnostic("EMIT", "EMIT: core refused the attempt without a reason",
                        ctx.entry, ctx.stop);
            } else {
                publishArmedDecision(gateOf(ctx.lastGateFailed), ctx.lastGateFailed, ctx.entry, ctx.stop);
            }
        }
        if (emitted) {
            // Latch (both modes, RC-16): cleared by this symbol's
            // PositionClosedEvent (real close or synthetic release).
            positionOpen = true;
            positionSeenSinceEmit = false;
            emittedSetupActive = true;       // AGENT-05.3
            closedAwaitingRearm = false;     // AGENT-05.3
            AccountState account = (context != null) ? context.getAccountState() : null;
            tradesAtEmit = (account != null) ? account.getTradesToday() : -1;
            tradingDayAtEmit = (account != null) ? account.getCurrentTradingDay() : null;
            lastArmedDiagnostic = null;
            if (ltfMachine) {
                ltfQuota.record(lastCandleInstant);   // AGENT-05.9: range.ltf.maxPerDay
                System.out.println("[" + logTag + "] LTF EMITTED " + ctx.htfBias + " range [" + ctx.rangeLow
                        + "," + ctx.rangeHigh + "] eq " + ctx.rangeEq + " entry " + ctx.entry + " stop " + ctx.stop
                        + " T1 " + ctx.t1 + " T2 " + ctx.t2 + " size " + ctx.sizeRequest
                        + " (" + ltfQuota.emitsOn(lastCandleInstant) + " today)");
            }
        }
    }

    /**
     * Scalp-mode size (V5 RC-14): the ONE risk-derived rule
     * ({@link StdvOteSizer#riskDerived}) against the profile's
     * {@code riskPerTrade} capped by the DLL/MLL room, clamped to
     * [size.minMicros, min(size.maxMicros, maxContracts)], THEN the prime
     * killzone boost (never above the cap). Returns 0 only for a SIZE deny.
     */
    private int scalpSize(SetupContext ctx, TradeTier tier, StrategyContext context) {
        double boost = 1.0;
        if (killzoneSizeBoost > 1.0 && lastCandleInstant != null
                && isPrimeKillzone(lastCandleInstant)) {
            boost = killzoneSizeBoost;
        }
        return riskDerivedSize(ctx, tier, context, boost);
    }

    /**
     * Legacy-mode size (V5 RC-14): the SAME risk-derived path as scalp mode
     * (no killzone boost). The old fixed tier table (18/14/10/6 micros) sent
     * sizes the risk engine then silently re-sized — gone.
     */
    private int sizeForTier(SetupContext ctx, TradeTier tier, StrategyContext context) {
        return riskDerivedSize(ctx, tier, context, 1.0);
    }

    /** The one sizing path for both modes; 0 = SIZE deny (published). */
    private int riskDerivedSize(SetupContext ctx, TradeTier tier, StrategyContext context,
                                double boost) {
        int cap = Math.min(Math.min(com.topstep.trading.risk.RiskConfig.maxMicros(), spec.maxMicros()),
                activeRiskLimits.getMaxContracts());
        if (ctx.ote == null || Double.isNaN(ctx.pdArrayInOte)) {
            // Geometry unknown: size.preferredMicros is the preference (the
            // validator's M7 will reject this attempt anyway).
            return Math.min(cap, com.topstep.trading.risk.RiskConfig.preferredMicros());
        }
        double entry = oteCalculator.chooseEntry(
                ctx.ote, OptionalDouble.of(ctx.pdArrayInOte), spec.tickSize());
        // Size on the SAME stop tryEmit will plan (Agent 04's anchored stop
        // when the OTE is dealing-range anchored) — never a different geometry.
        double stop = core.plannedStopForSizing(spec.tickSize(), stopBufferTicks);
        double dllRoom = Double.NaN;
        double mllRoom = Double.NaN;
        AccountState account = (context != null) ? context.getAccountState() : null;
        if (account != null) {
            dllRoom = activeRiskLimits.getMaxDailyLoss() + account.getNetDailyPnl();
            mllRoom = activeRiskLimits.getMaxLossLimit()
                    - (account.getHighestEndOfDayBalance() - account.getEquity());
        }
        double budget = StdvOteSizer.riskBudget(activeRiskLimits.getRiskPerTrade(), dllRoom, mllRoom);
        if (ltfMachine) {
            // AGENT-05.9: range.ltf.riskFraction (default 1.0) of the SAME budget
            // (never more); the sizer's caps and the risk engine are unchanged.
            budget = CounterTrendScalp.scaledBudget(budget, ltfConfig.riskFraction());
        }
        StdvOteSizer.RiskSize rs = StdvOteSizer.riskDerived(budget, entry, stop,
                spec.tickSize(), spec.tickValue(),
                com.topstep.trading.risk.RiskConfig.minMicros(), cap);
        lastRiskSize = rs;   // AGENT-05.4: the caller writes + publishes the deny
        if (rs.denied()) {
            return 0;
        }
        int size = rs.contracts();
        if (boost > 1.0) {
            // FABLE-REJECT #1: the boost never raises $ risk above the budget.
            int boosted = StdvOteSizer.applyBoost(size, boost, cap, budget, rs.perContract());
            if (boosted != size) {
                System.out.println("[" + logTag + "] KILLZONE SIZE BOOST x" + boost
                        + " -> " + boosted + " micros (risk-derived " + size + ", cap " + cap
                        + ", budget $" + budget + ")");
            }
            size = boosted;
        }
        return size;
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
        if (ctx.fvg != null && ctx.ote != null
                && ctx.fvg.getBottom() <= Math.max(ctx.ote.f62(), ctx.ote.f79())
                && ctx.fvg.getTop() >= Math.min(ctx.ote.f62(), ctx.ote.f79())) opt++; // O4 (overlap)
        // O5 (V5 Agent 04, RC-18): M7b in SCORING mode — a REACTED 30m OTE
        // for this direction is a confluence point, never a block.
        if (ote30mGate.mode() == Ote30mConfluenceGate.Mode.SCORING && ctx.htfBias != MarketBias.NEUTRAL) {
            ote30mGate.gateCheck(ctx.htfBias == MarketBias.BULLISH);
            if (ote30mGate.lastConfluent()) opt++;
        }

        boolean indexPair = "MNQ".equals(symbol) || "MES".equals(symbol);
        boolean smtOk = !indexPair || "DIVERGENT".equals(ctx.smtState);
        // V5 Agent 04: monotonic, TOTAL tier ladder — once M1..M9 pass
        // (legacy OR scalp) there is no "no qualifying tier" outcome.
        return com.topstep.trading.strategy.VariantSelector.resolveStdvOteTier(
                ctx.raidScore, opt, smtOk);
    }

    /** Read a double system property with a safe fallback (stdvOte.* pattern). */
    private static double doubleProperty(String name, double defaultValue) {
        String raw = com.topstep.trading.config.EngineConfig.current().getRaw(name);
        if (raw == null) return defaultValue;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            System.out.println("[StdvOteRunnerStrategy] WARN: invalid " + name
                    + "='" + raw + "', using default " + defaultValue);
            return defaultValue;
        }
    }

    /** Read an int system property with a safe fallback (stdvOte.* pattern). */
    private static int intProperty(String name, int defaultValue) {
        String raw = com.topstep.trading.config.EngineConfig.current().getRaw(name);
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
