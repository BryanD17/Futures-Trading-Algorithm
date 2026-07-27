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

    /** {@code bias.hysteresis.enabled} — DEFAULT false (counterfactual-log-only). */
    private boolean biasHysteresisEnabled =
            Boolean.getBoolean("bias.hysteresis.enabled");
    /** {@code bias.neutralGraceBars} — consecutive NEUTRAL 15m evaluations
     *  an in-flight setup survives; default 2, clamped [1,4]. */
    private int neutralGraceBars = clampGraceBars(
            Integer.getInteger("bias.neutralGraceBars", 2));
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

    @Override
    public void onCandle(Candle candle, StrategyContext context) {
        barIndex++;
        // SA5 will read detector outputs here and call the record* hooks.
        // SA4 implements the per-bar housekeeping (expiry only).
        if (setup.state != SetupState.IDLE
                && setup.state != SetupState.DONE
                && setup.state != SetupState.INVALIDATED
                && setupExpiryBars > 0
                && setup.createdAtBar > 0
                && barIndex - setup.createdAtBar > setupExpiryBars) {
            invalidate("expired (" + setupExpiryBars + " bars without progress)");
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
        if (isScalpMode() && scalpMinRaidScore > 0
                && raidScore < scalpMinRaidScore) {
            System.out.println("[" + symbol + "] SCALP raid-score gate: sweep rejected"
                    + " (score " + raidScore + " < floor " + scalpMinRaidScore + ")");
            return;
        }
        setup.sweep = sweep;
        setup.raidScore = raidScore;
        setup.state = SetupState.SWEEP_DONE;
    }

    /** Record a displacement candle and its FVG. Only valid from {@code SWEEP_DONE}. */
    void recordDisplacement(FairValueGap fvg) {
        if (setup.state != SetupState.SWEEP_DONE) return;
        if (fvg == null) return;
        boolean biasBullish = (setup.htfBias == MarketBias.BULLISH);
        if (fvg.isBullish() != biasBullish) return;
        setup.displacement = true;
        setup.fvg = fvg;
        setup.state = SetupState.DISPLACED;
    }

    /**
     * Record a Market Structure Shift / CHoCH in the bias direction. Only
     * valid from {@code DISPLACED}.
     */
    void recordMss() {
        if (setup.state != SetupState.DISPLACED) return;
        setup.mss = true;
        setup.state = SetupState.MSS_CONFIRMED;
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
        double stop = oteCalculator.stopPrice(setup.ote, tickSize, stopBufferTicks);
        double targetPrice;
        ScalpTargetCalculator.Decision scalpDecision = null;
        if (scalpTargetCalculator == null) {
            // LEGACY mode: target the −2σ STDV projection — unchanged.
            StdvProjection targetMinus2 = findProjection(-2.0);
            targetPrice = (targetMinus2 != null)
                    ? targetMinus2.effectivePrice()
                    : entry;
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
        }
        double rr = oteCalculator.rewardToRisk(entry, stop, targetPrice);

        setup.entry = entry;
        setup.stop = stop;
        setup.rr = rr;
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
        if (scalpDecision == null) {
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
