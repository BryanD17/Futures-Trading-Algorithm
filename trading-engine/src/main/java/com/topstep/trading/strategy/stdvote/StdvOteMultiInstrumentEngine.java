package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.connector.TradingConnector;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.StrategyContext;
import com.topstep.trading.strategy.TradingStrategy;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Multi-instrument runtime for the STDV+OTE strategy.
 *
 * <p>Runs one {@link StdvOteRunnerStrategy} per <em>active</em> instrument and
 * routes market data so that:
 *
 * <ul>
 *   <li>each active strategy receives its own symbol's candles via
 *       {@link StdvOteRunnerStrategy#onCandle};</li>
 *   <li>a separately-tracked set of <em>SMT-only</em> symbols is subscribed
 *       for market data but never trades; their candles are forwarded to
 *       the active strategy that names them as its SMT correlate, via
 *       {@link StdvOteRunnerStrategy#onSmtCandle}.</li>
 * </ul>
 *
 * <h2>Default configuration</h2>
 *
 * <pre>
 * active symbols : MNQ, MGC          (override: -Dstdvote.symbols.active=MNQ,MGC)
 * SMT correlates : MNQ → MES         (override: -Dstdvote.symbols.smt.MNQ=MES)
 *                  MGC → (none)      (override: -Dstdvote.symbols.smt.MGC=DXY)
 * SMT-only feeds : MES               (any symbol named as an SMT correlate
 *                                     that isn't itself active)
 * </pre>
 *
 * <p>Only registry-allowed symbols (MNQ / MES / MGC) are accepted as
 * <em>active</em>; full-size or non-tradeable inputs throw at construction.
 * SMT-only symbols are not registry-checked because they don't trade —
 * MES is the canonical pair, DXY is a future extension if a feed exists.
 *
 * <h2>Safety</h2>
 *
 * <p>The engine never places orders directly. It only updates strategies,
 * which publish {@code StrategySignalEvent}s on the shared {@link EventBus}
 * for the existing risk + execution pipeline to consume. Pausing or
 * stopping the engine stops candle routing but does not affect already-open
 * positions — those are still managed by the runner-side execution code.
 */
public final class StdvOteMultiInstrumentEngine {

    /** System-property name for the comma-separated active symbol list. */
    public static final String ACTIVE_SYMBOLS_PROPERTY = "stdvote.symbols.active";

    /** System-property prefix for SMT correlate per active symbol. */
    public static final String SMT_PROPERTY_PREFIX = "stdvote.symbols.smt.";

    /** Default active list when no override is provided. */
    public static final List<String> DEFAULT_ACTIVE = List.of("MNQ", "MGC");

    /** Default SMT pairing map. MGC has no canonical micro SMT pair. */
    public static final Map<String, String> DEFAULT_SMT;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("MNQ", "MES");
        m.put("MGC", "");
        DEFAULT_SMT = Collections.unmodifiableMap(m);
    }

    private final TradingConnector connector;
    private final EventBus eventBus;
    private final StrategyContext strategyContext;

    private final List<String> activeSymbols;
    /** Per active symbol: its SMT correlate (may be blank). */
    private final Map<String, String> smtBySymbol;
    /** All distinct SMT-correlate symbols (deduplicated, non-blank). */
    private final List<String> smtOnlySymbols;

    private final Map<String, StdvOteRunnerStrategy> strategies = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Optional tap invoked with EVERY dispatched candle before routing.
     * This engine subscribes symbols itself (connector → dispatchCandle), so
     * the owning runner's onMarketData never sees these candles — the tap is
     * how the runner keeps its ChartEngine and warmup staleness reference
     * warm on the real candle path. Volatile: set once at wiring time,
     * read on the market-data thread.
     */
    private volatile java.util.function.Consumer<Candle> candleTap;

    public StdvOteMultiInstrumentEngine(TradingConnector connector,
                                        EventBus eventBus,
                                        StrategyContext strategyContext) {
        this(connector, eventBus, strategyContext, resolveActiveSymbols(), resolveSmtMap());
    }

    public StdvOteMultiInstrumentEngine(TradingConnector connector,
                                        EventBus eventBus,
                                        StrategyContext strategyContext,
                                        List<String> activeSymbols,
                                        Map<String, String> smtBySymbol) {
        if (connector == null) throw new IllegalArgumentException("connector must not be null");
        if (eventBus == null) throw new IllegalArgumentException("eventBus must not be null");
        if (strategyContext == null) throw new IllegalArgumentException("strategyContext must not be null");
        if (activeSymbols == null || activeSymbols.isEmpty()) {
            throw new IllegalArgumentException("activeSymbols must contain at least one entry");
        }
        for (String s : activeSymbols) {
            if (!TradeableInstrument.isTradeable(s)) {
                throw new IllegalArgumentException(
                        "Active symbol '" + s + "' is not in the TradeableInstrument "
                                + "registry. Allowed: MNQ, MES, MGC.");
            }
        }
        this.connector = connector;
        this.eventBus = eventBus;
        this.strategyContext = strategyContext;
        this.activeSymbols = List.copyOf(activeSymbols);
        this.smtBySymbol = Map.copyOf(smtBySymbol == null ? Map.of() : smtBySymbol);

        // Build SMT-only list: any value of smtBySymbol that is NOT also in
        // the active list (active symbols already receive their own candles).
        this.smtOnlySymbols = this.smtBySymbol.values().stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .filter(s -> !this.activeSymbols.contains(s))
                .collect(Collectors.toUnmodifiableList());

        // Construct strategies eagerly so the runner can hold references
        // and pass one to EngineFacade.initialize() before start().
        for (String symbol : this.activeSymbols) {
            String smt = this.smtBySymbol.getOrDefault(symbol, "");
            String smtArg = (smt == null || smt.isBlank()) ? null : smt.trim();
            StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(symbol, smtArg, eventBus);
            strategies.put(symbol, s);
        }
    }

    /** Active symbols (strategies that emit trades). */
    public List<String> getActiveSymbols() {
        return activeSymbols;
    }

    /** SMT-only feed symbols (subscribed but never traded). */
    public List<String> getSmtOnlySymbols() {
        return smtOnlySymbols;
    }

    /** True when {@link #start()} has been called and {@link #stop()} has not. */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Initialise strategies and subscribe to market data for all
     * active + SMT-only symbols. Strategies were constructed eagerly in the
     * engine's constructor; this method only does lifecycle + subscription.
     *
     * @return the strategy map keyed by active symbol, in declaration order
     */
    public Map<String, StdvOteRunnerStrategy> start() {
        if (!running.compareAndSet(false, true)) {
            return Map.copyOf(strategies);
        }

        for (StdvOteRunnerStrategy s : strategies.values()) {
            s.initialize();
        }

        // Subscribe each active symbol — candles route to that strategy.
        for (String symbol : activeSymbols) {
            connector.subscribeMarketData(symbol, this::dispatchCandle);
        }

        // Subscribe SMT-only symbols — candles fan out to any active
        // strategy that pairs with them.
        for (String smtSymbol : smtOnlySymbols) {
            connector.subscribeMarketData(smtSymbol, this::dispatchCandle);
        }

        System.out.println("[" + getClass().getSimpleName() + "] started: active="
                + activeSymbols + " smtOnly=" + smtOnlySymbols);
        return Map.copyOf(strategies);
    }

    /** Primary active strategy (for EngineFacade.initialize and similar). */
    public StdvOteRunnerStrategy getPrimaryStrategy() {
        return strategies.get(activeSymbols.get(0));
    }

    /** Install the per-candle tap (see {@link #candleTap}). */
    public void setCandleTap(java.util.function.Consumer<Candle> tap) {
        this.candleTap = tap;
    }

    /**
     * Hand the runner's ChartEngine to every per-symbol strategy so they can
     * log the 30m OTE screenshot-pattern signal next to the live gate path.
     * Observability only — no gating reads this.
     */
    public void setChartEngine(com.topstep.trading.chart.ChartEngine engine) {
        for (StdvOteRunnerStrategy s : strategies.values()) {
            s.setChartEngine(engine);
        }
    }

    /**
     * Hand the ICT library to every per-symbol strategy so each one publishes
     * its LevelEngine (V4 Agent 03 — §S6 pools become raid-able levels).
     */
    public void setIctLibEngine(com.topstep.trading.ictlib.IctLibEngine engine) {
        for (StdvOteRunnerStrategy s : strategies.values()) {
            s.setIctLibEngine(engine);
        }
    }

    /**
     * Hand the confluence stack to every per-symbol strategy so each one
     * publishes the engine facts it already computes (V4 Agent 07).
     */
    public void setConfluenceService(
            com.topstep.trading.confluence.ConfluenceService service) {
        for (StdvOteRunnerStrategy s : strategies.values()) {
            s.setConfluenceService(service);
        }
    }

    /**
     * Stop routing. Unsubscribes market data and shuts strategies down.
     * Open positions managed by the execution engine are NOT closed here.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        // Unsubscribe in reverse to mirror the start order.
        List<String> allSymbols = symbolsForSubscription();
        for (int i = allSymbols.size() - 1; i >= 0; i--) {
            try {
                connector.unsubscribeMarketData(allSymbols.get(i));
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }

        for (StdvOteRunnerStrategy s : strategies.values()) {
            try {
                s.onSessionEnd();
                s.shutdown();
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
        strategies.clear();
        System.out.println("[" + getClass().getSimpleName() + "] stopped");
    }

    /**
     * Route a candle to every interested strategy:
     *
     * <ul>
     *   <li>If the candle's symbol is active → that strategy's
     *       {@code onCandle}.</li>
     *   <li>For every active strategy whose SMT correlate equals the
     *       candle's symbol → that strategy's {@code onSmtCandle}.</li>
     * </ul>
     *
     * <p>The same candle CAN reach the primary strategy (as a primary) AND
     * other strategies (as an SMT feed). This is intentional — MNQ is
     * an active symbol but it is also MES's SMT correlate in principle,
     * so when MNQ is active and MES is active they cross-feed.
     */
    public void dispatchCandle(Candle candle) {
        if (candle == null) return;
        String symbol = candle.getSymbol();
        if (symbol == null) return;

        // Runner tap first: chart-in-memory + warmup staleness tracking see
        // every candle (backfill replay and live alike) before routing.
        java.util.function.Consumer<Candle> tap = candleTap;
        if (tap != null) {
            tap.accept(candle);
        }

        // Direct routing to the symbol's own strategy if it's active.
        StdvOteRunnerStrategy own = strategies.get(symbol);
        if (own != null) {
            own.onCandle(candle, strategyContext);
        }

        // SMT routing: any active strategy whose correlate equals this symbol.
        for (Map.Entry<String, String> e : smtBySymbol.entrySet()) {
            String activeSym = e.getKey();
            String correlate = e.getValue();
            if (correlate == null || correlate.isBlank()) continue;
            if (!correlate.trim().equals(symbol)) continue;
            // Don't double-feed: if the active strategy already received
            // this candle as its own (above), skip.
            if (symbol.equals(activeSym)) continue;
            StdvOteRunnerStrategy target = strategies.get(activeSym);
            if (target != null) {
                target.onSmtCandle(candle);
            }
        }
    }

    /** Active strategy lookup (post-start). */
    public StdvOteRunnerStrategy getStrategy(String symbol) {
        return strategies.get(symbol);
    }

    /** Every symbol the engine subscribes to (active + SMT-only), in order. */
    public List<String> symbolsForSubscription() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(activeSymbols);
        all.addAll(smtOnlySymbols);
        return List.copyOf(all);
    }

    // ──────────────────────────────────────────────────────────────────────
    // System-property resolution helpers (overridable for tests)
    // ──────────────────────────────────────────────────────────────────────

    static List<String> resolveActiveSymbols() {
        String prop = System.getProperty(ACTIVE_SYMBOLS_PROPERTY);
        if (prop == null || prop.isBlank()) return DEFAULT_ACTIVE;
        return Arrays.stream(prop.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableList());
    }

    static Map<String, String> resolveSmtMap() {
        Map<String, String> out = new LinkedHashMap<>(DEFAULT_SMT);
        // Apply per-symbol overrides if present.
        for (String active : resolveActiveSymbols()) {
            String key = SMT_PROPERTY_PREFIX + active;
            String override = System.getProperty(key);
            if (override != null) {
                out.put(active, override);
            } else if (!out.containsKey(active)) {
                out.put(active, "");
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
