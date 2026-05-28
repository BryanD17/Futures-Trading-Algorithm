package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.StrategyContext;
import com.topstep.trading.strategy.TradingStrategy;

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
 * <p>The legacy {@code IctHighConfluenceStrategy} remains compilable and
 * runnable behind a configuration flag for A/B backtest comparison only —
 * see {@code BACKTEST_COMPARISON.md} (SA9).
 *
 * <p>This class is a stub in SA1. The state machine, gate evaluation, and
 * order emission are implemented in <strong>SA4</strong>; risk + sizing
 * integration in <strong>SA5</strong>.
 */
public final class StdvOteStrategy implements TradingStrategy {

    /** Strategy name as it appears in logs, status endpoints, and the dashboard. */
    public static final String NAME = "STDV_OTE";

    private final String symbol;
    private final SetupContext setup;

    public StdvOteStrategy(String symbol) {
        this.symbol = symbol;
        this.setup = new SetupContext();
        this.setup.symbol = symbol;
    }

    /** Read-only snapshot accessor for the API layer (SA6). */
    public SetupContext getSetupContext() {
        return setup;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void onCandle(Candle candle, StrategyContext context) {
        // SA1: stub. State machine + gates implemented in SA4.
        // Intentionally a no-op rather than throwing, so the strategy is
        // safely instantiable in unit tests and DI containers during SA1.
    }

    @Override
    public void initialize() {
        // SA4 will wire detectors here.
    }

    @Override
    public void onSessionEnd() {
        // SA4 will finalise any in-flight setup at session end.
    }

    @Override
    public void shutdown() {
        // SA4 will clean up detector resources here.
    }
}
