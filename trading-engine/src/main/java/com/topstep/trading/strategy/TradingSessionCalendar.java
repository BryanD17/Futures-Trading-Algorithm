package com.topstep.trading.strategy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * CME futures session calendar (V3 Agent 04) — the single source of truth
 * for H4/D1 bucketing semantics.
 *
 * <p>All calendar logic runs through {@code America/New_York} {@link ZoneId}
 * arithmetic — NEVER fixed UTC offsets (B7). That is what makes the ET
 * wall-clock anchors hold across DST transitions: on spring-forward the
 * 02:00 anchor resolves forward through the gap, on fall-back the 22:00
 * bucket simply spans one extra wall-clock hour.
 *
 * <ul>
 *   <li>Trading day: 18:00 ET → 17:00 ET next day. The daily bar for
 *       "Tuesday" OPENS Monday 18:00 ET; Sunday 18:00 ET opens Monday's
 *       session; Friday's daily closes 17:00 ET Friday.</li>
 *   <li>17:00–18:00 ET is the maintenance break: no bars, and any stray
 *       bar in it belongs to NO daily/H4 bucket (excluded, not merged).</li>
 *   <li>D1 bucket key = the SESSION date (a candle at Monday 19:30 ET
 *       belongs to Tuesday's daily bar).</li>
 *   <li>H4 buckets anchor to the session open: 18:00, 22:00, 02:00,
 *       06:00, 10:00, 14:00 ET wall-clock.</li>
 * </ul>
 */
public final class TradingSessionCalendar {

    /** The one timezone every session computation uses. */
    public static final ZoneId ET = ZoneId.of("America/New_York");

    private static final LocalTime SESSION_OPEN = LocalTime.of(18, 0);

    private TradingSessionCalendar() {}

    /** True inside the 17:00–18:00 ET maintenance break (no-man's-land). */
    public static boolean inMaintenanceBreak(Instant ts) {
        return ts.atZone(ET).getHour() == 17;
    }

    /**
     * The SESSION date a timestamp belongs to: candles at/after 18:00 ET
     * belong to the NEXT calendar date's session.
     */
    public static LocalDate sessionDate(Instant ts) {
        ZonedDateTime z = ts.atZone(ET);
        return (z.getHour() >= 18) ? z.toLocalDate().plusDays(1) : z.toLocalDate();
    }

    /** D1 bucket start: 18:00 ET on the eve of the session date. */
    public static Instant d1BucketStart(Instant ts) {
        LocalDate session = sessionDate(ts);
        return ZonedDateTime.of(session.minusDays(1), SESSION_OPEN, ET).toInstant();
    }

    /**
     * H4 bucket start: the latest of the 18/22/02/06/10/14 ET wall-clock
     * anchors at or before the timestamp.
     */
    public static Instant h4BucketStart(Instant ts) {
        ZonedDateTime z = ts.atZone(ET);
        int hour = z.getHour();
        LocalDate date = z.toLocalDate();
        int anchor;
        if (hour >= 22)      anchor = 22;
        else if (hour >= 18) anchor = 18;
        else if (hour >= 14) anchor = 14;
        else if (hour >= 10) anchor = 10;
        else if (hour >= 6)  anchor = 6;
        else if (hour >= 2)  anchor = 2;
        else {               anchor = 22; date = date.minusDays(1); }
        return ZonedDateTime.of(date, LocalTime.of(anchor, 0), ET).toInstant();
    }
}
