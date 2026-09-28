package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.event.StrategySignalEvent.SignalType;
import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.StrategyContext;
import com.topstep.trading.strategy.TradeTier;
import com.topstep.trading.strategy.TradingStrategy;
import com.topstep.trading.validation.MandatoryConfluenceValidator;
import com.topstep.trading.validation.ValidationResult;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The strict STDV + canonical OTE strategy that replaces the additive-scoring
 * {@code IctHighConfluenceStrategy} as the default trade source.
 *
 * <p>The strategy runs a sequential state machine
 * ({@link SetupState}) on a per-instrument {@link SetupContext}: HTF bias
 * (3-of-4) → manipulation leg + STDV ladder → liquidity sweep →
 * displacement + FVG → MSS/CHoCH → OTE arm (PD array inside the
 * 0.62–0.79 band) → entry + stop + STDV-anchored targets. Mandatory gates
 * M1..M9 are blocking and sequential; optional confluences only drive tier
 * and size within the hard {@code [5, 20]} micro band.
 *
 * <h2>How orchestration works</h2>
 *
 * The state machine itself is fully implemented and unit-tested via the
 * package-private {@code record*} hooks. In production the hooks are driven
 * by {@link StdvOteRunnerStrategy}, which owns every detector (HTF bias via
 * {@code BarAggregationManager}+{@code HtfTrendAnalyzer}, the raid pipeline,
 * displacement→FVG linkage, {@code ImpulseLegTracker},
 * {@code ManipulationLegDetector}) and calls the hooks per candle — this
 * class stays detector-free and pure. {@code onCandle} here only drives
 * time-based housekeeping (setup expiry). Strategy selection is controlled
 * by the {@code stdvOte.enabled} configuration flag (see
 * {@code StdvOteFactory}).
 *
 * <p>The legacy {@code IctHighConfluenceStrategy} remains compilable and
 * runnable behind a configuration flag for A/B backtest comparison only.
 */
public final class StdvOteStrategy implements TradingStrategy {

    /** Strategy name as it appears in logs, status endpoints, and the dashboard. */
    public static final String NAME = "STDV_OTE";

    private final String symbol;
    private final SetupContext setup;

    /** Pure projection engine; never null. */
    private final StdvProjectionEngine projectionEngine;

    /** Pure OTE entry calculator; never null. */
    private final OteEntryCalculator oteCalculator;

    /** Mandatory M1..M9 validator; never null. */
    private final MandatoryConfluenceValidator validator;

    /** Event bus the emitted signal is published on; may be null in unit tests. */
    private final EventBus eventBus;

    /** Captured for tests + the API surface (SA6). */
    private StrategySignalEvent lastEmittedSignal;

    /** Setup expiry in LTF bars; 0 disables the expiry guard. */
    private final long setupExpiryBars;

    /** Monotonic LTF bar counter (incremented on every onCandle call). */
    private long barIndex;

    /**
     * True when {@code setup.lastGateFailed} was last written by this class
     * (validator rejection summary or the recordOteImpulse M7 hint) rather
     * than by an external risk pre-flight. Self-written diagnostics are
     * cleared at the top of {@link #tryEmit} so a failed attempt cannot
     * poison the M9 gate on retry.
     */
    private boolean gateDiagnosticSelfWritten;

    /**
     * Scalp target calculator (SA3). Null = legacy mode: {@link #tryEmit}
     * targets the −2σ STDV projection exactly as before. Non-null = scalp
     * mode: the target comes from {@link ScalpTargetCalculator} (nearest
     * opposing liquidity vs FVG origin, hard-capped at 1R). The runner
     * injects this at construction when {@code scalpMode.enabled} is true —
     * the core itself stays pure and reads no system properties.
     */
    private ScalpTargetCalculator scalpTargetCalculator;

    /**
     * Binary raid-quality floor (SA4/SA5, scalp mode only): a sweep whose
     * score is below this floor never advances the machine to
     * {@code SWEEP_DONE}. STRICT (SA5): the floor applies to every score,
     * including the starved-pipeline instrument-base fallback — a score
     * that cannot be shown &ge; the floor does not trade in scalp mode.
     * Ignored in legacy mode.
     */
    private int scalpMinRaidScore = 0;

    /**
     * Nearest opposing liquidity price for the scalp target (Candidate A),
     * pre-computed by the runner each candle from LiquidityTargetIdentifier /
     * LevelEngine. Null when unknown. Unused in legacy mode.
     */
    private Double nearestOpposingLiquidity;

    /**
     * Candidate PD arrays for the M7 in-zone check (2026-07-27 funnel fix):
     * STDV_OTE_MODEL.md L4 requires "a PD array (FVG / OB / IFVG / breaker)
     * sits INSIDE the zone" — any qualifying array, not specifically the
     * displacement's own FVG. The implementation only ever tested
     * {@code setup.fvg}, and because the OTE band moves as the post-MSS
     * terminus extends, that single fixed FVG routinely falls out of the
     * band (30 "M7: no PD array" hits in one live hour). The runner feeds
     * the detector's current unfilled-FVG list here each candle;
     * {@link #recordOteImpulse} falls back to the NEWEST same-direction
     * candidate whose edge lies inside the zone. Null/empty = the
     * historical single-FVG behavior, byte-identical.
     */
    private List<FairValueGap> candidatePdArrays;

    /** Runner feed for the M7 PD-array fallback scan (may be null). */
    void setCandidatePdArrays(List<FairValueGap> unfilledFvgs) {
        this.candidatePdArrays = unfilledFvgs;
    }

    public StdvOteStrategy(String symbol,
                           StdvProjectionEngine projectionEngine,
                           OteEntryCalculator oteCalculator,
                           MandatoryConfluenceValidator validator,
                           EventBus eventBus,
                           long setupExpiryBars) {
        if (symbol == null) throw new IllegalArgumentException("symbol must not be null");
        if (projectionEngine == null) throw new IllegalArgumentException("projectionEngine must not be null");
        if (oteCalculator == null) throw new IllegalArgumentException("oteCalculator must not be null");
        if (validator == null) throw new IllegalArgumentException("validator must not be null");
        this.symbol = symbol;
        this.projectionEngine = projectionEngine;
        this.oteCalculator = oteCalculator;
        this.validator = validator;
        this.eventBus = eventBus;
        this.setupExpiryBars = Math.max(0L, setupExpiryBars);
        this.setup = new SetupContext();
        this.setup.symbol = symbol;
        StdvOteRegistry.register(this);
    }

    /** Read-only snapshot accessor for the API layer (SA6). */
    public SetupContext getSetupContext() {
        return setup;
    }

    /** Last emitted signal (or null). Used by tests + by the API for last-trade view. */
    public StrategySignalEvent getLastEmittedSignal() {
        return lastEmittedSignal;
    }

    /**
     * Switch this core into scalp mode (SA3). Package-private: called once
     * at construction by the runner when {@code scalpMode.enabled} is true.
     * Passing null keeps/returns legacy mode.
     */
    void enableScalpMode(ScalpTargetCalculator calculator) {
        enableScalpMode(calculator, 0);
    }

    /**
     * Scalp mode with the SA4 binary raid-score floor. {@code minRaidScore}
     * &le; 0 disables the floor (SA3 behaviour).
     */
    void enableScalpMode(ScalpTargetCalculator calculator, int minRaidScore) {
        this.scalpTargetCalculator = calculator;
        this.scalpMinRaidScore = Math.max(0, minRaidScore);
    }

    // ── BIAS HYSTERESIS (V2 Agent 04, config-gated, DEFAULT OFF) ────────
    // PRINCIPLE: NEUTRAL is UNCERTAINTY; OPPOSITE bias is CONTRADICTION.
    // With hysteresis ON, an in-flight setup survives a bounded number of
    // consecutive NEUTRAL evaluations (the 15m structure wobbling in and
    // out of definition) instead of being shredded on the first one; an
    // OPPOSITE flip still kills it instantly. HARD INVARIANT (tested):
    // entries STILL require the CURRENT bias evaluation to be non-NEUTRAL
    // and aligned — grace preserves PROGRESS, never entry permission.

    /** {@code bias.hysteresis} (alias {@code bias.hysteresis.enabled}) —
     *  V5 Agent 03 (RC-04): DEFAULT true. One NEUTRAL 15m read killed 8
     *  cfg-A setups on the real tape ("HTF bias became NEUTRAL"). */
    private boolean biasHysteresisEnabled = BiasConfig.hysteresis();
    /** {@code bias.neutralGraceBars} — consecutive NEUTRAL 15m evaluations
     *  an in-flight setup survives; V5 default 3, clamped [1,4]. */
    private int neutralGraceBars = clampGraceBars(BiasConfig.neutralGraceBars());
    /** Legacy-mode sweep floor (V5 Agent 03); 0 = disabled (unit tests). */
    private int legacyMinRaidScore = 0;

    /** Runner hook: install the legacy-mode sweep floor (instrument minimum). */
    void setLegacyMinRaidScore(int floor) {
        this.legacyMinRaidScore = Math.max(0, floor);
    }

    /** Last NON-NEUTRAL bias recorded — the reference for biasEpoch. */
    private MarketBias lastDirectionalBias = MarketBias.NEUTRAL;
    /** Monotonic count of REAL bias flips (see {@link #recordHtfBias}). */
    private long biasEpoch = 0L;
    /** Consecutive NEUTRAL evaluations seen while holding the setup. */
    private int neutralGraceCount = 0;
    /** The most recent bias EVALUATION (as opposed to the setup's stored
     *  direction, which is deliberately NOT overwritten during grace).
     *  The emission-time safety invariant reads this. */
    private MarketBias lastRecordedBias = MarketBias.NEUTRAL;

    private static int clampGraceBars(int v) {
        return Math.min(4, Math.max(1, v));
    }

    /** Wiring/test hook, mirroring {@link #enableScalpMode}'s pattern. */
    void configureBiasHysteresis(boolean enabled, int graceBars) {
        this.biasHysteresisEnabled = enabled;
        this.neutralGraceBars = clampGraceBars(graceBars);
    }

    /** True when this core targets via the scalp model. */
    boolean isScalpMode() {
        return scalpTargetCalculator != null;
    }

    /**
     * Supply the nearest-opposing-liquidity price for the scalp target
     * (Candidate A). The runner calls this every candle; null = unknown.
     * No-op relevance in legacy mode.
     */
    void setNearestOpposingLiquidity(Double price) {
        this.nearestOpposingLiquidity = price;
    }

    @Override
    public String getName() {
        return NAME;
    }

    // ── SETUP LIFECYCLE / EXPIRY (V5 Agent 02, RC-03 / PF-10) ───────────
    // Pre-V5 the whole path — bias, manipulation leg, sweep, displacement,
    // MSS, OTE — shared ONE budget counted from BIAS_SET (createdAtBar), so a
    // setup whose sweep came late was dead before it could arm. With the
    // SWEEP_DONE anchor the budgets are split:
    //   * BIAS_SET / MANIP_DONE: waiting for a sweep of a real level — a
    //     GENEROUS pre-sweep budget (default 480 min = one full session)
    //     from BIAS_SET; the setup is only waiting, nothing is at risk.
    //   * SWEEP_DONE .. OTE_ARMED: the hunt — expires N feed bars after the
    //     SWEEP_DONE arrival (default 60 min = 60 x 1m = 12 x 5m detector bars).
    //   * IN_TRADE / MANAGING: unchanged pre-V5 latch-release net (the
    //     constructor budget from BIAS_SET) until the position-close path
    //     releases it (RC-16, Agent 05).
    // The BIAS_SET anchor is the pre-V5 behaviour, byte-identical; it is the
    // core default (unit tests constructing the core directly) and the
    // runner selects it with session.gateMode=BLOCKING for A/B.

    private com.topstep.trading.strategy.session.SessionConfig.ExpiryAnchor expiryAnchor =
            com.topstep.trading.strategy.session.SessionConfig.ExpiryAnchor.BIAS_SET;
    /** Post-sweep budget in feed bars (SWEEP_DONE anchor); 0 disables. */
    private long huntExpiryBars;
    /** Pre-sweep budget in feed bars from BIAS_SET (SWEEP_DONE anchor); 0 disables. */
    private long preSweepExpiryBars;
    /** Bar index at which the setup reached SWEEP_DONE; -1 = not (yet) swept. */
    private long sweepAtBar = -1;

    /** Runner wiring: choose the expiry anchor and its budgets (feed bars). */
    void configureExpiry(com.topstep.trading.strategy.session.SessionConfig.ExpiryAnchor anchor,
                         long huntBars, long preSweepBars) {
        this.expiryAnchor = (anchor == null)
                ? com.topstep.trading.strategy.session.SessionConfig.ExpiryAnchor.BIAS_SET : anchor;
        this.huntExpiryBars = Math.max(0L, huntBars);
        this.preSweepExpiryBars = Math.max(0L, preSweepBars);
    }

    /** Bar index of the SWEEP_DONE arrival of the live setup, or -1 (tests / API). */
    long sweepAtBar() {
        return sweepAtBar;
    }

    /** Current monotonic bar index (tests / API). */
    long barIndex() {
        return barIndex;
    }

    @Override
    public void onCandle(Candle candle, StrategyContext context) {
        SetupState s = setup.state;
        boolean live = s != SetupState.IDLE
                && s != SetupState.DONE
                && s != SetupState.INVALIDATED;
        // Stamp the SWEEP_DONE arrival. recordSweep runs AFTER this method on
        // the bar the sweep fires, so a post-sweep state seen here without a
        // stamp arrived on the CURRENT index (before the increment below).
        boolean postSweep = live && s.ordinal() >= SetupState.SWEEP_DONE.ordinal();
        if (!postSweep) {
            sweepAtBar = -1;
        } else if (sweepAtBar < 0) {
            sweepAtBar = barIndex;
        }
        barIndex++;
        // SA5 will read detector outputs here and call the record* hooks.
        // SA4 implements the per-bar housekeeping (expiry only).
        if (!live) return;

        if (expiryAnchor == com.topstep.trading.strategy.session.SessionConfig.ExpiryAnchor.BIAS_SET) {
            // Pre-V5 behaviour, unchanged.
            if (setupExpiryBars > 0
                    && setup.createdAtBar > 0
                    && barIndex - setup.createdAtBar > setupExpiryBars) {
                invalidate("expired (" + setupExpiryBars + " bars without progress)");
            }
            return;
        }

        if (s == SetupState.IN_TRADE || s == SetupState.MANAGING) {
            // Latch-release net, identical to pre-V5 (see block comment).
            if (setupExpiryBars > 0
                    && setup.createdAtBar > 0
                    && barIndex - setup.createdAtBar > setupExpiryBars) {
                invalidate("expired (" + setupExpiryBars + " bars without progress)");
            }
            return;
        }
        if (postSweep) {
            setup.expiresAtBar = huntExpiryBars > 0 ? sweepAtBar + huntExpiryBars : 0L;
            if (huntExpiryBars > 0 && barIndex - sweepAtBar > huntExpiryBars) {
                invalidate("expired (" + huntExpiryBars + " bars after SWEEP_DONE without an entry)");
            }
            return;
        }
        // BIAS_SET / MANIP_DONE — waiting for the sweep.
        setup.expiresAtBar = (preSweepExpiryBars > 0 && setup.createdAtBar > 0)
                ? setup.createdAtBar + preSweepExpiryBars : 0L;
        if (preSweepExpiryBars > 0
                && setup.createdAtBar > 0
                && barIndex - setup.createdAtBar > preSweepExpiryBars) {
            invalidate("expired (" + preSweepExpiryBars + " bars before SWEEP_DONE)");
        }
    }

    @Override
    public void initialize() {
        // SA5 will wire detectors here.
    }

    @Override
    public void onSessionEnd() {
        // Force-invalidate an in-flight setup so it does not survive the session.
        if (setup.state != SetupState.IDLE
                && setup.state != SetupState.DONE
                && setup.state != SetupState.INVALIDATED) {
            invalidate("session ended");
        }
    }

    @Override
    public void shutdown() {
        StdvOteRegistry.unregister(symbol);
    }

    // ══════════════════════════════════════════════════════════════════════
    // State machine hooks  (package-private; SA5 calls these from onCandle,
    // unit tests call them directly)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Set HTF bias. Called once per HTF refresh. NEUTRAL invalidates an
     * in-flight setup; a flip from BULLISH ↔ BEARISH also invalidates.
     */
    void recordHtfBias(MarketBias bias) {
        if (bias == null) bias = MarketBias.NEUTRAL;
        lastRecordedBias = bias;
        // V5 Agent 03 (RC-04): biasEpoch increments on every REAL flip —
        // a new NON-NEUTRAL direction different from the last non-NEUTRAL
        // one. NEUTRAL wobbles (held by hysteresis) and repeated same-bias
        // records never move it, so consumers (Agent 04's anchors, the M2
        // check) can tell "same thesis" from "new thesis" idempotently.
        if (bias != MarketBias.NEUTRAL && bias != lastDirectionalBias) {
            if (lastDirectionalBias != MarketBias.NEUTRAL) {
                biasEpoch++;
            } else if (biasEpoch == 0L) {
                biasEpoch = 1L; // first directional read opens epoch 1
            }
            lastDirectionalBias = bias;
        }
        setup.biasEpoch = biasEpoch;
        // OPPOSITE flip = CONTRADICTION: dies immediately, hysteresis or
        // not (behavior unchanged from pre-V2).
        if (setup.htfBias != MarketBias.NEUTRAL
                && bias != MarketBias.NEUTRAL
                && bias != setup.htfBias
                && setup.state.ordinal() < SetupState.IN_TRADE.ordinal()) {
            invalidate("HTF bias flip " + setup.htfBias + " -> " + bias);
        }
        if (bias == MarketBias.NEUTRAL && setup.state != SetupState.IDLE
                && setup.state.ordinal() < SetupState.IN_TRADE.ordinal()) {
            if (!biasHysteresisEnabled) {
                // Counterfactual telemetry: identical invalidation to
                // pre-V2, PLUS the line the owner counts across sessions
                // to decide whether grace is worth enabling (Appendix F3).
                System.out.println("[BIAS] NEUTRAL flip invalidated setup "
                        + "(hysteresis OFF — grace would have held it "
                        + neutralGraceBars + " more bar(s))");
                invalidate("HTF bias became NEUTRAL");
                return;
            }
            neutralGraceCount++;
            if (neutralGraceCount > neutralGraceBars) {
                invalidate("HTF bias NEUTRAL beyond grace");
                return;
            }
            System.out.println("[BIAS] NEUTRAL wobble (" + neutralGraceCount
                    + "/" + neutralGraceBars + " grace) — setup held");
            // The setup keeps its ORIGINAL direction while held: htfBias is
            // deliberately not overwritten. lastRecordedBias (above) makes
            // the emission invariant see the real NEUTRAL.
            return;
        }
        if (neutralGraceCount > 0 && bias == setup.htfBias
                && bias != MarketBias.NEUTRAL) {
            System.out.println("[BIAS] bias restored to " + bias
                    + " within grace — setup continues, counter reset");
        }
        neutralGraceCount = 0;
        setup.htfBias = bias;
        if (bias != MarketBias.NEUTRAL && setup.state == SetupState.IDLE) {
            setup.state = SetupState.BIAS_SET;
            setup.createdAtBar = barIndex;
        }
    }

    /**
     * Record the manipulation leg and compute the STDV ladder. Only valid
     * from {@code BIAS_SET}. The leg defines the dealing range from which
     * projections are drawn.
     */
    void recordManipulationLeg(double legLow, double legHigh,
                               double tickSize, int snapTolTicks) {
        if (setup.state != SetupState.BIAS_SET) return;
        List<StdvProjection> projections = projectionEngine.project(
                legLow, legHigh, setup.htfBias, tickSize, snapTolTicks);
        if (projections.isEmpty()) return;
        setup.legLow = legLow;
        setup.legHigh = legHigh;
        setup.legBullish = (setup.htfBias == MarketBias.BULLISH);
        setup.projections = projections;
        setup.state = SetupState.MANIP_DONE;
    }

    /**
     * Record a liquidity sweep + its raid quality score. Only valid from
     * {@code MANIP_DONE}. Direction must match HTF bias (a SSL sweep for
     * a bullish setup, BSL for bearish); mismatches are ignored.
     *
     * <p>SA4/SA5 binary quality gate (scalp mode only, STRICT): a sweep
     * whose score is below the configured {@code scalp.minRaidScore} floor
     * is REJECTED — the machine stays in {@code MANIP_DONE} so a later,
     * higher-quality sweep can still arm the setup within the window. The
     * floor applies to EVERY score — pipeline-differentiated, base-fallback
     * (starved raid pipeline) and exact-base alike. Conservative rule: a
     * score that cannot be shown &ge; the floor does not trade in scalp
     * mode. Legacy mode (no scalp calculator) never applies the floor.
     */
    void recordSweep(LiquiditySweep sweep, int raidScore) {
        if (setup.state != SetupState.MANIP_DONE) return;
        if (sweep == null) return;
        boolean biasBullish = (setup.htfBias == MarketBias.BULLISH);
        // A bullish setup wants a sweep of LOWS (sellside) so the rejection
        // sets up the long. LiquiditySweep.isBullish() == true means sweep
        // of lows (per the existing class semantics).
        if (sweep.isBullish() != biasBullish) return;
        // V5 Agent 03 (RC-07): ONE floor per mode, applied at sweep time so
        // a sub-floor sweep never advances only to die at M4 on emission.
        // Scalp: scalp.minRaidScore (default 6). Legacy: the instrument's
        // raid minimum (M4's own number), installed by the runner; 0 = off.
        int floor = isScalpMode() ? scalpMinRaidScore : legacyMinRaidScore;
        if (floor > 0 && raidScore < floor) {
            System.out.println("[" + symbol + "] " + (isScalpMode() ? "SCALP " : "")
                    + "raid-score gate: sweep rejected"
                    + " (score " + raidScore + " < floor " + floor + ") "
                    + (sweep.isBullish() ? "LOW@" : "HIGH@") + sweep.getSweptLevel()
                    + " " + sweep.getTimestamp());
            return;
        }
        setup.sweep = sweep;
        setup.raidScore = raidScore;
        setup.state = SetupState.SWEEP_DONE;
    }

    /** Record a displacement candle and its FVG. Only valid from {@code SWEEP_DONE}. */
    void recordDisplacement(FairValueGap fvg) {
        recordDisplacement(fvg, "FVG", null);
    }

    /**
     * V5 Agent 04: displacement + its LINKED PD array ({@code linkKind} FVG /
     * IFVG / BREAKER / OB — RC-10) and the displacement bar's timestamp.
     */
    void recordDisplacement(FairValueGap fvg, String linkKind, java.time.Instant displacementAt) {
        if (setup.state != SetupState.SWEEP_DONE) return;
        if (fvg == null) return;
        boolean biasBullish = (setup.htfBias == MarketBias.BULLISH);
        if (fvg.isBullish() != biasBullish) return;
        setup.displacement = true;
        setup.fvg = fvg;
        setup.m5LinkKind = linkKind;
        setup.displacementAt = displacementAt;
        setup.state = SetupState.DISPLACED;
    }

    /**
     * Record a Market Structure Shift / CHoCH in the bias direction. Only
     * valid from {@code DISPLACED}.
     */
    void recordMss() {
        recordMss(null);
    }

    /** V5 Agent 04: MSS with the detector-bar timestamp of the break. */
    void recordMss(java.time.Instant mssAt) {
        if (setup.state != SetupState.DISPLACED) return;
        setup.mss = true;
        setup.mssAt = mssAt;
        setup.state = SetupState.MSS_CONFIRMED;
    }

    // -- V5 Agent 04: explicit OTE plan / ARM / ALARM (RC-11, RC-12) --

    /**
     * Fix the OTE zone on the ANCHORED leg (dealing range by default) once
     * the MSS is confirmed. Only valid from {@code MSS_CONFIRMED}; the zone
     * does not move afterwards (D-19). No state change.
     */
    boolean recordOtePlan(OteZone zone, String anchorMode, String anchorSource,
                          double sweepExtreme) {
        if (setup.state != SetupState.MSS_CONFIRMED || zone == null) return false;
        boolean bullish = (setup.htfBias == MarketBias.BULLISH);
        if (zone.bullish() != bullish) return false;
        setup.ote = zone;
        setup.oteAnchorMode = anchorMode;
        setup.oteAnchorSource = anchorSource;
        setup.sweepExtreme = sweepExtreme;
        return true;
    }

    /**
     * ARM: price traded into the band for the first time after the MSS.
     * {@code MSS_CONFIRMED -> OTE_ARMED}. Requires a planned zone.
     */
    boolean armOte(java.time.Instant at) {
        if (setup.state != SetupState.MSS_CONFIRMED || setup.ote == null
                || setup.oteAnchorMode == null) return false;
        setup.oteArmedAt = at;
        setup.state = SetupState.OTE_ARMED;
        return true;
    }

    /**
     * ALARM: a PD array overlaps the band and price reacted. Records the
     * entry level (already clamped into the band) and the array's far edge
     * (stop side). The emission attempt ({@link #tryEmit}) follows.
     */
    boolean recordOteAlarm(double entryLevel, String pdKind, double farEdge,
                           java.time.Instant at) {
        if (setup.state != SetupState.OTE_ARMED || setup.ote == null) return false;
        setup.pdArrayInOte = entryLevel;
        setup.pdArrayKind = pdKind;
        setup.pdArrayFarEdge = farEdge;
        setup.oteAlarmAt = at;
        return true;
    }

    /**
     * Record the impulse leg that the MSS produced, build the OTE zone, and
     * verify a PD-array edge sits inside. Only valid from
     * {@code MSS_CONFIRMED}. {@code reactionConfirmed} must be true (a
     * rejection wick / lower-TF CHoCH at the zone) for the state to advance.
     */
    void recordOteImpulse(double impulseLow, double impulseHigh,
                          double tickSize, boolean reactionConfirmed) {
        if (setup.state != SetupState.MSS_CONFIRMED) return;
        boolean bullish = (setup.htfBias == MarketBias.BULLISH);
        Optional<OteZone> zone = oteCalculator.buildZone(impulseLow, impulseHigh, bullish, tickSize);
        if (zone.isEmpty()) return;
        setup.ote = zone.get();
        OptionalDouble edge = oteCalculator.bestFvgEdgeInZone(setup.ote, setup.fvg);
        String pdKind = "FVG";
        if (edge.isEmpty() && candidatePdArrays != null) {
            // Spec-correct PD-array search (L4): any same-direction unfilled
            // FVG whose edge sits inside the band qualifies, newest first.
            for (int i = candidatePdArrays.size() - 1; i >= 0; i--) {
                FairValueGap candidate = candidatePdArrays.get(i);
                if (candidate == null || candidate.isBullish() != bullish) continue;
                OptionalDouble alt = oteCalculator.bestFvgEdgeInZone(setup.ote, candidate);
                if (alt.isPresent()) {
                    edge = alt;
                    setup.fvg = candidate;
                    pdKind = "FVG-alt";
                    break;
                }
            }
        }
        if (edge.isEmpty()) {
            setup.lastGateFailed = "M7: no PD array in OTE band";
            gateDiagnosticSelfWritten = true;
            return;
        }
        setup.pdArrayInOte = edge.getAsDouble();
        setup.pdArrayKind = pdKind;
        if (reactionConfirmed) {
            setup.state = SetupState.OTE_ARMED;
        }
    }

    /**
     * Compute the planned entry, stop, RR, request sizing, run the validator
     * and (if all gates pass) emit a {@link StrategySignalEvent}. Only valid
     * from {@code OTE_ARMED}.
     *
     * @param tickSize       instrument tick size
     * @param stopBufferTicks ticks beyond the OTE 1.0 for the stop buffer
     * @param tier            tier computed by the strategy's tier evaluator
     * @param sizeRequest     micros requested by the sizer (SA5)
     * @return true if a signal was emitted
     */
    boolean tryEmit(double tickSize, int stopBufferTicks,
                    TradeTier tier, int sizeRequest) {
        if (setup.state != SetupState.OTE_ARMED) return false;

        // SA5 fix: clear stale SELF-written gate diagnostics before running the
        // gates. Without this, the first failed attempt (or an earlier
        // "M7: no PD array" hint from recordOteImpulse) leaves lastGateFailed
        // set, and every retry then fails M9 forever — a poisoned-retry loop.
        // A diagnostic written externally (a real risk pre-flight, SA3+) is
        // preserved so the M9 contract still holds.
        if (gateDiagnosticSelfWritten) {
            setup.lastGateFailed = null;
            gateDiagnosticSelfWritten = false;
        }

        // ── BIAS SAFETY INVARIANT (V2 Agent 04, non-negotiable): an ENTRY
        // requires the CURRENT bias evaluation to be non-NEUTRAL and
        // aligned with the setup's direction, INDEPENDENT of any hysteresis
        // grace window. Grace preserves in-flight progress between gates;
        // it never, under any circumstances, permits an emission while the
        // live bias reads NEUTRAL (or contradicts the setup).
        if (lastRecordedBias == MarketBias.NEUTRAL
                || lastRecordedBias != setup.htfBias) {
            System.out.println("[BIAS] emission blocked: current bias "
                    + lastRecordedBias + " not aligned with setup "
                    + setup.htfBias + " (grace preserves progress, not entries)");
            return false;
        }

        double entry = oteCalculator.chooseEntry(
                setup.ote, OptionalDouble.of(setup.pdArrayInOte), tickSize);
        boolean anchored = setup.oteAnchorMode != null;
        double stop = anchored
                ? anchoredStop(setup.ote, tickSize, stopBufferTicks)
                : oteCalculator.stopPrice(setup.ote, tickSize, stopBufferTicks);
        double targetPrice;
        double t1Price;
        double[][] ladder = null;
        ScalpTargetCalculator.Decision scalpDecision = null;
        setup.scalpProfile = isScalpMode();
        if (scalpTargetCalculator == null && anchored) {
            // V5 Agent 04 -- owner's ladder on the anchored leg: T1 = 0.5,
            // T2 = 0.382, T3 = the leg terminus. The signal carries the
            // FURTHEST rung whose RR stays within the ONE ceiling; the M7
            // floor is checked against T1. Recomputed on every attempt.
            double[] rungs = oteCalculator.targetLadder(setup.ote, tickSize);
            setup.t1 = rungs[0];
            setup.t2 = rungs[1];
            setup.t3 = rungs[2];
            double ceiling = OteConfig.rrCeiling();
            t1Price = rungs[0];
            targetPrice = rungs[0];
            for (double r : rungs) {
                if (oteCalculator.rewardToRisk(entry, stop, r) <= ceiling + 1e-9) targetPrice = r;
            }
            double rT1 = oteCalculator.rewardToRisk(entry, stop, t1Price);
            double rFinal = oteCalculator.rewardToRisk(entry, stop, targetPrice);
            ladder = (targetPrice == t1Price)
                    ? new double[][] {{ rT1, 1.0 }}
                    : new double[][] {{ rT1, 0.5 }, { rFinal, 0.5 }};
        } else if (scalpTargetCalculator == null) {
            // LEGACY mode: target the −2σ STDV projection — unchanged.
            StdvProjection targetMinus2 = findProjection(-2.0);
            targetPrice = (targetMinus2 != null)
                    ? targetMinus2.effectivePrice()
                    : entry;
            // V5 Agent 04 (one RR band): when the -2σ would exceed the
            // ceiling, step inward along the STDV ladder to the furthest
            // projection that stays within it (a valid setup is re-planned,
            // not thrown away for an over-ambitious target).
            double ceiling = OteConfig.rrCeiling();
            if (oteCalculator.rewardToRisk(entry, stop, targetPrice) > ceiling + 1e-9) {
                for (double sigma : new double[] {-1.0, -0.27}) {
                    StdvProjection p = findProjection(sigma);
                    if (p == null) continue;
                    double px = p.effectivePrice();
                    boolean beyond = setup.legBullish ? px > entry : px < entry;
                    if (beyond && oteCalculator.rewardToRisk(entry, stop, px) <= ceiling + 1e-9) {
                        targetPrice = px;
                        break;
                    }
                }
            }
            t1Price = targetPrice;
        } else {
            // SCALP mode (SA3): closer of nearest-opposing-liquidity / FVG
            // origin, hard-capped at 1R; exactly 1R when no candidate is
            // valid within the window. Rejections are reason-logged and,
            // like validator failures, are self-written diagnostics (the
            // retry-clearing at the top of this method applies).
            boolean scalpBullish = setup.legBullish;
            Double fvgOrigin = (setup.fvg != null)
                    ? (scalpBullish ? setup.fvg.getTop() : setup.fvg.getBottom())
                    : null;
            scalpDecision = scalpTargetCalculator.computeTarget(
                    entry, stop, scalpBullish, tickSize,
                    nearestOpposingLiquidity, fvgOrigin);
            if (!scalpDecision.accepted()) {
                setup.lastGateFailed = "SCALP: " + scalpDecision.reason();
                gateDiagnosticSelfWritten = true;
                return false;
            }
            targetPrice = scalpDecision.targetPrice();
            t1Price = targetPrice;
        }
        double rr = oteCalculator.rewardToRisk(entry, stop, targetPrice);

        setup.entry = entry;
        setup.stop = stop;
        setup.rr = rr;
        setup.finalTarget = targetPrice;
        setup.rrT1 = oteCalculator.rewardToRisk(entry, stop, t1Price);
        setup.tier = tier;
        setup.sizeRequest = sizeRequest;

        ValidationResult result = validator.validateStdvOte(setup);
        if (!result.passed()) {
            setup.lastGateFailed = result.getSummary();
            gateDiagnosticSelfWritten = true;
            return false;
        }
        setup.lastGateFailed = null;
        gateDiagnosticSelfWritten = false;

        boolean bullish = setup.legBullish;
        OrderSide side = bullish ? OrderSide.BUY : OrderSide.SELL;
        SignalType type = bullish ? SignalType.LONG_ENTRY : SignalType.SHORT_ENTRY;
        StrategySignalEvent signal;
        if (scalpDecision == null && ladder != null) {
            // V5 anchored plan: carry the REAL RR and the real T1/final ladder.
            signal = new StrategySignalEvent(
                    type, symbol, side, entry, stop, targetPrice,
                    "STDV_OTE: " + tier + " size=" + sizeRequest
                            + " anchor=" + setup.oteAnchorMode
                            + " T1=" + setup.t1 + " T2=" + setup.t2 + " T3=" + setup.t3
                            + " RR(T1)=" + String.format("%.2f", setup.rrT1)
                            + " RR=" + String.format("%.2f", rr),
                    tier, sizeRequest, rr, ladder, false);
        } else if (scalpDecision == null) {
            // LEGACY signal construction — unchanged (tier-default RR and
            // tier-default partial ladder, exactly as before).
            signal = new StrategySignalEvent(
                    type, symbol, side, entry, stop, targetPrice,
                    "STDV_OTE: " + tier + " size=" + sizeRequest
                            + " RR=" + String.format("%.2f", rr),
                    tier, sizeRequest);
        } else {
            // SCALP signal: carry the REAL RR (not the tier's fictional
            // 2.0–5.0) and a single 100%-at-target take-profit level; the
            // rMultiple equals rr so any ladder consumer reproduces the
            // exact single target price.
            signal = new StrategySignalEvent(
                    type, symbol, side, entry, stop, targetPrice,
                    "STDV_OTE_SCALP: " + tier + " size=" + sizeRequest
                            + " target=" + scalpDecision.source()
                            + " RR=" + String.format("%.2f", rr),
                    tier, sizeRequest, rr,
                    new double[][] {{ rr, 1.0 }}, false);
        }
        if (eventBus != null) {
            eventBus.publish(signal);
        }
        lastEmittedSignal = signal;
        setup.sizeFilled = sizeRequest;
        setup.state = SetupState.IN_TRADE;
        return true;
    }

    /**
     * V5 Agent 04 stop for an anchored OTE: beyond the band's far edge
     * (0.786) or the PD array's far edge -- whichever is further from entry --
     * plus the buffer. The OTE thesis (sell premium / buy discount of the
     * dealing range) is void once price accepts beyond the 0.786 AND the array
     * the entry sits in; the range 1.0 would put the stop beyond the whole
     * retrace. G1: max(30673.00, OB top 30650.00) + 4 ticks = 30674.00.
     * IMPULSE_LEG (05.2) adds the sweep extreme: G1 max(30673.00, wick/OB
     * top 30650.00, sweep high 30650.00) + 4 ticks = 30674.00.
     */
    /**
     * AGENT-05 (V5 RC-14): the stop {@link #tryEmit} WILL plan for the current
     * setup — same branch (anchored vs OTE-1.0) — so the runner sizes on the
     * exact geometry the signal carries. Read-only; NaN without a zone.
     */
    double plannedStopForSizing(double tickSize, int stopBufferTicks) {
        if (setup.ote == null) return Double.NaN;
        return setup.oteAnchorMode != null
                ? anchoredStop(setup.ote, tickSize, stopBufferTicks)
                : oteCalculator.stopPrice(setup.ote, tickSize, stopBufferTicks);
    }

    private double anchoredStop(OteZone zone, double tickSize, int bufferTicks) {
        if ("ORIGIN".equals(OteConfig.stopMode())) {
            return oteCalculator.stopPrice(zone, tickSize, bufferTicks);
        }
        double buffer = Math.max(0, bufferTicks) * tickSize;
        double far = zone.f79();
        // V5 Agent 05.2: an IMPULSE_LEG entry also keeps the stop beyond the
        // retrace's own extreme (the sweep high for a short) - the thesis is
        // void once price trades back through the raid that armed it.
        boolean impulseLeg = OteConfig.ENTRY_MODEL_IMPULSE_LEG.equals(setup.oteEntryModel);
        if (zone.bullish()) {
            if (!Double.isNaN(setup.pdArrayFarEdge)) far = Math.min(far, setup.pdArrayFarEdge);
            if (impulseLeg && !Double.isNaN(setup.sweepExtreme)) far = Math.min(far, setup.sweepExtreme);
            return roundTick(far - buffer, tickSize);
        }
        if (!Double.isNaN(setup.pdArrayFarEdge)) far = Math.max(far, setup.pdArrayFarEdge);
        if (impulseLeg && !Double.isNaN(setup.sweepExtreme)) far = Math.max(far, setup.sweepExtreme);
        return roundTick(far + buffer, tickSize);
    }

    private static double roundTick(double p, double tick) {
        return tick > 0 ? Math.round(p / tick) * tick : p;
    }

    /**
     * Look up a sigma in the projection ladder; returns null if absent or
     * the ladder is empty.
     */
    StdvProjection findProjection(double sigma) {
        if (setup.projections == null) return null;
        for (StdvProjection p : setup.projections) {
            if (Double.compare(p.sigma(), sigma) == 0) return p;
        }
        return null;
    }

    /** Force-invalidate the current setup with a logged reason. */
    void invalidate(String reason) {
        setup.lastGateFailed = reason;
        setup.state = SetupState.INVALIDATED;
        neutralGraceCount = 0; // a dead setup carries no grace window
    }

    /** Reset to IDLE for the next window — one-move discipline. */
    void resetForNextWindow() {
        setup.resetForNextWindow();
        neutralGraceCount = 0;
    }
}
