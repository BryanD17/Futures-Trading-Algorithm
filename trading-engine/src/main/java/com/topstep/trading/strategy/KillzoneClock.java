package com.topstep.trading.strategy;

import java.time.*;

/**
 * Determines if the current time is within an ICT killzone.
 * Killzones are specific time windows during the NY session where ICT concepts
 * suggest the best trading opportunities occur.
 */
public class KillzoneClock {

    private final ZoneId newYorkZone = ZoneId.of("America/New_York");

    // NY AM Killzone (10:00 AM - 11:00 AM EST)
    private final LocalTime nyAmStart = LocalTime.of(10, 0);
    private final LocalTime nyAmEnd = LocalTime.of(11, 0);

    // NY PM Killzone (2:00 PM - 3:00 PM EST)
    private final LocalTime nyPmStart = LocalTime.of(14, 0);
    private final LocalTime nyPmEnd = LocalTime.of(15, 0);

    /**
     * Check if the given instant is within a NY killzone.
     */
    public boolean isInKillzone(Instant instant) {
        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();

        return isInNyAmKillzone(time) || isInNyPmKillzone(time);
    }

    /**
     * Check if time is in NY AM killzone (10:00 - 11:00 AM EST).
     */
    public boolean isInNyAmKillzone(LocalTime time) {
        return !time.isBefore(nyAmStart) && time.isBefore(nyAmEnd);
    }

    /**
     * Check if time is in NY PM killzone (2:00 - 3:00 PM EST).
     */
    public boolean isInNyPmKillzone(LocalTime time) {
        return !time.isBefore(nyPmStart) && time.isBefore(nyPmEnd);
    }

    /**
     * Get the killzone session name, or null if not in a killzone.
     */
    public String getKillzoneName(Instant instant) {
        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();

        if (isInNyAmKillzone(time)) {
            return "NY_AM_KILLZONE";
        } else if (isInNyPmKillzone(time)) {
            return "NY_PM_KILLZONE";
        }
        return null;
    }

    /**
     * Check if it's a valid trading day (Monday-Friday).
     */
    public boolean isTradingDay(Instant instant) {
        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        DayOfWeek day = nyTime.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
