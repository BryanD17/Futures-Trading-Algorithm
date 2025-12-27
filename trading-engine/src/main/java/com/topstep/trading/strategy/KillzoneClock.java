package com.topstep.trading.strategy;

import java.time.*;

/**
 * Enhanced Killzone Clock with session management and phase detection.
 *
 * Determines if the current time is within an ICT killzone and which phase.
 * Killzones are specific time windows during the NY session where ICT concepts
 * suggest the best trading opportunities occur.
 *
 * Each killzone has three phases:
 * - OPENING (First 30 min): Wait for liquidity sweep
 * - PRIME (Middle): Optimal entry window
 * - CLOSING (Last 30 min): No new entries
 */
public class KillzoneClock {

    private final ZoneId newYorkZone = ZoneId.of("America/New_York");
    private final ZoneId chicagoZone = ZoneId.of("America/Chicago");

    // NY AM Killzone (9:45 AM - 12:30 PM EST = 8:45 AM - 11:30 AM CT)
    private final LocalTime nyAmStart = LocalTime.of(9, 45);
    private final LocalTime nyAmEnd = LocalTime.of(12, 30);
    private final int nyAmOpeningDuration = 30;  // 30 min opening phase
    private final int nyAmClosingDuration = 30;  // 30 min closing phase

    // NY PM Killzone (1:45 PM - 4:00 PM EST = 12:45 PM - 3:00 PM CT)
    private final LocalTime nyPmStart = LocalTime.of(13, 45);
    private final LocalTime nyPmEnd = LocalTime.of(16, 0);
    private final int nyPmOpeningDuration = 30;
    private final int nyPmClosingDuration = 30;

    // London Killzone for Gold trading (3:00 AM - 12:00 PM EST)
    private final LocalTime londonStart = LocalTime.of(3, 0);
    private final LocalTime londonEnd = LocalTime.of(12, 0);

    // Asian Session for Yen/Nikkei (7:00 PM - 4:00 AM EST)
    private final LocalTime asianStart = LocalTime.of(19, 0);
    private final LocalTime asianEnd = LocalTime.of(4, 0);

    /**
     * Check if the given instant is within any NY killzone.
     */
    public boolean isInKillzone(Instant instant) {
        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();

        return isInNyAmKillzone(time) || isInNyPmKillzone(time);
    }

    /**
     * Check if time is in NY AM killzone (9:45 AM - 12:30 PM EST).
     */
    public boolean isInNyAmKillzone(LocalTime time) {
        return !time.isBefore(nyAmStart) && time.isBefore(nyAmEnd);
    }

    /**
     * Check if time is in NY PM killzone (1:45 - 4:00 PM EST).
     */
    public boolean isInNyPmKillzone(LocalTime time) {
        return !time.isBefore(nyPmStart) && time.isBefore(nyPmEnd);
    }

    /**
     * Check if in London session (for Gold trading).
     */
    public boolean isInLondonSession(Instant instant) {
        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();

        return !time.isBefore(londonStart) && time.isBefore(londonEnd);
    }

    /**
     * Check if in Asian session (for Yen/Nikkei).
     */
    public boolean isInAsianSession(Instant instant) {
        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();

        // Asian session spans midnight
        return !time.isBefore(asianStart) || time.isBefore(asianEnd);
    }

    /**
     * Get the current killzone phase for entry timing.
     */
    public KillzonePhase getKillzonePhase(Instant instant) {
        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();

        // Check NY AM Killzone
        if (isInNyAmKillzone(time)) {
            return calculatePhase(time, nyAmStart, nyAmEnd, nyAmOpeningDuration, nyAmClosingDuration);
        }

        // Check NY PM Killzone
        if (isInNyPmKillzone(time)) {
            return calculatePhase(time, nyPmStart, nyPmEnd, nyPmOpeningDuration, nyPmClosingDuration);
        }

        return KillzonePhase.OUTSIDE;
    }

    /**
     * Calculate the phase within a killzone based on time.
     */
    private KillzonePhase calculatePhase(LocalTime current, LocalTime start, LocalTime end,
                                         int openingMinutes, int closingMinutes) {
        long minutesFromStart = Duration.between(start, current).toMinutes();
        long minutesToEnd = Duration.between(current, end).toMinutes();

        // Opening phase: First N minutes
        if (minutesFromStart < openingMinutes) {
            return KillzonePhase.OPENING;
        }

        // Closing phase: Last N minutes
        if (minutesToEnd <= closingMinutes) {
            return KillzonePhase.CLOSING;
        }

        // Prime phase: Everything in between
        return KillzonePhase.PRIME;
    }

    /**
     * Check if new entries are allowed based on killzone phase.
     */
    public boolean allowsNewEntries(Instant instant) {
        KillzonePhase phase = getKillzonePhase(instant);
        return phase.allowsNewEntries();
    }

    /**
     * Check if we should wait for a liquidity sweep before entering.
     */
    public boolean shouldWaitForSweep(Instant instant) {
        KillzonePhase phase = getKillzonePhase(instant);
        return phase.shouldWaitForSweep();
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
        } else if (isInLondonSession(instant)) {
            return "LONDON_SESSION";
        } else if (isInAsianSession(instant)) {
            return "ASIAN_SESSION";
        }
        return "REGULAR_SESSION";
    }

    /**
     * Get detailed info about current killzone and phase.
     */
    public String getDetailedInfo(Instant instant) {
        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();
        String session = getKillzoneName(instant);
        KillzonePhase phase = getKillzonePhase(instant);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Time: %s ET | ", time));
        sb.append(String.format("Session: %s | ", session));
        sb.append(String.format("Phase: %s | ", phase.getDescription()));
        sb.append(String.format("Entries: %s", phase.allowsNewEntries() ? "ALLOWED" : "BLOCKED"));

        return sb.toString();
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
     * Get minutes remaining in current killzone.
     */
    public int getMinutesRemaining(Instant instant) {
        ZonedDateTime nyTime = instant.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();

        if (isInNyAmKillzone(time)) {
            return (int) Duration.between(time, nyAmEnd).toMinutes();
        } else if (isInNyPmKillzone(time)) {
            return (int) Duration.between(time, nyPmEnd).toMinutes();
        }

        return 0;
    }

    /**
     * Check if enough time remains in killzone for a trade (at least 30 min).
     */
    public boolean hasEnoughTimeForTrade(Instant instant, int minimumMinutes) {
        return getMinutesRemaining(instant) >= minimumMinutes;
    }

    /**
     * Get the next killzone start time.
     */
    public Instant getNextKillzoneStart(Instant currentTime) {
        ZonedDateTime nyTime = currentTime.atZone(newYorkZone);
        LocalTime time = nyTime.toLocalTime();
        LocalDate date = nyTime.toLocalDate();

        // If before AM killzone
        if (time.isBefore(nyAmStart)) {
            return date.atTime(nyAmStart).atZone(newYorkZone).toInstant();
        }

        // If between AM and PM killzones
        if (time.isAfter(nyAmEnd) && time.isBefore(nyPmStart)) {
            return date.atTime(nyPmStart).atZone(newYorkZone).toInstant();
        }

        // After PM killzone - next day AM
        return date.plusDays(1).atTime(nyAmStart).atZone(newYorkZone).toInstant();
    }
}
