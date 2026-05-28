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
 * <h2>How orchestration works in SA4</h2>
 *
 * The state machine itself is fully implemented and unit-tested. The
 * incoming feed of detector outputs (which {@code RaidDetector} fired, which
 * {@code FvgDetector} produced an FVG, etc.) is fed in via the
 * package-private {@code record*} hooks invoked from {@link #onCandle}. SA5
 * connects those hooks to the actual detectors registered on the
 * {@link EventBus}; until then, {@code onCandle} only drives time-based
 * housekeeping (setup expiry). Production runners must therefore
 * <em>not</em> use this strategy as the default until SA5 has wired the
 * detectors — see the {@code stdvOte.enabled} configuration flag (SA5).
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
        if (setup.htfBias != MarketBias.NEUTRAL
                && bias != MarketBias.NEUTRAL
                && bias != setup.htfBias
                && setup.state.ordinal() < SetupState.IN_TRADE.ordinal()) {
            invalidate("HTF bias flip " + setup.htfBias + " -> " + bias);
        }
        if (bias == MarketBias.NEUTRAL && setup.state != SetupState.IDLE
                && setup.state.ordinal() < SetupState.IN_TRADE.ordinal()) {
            invalidate("HTF bias became NEUTRAL");
            return;
        }
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
     */
    void recordSweep(LiquiditySweep sweep, int raidScore) {
        if (setup.state != SetupState.MANIP_DONE) return;
        if (sweep == null) return;
        boolean biasBullish = (setup.htfBias == MarketBias.BULLISH);
        // A bullish setup wants a sweep of LOWS (sellside) so the rejection
        // sets up the long. LiquiditySweep.isBullish() == true means sweep
        // of lows (per the existing class semantics).
        if (sweep.isBullish() != biasBullish) return;
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
        if (edge.isEmpty()) {
            // Allow PD array to be an OB or IFVG via direct injection elsewhere.
            // For SA4 we require the FVG to overlap.
            setup.lastGateFailed = "M7: no PD array in OTE band";
            return;
        }
        setup.pdArrayInOte = edge.getAsDouble();
        setup.pdArrayKind = "FVG";
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

        double entry = oteCalculator.chooseEntry(
                setup.ote, OptionalDouble.of(setup.pdArrayInOte), tickSize);
        double stop = oteCalculator.stopPrice(setup.ote, tickSize, stopBufferTicks);
        StdvProjection targetMinus2 = findProjection(-2.0);
        double targetPrice = (targetMinus2 != null)
                ? targetMinus2.effectivePrice()
                : entry;
        double rr = oteCalculator.rewardToRisk(entry, stop, targetPrice);

        setup.entry = entry;
        setup.stop = stop;
        setup.rr = rr;
        setup.tier = tier;
        setup.sizeRequest = sizeRequest;

        ValidationResult result = validator.validateStdvOte(setup);
        if (!result.passed()) {
            setup.lastGateFailed = result.getSummary();
            return false;
        }
        setup.lastGateFailed = null;

        boolean bullish = setup.legBullish;
        OrderSide side = bullish ? OrderSide.BUY : OrderSide.SELL;
        SignalType type = bullish ? SignalType.LONG_ENTRY : SignalType.SHORT_ENTRY;
        StrategySignalEvent signal = new StrategySignalEvent(
                type, symbol, side, entry, stop, targetPrice,
                "STDV_OTE: " + tier + " size=" + sizeRequest
                        + " RR=" + String.format("%.2f", rr),
                tier, sizeRequest);
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
    }

    /** Reset to IDLE for the next window — one-move discipline. */
    void resetForNextWindow() {
        setup.resetForNextWindow();
    }
}
