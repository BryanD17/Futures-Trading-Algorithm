package com.topstep.trading.domain;

import java.time.*;
import java.time.temporal.ChronoUnit;

/**
 * Manages trading session boundaries for futures markets.
 *
 * CME Futures Trading Sessions (Central Time):
 * - Regular Trading Hours (RTH): 8:30 AM - 3:15 PM CT
 * - Electronic Trading (Globex): 5:00 PM - 4:00 PM CT next day
 *
 * For Topstep prop accounts, the trading day is typically:
 * - Start: 5:00 PM CT (previous calendar day)
 * - End: 4:00 PM CT (current calendar day)
 *
 * This manager helps determine:
 * 1. When a new trading session begins (for daily reset)
 * 2. What trading session a given timestamp belongs to
 * 3. Whether we've crossed into a new session
 */
public class TradingSessionManager {

    // Trading session boundaries in Central Time
    private static final int SESSION_START_HOUR = 17;  // 5:00 PM CT
    private static final int SESSION_END_HOUR = 16;    // 4:00 PM CT

    private static final ZoneId CENTRAL_TIME = ZoneId.of("America/Chicago");

    private LocalDate currentSessionDate;
    private ZonedDateTime lastProcessedTime;

    public TradingSessionManager() {
        this.currentSessionDate = null;
        this.lastProcessedTime = null;
    }

    /**
     * Initialize with the first timestamp.
     *
     * @param timestamp First timestamp to process
     */
    public void initialize(Instant timestamp) {
        ZonedDateTime zonedTime = timestamp.atZone(CENTRAL_TIME);
        this.currentSessionDate = getTradingSessionDate(zonedTime);
        this.lastProcessedTime = zonedTime;
    }

    /**
     * Check if we've crossed into a new trading session.
     *
     * @param timestamp Current timestamp
     * @return true if a new session has started
     */
    public boolean hasNewSessionStarted(Instant timestamp) {
        if (currentSessionDate == null) {
            initialize(timestamp);
            return true;
        }

        ZonedDateTime zonedTime = timestamp.atZone(CENTRAL_TIME);
        LocalDate sessionDate = getTradingSessionDate(zonedTime);

        boolean isNewSession = !sessionDate.equals(currentSessionDate);

        if (isNewSession) {
            this.currentSessionDate = sessionDate;
        }

        this.lastProcessedTime = zonedTime;

        return isNewSession;
    }

    /**
     * Get the trading session date for a given timestamp.
     *
     * The trading session date is:
     * - If time is before 5:00 PM CT: previous calendar day
     * - If time is 5:00 PM CT or later: current calendar day
     *
     * @param zonedTime Timestamp in Central Time
     * @return Trading session date
     */
    private LocalDate getTradingSessionDate(ZonedDateTime zonedTime) {
        LocalTime time = zonedTime.toLocalTime();
        LocalDate calendarDate = zonedTime.toLocalDate();

        // If it's before 5:00 PM (session start), we're still in the previous day's session
        if (time.isBefore(LocalTime.of(SESSION_START_HOUR, 0))) {
            return calendarDate.minusDays(1);
        } else {
            return calendarDate;
        }
    }

    /**
     * Get the current trading session date.
     *
     * @return Current trading session date
     */
    public LocalDate getCurrentSessionDate() {
        return currentSessionDate;
    }

    /**
     * Get the session start time for a given session date.
     *
     * @param sessionDate Trading session date
     * @return Session start instant
     */
    public Instant getSessionStart(LocalDate sessionDate) {
        // Session starts at 5:00 PM CT on the session date
        ZonedDateTime sessionStart = ZonedDateTime.of(
                sessionDate,
                LocalTime.of(SESSION_START_HOUR, 0),
                CENTRAL_TIME
        );
        return sessionStart.toInstant();
    }

    /**
     * Get the session end time for a given session date.
     *
     * @param sessionDate Trading session date
     * @return Session end instant
     */
    public Instant getSessionEnd(LocalDate sessionDate) {
        // Session ends at 4:00 PM CT on the day after session date
        ZonedDateTime sessionEnd = ZonedDateTime.of(
                sessionDate.plusDays(1),
                LocalTime.of(SESSION_END_HOUR, 0),
                CENTRAL_TIME
        );
        return sessionEnd.toInstant();
    }

    /**
     * Check if a timestamp is during Regular Trading Hours (RTH).
     *
     * @param timestamp Timestamp to check
     * @return true if during RTH
     */
    public boolean isDuringRth(Instant timestamp) {
        ZonedDateTime zonedTime = timestamp.atZone(CENTRAL_TIME);
        LocalTime time = zonedTime.toLocalTime();

        LocalTime rthStart = LocalTime.of(8, 30);   // 8:30 AM CT
        LocalTime rthEnd = LocalTime.of(15, 15);    // 3:15 PM CT

        return !time.isBefore(rthStart) && !time.isAfter(rthEnd);
    }

    /**
     * Check if a timestamp is during Electronic Trading Hours (ETH/Globex).
     *
     * @param timestamp Timestamp to check
     * @return true if during ETH
     */
    public boolean isDuringEth(Instant timestamp) {
        return !isDuringRth(timestamp);
    }

    /**
     * Get the number of complete trading sessions between two timestamps.
     *
     * @param start Start timestamp
     * @param end End timestamp
     * @return Number of complete sessions
     */
    public long getSessionCount(Instant start, Instant end) {
        ZonedDateTime startZoned = start.atZone(CENTRAL_TIME);
        ZonedDateTime endZoned = end.atZone(CENTRAL_TIME);

        LocalDate startSession = getTradingSessionDate(startZoned);
        LocalDate endSession = getTradingSessionDate(endZoned);

        return ChronoUnit.DAYS.between(startSession, endSession) + 1;
    }

    @Override
    public String toString() {
        return String.format("TradingSessionManager{currentSession=%s, lastProcessed=%s}",
                currentSessionDate,
                lastProcessedTime != null ? lastProcessedTime.toLocalDateTime() : "none");
    }
}
