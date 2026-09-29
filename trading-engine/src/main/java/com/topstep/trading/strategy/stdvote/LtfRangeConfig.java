package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.config.EngineConfig;
import com.topstep.trading.strategy.session.SessionWindow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * V5 Agent 05.9 - the INDEPENDENT LOWER-TIMEFRAME dealing-range machine
 * ({@code range.ltf.*}), OPT-IN, default OFF.
 *
 * <p>With {@code range.ltf.enabled=true} every active symbol runs a SECOND
 * full STDV+OTE setup machine (its own {@link StdvOteStrategy} core, its own
 * {@link SetupContext}, its own {@link DealingRangeTracker}) fed by the same
 * candles. Its dealing range is the most recent confirmed 5m fractal swing leg
 * of at least {@code range.ltf.minLegTicks} ({@link DealingRangeTracker.Window#INTRADAY_SWINGS});
 * its bias is that range's direction ({@link Gating#INDEPENDENT}) or, for the
 * A/B comparison, that direction only when it equals the HTF bias and the entry
 * sits on the HTF discount (long) / premium (short) side ({@link Gating#HTF_ALIGNED}).
 * Every gate M1..M9 runs on the LTF range. One position per symbol: whichever
 * machine emits first holds it.
 *
 * <p>With the flag off the runner never constructs the second machine and the
 * engine is byte-identical to the pre-05.9 runner.
 */
public record LtfRangeConfig(boolean enabled, int minLegTicks, String window, Gating gating,
                             int maxPerDay, double riskFraction, Set<SessionWindow> sessions) {

    /** {@code range.ltf.gating}. */
    public enum Gating { INDEPENDENT, HTF_ALIGNED }

    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_MIN_LEG_TICKS = 120;
    public static final String DEFAULT_WINDOW = "INTRADAY_SWINGS";
    public static final Gating DEFAULT_GATING = Gating.INDEPENDENT;
    public static final int DEFAULT_MAX_PER_DAY = 4;
    public static final double DEFAULT_RISK_FRACTION = 1.0;
    /** Every open session (NO_ENTRY / WEEKEND are never allowed). */
    public static final String DEFAULT_SESSIONS = "ASIA,LONDON,PRE_NY,NY_AM,NY_LUNCH,NY_PM,PRE_ASIA";
    /** Fractal strength of the 5m swings (2 bars each side - FractalSwings' formula). */
    public static final int SWING_STRENGTH = 2;

    /** Signal reason prefix of every LTF emission (and of its GateDecisionEvent reasons). */
    public static final String REASON_PREFIX = "STDV_OTE_LTF:";
    /** {@code SetupContext.machine} values. */
    public static final String MACHINE_HTF = "HTF";
    public static final String MACHINE_LTF = "LTF";

    public LtfRangeConfig {
        minLegTicks = Math.max(1, minLegTicks);
        window = (window == null || window.isBlank()) ? DEFAULT_WINDOW : window.trim().toUpperCase(Locale.ROOT);
        gating = gating == null ? DEFAULT_GATING : gating;
        maxPerDay = Math.max(0, maxPerDay);
        // Never MORE than the normal budget; a non-positive fraction = no budget.
        riskFraction = Double.isNaN(riskFraction) ? 0.0 : Math.max(0.0, Math.min(1.0, riskFraction));
        Set<SessionWindow> s = EnumSet.noneOf(SessionWindow.class);
        if (sessions != null) {
            for (SessionWindow w : sessions) if (w != null && !w.blocksEntry()) s.add(w);
        }
        sessions = Collections.unmodifiableSet(s);
    }

    /** Only the flag: the runner builds nothing when it is off. */
    public static boolean enabledInConfig() {
        return EngineConfig.current().getBoolean("range.ltf.enabled", DEFAULT_ENABLED);
    }

    /** Read the keys through {@link EngineConfig} for one symbol (per-symbol minLegTicks override). */
    public static LtfRangeConfig fromEngineConfig(String symbol) {
        EngineConfig c = EngineConfig.current();
        int base = c.getInt("range.ltf.minLegTicks", DEFAULT_MIN_LEG_TICKS);
        int leg = symbol == null ? base : c.getInt("range.ltf.minLegTicks." + symbol, base);
        return new LtfRangeConfig(
                c.getBoolean("range.ltf.enabled", DEFAULT_ENABLED),
                leg,
                c.getString("range.ltf.window", DEFAULT_WINDOW),
                parseGating(c.getString("range.ltf.gating", DEFAULT_GATING.name())),
                c.getInt("range.ltf.maxPerDay", DEFAULT_MAX_PER_DAY),
                c.getDouble("range.ltf.riskFraction", DEFAULT_RISK_FRACTION),
                CounterTrendScalp.Config.parseSessions(c.getString("range.ltf.sessions", DEFAULT_SESSIONS)));
    }

    static Gating parseGating(String raw) {
        if (raw == null) return DEFAULT_GATING;
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if ("HTF_ALIGNED".equals(t) || "ALIGNED".equals(t)) return Gating.HTF_ALIGNED;
        if (!"INDEPENDENT".equals(t) && !t.isEmpty()) {
            System.out.println("[LTF] range.ltf.gating: unknown value '" + raw + "' -> INDEPENDENT");
        }
        return Gating.INDEPENDENT;
    }

    public boolean sessionAllowed(SessionWindow w) {
        return w != null && !w.blocksEntry() && sessions.contains(w);
    }

    public String describe() {
        return "range.ltf.enabled=" + enabled + " window=" + window + " minLegTicks=" + minLegTicks
                + " gating=" + gating + " maxPerDay=" + maxPerDay + " riskFraction=" + riskFraction
                + " sessions=" + sessions;
    }

    /** Emissions per CME trading day (18:00 ET roll) - the {@code range.ltf.maxPerDay} counter. */
    public static final class DailyQuota {
        private final Map<LocalDate, Integer> perDay = new HashMap<>();

        public int emitsOn(Instant now) {
            return now == null ? 0 : perDay.getOrDefault(CounterTrendScalp.tradingDay(now), 0);
        }

        public boolean left(Instant now, int maxPerDay) {
            return emitsOn(now) < maxPerDay;
        }

        public void record(Instant now) {
            if (now != null) perDay.merge(CounterTrendScalp.tradingDay(now), 1, Integer::sum);
        }

        public void reset() {
            perDay.clear();
        }
    }
}
