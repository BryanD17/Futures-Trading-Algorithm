package com.topstep.trading.strategy;

import java.time.*;

/**
 * ICT Silver Bullet Time Windows.
 *
 * The Silver Bullet is a specific ICT methodology with THREE exact time windows
 * during which high-probability setups occur. These are NOT the same as generic
 * killzones - they are precise 1-hour windows.
 *
 * Time Windows (All in Eastern Time / New York):
 * 1. London Open Silver Bullet: 3:00 AM - 4:00 AM EST
 *    - Best for Gold (GC), Euro (6E), British Pound (6B)
 *    - Targets London session liquidity
 *
 * 2. New York AM Silver Bullet: 10:00 AM - 11:00 AM EST
 *    - Best for Indices (ES, NQ), Crude (CL), Gold (GC)
 *    - Targets NY open liquidity pools
 *
 * 3. New York PM Silver Bullet: 2:00 PM - 3:00 PM EST
 *    - Best for Indices (ES, NQ)
 *    - Targets afternoon liquidity and manipulation
 *
 * Setup Requirements:
 * 1. Price must raid a known liquidity level (session high/low, equal H/L, PDH/PDL)
 * 2. Market Structure Shift (MSS) on 1m/3m in direction of next draw on liquidity
 * 3. Displacement creates entry FVG associated with MSS leg
 * 4. Entry on FVG retrace with defined invalidation
 */
public class SilverBulletClock {

    public enum SilverBulletWindow {
        LONDON_OPEN("London Open SB", LocalTime.of(3, 0), LocalTime.of(4, 0)),
        NY_AM("NY AM SB", LocalTime.of(10, 0), LocalTime.of(11, 0)),
        NY_PM("NY PM SB", LocalTime.of(14, 0), LocalTime.of(15, 0)),
        NONE("Outside SB", null, null);

        private final String name;
        private final LocalTime start;
        private final LocalTime end;

        SilverBulletWindow(String name, LocalTime start, LocalTime end) {
            this.name = name;
            this.start = start;
            this.end = end;
        }

        public String getName() { return name; }
        public LocalTime getStart() { return start; }
        public LocalTime getEnd() { return end; }

        public boolean isActive() {
            return this != NONE;
        }
    }

    /**
     * Represents best instruments for each SB window.
     */
    public enum SilverBulletSymbolGroup {
        FOREX_METALS(new String[]{"6E", "6B", "6J", "GC", "SI"}),      // London Open
        INDICES_ENERGY(new String[]{"ES", "NQ", "CL", "GC", "NG"}),    // NY AM
        INDICES(new String[]{"ES", "NQ", "RTY", "YM"});                 // NY PM

        private final String[] symbols;

        SilverBulletSymbolGroup(String[] symbols) {
            this.symbols = symbols;
        }

        public String[] getSymbols() { return symbols; }

        public boolean includes(String symbol) {
            for (String s : symbols) {
                if (s.equalsIgnoreCase(symbol)) return true;
            }
            return false;
        }
    }

    private final ZoneId newYorkZone = ZoneId.of("America/New_York");

    /**
     * Get the current Silver Bullet window.
     */
    public SilverBulletWindow getCurrentWindow(Instant instant) {
        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();

        // Check London Open SB
        if (isInWindow(time, SilverBulletWindow.LONDON_OPEN)) {
            return SilverBulletWindow.LONDON_OPEN;
        }

        // Check NY AM SB
        if (isInWindow(time, SilverBulletWindow.NY_AM)) {
            return SilverBulletWindow.NY_AM;
        }

        // Check NY PM SB
        if (isInWindow(time, SilverBulletWindow.NY_PM)) {
            return SilverBulletWindow.NY_PM;
        }

        return SilverBulletWindow.NONE;
    }

    /**
     * Check if a time is within a specific window.
     */
    private boolean isInWindow(LocalTime time, SilverBulletWindow window) {
        if (window.getStart() == null || window.getEnd() == null) {
            return false;
        }
        return !time.isBefore(window.getStart()) && time.isBefore(window.getEnd());
    }

    /**
     * Check if currently in any Silver Bullet window.
     */
    public boolean isInSilverBulletWindow(Instant instant) {
        return getCurrentWindow(instant).isActive();
    }

    /**
     * Get the current Silver Bullet window name for compatibility with RaidDetector.
     */
    public String getCurrentWindowName(Instant instant) {
        return getCurrentWindow(instant).getName();
    }

    /**
     * Check if a symbol is optimal for the current SB window.
     */
    public boolean isOptimalSymbol(String symbol, Instant instant) {
        SilverBulletWindow window = getCurrentWindow(instant);

        switch (window) {
            case LONDON_OPEN:
                return SilverBulletSymbolGroup.FOREX_METALS.includes(symbol);
            case NY_AM:
                return SilverBulletSymbolGroup.INDICES_ENERGY.includes(symbol);
            case NY_PM:
                return SilverBulletSymbolGroup.INDICES.includes(symbol);
            default:
                return false;
        }
    }

    /**
     * Get minutes remaining in current SB window.
     */
    public int getMinutesRemaining(Instant instant) {
        SilverBulletWindow window = getCurrentWindow(instant);
        if (!window.isActive()) {
            return 0;
        }

        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();

        return (int) Duration.between(time, window.getEnd()).toMinutes();
    }

    /**
     * Check if enough time remains for a trade (minimum 15 minutes).
     */
    public boolean hasEnoughTimeForTrade(Instant instant) {
        return getMinutesRemaining(instant) >= 15;
    }

    /**
     * Get the next Silver Bullet window start time.
     */
    public Instant getNextWindowStart(Instant currentTime) {
        ZonedDateTime nyTime = currentTime.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();
        LocalDate date = nyTime.toLocalDate();

        // Check each window in order
        if (time.isBefore(SilverBulletWindow.LONDON_OPEN.getStart())) {
            return date.atTime(SilverBulletWindow.LONDON_OPEN.getStart()).atZone(newYorkZone).toInstant();
        }
        if (time.isBefore(SilverBulletWindow.NY_AM.getStart())) {
            return date.atTime(SilverBulletWindow.NY_AM.getStart()).atZone(newYorkZone).toInstant();
        }
        if (time.isBefore(SilverBulletWindow.NY_PM.getStart())) {
            return date.atTime(SilverBulletWindow.NY_PM.getStart()).atZone(newYorkZone).toInstant();
        }

        // Next day's London Open
        return date.plusDays(1).atTime(SilverBulletWindow.LONDON_OPEN.getStart()).atZone(newYorkZone).toInstant();
    }

    /**
     * Check if it's a valid trading day (Monday-Friday).
     */
    public boolean isTradingDay(Instant instant) {
        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        DayOfWeek day = nyTime.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    /**
     * Get detailed info about current SB window.
     */
    public String getDetailedInfo(Instant instant) {
        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();
        SilverBulletWindow window = getCurrentWindow(instant);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Time: %s ET | ", time));
        sb.append(String.format("Window: %s | ", window.getName()));

        if (window.isActive()) {
            sb.append(String.format("Remaining: %d min | ", getMinutesRemaining(instant)));
            sb.append(String.format("Trade: %s", hasEnoughTimeForTrade(instant) ? "ALLOWED" : "TIME LOW"));
        } else {
            Instant nextStart = getNextWindowStart(instant);
            ZonedDateTime nextNyTime = nextStart.atZone(newYorkZone);
            sb.append(String.format("Next SB: %s at %s",
                getCurrentWindow(nextStart).getName(),
                nextNyTime.toLocalTime()));
        }

        return sb.toString();
    }

    /**
     * Get the session's key liquidity levels that are typical raid targets.
     */
    public static class LiquidityTargets {
        public double asiaHigh;
        public double asiaLow;
        public double londonHigh;
        public double londonLow;
        public double previousDayHigh;
        public double previousDayLow;
        public double currentSessionHigh;
        public double currentSessionLow;

        public LiquidityTargets() {
            this.asiaHigh = 0;
            this.asiaLow = Double.MAX_VALUE;
            this.londonHigh = 0;
            this.londonLow = Double.MAX_VALUE;
            this.previousDayHigh = 0;
            this.previousDayLow = Double.MAX_VALUE;
            this.currentSessionHigh = 0;
            this.currentSessionLow = Double.MAX_VALUE;
        }

        public void updateAsiaRange(double high, double low) {
            this.asiaHigh = high;
            this.asiaLow = low;
        }

        public void updateLondonRange(double high, double low) {
            this.londonHigh = high;
            this.londonLow = low;
        }

        public void updatePreviousDay(double high, double low) {
            this.previousDayHigh = high;
            this.previousDayLow = low;
        }

        public void updateCurrentSession(double high, double low) {
            this.currentSessionHigh = high;
            this.currentSessionLow = low;
        }

        /**
         * Check if price has raided buy-side liquidity (above a high).
         */
        public boolean hasBuySideRaid(double currentHigh, double tolerance) {
            return currentHigh >= asiaHigh - tolerance ||
                   currentHigh >= londonHigh - tolerance ||
                   currentHigh >= previousDayHigh - tolerance ||
                   currentHigh >= currentSessionHigh - tolerance;
        }

        /**
         * Check if price has raided sell-side liquidity (below a low).
         */
        public boolean hasSellSideRaid(double currentLow, double tolerance) {
            return currentLow <= asiaLow + tolerance ||
                   currentLow <= londonLow + tolerance ||
                   currentLow <= previousDayLow + tolerance ||
                   currentLow <= currentSessionLow + tolerance;
        }
    }
}
