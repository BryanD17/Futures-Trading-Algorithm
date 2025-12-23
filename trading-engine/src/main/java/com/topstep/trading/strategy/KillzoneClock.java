package com.topstep.trading.strategy;

import java.time.*;

/**
 * Determines if the current time is within an ICT killzone.
 * Killzones are specific time windows during the NY session where ICT concepts
 * suggest the best trading opportunities occur.
 */
public class KillzoneClock {

    private final ZoneId newYorkZone = ZoneId.of("America/New_York");

    // NY AM Killzone (9:45 AM - 12:30 PM EST = 8:45 AM - 11:30 AM CT)
    private final LocalTime nyAmStart = LocalTime.of(9, 45);
    private final LocalTime nyAmEnd = LocalTime.of(12, 30);

    // NY PM Killzone (1:45 PM - 4:00 PM EST = 12:45 PM - 3:00 PM CT)
    private final LocalTime nyPmStart = LocalTime.of(13, 45);
    private final LocalTime nyPmEnd = LocalTime.of(16, 0);

    /**
     * Check if the given instant is within a NY killzone.
     */
    public boolean isInKillzone(Instant instant) {
        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();

        return isInNyAmKillzone(time) || isInNyPmKillzone(time);
    }

    /**
     * Check if time is in NY AM killzone (9:45 AM - 12:30 PM EST = 8:45-11:30 AM CT).
     */
    public boolean isInNyAmKillzone(LocalTime time) {
        return !time.isBefore(nyAmStart) && time.isBefore(nyAmEnd);
    }

    /**
     * Check if time is in NY PM killzone (1:45 - 4:00 PM EST = 12:45-3:00 PM CT).
     */
    public boolean isInNyPmKillzone(LocalTime time) {
        return !time.isBefore(nyPmStart) && time.isBefore(nyPmEnd);
    }

    /**
     * Get the killzone session name, or "REGULAR_SESSION" if not in a killzone.
     */
    public String getKillzoneName(Instant instant) {
        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();

        if (isInNyAmKillzone(time)) {
            return "NY_AM_KILLZONE";
        } else if (isInNyPmKillzone(time)) {
            return "NY_PM_KILLZONE";
        }
        return "REGULAR_SESSION";
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
