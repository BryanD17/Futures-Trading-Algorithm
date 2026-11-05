package com.topstep.trading;

/**
 * V6 Agent 05.1 (R6 / I9): the Java engine no longer places live orders.
 * Every LIVE entry point (CLI {@code LIVE} mode, {@code EngineFacade.startLive},
 * {@code LiveEngineRunner} construction, the api-backend LIVE control
 * endpoints) fails loud with {@link #MESSAGE}. Live trading runs on
 * TradingView + the webhook relay.
 */
public final class LiveTradingRemovedException extends IllegalStateException {

    /** The exact V6 message (TRADINGVIEW_MIGRATION_MASTER_PROMPT_V6 task 05.1). */
    public static final String MESSAGE =
        "Topstep removed in V6 — live trading runs on TradingView (see RUNBOOK_TRADINGVIEW.md)";

    public LiveTradingRemovedException() {
        super(MESSAGE);
    }

    /** Always throws; usable as a statement where a {@code throw} would make the rest unreachable. */
    public static void fail() {
        throw new LiveTradingRemovedException();
    }
}
