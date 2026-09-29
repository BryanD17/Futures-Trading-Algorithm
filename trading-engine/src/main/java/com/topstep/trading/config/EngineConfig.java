package com.topstep.trading.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * THE single source of truth for every behavioural engine flag
 * (TRADE_FLOW_UNBLOCK_MASTER_PROMPT_V5, Agent 01, rule R8).
 *
 * <p>Every flag the engine reads is registered in {@link #KEYS} with a type,
 * a documented code default and (where V5 Appendix C renamed it) the legacy
 * names it still answers to. Every read in the engine goes through the typed
 * getters below; nothing else in {@code trading-engine/src/main} reads a JVM
 * system property directly. The only class that touches the raw sources is
 * {@link EngineConfigLoader}.
 *
 * <h2>Precedence (highest first)</h2>
 * <ol>
 *   <li>{@code -D} JVM system property ({@link Source#SYSTEM_PROPERTY})</li>
 *   <li>{@code ENGINE_<KEY>} environment variable, key upper-cased with every
 *       non-alphanumeric character replaced by {@code _}
 *       (e.g. {@code ENGINE_BACKFILL_DAYS}) ({@link Source#ENV})</li>
 *   <li>{@code ${user.home}/topstep-trading/engine.properties} (or the file
 *       named by {@code -Dengine.props}) ({@link Source#PROPERTIES_FILE})</li>
 *   <li>classpath {@code engine-defaults.properties} ({@link Source#DEFAULT})</li>
 *   <li>the code default registered here / at the call site ({@link Source#DEFAULT})</li>
 * </ol>
 * Within one layer the canonical (V5) name wins over a legacy alias.
 *
 * <p>The file / env / classpath layers are loaded ONCE (at EngineFacade
 * construction, or lazily on the first read). The system-property layer is
 * consulted through a reader supplied by the loader at read time so that
 * {@code -D} flags keep the highest precedence even for unit tests that call
 * {@code System.setProperty} after the config was loaded; in production no
 * code sets system properties after boot, so the boot table IS the runtime
 * configuration.
 */
public final class EngineConfig {

    /** Where an effective value came from. */
    public enum Source { DEFAULT, PROPERTIES_FILE, SYSTEM_PROPERTY, ENV }

    /** Value type (documentation). */
    public enum Type { BOOL, INT, LONG, DOUBLE, STRING, ENUM, TIME, PATH, CSV }

    /**
     * A registered key. {@code name} may end in one {@code <SYM>} or
     * {@code <FIELD>} placeholder (a templated family, e.g.
     * {@code chart.minLegTicks.<SYM>}). {@code defaultValue} is the CODE
     * default ({@code null} = derived at the call site; see {@code doc}).
     */
    public record Key(String name, Type type, String defaultValue, String doc,
                      List<String> aliases, boolean secret) {
        public boolean isTemplate() { return name.contains("<"); }
        String templatePrefix() { return name.substring(0, name.indexOf('<')); }
    }

    /** One effective value. */
    public record Entry(String key, String value, Source source, String origin,
                        String codeDefault, String doc) {}

    // ──────────────────────────────────────────────────────────────────────
    // REGISTRY — every key in DIAGNOSIS_V5.md §2 (94) + the V5 additions
    // ──────────────────────────────────────────────────────────────────────

    /** Symbols a {@code <SYM>} template expands to. */
    public static final List<String> SYMBOLS = List.of("MNQ", "MES", "MGC");

    /** Keys a {@code confluence.weight.<FIELD>} template expands to (ConfluenceField keys). */
    public static final List<String> CONFLUENCE_FIELDS = List.of(
            "inTradingKillzone", "htfBiasAligned", "voteBiasAligned", "pdVerdict",
            "recentSweep", "raidScore", "machineOteState", "activeFvgInDirection",
            "priceInsideFvg", "nearestObZone", "bprPresent", "viNearby",
            "openingGapMagnet", "poolSweptRecently", "structureState", "chartOteState");

    /** Every registered key, in boot-table order. */
    public static final Map<String, Key> KEYS;
    private static final Map<String, String> ALIAS_TO_CANONICAL;

    static {
        List<Key> k = new ArrayList<>();
        // strategy selection (M1)
        k.add(key("strategy.stdvOte", Type.BOOL, "true", "StdvOte runner strategy vs legacy IctHighConfluenceStrategy", "stdvOte.enabled"));
        k.add(key("strategy.legacyFallback", Type.BOOL, "false", "allow a non-{MNQ,MES,MGC} symbol to fall back to the legacy strategy (else fail fast)"));
        // engine wiring
        k.add(key("engine.symbol", Type.STRING, "MNQ", "single-symbol primary (multiInstrument=false)", "stdvote.symbol"));
        k.add(key("engine.smtSymbol", Type.STRING, "MES", "single-symbol SMT pair (LIVE)", "stdvote.smt"));
        k.add(key("engine.multiInstrument", Type.BOOL, "true", "multi-instrument engine (MNQ+MGC active, MES SMT)", "stdvote.multiInstrument"));
        k.add(key("stdvote.symbols.active", Type.CSV, "MNQ,MGC", "active symbols in multi-instrument mode"));
        k.add(key("stdvote.symbols.smt.<SYM>", Type.STRING, null, "SMT pair per active symbol (default MNQ->MES)"));
        k.add(key("notify.discord.enabled", Type.BOOL, "true", "Discord alerts (read by PR #151 builds; carried)"));
        // session / time (Agent 02 consumes session.gateMode)
        k.add(key("session.allSessions", Type.BOOL, "true", "entries in any open session, not just prime killzones", "scalp.allSessions"));
        k.add(key("session.gateMode", Type.ENUM, "SCORING", "M3 session gate SCORING | BLOCKING (consumer: Agent 02)"));
        k.add(key("session.killzoneSizeBoost", Type.DOUBLE, "1.5", "size multiplier inside prime killzones [1,2]", "scalp.killzoneSizeBoost"));
        // setup state machine windows
        k.add(key("detector.timeframe", Type.INT, "5", "entry-anatomy detector timeframe minutes (1|3|5|15)", "stdvote.detectorTimeframe"));
        k.add(key("setup.expiryBars", Type.INT, "40", "setup expiry in detector bars", "stdvOte.setupExpiryBars"));
        // V5 Agent 02 lifecycle keys (SessionConfig) + Agent 05.1 PHASED budgets (minutes = 1m feed bars)
        k.add(key("setup.expiryAnchor", Type.ENUM, null, "PHASED | SWEEP_DONE_TOTAL (alias SWEEP_DONE) | BIAS_SET; default PHASED (SCORING) / BIAS_SET (BLOCKING)"));
        k.add(key("setup.expiryMinutes", Type.INT, null, "SWEEP_DONE_TOTAL / BIAS_SET single budget in minutes (wins over setup.expiryBars)"));
        k.add(key("setup.preSweepExpiryMinutes", Type.INT, "480", "BIAS_SET/MANIP_DONE budget in minutes (PHASED + SWEEP_DONE_TOTAL)"));
        k.add(key("setup.expiry.sweepToDisplacement", Type.INT, "60", "PHASED: SWEEP_DONE -> DISPLACED budget, minutes (Agent 05.1)"));
        k.add(key("setup.expiry.displacementToMss", Type.INT, "60", "PHASED: DISPLACED -> MSS_CONFIRMED budget, minutes (Agent 05.1)"));
        k.add(key("setup.expiry.mssToOte", Type.INT, "240", "PHASED: MSS_CONFIRMED -> OTE_ARMED budget, minutes; backstop to the OTE 1.0 invalidation (Agent 05.1)"));
        k.add(key("ote.windowBars", Type.INT, "8", "OTE window in detector bars", "stdvOte.oteWindowBars"));
        k.add(key("mss.freshBars", Type.INT, "30", "MSS freshness in detector bars", "stdvOte.mssFreshBars"));
        k.add(key("setup.entryTimeoutBars", Type.INT, null, "unfilled-entry timeout in feed bars (default 2 x OTE window)", "stdvOte.entryTimeoutBars"));
        k.add(key("setup.rearmOnInvalidated", Type.BOOL, "true", "legacy re-arm after invalidation", "stdvOte.rearmOnInvalidated"));
        k.add(key("setup.rearmCooldownBars", Type.INT, "5", "re-arm cooldown in feed bars", "scalp.rearmCooldownBars"));
        k.add(key("setup.rearmAfterClose", Type.BOOL, "true", "re-arm IN_TRADE after the position closes, every target model (false = one trade per window, A/B) (Agent 05.3)"));
        k.add(key("stdvOte.stopBufferTicks", Type.INT, "4", "stop buffer beyond OTE 1.0 (ticks)"));
        k.add(key("stdvOte.reactionWickTicks", Type.INT, "2", "minimum OTE rejection wick (ticks)"));
        // displacement (owner's live flags)
        k.add(key("displacement.atrMult", Type.DOUBLE, "1.5", "displacement range >= atrMult x ATR", "stdvote.displacement.atrMult"));
        k.add(key("displacement.bodyPct", Type.DOUBLE, "0.65", "displacement body fraction", "stdvote.displacement.bodyPct"));
        k.add(key("displacement.recentBars", Type.INT, "5", "displacement recency window in detector bars (PR #151 key)", "stdvote.displacement.recentBars"));
        // V5 Agent 04: FVG linkage, OTE anchor/fibs/stop, ONE RR band (read via OteConfig)
        k.add(key("fvg.linkBars", Type.INT, "3", "M5: FVG created by the displacement or within N detector bars (Agent 04)"));
        k.add(key("ote.anchorMode", Type.ENUM, "DEALING_RANGE", "OTE anchor DEALING_RANGE | TRADING_DAY | IMPULSE (Agent 04)"));
        k.add(key("ote.fib62", Type.DOUBLE, "0.618", "OTE band near edge (Agent 04)"));
        k.add(key("ote.fib705", Type.DOUBLE, "0.705", "OTE sweet spot (Agent 04)"));
        k.add(key("ote.fib79", Type.DOUBLE, "0.786", "OTE band far edge (Agent 04)"));
        k.add(key("ote.stopMode", Type.ENUM, "BAND", "OTE stop BAND (beyond 0.786 / PD array) | ORIGIN (beyond 1.0) (Agent 04)"));
        k.add(key("ote.entryModel", Type.ENUM, "IMPULSE_LEG", "OTE entry model IMPULSE_LEG (M5/M6 on the dealing-range impulse, arm on the OTE-band sweep) | POST_SWEEP (new displacement+MSS after the sweep) (Agent 05.2)"));
        k.add(key("ote.impulseLeg.minSweepFib", Type.DOUBLE, "0.705", "IMPULSE_LEG: dealing-range fib the sweep extreme must reach (Agent 05.2)"));
        k.add(key("ote.pdArraySource", Type.ENUM, "ICT_OB", "IMPULSE_LEG M7 order block at the sweep: ICT_OB (last opposite-close 1m bar before the sweep bar overlapping the band, added to the M7 candidates) | SWEEP_BAR (05.2: the 5m bar containing the sweep only) (Agent 05.5)"));
        k.add(key("ote.obLookbackBars", Type.INT, "5", "ICT_OB: 1m bars walked back from the sweep bar to find the last opposite-close candle (Agent 05.5)"));
        k.add(key("risk.rrFloor", Type.DOUBLE, "1.0", "ONE RR band: floor vs T1, legacy profile (Agent 04; risk engine: Agent 05)"));
        k.add(key("risk.rrFloor.scalp", Type.DOUBLE, "0.8", "ONE RR band: floor vs T1, scalp profile (Agent 04)"));
        k.add(key("risk.rrCeiling", Type.DOUBLE, "5.0", "ONE RR band: ceiling vs final target, both profiles (Agent 04)"));
        // V5 Agent 05.8: opt-in counter-trend scalp (premium sweep -> equilibrium), default OFF
        k.add(key("entry.counterTrendScalp", Type.BOOL, "false", "opt-in COUNTER-TREND SCALP: short a premium-OTE-band sweep of a BULLISH range to equilibrium (long a discount-band sweep of a BEARISH range); false = byte-identical pre-05.8 engine (Agent 05.8)"));
        k.add(key("entry.counterTrend.sessions", Type.CSV, "ASIA,LONDON,PRE_NY", "session windows the counter-trend scalp may enter in (NO_ENTRY / WEEKEND are never allowed; NY_AM,NY_LUNCH,NY_PM opt-in) (Agent 05.8)"));
        k.add(key("entry.counterTrend.minRangeTicks", Type.INT, "400", "minimum dealing range (ticks) for a counter-trend scalp: a move to equilibrium must be worth >= 1R (MNQ 400 = 100 pt) (Agent 05.8)"));
        k.add(key("entry.counterTrend.maxRiskFraction", Type.DOUBLE, "0.5", "counter-trend scalp $ risk = the normal risk-derived budget x this fraction (clamped 0..1) (Agent 05.8)"));
        k.add(key("entry.counterTrend.maxPerDay", Type.INT, "2", "maximum counter-trend scalp emissions per CME trading day (18:00 ET roll) (Agent 05.8)"));
        // bias
        k.add(key("bias.vote.mode", Type.ENUM, "VOTE", "LEGACY | LOG | VOTE - which bias feeds recordHtfBias (V5 Agent 03: VOTE)"));
        k.add(key("bias.voteRule", Type.ENUM, "ADAPTIVE", "STRICT_3OF4 | ADAPTIVE vote aggregation (Agent 03)"));
        k.add(key("bias.source", Type.ENUM, "RANGE", "RANGE (dealing-range anchor) | VOTE (Agent 03)"));
        k.add(key("bias.range.minRangePct", Type.DOUBLE, "0.08", "dealing range decisive span, % of price (Agent 03)"));
        k.add(key("bias.range.reanchorFraction", Type.DOUBLE, "0.5", "pullback share of the leg that re-anchors the dealing range on a BOS (Agent 03)"));
        k.add(key("bias.range.window", Type.ENUM, "AUTO", "dealing-range window SESSION_DAY (Globex day from 18:00 ET) | RTH_FIRST (from 09:30 ET the RTH impulse leg) | AUTO (RTH leg once >= minLegTicks, else session day) (Agent 05.6)"));
        k.add(key("bias.range.minLegTicks", Type.INT, "400", "minimum RTH impulse leg (ticks) for RTH_FIRST/AUTO (Agent 05.6)"));
        k.add(key("bias.range.carryAcrossReopen", Type.BOOL, "true", "keep the previous session's governing dealing range across the 18:00 ET reopen until the new session prints an impulse >= minLegTicks; false = restart at the reopen (Agent 05.7)"));
        k.add(key("bias.range.minLegTicks.<SYM>", Type.INT, null, "per-symbol minimum RTH impulse leg (falls back to bias.range.minLegTicks)"));
        k.add(key("bias.v1.includeH4", Type.BOOL, "false", "V1 vote consults H4"));
        k.add(key("bias.hysteresis", Type.BOOL, "true", "NEUTRAL-flip grace for in-flight setups", "bias.hysteresis.enabled"));
        k.add(key("bias.neutralGraceBars", Type.INT, "3", "NEUTRAL grace length [1,4]"));
        // premium/discount, 30m OTE
        k.add(key("pd.gate.mode", Type.ENUM, "BLOCK", "M2b mode OFF | LOG | BLOCK (V5 Agent 03: BLOCK on the dealing range)"));
        k.add(key("pd.eqBandTicks", Type.INT, "2", "equilibrium band (ticks)"));
        k.add(key("pd.minRangeTicks", Type.INT, null, "min dealing range ticks (default 2 x chart.minLegTicks)"));
        k.add(key("pd.minRangeTicks.<SYM>", Type.INT, null, "per-symbol min dealing range"));
        k.add(key("pd.d1MinBars", Type.INT, "10", "D1 depth before R0 governs"));
        k.add(key("ote30m.mode", Type.ENUM, "SCORING", "M7b mode OFF | LOG | SCORING | GATE (V5 Agent 04: SCORING)", "ote30m.confluence"));
        k.add(key("ote30m.acceptArmed", Type.BOOL, "false", "M7b accepts ARMED"));
        k.add(key("ote.stats.file", Type.PATH, "data/ote_agreement_stats.jsonl", "OTE agreement stats output"));
        // levels + raid scoring (Agent 03)
        k.add(key("levels.minBarsPerDay", Type.INT, "60", "trading days with fewer bars are phantoms (never PDH/PDL)"));
        k.add(key("levels.rearmDistanceTicks", Type.INT, "100", "a raided level re-arms once a candle has left it by this many ticks"));
        k.add(key("levels.asia.start", Type.TIME, "20:00", "Asia level window start (ET)"));
        k.add(key("levels.asia.end", Type.TIME, "00:00", "Asia level window end (ET, wraps midnight)"));
        k.add(key("levels.london.start", Type.TIME, "04:00", "London level window start (ET; owner LuxAlgo 30640 parity)"));
        k.add(key("levels.london.end", Type.TIME, "06:00", "London level window end (ET)"));
        k.add(key("levels.nyam.start", Type.TIME, "09:30", "NY AM level window start (ET)"));
        k.add(key("levels.nyam.end", Type.TIME, "12:00", "NY AM level window end (ET)"));
        k.add(key("levels.nypm.start", Type.TIME, "13:30", "NY PM level window start (ET)"));
        k.add(key("levels.nypm.end", Type.TIME, "16:00", "NY PM level window end (ET)"));
        k.add(key("levels.ny.start", Type.TIME, "09:30", "NY full-session level window start (ET)"));
        k.add(key("levels.ny.end", Type.TIME, "16:00", "NY full-session level window end (ET)"));
        k.add(key("raid.starvedScore", Type.INT, "-1", "score of an unscoreable sweep (-1 = instrument floor - 1)"));
        k.add(key("raid.sweepMode", Type.ENUM, "PIPELINE", "PIPELINE | LEGACY (rollback: pre-V5 sweep path)"));
        k.add(key("raid.weights", Type.ENUM, "V5", "V5 | V4 (rollback: pre-V5 raid weight table)"));
        k.add(key("manip.legMode", Type.ENUM, "SESSION", "SESSION | KILLZONE (rollback: pre-V5 manipulation leg)"));
        // scalp
        k.add(key("scalp.enabled", Type.BOOL, "false", "scalp master switch (risk profile, windows, re-arm, sizer, brackets)", "scalpMode.enabled"));
        k.add(key("scalp.breakevenAtHalfR", Type.BOOL, "true", "breakeven at +0.5R"));
        k.add(key("scalp.minTargetClearanceTicks", Type.INT, "2", "scalp target clearance (ticks)"));
        k.add(key("scalp.candidateWindowR", Type.DOUBLE, "1.5", "scalp target window (R)"));
        k.add(key("raid.minScore.scalp", Type.INT, "6", "scalp raid-score floor", "scalp.minRaidScore"));
        k.add(key("scalp.londonPrimeStartEt", Type.TIME, "03:00", "MGC London prime start (ET)"));
        k.add(key("scalp.londonPrimeEndEt", Type.TIME, "05:00", "MGC London prime end (ET)"));
        k.add(key("scalp.sizerSafetyCushion", Type.DOUBLE, "200", "sizer cushion ($)"));
        // post-signal pipeline (V5 Agent 05: warmup, sizing, RR band, account, news, SIM orders)
        k.add(key("warmup.timeoutSeconds", Type.INT, "120", "warmup completes after N s even if a required feed never delivered a live candle (WARN names it)"));
        k.add(key("size.minMicros", Type.INT, "1", "risk-derived size below this is DENIED (SIZE: ...); band [1,20]"));
        k.add(key("size.preferredMicros", Type.INT, "5", "fallback size when geometry is unknown; never a floor"));
        k.add(key("size.maxMicros", Type.INT, "20", "hard micro ceiling per position (also capped by RiskLimits.maxContracts)"));
        k.add(key("risk.haltOnProfitTarget", Type.BOOL, null, "stop at the profit target (unset: LIVE true / SIM false)"));
        k.add(key("news.blockWithoutCalendar", Type.BOOL, "false", "let a Mock/absent economic calendar block trades"));
        k.add(key("order.ttlBars", Type.INT, null, "SIM resting-order TTL in 1m bars (unset: ote.windowBars x detector.timeframe x 2 = 80)"));
        // trade profile
        k.add(key("trade.profile", Type.ENUM, "STRICT", "STRICT | STANDARD | MINIMAL"));
        k.add(key("profile.sim.file", Type.PATH, "data/profile_sim.jsonl", "profile simulator output"));
        // chart
        k.add(key("chart.minLegTicks.<SYM>", Type.INT, null, "chart min leg ticks (ChartEngine ctor default)"));
        k.add(key("chart.swingStrength.<SYM>", Type.INT, null, "chart fractal strength (ctor default)"));
        k.add(key("chart.zoneExpiryBars.<SYM>", Type.INT, null, "chart zone expiry (ctor default)"));
        k.add(key("chart.anchorCompare", Type.BOOL, "false", "log both anchor modes"));
        k.add(key("chart.anchorMode", Type.ENUM, "FRACTAL_LEG", "OTE leg anchoring"));
        k.add(key("chart.anchorMode.<SYM>", Type.ENUM, null, "per-symbol anchoring (falls back to chart.anchorMode)"));
        k.add(key("chart.oteBand", Type.STRING, null, "OTE band (unset = engine default)"));
        k.add(key("chart.oteBand.<SYM>", Type.STRING, null, "per-symbol band (falls back to chart.oteBand)"));
        // confluence
        k.add(key("confluence.weight.<FIELD>", Type.DOUBLE, null, "confluence weight per field (ConfluenceField default)"));
        k.add(key("confluence.nearTicks", Type.INT, "40", "near-price distance (ticks)"));
        k.add(key("confluence.raidScoreFloor", Type.INT, "5", "raid-score confluence floor"));
        k.add(key("confluence.recentMinutes", Type.INT, "120", "recency window (minutes)"));
        // SIM / mock / backfill
        k.add(key("backfill.days", Type.INT, "3", "1m backfill depth [1,7]"));
        k.add(key("htf.backfill.days", Type.INT, "30", "HTF backfill depth [7,90]"));
        k.add(key("sim.warmBoot", Type.BOOL, "true", "SIM synthetic warm boot"));
        k.add(key("sim.tape", Type.ENUM, "CHOREOGRAPHY", "SIM tape CHOREOGRAPHY | RANDOM"));
        k.add(key("sim.backfill.seed", Type.LONG, "42", "SIM RNG seed"));
        k.add(key("mock.candleIntervalMs", Type.LONG, "5000", "SIM candle cadence (ms)"));
        k.add(key("mock.virtualClock", Type.BOOL, "false", "SIM virtual timeline"));
        k.add(key("mock.virtualMinutes", Type.LONG, "2000", "virtual timeline offset (min)"));
        // backtest
        k.add(key("backtest.commissionPerSide", Type.DOUBLE, "1.55", "backtest commission ($/side/contract)"));
        k.add(key("backtest.slippageTicks", Type.INT, "1", "backtest slippage (ticks/side)"));
        // broker
        k.add(key("topstep.allowNonSimulated", Type.BOOL, "false", "allow a real-money (non-simulated) account"));
        k.add(secret("topstep.apiUrl", "TopstepX API URL (else credentials file / env)"));
        k.add(secret("topstep.username", "TopstepX username"));
        k.add(secret("topstep.apiKey", "TopstepX API key"));
        k.add(secret("topstep.accountId", "TopstepX account id"));
        // ictlib (observation only)
        k.add(key("ictlib.enabled", Type.BOOL, "true", "ictlib master"));
        k.add(key("ictlib.displacement.meanLen", Type.INT, "5", "ictlib"));
        k.add(key("ictlib.displacement.wickRatioMax", Type.DOUBLE, "0.36", "ictlib"));
        k.add(key("ictlib.retain.displacement", Type.INT, "50", "ictlib"));
        k.add(key("ictlib.fvg.mode", Type.ENUM, "FVG", "ictlib FVG | IFVG"));
        k.add(key("ictlib.retain.fvg", Type.INT, "10", "ictlib"));
        k.add(key("ictlib.retain.bpr", Type.INT, "5", "ictlib"));
        k.add(key("ictlib.retain.volumeImbalance", Type.INT, "6", "ictlib"));
        k.add(key("ictlib.vi.projectBars", Type.INT, "3", "ictlib"));
        k.add(key("ictlib.retain.gapWeekly", Type.INT, "3", "ictlib"));
        k.add(key("ictlib.retain.gapDaily", Type.INT, "2", "ictlib"));
        k.add(key("ictlib.pool.swingLen", Type.INT, "5", "ictlib"));
        k.add(key("ictlib.pool.toleranceDiv", Type.DOUBLE, "2.5", "ictlib"));
        k.add(key("ictlib.pool.minCluster", Type.INT, "3", "ictlib"));
        k.add(key("ictlib.pool.scanDepth", Type.INT, "50", "ictlib"));
        k.add(key("ictlib.retain.pool", Type.INT, "4", "ictlib"));
        k.add(key("ictlib.pool.atrPeriod", Type.INT, "10", "ictlib"));
        k.add(key("ictlib.ob.swingLen", Type.INT, "10", "ictlib"));
        k.add(key("ictlib.ob.useBody", Type.BOOL, "true", "ictlib"));
        k.add(key("ictlib.retain.orderBlock", Type.INT, "5", "ictlib"));
        k.add(key("ictlib.structure.pivotLeft", Type.INT, "5", "ictlib"));
        k.add(key("ictlib.structure.pivotRight", Type.INT, "1", "ictlib"));
        k.add(key("ictlib.structure.historyCap", Type.INT, "200", "ictlib"));
        k.add(key("ictlib.structure.mssAgreeWindow", Type.INT, "5", "ictlib"));

        Map<String, Key> keys = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        for (Key key : k) {
            if (keys.put(key.name(), key) != null) {
                throw new IllegalStateException("duplicate EngineConfig key " + key.name());
            }
            for (String a : key.aliases()) {
                if (aliases.put(a, key.name()) != null) {
                    throw new IllegalStateException("duplicate EngineConfig alias " + a);
                }
            }
        }
        KEYS = Collections.unmodifiableMap(keys);
        ALIAS_TO_CANONICAL = Collections.unmodifiableMap(aliases);
    }

    private static Key key(String name, Type type, String def, String doc, String... aliases) {
        return new Key(name, type, def, doc, List.of(aliases), false);
    }

    private static Key secret(String name, String doc) {
        return new Key(name, Type.STRING, null, doc, List.of(), true);
    }

    /** Every concrete name the engine may read (canonical + aliases + expanded templates). */
    public static Set<String> allReadableNames() {
        Set<String> out = new TreeSet<>();
        for (Key k : KEYS.values()) {
            List<String> names = new ArrayList<>();
            names.add(k.name());
            names.addAll(k.aliases());
            for (String n : names) {
                if (n.contains("<")) {
                    String prefix = n.substring(0, n.indexOf('<'));
                    for (String x : n.contains("<FIELD>") ? CONFLUENCE_FIELDS : SYMBOLS) {
                        out.add(prefix + x);
                    }
                } else {
                    out.add(n);
                }
            }
        }
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────
    // INSTANCE
    // ──────────────────────────────────────────────────────────────────────

    private final Map<String, String> classpathDefaults;
    private final String classpathOrigin;
    private final Map<String, String> fileProps;
    private final String filePath;
    private final boolean fileLoaded;
    private final Map<String, String> env;
    private final Function<String, String> systemProperty;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    EngineConfig(Map<String, String> classpathDefaults, String classpathOrigin,
                 Map<String, String> fileProps, String filePath, boolean fileLoaded,
                 Map<String, String> env, Function<String, String> systemProperty) {
        this.classpathDefaults = Map.copyOf(classpathDefaults);
        this.classpathOrigin = classpathOrigin;
        this.fileProps = Map.copyOf(fileProps);
        this.filePath = filePath;
        this.fileLoaded = fileLoaded;
        this.env = Map.copyOf(env);
        this.systemProperty = Objects.requireNonNull(systemProperty);
    }

    private static volatile EngineConfig current;

    /** The installed config; loads it (once) on first use. */
    public static EngineConfig current() {
        EngineConfig c = current;
        if (c == null) {
            synchronized (EngineConfig.class) {
                c = current;
                if (c == null) {
                    c = EngineConfigLoader.load();
                    current = c;
                }
            }
        }
        return c;
    }

    /** True once a config has been loaded/installed in this JVM. */
    public static boolean isLoaded() {
        return current != null;
    }

    /** Install a config (EngineFacade at construction; tests). */
    public static synchronized void install(EngineConfig config) {
        current = Objects.requireNonNull(config);
    }

    /** Drop the installed config so the next {@link #current()} reloads (tests). */
    public static synchronized void reset() {
        current = null;
    }

    public String filePath() { return filePath; }
    public boolean fileLoaded() { return fileLoaded; }
    public Map<String, String> fileProperties() { return fileProps; }

    // ── resolution ──

    /** Canonical V5 name for a key or legacy alias (identity when unknown). */
    public static String canonical(String name) {
        return ALIAS_TO_CANONICAL.getOrDefault(name, name);
    }

    /** Every name the key answers to: canonical first, then legacy aliases. */
    static List<String> namesFor(String name) {
        String c = canonical(name);
        Key k = KEYS.get(c);
        if (k == null || k.aliases().isEmpty()) return List.of(c);
        List<String> out = new ArrayList<>(1 + k.aliases().size());
        out.add(c);
        out.addAll(k.aliases());
        return out;
    }

    /** ENGINE_ environment variable name for a key. */
    public static String envName(String key) {
        return "ENGINE_" + key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    /** The effective raw value and its source, or {@code null} when unset in every layer. */
    public Entry lookup(String name) {
        List<String> names = namesFor(name);
        for (String n : names) {
            String v = systemProperty.apply(n);
            if (v != null) return entry(names.get(0), v, Source.SYSTEM_PROPERTY, "-D" + n);
        }
        for (String n : names) {
            String v = env.get(envName(n));
            if (v != null) return entry(names.get(0), v, Source.ENV, envName(n));
        }
        for (String n : names) {
            String v = fileProps.get(n);
            if (v != null) return entry(names.get(0), v, Source.PROPERTIES_FILE, filePath);
        }
        for (String n : names) {
            String v = classpathDefaults.get(n);
            if (v != null) return entry(names.get(0), v, Source.DEFAULT, classpathOrigin);
        }
        return null;
    }

    private Entry entry(String key, String value, Source source, String origin) {
        Key k = registered(key);
        return new Entry(key, value, source, origin,
                k == null ? null : k.defaultValue(), k == null ? null : k.doc());
    }

    /** The registered definition for a concrete key (template-aware), or null. */
    public static Key registered(String name) {
        String c = canonical(name);
        Key k = KEYS.get(c);
        if (k != null) return k;
        for (Key t : KEYS.values()) {
            if (!t.isTemplate()) continue;
            String p = t.templatePrefix();
            if (c.startsWith(p) && c.length() > p.length() && c.indexOf('.', p.length()) < 0) {
                return t;
            }
        }
        return null;
    }

    /** Raw string value or {@code null} when unset in every layer. */
    public String getRaw(String name) {
        Entry e = lookup(name);
        return e == null ? null : e.value();
    }

    public String getString(String name, String defaultValue) {
        String v = getRaw(name);
        return v == null ? defaultValue : v;
    }

    /** "true"/"false" (case-insensitive); anything else = the default (preserves every legacy parser). */
    public boolean getBoolean(String name, boolean defaultValue) {
        String v = getRaw(name);
        if (v == null) return defaultValue;
        String t = v.trim();
        if ("true".equalsIgnoreCase(t)) return true;
        if ("false".equalsIgnoreCase(t)) return false;
        warnInvalid(name, v, defaultValue);
        return defaultValue;
    }

    public int getInt(String name, int defaultValue) {
        String v = getRaw(name);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            warnInvalid(name, v, defaultValue);
            return defaultValue;
        }
    }

    public long getLong(String name, long defaultValue) {
        String v = getRaw(name);
        if (v == null) return defaultValue;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            warnInvalid(name, v, defaultValue);
            return defaultValue;
        }
    }

    public double getDouble(String name, double defaultValue) {
        String v = getRaw(name);
        if (v == null) return defaultValue;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            warnInvalid(name, v, defaultValue);
            return defaultValue;
        }
    }

    private void warnInvalid(String name, String raw, Object def) {
        if (warned.add(name + "=" + raw)) {
            System.out.println("[EngineConfig] WARN: invalid " + name + "='" + raw
                    + "', using default " + def);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // EFFECTIVE TABLE
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Every effective key: all registered exact keys (value or code default),
     * the concrete members of templated families that some layer sets, and
     * any unregistered key present in the file/classpath layers (flags other
     * agents add before they are registered).
     */
    public List<Entry> effectiveEntries() {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Key k : KEYS.values()) {
            if (k.isTemplate()) {
                List<String> exp = k.name().contains("<FIELD>") ? CONFLUENCE_FIELDS : SYMBOLS;
                boolean any = false;
                for (String x : exp) {
                    String concrete = k.templatePrefix() + x;
                    Entry e = lookup(concrete);
                    if (e != null) {
                        out.put(concrete, mask(k, e));
                        any = true;
                    }
                }
                if (!any) {
                    out.put(k.name(), new Entry(k.name(), null, Source.DEFAULT, "code",
                            k.defaultValue(), k.doc()));
                }
                continue;
            }
            Entry e = lookup(k.name());
            if (e == null) {
                e = new Entry(k.name(), k.defaultValue(), Source.DEFAULT, "code",
                        k.defaultValue(), k.doc());
            }
            out.put(k.name(), mask(k, e));
        }
        Set<String> extra = new TreeSet<>();
        extra.addAll(classpathDefaults.keySet());
        extra.addAll(fileProps.keySet());
        for (String name : extra) {
            String c = canonical(name);
            if (out.containsKey(c) || registered(c) != null) continue;
            Entry e = lookup(c);
            if (e != null) out.put(c, e);
        }
        return new ArrayList<>(out.values());
    }

    /** Keys present in engine.properties that no registry entry knows (typos or not-yet-registered flags). */
    public List<String> unknownFileKeys() {
        List<String> out = new ArrayList<>();
        for (String name : new TreeSet<>(fileProps.keySet())) {
            if (registered(name) == null) out.add(name);
        }
        return out;
    }

    private static Entry mask(Key k, Entry e) {
        if (!k.secret() || e.value() == null) return e;
        return new Entry(e.key(), "****", e.source(), e.origin(), e.codeDefault(), e.doc());
    }

    /** Value as the table shows it. */
    private static String shown(Entry e) {
        if (e.value() != null) return e.value();
        return e.codeDefault() == null ? "(unset)" : e.codeDefault();
    }

    /** The fixed-width boot table: key | value | source, then one line per mode consequence. */
    public String formatBootTable() {
        List<Entry> entries = effectiveEntries();
        int kw = "key".length();
        int vw = "value".length();
        for (Entry e : entries) {
            kw = Math.max(kw, e.key().length());
            vw = Math.max(vw, Math.min(40, shown(e).length()));
        }
        String fmt = "| %-" + kw + "s | %-" + vw + "s | %-15s |%n";
        String rule = "+" + "-".repeat(kw + 2) + "+" + "-".repeat(vw + 2) + "+" + "-".repeat(17) + "+";
        StringBuilder sb = new StringBuilder();
        sb.append("================ EFFECTIVE ENGINE CONFIG ================\n");
        sb.append("precedence: -D > ENGINE_<KEY> env > ").append(filePath)
                .append(fileLoaded ? " (LOADED, " + fileProps.size() + " keys)" : " (not present)")
                .append(" > ").append(classpathOrigin).append(" > code\n");
        sb.append(rule).append('\n');
        sb.append(String.format(fmt, "key", "value", "source"));
        sb.append(rule).append('\n');
        for (Entry e : entries) {
            String v = shown(e);
            if (v.length() > 40) v = v.substring(0, 37) + "...";
            sb.append(String.format(fmt, e.key(), v, e.source().name()));
        }
        sb.append(rule).append('\n');
        for (String line : modeConsequences()) sb.append(line).append('\n');
        for (String unknown : unknownFileKeys()) {
            sb.append("WARN unknown key in ").append(filePath).append(": ").append(unknown)
                    .append(" (not in the EngineConfig registry; carried, read only if a consumer asks for it)\n");
        }
        sb.append("=========================================================");
        return sb.toString();
    }

    /** One line per behavioural consequence of the effective flags. */
    public List<String> modeConsequences() {
        List<String> out = new ArrayList<>();
        boolean stdv = getBoolean("strategy.stdvOte", true);
        boolean fallback = getBoolean("strategy.legacyFallback", false);
        boolean scalp = getBoolean("scalp.enabled", false);
        boolean multi = getBoolean("engine.multiInstrument", true);
        boolean nonSim = getBoolean("topstep.allowNonSimulated", false);
        String gateMode = getString("session.gateMode", "SCORING").trim().toUpperCase(Locale.ROOT);
        out.add("STRATEGY: " + (stdv ? "StdvOteRunnerStrategy" : "IctHighConfluenceStrategy (legacy)")
                + " | non-{MNQ,MES,MGC} symbol -> "
                + (fallback ? "legacy fallback (strategy.legacyFallback=true)"
                            : "FAIL FAST (strategy.legacyFallback=false)"));
        out.add("INSTRUMENTS: " + (multi && stdv
                ? "multi-instrument active=" + getString("stdvote.symbols.active", "MNQ,MGC") + " (MES = SMT feed)"
                : "single-symbol " + getString("engine.symbol", "MNQ")
                        + " (SMT " + getString("engine.smtSymbol", "MES") + ")"));
        out.add("SCALP MODE: " + (scalp
                ? "ON (1R-capped targets, RiskLimits.topstep50kScalp, raid floor "
                        + getInt("raid.minScore.scalp", 6) + ")"
                : "OFF (legacy -2 sigma targets, RiskLimits.topstep50k)"));
        out.add("SESSION GATE: " + ("BLOCKING".equals(gateMode)
                ? "BLOCKING (entries only inside prime killzones)"
                : gateMode + " (entries allowed all sessions except 14:45-17:00 CT)")
                + " [session.gateMode consumer = Agent 02; session.allSessions="
                + getBoolean("session.allSessions", true) + "]");
        out.add("BIAS: vote.mode=" + getString("bias.vote.mode", "LOG")
                + " hysteresis=" + (getBoolean("bias.hysteresis", false) ? "ON" : "OFF")
                + " neutralGraceBars=" + getInt("bias.neutralGraceBars", 2));
        out.add("DISPLACEMENT: atrMult=" + getDouble("displacement.atrMult", 1.2)
                + " bodyPct=" + getDouble("displacement.bodyPct", 0.50)
                + " recentBars=" + getInt("displacement.recentBars", 12)
                + " on " + getInt("detector.timeframe", 5) + "m detector bars");
        out.add("OTE (Agent 04): anchor=" + getString("ote.anchorMode", "DEALING_RANGE")
                + " fibs=" + getDouble("ote.fib62", 0.618) + "/" + getDouble("ote.fib705", 0.705)
                + "/" + getDouble("ote.fib79", 0.786) + " M7b=" + getString("ote30m.mode", "SCORING")
                + " entryModel=" + getString("ote.entryModel", "IMPULSE_LEG")
                + " (minSweepFib " + getDouble("ote.impulseLeg.minSweepFib", 0.705) + ")"
                + " pdArraySource=" + getString("ote.pdArraySource", "ICT_OB")
                + " obLookbackBars=" + getInt("ote.obLookbackBars", 5)
                + " | ONE RR band legacy [" + getDouble("risk.rrFloor", 1.0) + ", " + getDouble("risk.rrCeiling", 5.0)
                + "] scalp [" + getDouble("risk.rrFloor.scalp", 0.8) + ", " + getDouble("risk.rrCeiling", 5.0) + "]");
        out.add("COUNTER-TREND SCALP (Agent 05.8): " + (getBoolean("entry.counterTrendScalp", false)
                ? "ON sessions=" + getString("entry.counterTrend.sessions", "ASIA,LONDON,PRE_NY")
                        + " minRangeTicks=" + getInt("entry.counterTrend.minRangeTicks", 400)
                        + " maxRiskFraction=" + getDouble("entry.counterTrend.maxRiskFraction", 0.5)
                        + " maxPerDay=" + getInt("entry.counterTrend.maxPerDay", 2)
                : "OFF (entry.counterTrendScalp=false)"));
        out.add("LIFECYCLE (Agent 05.3): setup.rearmAfterClose=" + getBoolean("setup.rearmAfterClose", true)
                + " rearmCooldownBars=" + getInt("setup.rearmCooldownBars", 5)
                + " | unfilled entry cancelled when its setup ends (order.ttlBars = backstop)");
        out.add("BACKFILL: 1m " + getInt("backfill.days", 3) + " day(s) [clamped 1..7], HTF "
                + getInt("htf.backfill.days", 30) + " day(s) [clamped 7..90]");
        out.add("TRADE PROFILE: " + getString("trade.profile", "STRICT"));
        out.add("ACCOUNT GUARD: topstep.allowNonSimulated=" + nonSim
                + (nonSim ? " (REAL-MONEY ACCOUNTS ALLOWED)" : " (practice/simulated accounts only)"));
        return out;
    }

    /** JSON-friendly view for GET /api/status ({@code effectiveConfig}). */
    public Map<String, Object> toApiMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configFile", filePath);
        out.put("configFileLoaded", fileLoaded);
        out.put("precedence",
                "SYSTEM_PROPERTY > ENV (ENGINE_<KEY>) > PROPERTIES_FILE > DEFAULT (classpath engine-defaults.properties > code)");
        Map<String, Object> keys = new LinkedHashMap<>();
        for (Entry e : effectiveEntries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("value", e.value() != null ? e.value() : e.codeDefault());
            row.put("source", e.source().name());
            row.put("origin", e.origin());
            row.put("codeDefault", e.codeDefault());
            keys.put(e.key(), row);
        }
        out.put("keys", keys);
        out.put("modes", modeConsequences());
        out.put("unknownFileKeys", unknownFileKeys());
        return out;
    }
}
