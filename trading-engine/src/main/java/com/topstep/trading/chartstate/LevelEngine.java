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

    // Session time boundaries (in NY time)
    private static final LocalTime ASIA_START = LocalTime.of(20, 0);    // 8 PM NY (previous day)
    private static final LocalTime ASIA_END = LocalTime.of(0, 0);       // Midnight
    private static final LocalTime LONDON_START = LocalTime.of(2, 0);   // 2 AM
    private static final LocalTime LONDON_END = LocalTime.of(5, 0);     // 5 AM
    private static final LocalTime NY_START = LocalTime.of(9, 30);      // 9:30 AM
    private static final LocalTime NY_END = LocalTime.of(16, 0);        // 4 PM
    private static final LocalTime RTH_CLOSE = LocalTime.of(17, 0);     // 5 PM (new day boundary)

    private final String symbol;
    private final CandleSeries candleSeries;
    private final InstrumentRaidConfig config;

    // ═══════════════════════════════════════════════════════════════════
    // LEVEL STORAGE
    // ═══════════════════════════════════════════════════════════════════

    private final Map<LevelType, KnownLevel> levels = new ConcurrentHashMap<>();

    // Daily tracking
    private LocalDate currentTradingDay;
    private double todayHigh = Double.MIN_VALUE;
    private double todayLow = Double.MAX_VALUE;
    private double todayOpen = 0;

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

    // Session opens
    private double asiaOpen = 0;
    private double londonOpen = 0;
    private double nyOpen = 0;

    // Session state tracking
    private boolean inAsia = false;
    private boolean inLondon = false;
    private boolean inNY = false;

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

        // Update session tracking
        updateSessionTracking(nyTime, candle);

        // Update daily extremes
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
        // Store previous day as PDH/PDL if we have data
        if (currentTradingDay != null && todayHigh != Double.MIN_VALUE) {
            registerLevel(LevelType.PDH, todayHigh, candle.getTimestamp());
            registerLevel(LevelType.PDL, todayLow, candle.getTimestamp());
            registerLevel(LevelType.DAILY_OPEN, todayOpen, candle.getTimestamp());
        }

        // Reset for new day
        currentTradingDay = newTradingDay;
        todayHigh = candle.getHigh();
        todayLow = candle.getLow();
        todayOpen = candle.getOpen();

        // Reset session tracking
        asiaHigh = Double.MIN_VALUE;
        asiaLow = Double.MAX_VALUE;
        londonHigh = Double.MIN_VALUE;
        londonLow = Double.MAX_VALUE;
        nyHigh = Double.MIN_VALUE;
        nyLow = Double.MAX_VALUE;
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
        int hour = time.getHour();

        // Determine current session
        boolean wasInAsia = inAsia;
        boolean wasInLondon = inLondon;
        boolean wasInNY = inNY;

        // Asia: 8 PM - 12 AM NY time
        inAsia = (hour >= 20 || hour < 0);

        // London: 2 AM - 5 AM NY time
        inLondon = (hour >= 2 && hour < 5);

        // NY: 9:30 AM - 4 PM NY time
        inNY = (hour >= 9 && hour < 16) || (hour == 9 && time.getMinute() >= 30);

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

        KnownLevel existing = levels.get(type);
        if (existing == null || Math.abs(existing.getPrice() - price) > config.getTolerancePrice()) {
            // New level or significantly different price
            levels.put(type, new KnownLevel(type, price, timestamp));
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

    // ═══════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get trading day from NY time.
     * Trading day changes at 5 PM NY time (RTH close).
     */
    private LocalDate getTradingDay(ZonedDateTime nyTime) {
        // If before 5 PM, it's still the previous trading day
        if (nyTime.getHour() < 17) {
            return nyTime.toLocalDate();
        }
        // After 5 PM, it's the next trading day
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

    @Override
    public String toString() {
        return String.format("LevelEngine{%s: %d levels, day=%s}",
                symbol, levels.size(), currentTradingDay);
    }
}
