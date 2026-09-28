package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.IctHighConfluenceStrategy;
import com.topstep.trading.strategy.TradingStrategy;

/**
 * Strategy factory for runners (SimEngineRunner, LiveEngineRunner,
 * AbBacktestComparison). THE single point of strategy selection (M1).
 *
 * <ul>
 *   <li>{@code strategy.stdvOte} (legacy name {@code stdvOte.enabled}),
 *       default {@code true}: construct {@link StdvOteRunnerStrategy}.
 *       Required symbols are MNQ/MES/MGC.</li>
 *   <li>{@code strategy.stdvOte=false}: construct the legacy
 *       {@link IctHighConfluenceStrategy} (explicit, logged rollback).</li>
 * </ul>
 *
 * <p>V5 Agent 01 (PF-01 / D-09): a non-{MNQ,MES,MGC} symbol no longer falls
 * back SILENTLY to the legacy strategy. It FAILS FAST with an
 * {@link IllegalArgumentException} naming {@code strategy.legacyFallback},
 * unless {@code strategy.legacyFallback=true} is set explicitly. The chosen
 * strategy class is always logged. All flags are read from
 * {@link com.topstep.trading.config.EngineConfig}.
 */
public final class StdvOteFactory {

    /** Legacy system property name that toggles the new strategy on/off (alias of strategy.stdvOte). */
    public static final String ENABLED_PROPERTY = "stdvOte.enabled";

    /** EngineConfig key: allow a non-tradeable symbol to fall back to the legacy strategy. */
    public static final String LEGACY_FALLBACK_PROPERTY = "strategy.legacyFallback";

    private StdvOteFactory() {}

    /**
     * Build the active strategy for a runner.
     *
     * @param primarySymbol the instrument the strategy will trade
     * @param smtSymbol     the SMT correlate (may be null)
     * @param eventBus      bus to publish StrategySignalEvent on
     * @return an active {@link TradingStrategy}
     * @throws IllegalArgumentException for a non-{MNQ,MES,MGC} symbol unless
     *         {@code strategy.legacyFallback=true}
     */
    public static TradingStrategy build(String primarySymbol, String smtSymbol, EventBus eventBus) {
        if (!isEnabled()) {
            System.out.println("[StdvOteFactory] strategy.stdvOte=false -> building "
                    + IctHighConfluenceStrategy.class.getSimpleName() + " for " + primarySymbol
                    + " (explicit legacy rollback)");
            return new IctHighConfluenceStrategy(primarySymbol, smtSymbol, eventBus);
        }
        if (!TradeableInstrument.isTradeable(primarySymbol)) {
            if (!legacyFallbackAllowed()) {
                throw new IllegalArgumentException("[StdvOteFactory] symbol '" + primarySymbol
                        + "' is not in {MNQ,MES,MGC} and strategy.legacyFallback=false: refusing to "
                        + "silently run the legacy strategy. Use MNQ/MES/MGC, or set "
                        + "strategy.legacyFallback=true (engine.properties or -Dstrategy.legacyFallback=true) "
                        + "to run IctHighConfluenceStrategy on purpose.");
            }
            System.out.println("[StdvOteFactory] WARN: symbol '" + primarySymbol
                    + "' is not in {MNQ,MES,MGC}; strategy.legacyFallback=true -> building "
                    + IctHighConfluenceStrategy.class.getSimpleName());
            return new IctHighConfluenceStrategy(primarySymbol, smtSymbol, eventBus);
        }
        // Scalp mode (SA3) piggybacks on this selection point: the runner
        // reads ScalpConfig at construction and swaps ONLY the target/risk
        // model (1R-capped targets + topstep50kScalp profile).
        System.out.println("[StdvOteFactory] building " + StdvOteRunnerStrategy.class.getSimpleName()
                + " for " + primarySymbol + " (smt=" + smtSymbol + ", scalp.enabled="
                + ScalpConfig.isEnabled() + ")");
        return new StdvOteRunnerStrategy(primarySymbol, smtSymbol, eventBus);
    }

    /** True when the new strategy should be used (the default). */
    public static boolean isEnabled() {
        String prop = com.topstep.trading.config.EngineConfig.current().getRaw(ENABLED_PROPERTY);
        if (prop == null) return true; // default-on after the refactor
        return "true".equalsIgnoreCase(prop.trim());
    }

    /** True only when strategy.legacyFallback=true is set explicitly (default false). */
    public static boolean legacyFallbackAllowed() {
        return com.topstep.trading.config.EngineConfig.current()
                .getBoolean(LEGACY_FALLBACK_PROPERTY, false);
    }
}
