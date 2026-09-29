package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.config.EngineConfig;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.session.SessionWindow;
import com.topstep.trading.strategy.stdvote.PdArrayLocator.PdArray;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * V5 Agent 05.8 — the OPT-IN, bounded COUNTER-TREND SCALP entry type
 * ({@code entry.counterTrendScalp}, default {@code false}).
 *
 * <p>Owner's case (LIVE 2026-09-29 05:34 ET, LONDON): MNQ dealing range
 * BULLISH 30371.75 → 30635.00, EQ 30503.375. Price sat in premium
 * (30545–30600): the bias rule refused longs there (correctly) and the model
 * had no way to "short the premium back to equilibrium, then long from the
 * discount OTE band [30428.00, 30472.25]". This entry type is exactly that
 * short, and nothing wider:
 *
 * <ul>
 *   <li><b>Direction</b>: OPPOSITE the range bias, only when the swept level
 *       is in the premium half (short in a BULLISH range) / discount half
 *       (long in a BEARISH range) AND inside that range's OPPOSITE OTE band
 *       — for a short, the 0.618–0.786 retracement measured from the low
 *       ({@code low + 0.618·R .. low + 0.786·R} = 30534.50–30578.75 on the
 *       live range). The sweep extreme must stay in the band and reach
 *       {@code ote.impulseLeg.minSweepFib} (the IMPULSE_LEG rule), and no
 *       close may print beyond the range 1.0.</li>
 *   <li><b>Trigger</b>: the IMPULSE_LEG trigger — a liquidity sweep of a
 *       high (score &ge; the M4 floor), a PD array AT the sweep (the ICT
 *       order block before the raid bar, the 5m sweep-bar OB, the rejection
 *       wick, or an FVG/IFVG/breaker reaching the swept level) overlapping the
 *       band, and the alarm on the REJECTION close (close back below the swept
 *       level, down-close).</li>
 *   <li><b>Target</b>: T1 = the range equilibrium; FINAL = the opposite OTE
 *       band edge nearest price (top of the discount band for a short =
 *       {@code low + 0.382·R}) — never beyond. <b>Stop</b>: beyond the sweep
 *       high (max of the 0.786, the PD array's far edge and the sweep extreme)
 *       plus the normal buffer. <b>RR</b>: the normal band — RR to
 *       equilibrium below the floor is refused with both numbers.</li>
 *   <li><b>Bounds</b>: sessions {@code entry.counterTrend.sessions} (default
 *       ASIA, LONDON, PRE_NY — NO_ENTRY / WEEKEND can never be enabled), a
 *       range of at least {@code entry.counterTrend.minRangeTicks} (400 = 100
 *       MNQ points, so a move to equilibrium is worth &ge; 1R), at most
 *       {@code entry.counterTrend.maxRiskFraction} (0.5) of the normal $ risk
 *       budget through the existing risk-derived sizer, at most
 *       {@code entry.counterTrend.maxPerDay} (2) per CME trading day.</li>
 *   <li><b>One position per symbol</b>: the scalp emits only when the symbol
 *       is flat and no with-trend order is working; while the scalp's order or
 *       position is open a with-trend setup may arm but may not emit. When the
 *       scalp closes (target, stop, release) the machine is back to hunting
 *       the with-trend setup.</li>
 * </ul>
 *
 * <p>This class holds the pure rules (qualify / plan / size / quota) and the
 * scalp's own state; {@link StdvOteRunnerStrategy} feeds it the detectors.
 * With the flag OFF the runner never constructs it — behaviour is
 * byte-identical to the pre-05.8 engine.
 */
public final class CounterTrendScalp {

    /** {@code SetupContext.entryKind} of a counter-trend scalp (M2 branch in the validator). */
    public static final String ENTRY_KIND = "COUNTER_TREND_SCALP";
    /** Every emitted signal's reason starts with this (dashboard / journal tell). */
    public static final String REASON_PREFIX = "STDV_OTE_CT:";
    /** GateDecisionEvent gate name of every counter-trend decision. */
    public static final String GATE = "CT";

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime ROLL = LocalTime.of(18, 0);

    // ══════════════════════════════════════════════════════════════════════
    // Configuration
    // ══════════════════════════════════════════════════════════════════════

    public static final boolean DEFAULT_ENABLED = false;
    public static final String DEFAULT_SESSIONS = "ASIA,LONDON,PRE_NY";
    public static final int DEFAULT_MIN_RANGE_TICKS = 400;
    public static final double DEFAULT_MAX_RISK_FRACTION = 0.5;
    public static final int DEFAULT_MAX_PER_DAY = 2;

    /** The five {@code entry.counterTrend*} keys. */
    public record Config(boolean enabled, Set<SessionWindow> sessions, int minRangeTicks,
                         double maxRiskFraction, int maxPerDay) {

        public Config {
            Set<SessionWindow> s = EnumSet.noneOf(SessionWindow.class);
            if (sessions != null) {
                for (SessionWindow w : sessions) {
                    if (w != null && !w.blocksEntry()) s.add(w);   // NO_ENTRY / WEEKEND: never
                }
            }
            sessions = Collections.unmodifiableSet(s);
            minRangeTicks = Math.max(1, minRangeTicks);
            // Never MORE than the normal budget; a non-positive fraction = no budget.
            maxRiskFraction = Double.isNaN(maxRiskFraction) ? 0.0 : Math.max(0.0, Math.min(1.0, maxRiskFraction));
            maxPerDay = Math.max(0, maxPerDay);
        }

        /** Read the keys through {@link EngineConfig} (call time: the -D layer keeps precedence). */
        public static Config fromEngineConfig() {
            EngineConfig c = EngineConfig.current();
            return new Config(
                    c.getBoolean("entry.counterTrendScalp", DEFAULT_ENABLED),
                    parseSessions(c.getString("entry.counterTrend.sessions", DEFAULT_SESSIONS)),
                    c.getInt("entry.counterTrend.minRangeTicks", DEFAULT_MIN_RANGE_TICKS),
                    c.getDouble("entry.counterTrend.maxRiskFraction", DEFAULT_MAX_RISK_FRACTION),
                    c.getInt("entry.counterTrend.maxPerDay", DEFAULT_MAX_PER_DAY));
        }

        /** Only the flag: the runner builds nothing when it is off. */
        public static boolean enabledInConfig() {
            return EngineConfig.current().getBoolean("entry.counterTrendScalp", DEFAULT_ENABLED);
        }

        /** CSV of {@link SessionWindow} names; unknown names, NO_ENTRY and WEEKEND are dropped. */
        public static Set<SessionWindow> parseSessions(String csv) {
            Set<SessionWindow> out = EnumSet.noneOf(SessionWindow.class);
            if (csv == null) return out;
            for (String raw : csv.split(",")) {
                String t = raw.trim().toUpperCase(Locale.ROOT);
                if (t.isEmpty()) continue;
                try {
                    SessionWindow w = SessionWindow.valueOf(t);
                    if (!w.blocksEntry()) out.add(w);
                } catch (IllegalArgumentException ignored) {
                    System.out.println("[CT] entry.counterTrend.sessions: unknown window '" + raw + "' ignored");
                }
            }
            return out;
        }

        public boolean sessionAllowed(SessionWindow w) {
            return w != null && !w.blocksEntry() && sessions.contains(w);
        }

        public String describe() {
            return "entry.counterTrendScalp=" + enabled + " sessions=" + sessions
                    + " minRangeTicks=" + minRangeTicks + " maxRiskFraction=" + maxRiskFraction
                    + " maxPerDay=" + maxPerDay;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Pure rules
    // ══════════════════════════════════════════════════════════════════════

    /** A decision with its two numbers; {@code zone} is set when the sweep qualifies. */
    public record Verdict(String reason, double a, double b, OteZone zone) {
        public boolean armed() { return zone != null && reason == null; }
    }

    /** The planned scalp. {@code target} is what the signal carries (final, or T1 above the ceiling). */
    public record Plan(boolean tradeBullish, double entry, double stop, double t1, double finalTarget,
                       double target, double rrT1, double rrFinal, PdArray pd) {}

    /**
     * Does this sweep arm a counter-trend scalp? Pure.
     *
     * @param rangeDir      the dealing range's direction (the scalp trades the opposite way)
     * @param bias          the engine's current bias (must agree with the range)
     * @param sweepExtreme  retrace extreme since the sweep (short: highest high incl. the swept level)
     * @param close         close of the current bar (a close beyond the range 1.0 refuses)
     */
    public static Verdict qualify(Config cfg, double tick, double rangeHigh, double rangeLow,
                                  MarketBias rangeDir, boolean rangeDecisive, MarketBias bias,
                                  LiquiditySweep sweep, int score, int floor, double sweepExtreme,
                                  double minSweepFib, SessionWindow window, double close) {
        if (!cfg.sessionAllowed(window)) {
            return new Verdict("CT: session " + window + " not in entry.counterTrend.sessions "
                    + cfg.sessions(), Double.NaN, Double.NaN, null);
        }
        if (!rangeDecisive || rangeDir == null || rangeDir == MarketBias.NEUTRAL
                || !(rangeHigh > rangeLow)) {
            return new Verdict("CT: no decisive dealing range", rangeHigh, rangeLow, null);
        }
        if (bias != rangeDir) {
            return new Verdict("CT: bias " + bias + " disagrees with range " + rangeDir,
                    rangeHigh, rangeLow, null);
        }
        double rangeTicks = Math.round((rangeHigh - rangeLow) / tick * 1e6) / 1e6;
        if (rangeTicks < cfg.minRangeTicks()) {
            return new Verdict("CT: range " + fmt(rangeTicks) + " ticks < entry.counterTrend.minRangeTicks "
                    + cfg.minRangeTicks(), rangeTicks, cfg.minRangeTicks(), null);
        }
        boolean tradeBullish = rangeDir == MarketBias.BEARISH;   // long in a bearish range
        if (sweep == null) {
            return new Verdict("CT: no sweep", Double.NaN, Double.NaN, null);
        }
        if (sweep.isBullish() != tradeBullish) {
            return new Verdict("CT: " + (sweep.isBullish() ? "LOW" : "HIGH") + " sweep does not prime a "
                    + (tradeBullish ? "long" : "short"), sweep.getSweptLevel(), Double.NaN, null);
        }
        Optional<OteZone> zOpt = new OteEntryCalculator().buildZone(rangeLow, rangeHigh, tradeBullish, tick);
        if (zOpt.isEmpty()) {
            return new Verdict("CT: no OTE zone on the range", rangeHigh, rangeLow, null);
        }
        OteZone z = zOpt.get();
        double lo = Math.min(z.f62(), z.f79());
        double hi = Math.max(z.f62(), z.f79());
        double level = sweep.getSweptLevel();
        double eq = (rangeHigh + rangeLow) / 2.0;
        boolean rightHalf = tradeBullish ? level < eq : level > eq;
        if (!rightHalf) {
            return new Verdict("CT: sweep " + level + " not in the " + (tradeBullish ? "discount" : "premium")
                    + " half (eq " + eq + ")", level, eq, null);
        }
        if (level < lo - 1e-9 || level > hi + 1e-9) {
            return new Verdict("CT: sweep " + level + " not in the " + (tradeBullish ? "discount" : "premium")
                    + " OTE band [" + lo + "," + hi + "]", level, tradeBullish ? hi : lo, null);
        }
        if (Double.isNaN(sweepExtreme) || sweepExtreme < lo - 1e-9 || sweepExtreme > hi + 1e-9) {
            return new Verdict("CT: sweep extreme " + sweepExtreme + " outside the OTE band [" + lo + "," + hi + "]",
                    sweepExtreme, tradeBullish ? lo : hi, null);
        }
        double size = rangeHigh - rangeLow;
        double need = tradeBullish ? rangeHigh - minSweepFib * size : rangeLow + minSweepFib * size;
        if (tradeBullish ? sweepExtreme > need + 1e-9 : sweepExtreme < need - 1e-9) {
            return new Verdict("CT: sweep extreme " + sweepExtreme + " short of " + minSweepFib
                    + " (" + roundTick(need, tick) + ")", sweepExtreme, roundTick(need, tick), null);
        }
        if (tradeBullish ? close < z.one00() : close > z.one00()) {
            return new Verdict("CT: close " + close + " beyond the range 1.0 " + z.one00(), close, z.one00(), null);
        }
        // M4's floor, unchanged (checked last so a refusal names the geometry first).
        if (score < floor) {
            return new Verdict("CT: raid score " + score + " < floor " + floor + " (sweep " + level
                    + " in band [" + lo + "," + hi + "])", score, floor, null);
        }
        return new Verdict(null, level, sweepExtreme, z);
    }

    /**
     * Entry / stop / T1 / final for the picked PD array. Pure. Entry = the
     * array's level clamped into the band (OB: mean threshold); stop = beyond
     * max(0.786, array far edge, sweep extreme) + buffer (short); T1 = the
     * range equilibrium; FINAL = the opposite band's near edge (0.382 of the
     * zone = top of the discount band for a short); the signal carries FINAL
     * unless its RR exceeds the ceiling (then T1).
     */
    public static Plan plan(OteZone z, PdArray pd, double sweepExtreme, double tick, int bufferTicks,
                            double rrCeiling) {
        OteEntryCalculator calc = new OteEntryCalculator();
        boolean bullish = z.bullish();
        double entry = roundTick(PdArrayLocator.entryLevel(pd, z), tick);
        double buffer = Math.max(0, bufferTicks) * tick;
        double far = z.f79();
        if (bullish) {
            far = Math.min(far, pd.farEdge());
            if (!Double.isNaN(sweepExtreme)) far = Math.min(far, sweepExtreme);
        } else {
            far = Math.max(far, pd.farEdge());
            if (!Double.isNaN(sweepExtreme)) far = Math.max(far, sweepExtreme);
        }
        double stop = roundTick(bullish ? far - buffer : far + buffer, tick);
        double t1 = calc.fibLevel(z, OteConfig.FIB_50, tick);
        double fin = calc.fibLevel(z, OteConfig.FIB_382, tick);
        double rrT1 = calc.rewardToRisk(entry, stop, t1);
        double rrFinal = calc.rewardToRisk(entry, stop, fin);
        double target = rrFinal <= rrCeiling + 1e-9 ? fin : t1;
        return new Plan(bullish, entry, stop, t1, fin, target, rrT1,
                calc.rewardToRisk(entry, stop, target), pd);
    }

    /** The scalp's $ budget: the normal budget x {@code maxRiskFraction}. Pure. */
    public static double scaledBudget(double normalBudget, double fraction) {
        if (!(normalBudget > 0)) return normalBudget;
        return normalBudget * Math.max(0.0, Math.min(1.0, fraction));
    }

    /** CME trading day of a candle (the 18:00 ET roll starts the next day). */
    public static LocalDate tradingDay(Instant t) {
        ZonedDateTime et = t.atZone(ET);
        return et.toLocalTime().isBefore(ROLL) ? et.toLocalDate() : et.toLocalDate().plusDays(1);
    }

    /**
     * PD arrays AT the sweep (the IMPULSE_LEG candidates): the 5m sweep-bar
     * OB, the ICT order block (newest opposite-close 1m bar before the raid
     * bar), the raid bar's rejection wick, and the direction's FVG / IFVG /
     * BREAKER arrays that REACH the swept level.
     */
    static List<PdArray> candidates(boolean tradeBullish, double level, Candle sweepBar,
                                    List<Candle> sweepPrior, Optional<PdArray> sweepBarOb,
                                    List<PdArray> directionArrays) {
        List<PdArray> out = new ArrayList<>();
        sweepBarOb.ifPresent(out::add);
        for (int i = sweepPrior.size() - 1; i >= 0; i--) {
            Candle c = sweepPrior.get(i);
            boolean opposite = tradeBullish ? c.getClose() < c.getOpen() : c.getClose() > c.getOpen();
            if (!opposite) continue;
            out.add(new PdArray("OB", tradeBullish, c.getLow(), c.getHigh(), c.getTimestamp()));
            break;
        }
        if (sweepBar != null) {
            double range = sweepBar.getHigh() - sweepBar.getLow();
            double bodyTop = Math.max(sweepBar.getOpen(), sweepBar.getClose());
            double bodyBot = Math.min(sweepBar.getOpen(), sweepBar.getClose());
            boolean rejected = tradeBullish ? sweepBar.getClose() > level : sweepBar.getClose() < level;
            double wick = tradeBullish ? bodyBot - sweepBar.getLow() : sweepBar.getHigh() - bodyTop;
            if (rejected && range > 0 && wick / range >= OteSetupDriver.REJECTION_WICK_MIN) {
                out.add(tradeBullish
                        ? new PdArray("WICK", true, sweepBar.getLow(), bodyBot, sweepBar.getTimestamp())
                        : new PdArray("WICK", false, bodyTop, sweepBar.getHigh(), sweepBar.getTimestamp()));
            }
        }
        for (PdArray p : directionArrays) {
            if (p == null || p.bullish() != tradeBullish) continue;
            if (tradeBullish ? p.bottom() <= level : p.top() >= level) out.add(p);
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    // State (one per symbol, candle-thread confined except the latch)
    // ══════════════════════════════════════════════════════════════════════

    public enum Phase { HUNT, ARMED, WORKING, IN_POSITION }

    private final Config config;
    private final double tick;
    private final int obLookbackBars;
    private final double minSweepFib;
    private final SetupContext ctx = new SetupContext();

    private Phase phase = Phase.HUNT;
    /** True from emission until the scalp's order/position is released or closed (read on the bus thread). */
    private volatile boolean ownsPosition;
    private final Map<LocalDate, Integer> emitsPerDay = new HashMap<>();
    private final Deque<Candle> feed = new ArrayDeque<>();
    private static final int FEED_BUFFER = 32;

    // armed episode
    private OteZone zone;
    private LiquiditySweep sweep;
    private int score;
    private double sweepExtreme = Double.NaN;
    private Candle sweepBar;
    private List<Candle> sweepPrior = List.of();
    private Instant armedAt;
    private int barsArmed;
    private boolean alarmed;
    private Plan plan;
    // working order
    private int workingBars;
    private Instant emittedAt;
    // sweep consumption (independent of the with-trend machine's markers)
    private String consumedRaidId;
    private Instant consumedSweepTs;
    private String lastPublished;

    public CounterTrendScalp(String symbol, Config config, double tickSize) {
        this.config = config;
        this.tick = tickSize;
        this.obLookbackBars = OteConfig.obLookbackBars();
        this.minSweepFib = OteConfig.impulseMinSweepFib();
        this.ctx.symbol = symbol;
        this.ctx.entryKind = ENTRY_KIND;
    }

    public Config config() { return config; }
    public Phase phase() { return phase; }
    public SetupContext context() { return ctx; }
    public boolean ownsPosition() { return ownsPosition; }
    OteZone zone() { return zone; }
    LiquiditySweep sweep() { return sweep; }
    int score() { return score; }
    double sweepExtreme() { return sweepExtreme; }
    Candle sweepBar() { return sweepBar; }
    List<Candle> sweepPrior() { return sweepPrior; }
    Instant armedAt() { return armedAt; }
    int barsArmed() { return barsArmed; }
    boolean alarmed() { return alarmed; }
    Plan plan() { return plan; }
    int workingBars() { return workingBars; }
    Instant emittedAt() { return emittedAt; }
    String consumedRaidId() { return consumedRaidId; }
    Instant consumedSweepTs() { return consumedSweepTs; }

    /** Every primary 1m candle (buffer for the raid bar / ICT order block). */
    void onFeedCandle(Candle c) {
        feed.addLast(c);
        while (feed.size() > FEED_BUFFER) feed.removeFirst();
        if (phase == Phase.ARMED) {
            barsArmed++;
            if (zone != null) {
                sweepExtreme = zone.bullish() ? Math.min(sweepExtreme, c.getLow())
                        : Math.max(sweepExtreme, c.getHigh());
            }
        } else if (phase == Phase.WORKING) {
            workingBars++;
        }
    }

    /** Emissions already counted for the trading day of {@code now}. */
    public int emitsOn(Instant now) {
        return emitsPerDay.getOrDefault(tradingDay(now), 0);
    }

    public boolean quotaLeft(Instant now) {
        return emitsOn(now) < config.maxPerDay();
    }

    void markConsumed(String raidId, Instant sweepTs) {
        if (raidId != null) consumedRaidId = raidId;
        if (sweepTs != null) consumedSweepTs = sweepTs;
    }

    /** ARM on a qualifying sweep (replaces an earlier arm). */
    void arm(Verdict v, LiquiditySweep s, int raidScore, Candle current, Instant at) {
        zone = v.zone();
        sweep = s;
        score = raidScore;
        sweepExtreme = v.b();
        armedAt = at;
        barsArmed = 0;
        alarmed = false;
        plan = null;
        // The raid bar: the buffered 1m bar stamped at the sweep, else this bar.
        Candle bar = current;
        if (s.getTimestamp() != null) {
            for (Iterator<Candle> it = feed.descendingIterator(); it.hasNext(); ) {
                Candle c = it.next();
                if (s.getTimestamp().equals(c.getTimestamp())) { bar = c; break; }
            }
        }
        sweepBar = bar;
        List<Candle> prior = new ArrayList<>();
        for (Iterator<Candle> it = feed.descendingIterator(); it.hasNext() && prior.size() < obLookbackBars; ) {
            Candle c = it.next();
            if (bar.getTimestamp() != null && c.getTimestamp() != null
                    && !c.getTimestamp().isBefore(bar.getTimestamp())) continue;
            prior.add(0, c);
        }
        sweepPrior = List.copyOf(prior);
        phase = Phase.ARMED;
        ctx.state = SetupState.OTE_ARMED;
        ctx.sweep = s;
        ctx.raidScore = raidScore;
        ctx.ote = zone;
        ctx.oteArmedAt = at;
        ctx.oteAlarmAt = null;
    }

    void markAlarmed(Plan p, Instant at) {
        alarmed = true;
        plan = p;
        ctx.oteAlarmAt = at;
    }

    void disarm() {
        zone = null;
        sweep = null;
        score = 0;
        sweepExtreme = Double.NaN;
        sweepBar = null;
        sweepPrior = List.of();
        armedAt = null;
        barsArmed = 0;
        alarmed = false;
        plan = null;
        if (phase == Phase.ARMED) phase = Phase.HUNT;
        ctx.state = SetupState.IDLE;
    }

    /** The scalp's signal went out: latch, count, WORKING. */
    void onEmitted(Instant now) {
        emitsPerDay.merge(tradingDay(now), 1, Integer::sum);
        ownsPosition = true;
        workingBars = 0;
        emittedAt = now;
        phase = Phase.WORKING;
        ctx.state = SetupState.IN_TRADE;
    }

    /** The order filled: a live scalp position. */
    void onFilled() {
        if (phase == Phase.WORKING) phase = Phase.IN_POSITION;
    }

    /** Closed / released / cancelled: flat, back to hunting. */
    void onFlat() {
        ownsPosition = false;
        workingBars = 0;
        emittedAt = null;
        zone = null;
        sweep = null;
        sweepBar = null;
        sweepPrior = List.of();
        alarmed = false;
        plan = null;
        phase = Phase.HUNT;
        ctx.state = SetupState.IDLE;
    }

    /** Write a refusal / decision into the scalp's SetupContext; true when it is new (publish it). */
    boolean decide(String reason) {
        ctx.lastGateFailed = reason;
        if (reason == null || reason.equals(lastPublished)) return false;
        lastPublished = reason;
        return true;
    }

    public void reset() {
        onFlat();
        disarm();
        emitsPerDay.clear();
        feed.clear();
        consumedRaidId = null;
        consumedSweepTs = null;
        lastPublished = null;
        ctx.lastGateFailed = null;
    }

    static double roundTick(double p, double tick) {
        return tick > 0 ? Math.round(p / tick) * tick : p;
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
