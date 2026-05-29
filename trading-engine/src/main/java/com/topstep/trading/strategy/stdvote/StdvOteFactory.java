package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.IctHighConfluenceStrategy;
import com.topstep.trading.strategy.TradingStrategy;

/**
 * Strategy factory for runners (BacktestExample, SimEngineRunner,
 * LiveEngineRunner). Reads the {@code stdvOte.enabled} system property:
 *
 * <ul>
 *   <li><code>true</code> (default): construct the new
 *       {@link StdvOteRunnerStrategy}. Required symbols are MNQ/MES/MGC;
 *       non-tradeable symbols are routed to the legacy strategy as a
 *       fallback so existing demo paths (which use NQ/ES/GC) still run.</li>
 *   <li><code>false</code>: construct the legacy
 *       {@link IctHighConfluenceStrategy} (pre-refactor behaviour).</li>
 * </ul>
 *
 * <p>This is the single point of strategy selection in the codebase. To roll
 * back the refactor at runtime without code changes, set
 * {@code -DstdvOte.enabled=false} on the JVM command line.
 */
public final class StdvOteFactory {

    /** System property name that toggles the new strategy on/off. */
    public static final String ENABLED_PROPERTY = "stdvOte.enabled";

    private StdvOteFactory() {}

    /**
     * Build the active strategy for a runner.
     *
     * @param primarySymbol the instrument the strategy will trade
     * @param smtSymbol     the SMT correlate (may be null)
     * @param eventBus      bus to publish StrategySignalEvent on
     * @return an active {@link TradingStrategy}
     */
    public static TradingStrategy build(String primarySymbol, String smtSymbol, EventBus eventBus) {
        if (!isEnabled()) {
            return new IctHighConfluenceStrategy(primarySymbol, smtSymbol, eventBus);
        }
        // If the caller passed a non-MNQ/MES/MGC symbol while the new
        // strategy is "enabled", fall back to legacy rather than throwing —
        // the existing backtest/demo paths pre-date the registry.
        if (!TradeableInstrument.isTradeable(primarySymbol)) {
            System.out.println("[StdvOteFactory] WARN: symbol '" + primarySymbol
                    + "' is not in {MNQ,MES,MGC}; falling back to legacy strategy "
                    + "(set -DstdvOte.enabled=false to silence).");
            return new IctHighConfluenceStrategy(primarySymbol, smtSymbol, eventBus);
        }
        return new StdvOteRunnerStrategy(primarySymbol, smtSymbol, eventBus);
    }

    /** True when the new strategy should be used (the default). */
    public static boolean isEnabled() {
        String prop = System.getProperty(ENABLED_PROPERTY);
        if (prop == null) return true; // default-on after the refactor
        return "true".equalsIgnoreCase(prop.trim());
    }
}
