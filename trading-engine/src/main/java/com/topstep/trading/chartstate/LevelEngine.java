package com.topstep.trading.chartstate;

import com.topstep.trading.domain.Candle;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes and manages liquidity levels for a single instrument.
 *
 * This engine tracks:
 * - PDH/PDL (Previous Day High/Low) - updated at session boundaries
 * - PWH/PWL (Previous Week High/Low) - updated weekly
 * - Session extremes (Asia, London, NY highs/lows)
 * - Opening prices (daily, weekly, session)
 *
 * Levels are automatically computed from candle history and updated
 * at appropriate session boundaries. The engine exposes levels through
 * a simple query API for raid detection.
 *
 * Thread-safe: All operations synchronized on instance.
 */
public class LevelEngine {

    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");

    // ── Session windows (ET) — V5 Agent 03 (RC-08): configurable, read at
    // construction via BiasConfig (levels.<session>.start/end, HH:mm), and
    // DEFAULTED to reproduce the owner's LuxAlgo levels on the real tape:
    //   ASIA   20:00–00:00  (LuxAlgo default Asia)
    //   LONDON 04:00–06:00  (the window whose high is the owner's 30640.00
    //                        on 2026-09-28 — LuxAlgo's 01:00–03:00 London
    //                        input evaluated in the owner's UTC-7 chart
    //                        timezone = 04:00–06:00 ET; the classic
    //                        02:00–05:00 gives 30679.00, see A-03 DECISIONS)
    //   NY_AM  09:30–12:00  → NY_AM_HIGH/LOW (09-28: 30759.25 / 30356.75)
    //   NY_PM  13:30–16:00  → NY_PM_HIGH/LOW
    //   NY     09:30–16:00  → NY_HIGH/LOW (the old inNY started at 09:00)
    // A window whose end <= start wraps midnight (fixes the dead
    // "hour < 0" Asia test).
    static final LocalTime DEFAULT_ASIA_START = LocalTime.of(20, 0);
    static final LocalTime DEFAULT_ASIA_END = LocalTime.of(0, 0);
    static final LocalTime DEFAULT_LONDON_START = LocalTime.of(4, 0);
    static final LocalTime DEFAULT_LONDON_END = LocalTime.of(6, 0);
    static final LocalTime DEFAULT_NYAM_START = LocalTime.of(9, 30);
    static final LocalTime DEFAULT_NYAM_END = LocalTime.of(12, 0);
    static final LocalTime DEFAULT_NYPM_START = LocalTime.of(13, 30);
    static final LocalTime DEFAULT_NYPM_END = LocalTime.of(16, 0);
    static final LocalTime DEFAULT_NY_START = LocalTime.of(9, 30);
    static final LocalTime DEFAULT_NY_END = LocalTime.of(16, 0);
    /** CME Globex halt 17:00–18:00 ET; the trading day rolls at 18:00. */
    private static final LocalTime HALT_START = LocalTime.of(17, 0);
    private static final LocalTime DAY_ROLL = LocalTime.of(18, 0);

    private final LocalTime asiaStart = com.topstep.trading.strategy.stdvote.BiasConfig
            .levelWindow("asia", "start", DEFAULT_ASIA_START);
    private final LocalTime asiaEnd = com.topstep.trading.strategy.stdvote.BiasConfig
            .levelWindow("asia", "end", DEFAULT_ASIA_END);
    private final LocalTime londonStart = com.topstep.trading.strategy.stdvote.BiasConfig
            .levelWindow("london", "start", DEFAULT_LONDON_START);
    private final LocalTime londonEnd = com.topstep.trading.strategy.stdvote.BiasConfig
            .levelWindow("london", "end", DEFAULT_LONDON_END);
    private final LocalTime nyAmStart = com.topstep.trading.strategy.stdvote.BiasConfig
            .levelWindow("nyam", "start", DEFAULT_NYAM_START);
    private final LocalTime nyAmEnd = com.topstep.trading.strategy.stdvote.BiasConfig
            .levelWindow("nyam", "end", DEFAULT_NYAM_END);
    private final LocalTime nyPmStart = com.topstep.trading.strategy.stdvote.BiasConfig
            .levelWindow("nypm", "start", DEFAULT_NYPM_START);
    private final LocalTime nyPmEnd = com.topstep.trading.strategy.stdvote.BiasConfig
            .levelWindow("nypm", "end", DEFAULT_NYPM_END);
    private final LocalTime nyStart = com.topstep.trading.strategy.stdvote.BiasConfig
            .levelWindow("ny", "start", DEFAULT_NY_START);
    private final LocalTime nyEnd = com.topstep.trading.strategy.stdvote.BiasConfig
            .levelWindow("ny", "end", DEFAULT_NY_END);
    /** Phantom-day guard (levels.minBarsPerDay, default 60). */
    private final int minBarsPerDay =
            com.topstep.trading.strategy.stdvote.BiasConfig.levelsMinBarsPerDay();
    /** Re-arm distance for raided levels (levels.rearmDistanceTicks). */
    private final int rearmDistanceTicks =
            com.topstep.trading.strategy.stdvote.BiasConfig.levelsRearmDistanceTicks();

    /** True when {@code t} is inside [start, end); end <= start wraps midnight. */
    static boolean inWindow(LocalTime t, LocalTime start, LocalTime end) {
        if (start.equals(end)) return false;
        if (start.isBefore(end)) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        return !t.isBefore(start) || t.isBefore(end);
    }

    private final String symbol;
    private final CandleSeries candleSeries;
    private final InstrumentRaidConfig config;

    // ═══════════════════════════════════════════════════════════════════
    // LEVEL STORAGE
    // ═══════════════════════════════════════════════════════════════════

    private final Map<LevelType, KnownLevel> levels = new ConcurrentHashMap<>();

    // Zone flip listeners (FIX 3: demand/supply zone flip detection)
    private final List<ZoneFlipListener> zoneFlipListeners = new ArrayList<>();

    // Daily tracking
    private LocalDate currentTradingDay;
    private double todayHigh = Double.MIN_VALUE;
    private double todayLow = Double.MAX_VALUE;
    private double todayOpen = 0;
    /** Bars folded into the current trading day (phantom-day guard). */
    private int todayBars = 0;

    // True-day-open tracking (midnight ET calendar date last stamped).
    private LocalDate currentMidnightDate;

    // Weekly tracking
    private LocalDate currentWeekStart;
    private double weekHigh = Double.MIN_VALUE;
    private double weekLow = Double.MAX_VALUE;
    private double weekOpen = 0;

    // Session tracking
    private double asiaHigh = Double.MIN_VALUE;
    private double asiaLow = Double.MAX_VALUE;
    private double londonHigh = Double.MIN_VALUE;
    private double londonLow = Double.MAX_VALUE;
    private double nyHigh = Double.MIN_VALUE;
    private double nyLow = Double.MAX_VALUE;
    private double nyAmHigh = Double.MIN_VALUE;
    private double nyAmLow = Double.MAX_VALUE;
    private double nyPmHigh = Double.MIN_VALUE;
    private double nyPmLow = Double.MAX_VALUE;

    // Session opens
    private double asiaOpen = 0;
    private double londonOpen = 0;
    private double nyOpen = 0;

    // Session state tracking
    private boolean inAsia = false;
    private boolean inLondon = false;
    private boolean inNY = false;
    private boolean inNyAm = false;
    private boolean inNyPm = false;

    public LevelEngine(String symbol, CandleSeries candleSeries) {
        this.symbol = symbol;
        this.candleSeries = candleSeries;
        this.config = InstrumentRaidConfig.forSymbol(symbol);
    }

    // ═══════════════════════════════════════════════════════════════════
    // CANDLE PROCESSING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Process a new candle to update levels.
     * Called each time a new candle closes.
     */
    public synchronized void processCandle(Candle candle) {
        if (candle == null) return;

        Instant timestamp = candle.getTimestamp();
        ZonedDateTime nyTime = timestamp.atZone(NY_ZONE);
        // V5 Agent 03 (RC-08): a bar inside the 17:00–18:00 ET Globex halt
        // is a settlement print (2026-09-25 17:00: one 1-lot bar) — it is
        // never part of any trading day, session or level.
        LocalTime clock = nyTime.toLocalTime();
        if (!clock.isBefore(HALT_START) && clock.isBefore(DAY_ROLL)) {
            return;
        }
        LocalDate tradingDay = getTradingDay(nyTime);
        LocalDate weekStart = getWeekStart(tradingDay);

        // Check for day change
        if (currentTradingDay == null || !tradingDay.equals(currentTradingDay)) {
            onDayChange(tradingDay, candle);
        }

        // Check for week change
        if (currentWeekStart == null || !weekStart.equals(currentWeekStart)) {
            onWeekChange(weekStart, candle);
        }

        // True day open (midnight ET): the first candle of each ET calendar
        // date stamps MIDNIGHT_OPEN — §H1's "price vs true day open" vote
        // reads this, NOT the 18:00-session DAILY_OPEN (V3 Agent 03).
        LocalDate midnightDate = nyTime.toLocalDate();
        if (currentMidnightDate == null || !midnightDate.equals(currentMidnightDate)) {
            currentMidnightDate = midnightDate;
            levels.put(LevelType.MIDNIGHT_OPEN,
                    new KnownLevel(LevelType.MIDNIGHT_OPEN, candle.getOpen(), timestamp));
        }

        // Update session tracking
        updateSessionTracking(nyTime, candle);

        // V5 Agent 03: raided levels re-arm once price has LEFT them.
        rearmRaidedLevels(candle);

        // Update daily extremes
        todayBars++;
        if (candle.getHigh() > todayHigh) {
            todayHigh = candle.getHigh();
        }
        if (candle.getLow() < todayLow) {
            todayLow = candle.getLow();
        }

        // Update weekly extremes
        if (candle.getHigh() > weekHigh) {
            weekHigh = candle.getHigh();
        }
        if (candle.getLow() < weekLow) {
            weekLow = candle.getLow();
        }
    }

    /**
     * Handle day change - lock previous day levels.
     */
    private void onDayChange(LocalDate newTradingDay, Candle candle) {
        // Store previous day as PDH/PDL if we have data — and only if it was
        // a REAL trading day (V5 Agent 03, RC-08): a "day" of fewer than
        // levels.minBarsPerDay bars is a phantom (settlement print / feed
        // stub) and must never overwrite PDH/PDL.
        if (currentTradingDay != null && todayHigh != Double.MIN_VALUE) {
            if (todayBars >= minBarsPerDay) {
                registerLevel(LevelType.PDH, todayHigh, candle.getTimestamp());
                registerLevel(LevelType.PDL, todayLow, candle.getTimestamp());
                registerLevel(LevelType.DAILY_OPEN, todayOpen, candle.getTimestamp());
            } else {
                System.out.println("[LEVELS " + symbol + "] phantom trading day "
                        + currentTradingDay + " (" + todayBars + " bars < "
                        + minBarsPerDay + ") ignored for PDH/PDL");
            }
        }

        // Reset for new day
        currentTradingDay = newTradingDay;
        todayHigh = candle.getHigh();
        todayLow = candle.getLow();
        todayOpen = candle.getOpen();
        todayBars = 0;

        // Reset session tracking
        asiaHigh = Double.MIN_VALUE;
        asiaLow = Double.MAX_VALUE;
        londonHigh = Double.MIN_VALUE;
        londonLow = Double.MAX_VALUE;
        nyHigh = Double.MIN_VALUE;
        nyLow = Double.MAX_VALUE;
        nyAmHigh = Double.MIN_VALUE;
        nyAmLow = Double.MAX_VALUE;
        nyPmHigh = Double.MIN_VALUE;
        nyPmLow = Double.MAX_VALUE;
        asiaOpen = 0;
        londonOpen = 0;
        nyOpen = 0;
    }

    /**
     * Handle week change - lock previous week levels.
     */
    private void onWeekChange(LocalDate newWeekStart, Candle candle) {
        // Store previous week as PWH/PWL if we have data
        if (currentWeekStart != null && weekHigh != Double.MIN_VALUE) {
            registerLevel(LevelType.PWH, weekHigh, candle.getTimestamp());
            registerLevel(LevelType.PWL, weekLow, candle.getTimestamp());
            registerLevel(LevelType.WEEKLY_OPEN, weekOpen, candle.getTimestamp());
        }

        // Reset for new week
        currentWeekStart = newWeekStart;
        weekHigh = candle.getHigh();
        weekLow = candle.getLow();
        weekOpen = candle.getOpen();
    }

    /**
     * Update session-specific levels.
     */
    private void updateSessionTracking(ZonedDateTime nyTime, Candle candle) {
        LocalTime time = nyTime.toLocalTime();

        // Determine current session
        boolean wasInAsia = inAsia;
        boolean wasInLondon = inLondon;
        boolean wasInNY = inNY;
        boolean wasInNyAm = inNyAm;
        boolean wasInNyPm = inNyPm;

        // V5 Agent 03: configurable windows (see the constants above).
        inAsia = inWindow(time, asiaStart, asiaEnd);
        inLondon = inWindow(time, londonStart, londonEnd);
        inNY = inWindow(time, nyStart, nyEnd);
        inNyAm = inWindow(time, nyAmStart, nyAmEnd);
        inNyPm = inWindow(time, nyPmStart, nyPmEnd);

        // NY AM / NY PM sub-session extremes lock at their window close.
        if (!inNyAm && wasInNyAm && nyAmHigh != Double.MIN_VALUE) {
            registerLevel(LevelType.NY_AM_HIGH, nyAmHigh, candle.getTimestamp());
            registerLevel(LevelType.NY_AM_LOW, nyAmLow, candle.getTimestamp());
        }
        if (!inNyPm && wasInNyPm && nyPmHigh != Double.MIN_VALUE) {
            registerLevel(LevelType.NY_PM_HIGH, nyPmHigh, candle.getTimestamp());
            registerLevel(LevelType.NY_PM_LOW, nyPmLow, candle.getTimestamp());
        }
        if (inNyAm) {
            if (candle.getHigh() > nyAmHigh) nyAmHigh = candle.getHigh();
            if (candle.getLow() < nyAmLow) nyAmLow = candle.getLow();
        }
        if (inNyPm) {
            if (candle.getHigh() > nyPmHigh) nyPmHigh = candle.getHigh();
            if (candle.getLow() < nyPmLow) nyPmLow = candle.getLow();
        }

        // Handle session transitions
        if (inAsia && !wasInAsia) {
            // Starting Asia - record open
            asiaOpen = candle.getOpen();
            registerLevel(LevelType.ASIA_OPEN, asiaOpen, candle.getTimestamp());
        } else if (!inAsia && wasInAsia && asiaHigh != Double.MIN_VALUE) {
            // Ending Asia - lock levels
            registerLevel(LevelType.ASIA_HIGH, asiaHigh, candle.getTimestamp());
            registerLevel(LevelType.ASIA_LOW, asiaLow, candle.getTimestamp());
        }

        if (inLondon && !wasInLondon) {
            // Starting London - record open
            londonOpen = candle.getOpen();
            registerLevel(LevelType.LONDON_OPEN, londonOpen, candle.getTimestamp());
        } else if (!inLondon && wasInLondon && londonHigh != Double.MIN_VALUE) {
            // Ending London - lock levels
            registerLevel(LevelType.LONDON_HIGH, londonHigh, candle.getTimestamp());
            registerLevel(LevelType.LONDON_LOW, londonLow, candle.getTimestamp());
        }

        if (inNY && !wasInNY) {
            // Starting NY - record open
            nyOpen = candle.getOpen();
            registerLevel(LevelType.NY_OPEN, nyOpen, candle.getTimestamp());
        } else if (!inNY && wasInNY && nyHigh != Double.MIN_VALUE) {
            // Ending NY - lock levels
            registerLevel(LevelType.NY_HIGH, nyHigh, candle.getTimestamp());
            registerLevel(LevelType.NY_LOW, nyLow, candle.getTimestamp());
        }

        // Update session extremes
        if (inAsia) {
            if (candle.getHigh() > asiaHigh) asiaHigh = candle.getHigh();
            if (candle.getLow() < asiaLow) asiaLow = candle.getLow();
        }
        if (inLondon) {
            if (candle.getHigh() > londonHigh) londonHigh = candle.getHigh();
            if (candle.getLow() < londonLow) londonLow = candle.getLow();
        }
        if (inNY) {
            if (candle.getHigh() > nyHigh) nyHigh = candle.getHigh();
            if (candle.getLow() < nyLow) nyLow = candle.getLow();
        }
    }

    /**
     * Register or update a known level.
     */
    private void registerLevel(LevelType type, double price, Instant timestamp) {
        if (price <= 0 || price == Double.MIN_VALUE || price == Double.MAX_VALUE) {
            return;
        }
        // V5 Agent 03: every registration is a NEW session/day level and
        // replaces the old object. The pre-V5 "keep the existing one when
        // within tolerance" rule kept yesterday's RAIDED object alive when
        // today's extreme printed within a tick of it — a fresh level that
        // could never be raided.
        levels.put(type, new KnownLevel(type, price, timestamp));
    }

    /**
     * V5 Agent 03 (RC-07/RC-08): a raided HIGH level re-arms once a whole
     * candle trades at least {@code levels.rearmDistanceTicks} BELOW it
     * (price has left the level; fresh buy stops rest above it again) —
     * mirrored for LOW levels. This is how the 2026-09-28 London high
     * 30640.00, first traded at 07:00, is again the liquidity the 14:52
     * retrace raids after the 30356.75 low. Opening prices never re-arm.
     */
    private void rearmRaidedLevels(Candle candle) {
        double dist = rearmDistanceTicks * config.getTickSize();
        if (dist <= 0) return;
        for (KnownLevel level : levels.values()) {
            if (!level.isRaided()) continue;
            if (level.getType().name().contains("OPEN")) continue;
            boolean left = level.getType().isHigh()
                    ? candle.getHigh() < level.getPrice() - dist
                    : candle.getLow() > level.getPrice() + dist;
            if (left) {
                level.rearm();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get a specific level by type.
     */
    public synchronized Optional<KnownLevel> getLevel(LevelType type) {
        return Optional.ofNullable(levels.get(type));
    }

    /**
     * Get all active levels.
     */
    public synchronized List<KnownLevel> getAllLevels() {
        return new ArrayList<>(levels.values());
    }

    /**
     * Get levels that haven't been raided.
     */
    public synchronized List<KnownLevel> getUnraidedLevels() {
        List<KnownLevel> unraided = new ArrayList<>();
        for (KnownLevel level : levels.values()) {
            if (!level.isRaided()) {
                unraided.add(level);
            }
        }
        return unraided;
    }

    /**
     * Get levels near a price (for raid detection).
     * Returns levels within tolerance distance.
     */
    public synchronized List<KnownLevel> getLevelsNearPrice(double price) {
        List<KnownLevel> nearby = new ArrayList<>();
        double tolerance = config.getTolerancePrice();

        for (KnownLevel level : levels.values()) {
            if (!level.isRaided() && Math.abs(level.getPrice() - price) <= tolerance) {
                nearby.add(level);
            }
        }

        return nearby;
    }

    /**
     * Get the nearest level above a price.
     */
    public synchronized Optional<KnownLevel> getNearestLevelAbove(double price) {
        KnownLevel nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (KnownLevel level : levels.values()) {
            if (!level.isRaided() && level.getPrice() > price) {
                double dist = level.getPrice() - price;
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = level;
                }
            }
        }

        return Optional.ofNullable(nearest);
    }

    /**
     * Get the nearest level below a price.
     */
    public synchronized Optional<KnownLevel> getNearestLevelBelow(double price) {
        KnownLevel nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (KnownLevel level : levels.values()) {
            if (!level.isRaided() && level.getPrice() < price) {
                double dist = price - level.getPrice();
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = level;
                }
            }
        }

        return Optional.ofNullable(nearest);
    }

    /**
     * Get PDH level if available.
     */
    public synchronized Optional<Double> getPDH() {
        return getLevel(LevelType.PDH).map(KnownLevel::getPrice);
    }

    /**
     * Get PDL level if available.
     */
    public synchronized Optional<Double> getPDL() {
        return getLevel(LevelType.PDL).map(KnownLevel::getPrice);
    }

    /**
     * The CURRENT trading day's developing high (since the session
     * rollover), or empty before the first candle of the day. Used by the
     * M2b premium/discount R2 range (breakout days) — read-only telemetry
     * of state this engine already tracks.
     */
    public synchronized Optional<Double> getDevelopingDayHigh() {
        return (todayHigh == Double.MIN_VALUE) ? Optional.empty() : Optional.of(todayHigh);
    }

    /** The CURRENT trading day's developing low; empty before the first candle. */
    public synchronized Optional<Double> getDevelopingDayLow() {
        return (todayLow == Double.MAX_VALUE) ? Optional.empty() : Optional.of(todayLow);
    }

    /**
     * Get PWH level if available.
     */
    public synchronized Optional<Double> getPWH() {
        return getLevel(LevelType.PWH).map(KnownLevel::getPrice);
    }

    /**
     * Get PWL level if available.
     */
    public synchronized Optional<Double> getPWL() {
        return getLevel(LevelType.PWL).map(KnownLevel::getPrice);
    }

    /**
     * Check if price has swept a level (for external verification).
     */
    public synchronized boolean hasPriceSweptLevel(double high, double low, KnownLevel level) {
        double levelPrice = level.getPrice();
        double minPenetration = config.getMinPenetrationPrice();

        if (level.getType().isHigh()) {
            // For high levels, check if high exceeded level by min penetration
            return high > levelPrice + minPenetration;
        } else {
            // For low levels, check if low went below level by min penetration
            return low < levelPrice - minPenetration;
        }
    }

    /**
     * Mark a level as raided.
     */
    public synchronized void markLevelRaided(LevelType type, Instant timestamp) {
        KnownLevel level = levels.get(type);
        if (level != null) {
            level.markRaided(timestamp);
        }
    }

    /**
     * Add or update an equal level (from EqualLevelDetector).
     */
    public synchronized void addEqualLevel(LevelType type, double price, int clusterSize, Instant timestamp) {
        levels.put(type, new KnownLevel(type, price, timestamp, clusterSize));
    }

    /**
     * Add or update an equal level, tagged with its origin (V4 Agent 03).
     *
     * <p>Used by {@code IctLibLevelAdapter} to publish §S6 liquidity pools into
     * the ONE level universe the raid pipeline already reads, so a clustered
     * pool the Bot Chart draws is the same object a raid can fire on. The level
     * is otherwise indistinguishable from a natively computed equal level —
     * the tag is descriptive, not behavioural.
     */
    public synchronized void addEqualLevel(LevelType type, double price, int clusterSize,
                                           Instant timestamp, String source) {
        levels.put(type, new KnownLevel(type, price, timestamp, clusterSize, source));
    }

    // ═══════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get trading day from NY time.
     * Trading day changes at 5 PM NY time (RTH close).
     */
    private LocalDate getTradingDay(ZonedDateTime nyTime) {
        // V5 Agent 03 (RC-08): the CME Globex day rolls at 18:00 ET (the
        // reopen), not 17:00 — the 17:00 roll made the lone Friday 17:00
        // settlement print a whole "trading day" that became PDH/PDL.
        if (nyTime.getHour() < 18) {
            return nyTime.toLocalDate();
        }
        return nyTime.toLocalDate().plusDays(1);
    }

    /**
     * Get week start (Monday) from a date.
     */
    private LocalDate getWeekStart(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1);
    }

    public String getSymbol() {
        return symbol;
    }

    public LocalDate getCurrentTradingDay() {
        return currentTradingDay;
    }

    public boolean isInAsia() {
        return inAsia;
    }

    public boolean isInLondon() {
        return inLondon;
    }

    public boolean isInNY() {
        return inNY;
    }

    /**
     * Get summary of current levels for logging.
     */
    public synchronized String getLevelsSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Levels for ").append(symbol).append(":\n");

        // Daily levels
        getLevel(LevelType.PDH).ifPresent(l -> sb.append("  PDH: ").append(l.getPrice()).append("\n"));
        getLevel(LevelType.PDL).ifPresent(l -> sb.append("  PDL: ").append(l.getPrice()).append("\n"));

        // Weekly levels
        getLevel(LevelType.PWH).ifPresent(l -> sb.append("  PWH: ").append(l.getPrice()).append("\n"));
        getLevel(LevelType.PWL).ifPresent(l -> sb.append("  PWL: ").append(l.getPrice()).append("\n"));

        // Session levels
        getLevel(LevelType.ASIA_HIGH).ifPresent(l -> sb.append("  Asia High: ").append(l.getPrice()).append("\n"));
        getLevel(LevelType.ASIA_LOW).ifPresent(l -> sb.append("  Asia Low: ").append(l.getPrice()).append("\n"));
        getLevel(LevelType.LONDON_HIGH).ifPresent(l -> sb.append("  London High: ").append(l.getPrice()).append("\n"));
        getLevel(LevelType.LONDON_LOW).ifPresent(l -> sb.append("  London Low: ").append(l.getPrice()).append("\n"));

        // Equal levels
        getLevel(LevelType.EQUAL_HIGH).ifPresent(l ->
                sb.append("  Equal High: ").append(l.getPrice())
                        .append(" (cluster=").append(l.getClusterSize()).append(")\n"));
        getLevel(LevelType.EQUAL_LOW).ifPresent(l ->
                sb.append("  Equal Low: ").append(l.getPrice())
                        .append(" (cluster=").append(l.getClusterSize()).append(")\n"));

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════
    // ZONE FLIP DETECTION (FIX 3)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Add a listener for zone flip events.
     */
    public void addZoneFlipListener(ZoneFlipListener listener) {
        zoneFlipListeners.add(listener);
    }

    /**
     * Detect zone flips — when a demand level gets broken by displacement,
     * it flips to a supply (bearish breaker), and vice versa.
     */
    public synchronized void detectZoneFlips(Candle candle) {
        double atrBaseline = candleSeries.getAverageRange(20);

        // Check bullish (low) levels for bearish flip
        for (KnownLevel level : new ArrayList<>(levels.values())) {
            if (level.isFlipped()) continue;

            if (level.getType().isLowLevel() && !level.getType().name().contains("OPEN")) {
                boolean brokenByDisplacement =
                        candle.getClose() < level.getPrice() - config.getTolerancePrice()
                        && (candle.getHigh() - candle.getLow()) > atrBaseline * 1.5;

                if (brokenByDisplacement) {
                    level.setFlipped(true);
                    level.setFlipTimestamp(candle.getTimestamp());
                    for (ZoneFlipListener l : zoneFlipListeners) {
                        l.onZoneFlip(level, false); // false = now bearish
                    }
                }
            }

            if (level.getType().isHighLevel() && !level.getType().name().contains("OPEN")) {
                boolean brokenByDisplacement =
                        candle.getClose() > level.getPrice() + config.getTolerancePrice()
                        && (candle.getHigh() - candle.getLow()) > atrBaseline * 1.5;

                if (brokenByDisplacement) {
                    level.setFlipped(true);
                    level.setFlipTimestamp(candle.getTimestamp());
                    for (ZoneFlipListener l : zoneFlipListeners) {
                        l.onZoneFlip(level, true); // true = now bullish
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ENHANCED QUERY API (FIX 12)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get the nearest unraided high-type level above a price.
     */
    public synchronized Optional<KnownLevel> getNearestUnraidedLevelAbove(double price) {
        return levels.values().stream()
                .filter(l -> l.getType().isHighLevel())
                .filter(l -> !l.isRaided())
                .filter(l -> l.getPrice() > price)
                .min(java.util.Comparator.comparingDouble(l -> l.getPrice() - price));
    }

    /**
     * Get the nearest unraided low-type level below a price.
     */
    public synchronized Optional<KnownLevel> getNearestUnraidedLevelBelow(double price) {
        return levels.values().stream()
                .filter(l -> l.getType().isLowLevel())
                .filter(l -> !l.isRaided())
                .filter(l -> l.getPrice() < price)
                .min(java.util.Comparator.comparingDouble(l -> price - l.getPrice()));
    }

    /**
     * Get average candle range from the candle series.
     */
    public double getAverageRange(int lookback) {
        return candleSeries.getAverageRange(lookback);
    }

    @Override
    public String toString() {
        return String.format("LevelEngine{%s: %d levels, day=%s}",
                symbol, levels.size(), currentTradingDay);
    }
}
