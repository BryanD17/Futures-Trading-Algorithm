package com.topstep.trading.strategy.session;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * THE single source of every session / killzone boundary in the engine
 * (V5 Agent 02, RC-02 / PF-03 / PF-09). Every other time system —
 * {@code KillzoneClock}, {@code SilverBulletClock}, the runner's M3 /
 * re-arm / O1 / size-boost checks, the validator's M3 — reads these
 * constants or calls these methods.
 *
 * <h2>Zone rule</h2>
 * All constants are America/New_York wall-clock times and every
 * classification converts the CANDLE instant with the tz database, so DST is
 * never hand-coded: America/Chicago is always ET - 1 h and
 * America/Los_Angeles (the owner's TradingView chart, UTC-7 in summer) is
 * always ET - 3 h, because all three switch on the same US dates
 * (2026-03-08 and 2026-11-01, 02:00 local) — the only exception is the
 * single transition hour early on those Sunday mornings, which is WEEKEND
 * (market closed) in every zone. The daily no-entry block
 * 14:45-17:00 CT is therefore exactly 15:45-18:00 ET all year.
 *
 * <h2>Windows (ET, start inclusive / end exclusive)</h2>
 * <pre>
 *   ASIA      19:00-02:00   (CT 18:00-01:00, PT 16:00-23:00)
 *   LONDON    02:00-08:00   (CT 01:00-07:00, PT 23:00-05:00)  prime 02:00-05:00
 *   PRE_NY    08:00-09:30   (CT 07:00-08:30, PT 05:00-06:30)
 *   NY_AM     09:30-12:00   (CT 08:30-11:00, PT 06:30-09:00)  prime 09:45-11:00
 *   NY_LUNCH  12:00-13:30   (CT 11:00-12:30, PT 09:00-10:30)
 *   NY_PM     13:30-15:45   (CT 12:30-14:45, PT 10:30-12:45)  prime 13:45-15:45
 *   NO_ENTRY  15:45-18:00   (CT 14:45-17:00, PT 12:45-15:00)  SACRED
 *   PRE_ASIA  18:00-19:00   (CT 17:00-18:00, PT 15:00-16:00)
 *   WEEKEND   Fri 17:00 -> Sun 18:00  (CT Fri 16:00 -> Sun 17:00)  SACRED
 * </pre>
 * The Sunday Globex open (18:00 ET) enters PRE_ASIA exactly like every
 * weekday reopen; ASIA starts 19:00 on Sunday too. Friday 15:45-17:00 is
 * NO_ENTRY, Friday 17:00 onward is WEEKEND.
 *
 * <h2>Legacy killzones (BLOCKING mode / scoring inputs, values unchanged)</h2>
 * NY AM KZ 09:45-12:30, NY PM KZ 13:45-16:00, London session 03:00-12:00,
 * Asian session 19:00-04:00, Silver Bullet 03-04 / 10-11 / 14-15.
 */
public final class SessionClassifier {

    private SessionClassifier() {}

    public static final ZoneId ET = ZoneId.of("America/New_York");
    /** Documentation / conversions only — never a second source of boundaries. */
    public static final ZoneId CT = ZoneId.of("America/Chicago");
    public static final ZoneId PT = ZoneId.of("America/Los_Angeles");

    // ── Session window starts (ET) ──────────────────────────────────────
    public static final LocalTime LONDON_START = LocalTime.of(2, 0);
    public static final LocalTime PRE_NY_START = LocalTime.of(8, 0);
    public static final LocalTime NY_AM_START = LocalTime.of(9, 30);
    public static final LocalTime NY_LUNCH_START = LocalTime.of(12, 0);
    public static final LocalTime NY_PM_START = LocalTime.of(13, 30);
    /** 15:45 ET = 14:45 CT: no new entries (25 min before the 15:10 CT flatten). */
    public static final LocalTime NO_ENTRY_START = LocalTime.of(15, 45);
    /** 18:00 ET = 17:00 CT: Globex reopen; also the Sunday weekly open. */
    public static final LocalTime PRE_ASIA_START = LocalTime.of(18, 0);
    public static final LocalTime ASIA_START = LocalTime.of(19, 0);
    /** Friday 17:00 ET = 16:00 CT weekly close. */
    public static final LocalTime WEEKLY_CLOSE_FRIDAY = LocalTime.of(17, 0);
    /** Sunday 18:00 ET = 17:00 CT weekly open. */
    public static final LocalTime WEEKLY_OPEN_SUNDAY = PRE_ASIA_START;

    // ── Prime killzones (ET) — O1 confluence + killzone size boost ──────
    public static final LocalTime LONDON_PRIME_START = LocalTime.of(2, 0);
    public static final LocalTime LONDON_PRIME_END = LocalTime.of(5, 0);
    public static final LocalTime NY_AM_PRIME_START = LocalTime.of(9, 45);
    public static final LocalTime NY_AM_PRIME_END = LocalTime.of(11, 0);
    public static final LocalTime NY_PM_PRIME_START = LocalTime.of(13, 45);
    /**
     * NY PM prime ends where entries end: the legacy NY PM killzone closed at
     * 15:00 CT (= 16:00 ET) and is clipped by the SACRED 14:45 CT block, so
     * prime runs to 15:45 ET. (G1, 2026-09-28 15:05 ET, is prime.)
     */
    public static final LocalTime NY_PM_PRIME_END = NO_ENTRY_START;

    // ── Silver Bullet windows (ET) ──────────────────────────────────────
    public static final LocalTime SB_LONDON_START = LocalTime.of(3, 0);
    public static final LocalTime SB_LONDON_END = LocalTime.of(4, 0);
    public static final LocalTime SB_NY_AM_START = LocalTime.of(10, 0);
    public static final LocalTime SB_NY_AM_END = LocalTime.of(11, 0);
    public static final LocalTime SB_NY_PM_START = LocalTime.of(14, 0);
    public static final LocalTime SB_NY_PM_END = LocalTime.of(15, 0);

    // ── Legacy killzones (ET) — KillzoneClock public API, BLOCKING M3 ───
    public static final LocalTime KZ_NY_AM_START = LocalTime.of(9, 45);
    public static final LocalTime KZ_NY_AM_END = LocalTime.of(12, 30);
    public static final LocalTime KZ_NY_PM_START = LocalTime.of(13, 45);
    public static final LocalTime KZ_NY_PM_END = LocalTime.of(16, 0);
    public static final LocalTime KZ_LONDON_SESSION_START = LocalTime.of(3, 0);
    public static final LocalTime KZ_LONDON_SESSION_END = LocalTime.of(12, 0);
    public static final LocalTime KZ_ASIAN_SESSION_START = LocalTime.of(19, 0);
    public static final LocalTime KZ_ASIAN_SESSION_END = LocalTime.of(4, 0);
    /** Overlap helpers as KillzoneClock.isSessionOverlap has always defined them. */
    public static final LocalTime NY_OPEN_FOR_OVERLAP = LocalTime.of(9, 30);
    public static final LocalTime LONDON_EARLY_END = LocalTime.of(4, 0);

    /** Full classification of one candle instant. */
    public record Classification(Instant at, SessionWindow window,
                                 boolean primeKillzone, boolean silverBullet) {
        public boolean blocksEntry() { return window.blocksEntry(); }
    }

    /** The session window of {@code at} (candle time). */
    public static SessionWindow classify(Instant at) {
        if (at == null) throw new IllegalArgumentException("instant must not be null");
        ZonedDateTime z = at.atZone(ET);
        DayOfWeek d = z.getDayOfWeek();
        LocalTime t = z.toLocalTime();
        if (d == DayOfWeek.SATURDAY) return SessionWindow.WEEKEND;
        if (d == DayOfWeek.SUNDAY && t.isBefore(WEEKLY_OPEN_SUNDAY)) return SessionWindow.WEEKEND;
        if (d == DayOfWeek.FRIDAY && !t.isBefore(WEEKLY_CLOSE_FRIDAY)) return SessionWindow.WEEKEND;
        if (!t.isBefore(ASIA_START) || t.isBefore(LONDON_START)) return SessionWindow.ASIA;
        if (t.isBefore(PRE_NY_START)) return SessionWindow.LONDON;
        if (t.isBefore(NY_AM_START)) return SessionWindow.PRE_NY;
        if (t.isBefore(NY_LUNCH_START)) return SessionWindow.NY_AM;
        if (t.isBefore(NY_PM_START)) return SessionWindow.NY_LUNCH;
        if (t.isBefore(NO_ENTRY_START)) return SessionWindow.NY_PM;
        if (t.isBefore(PRE_ASIA_START)) return SessionWindow.NO_ENTRY;
        return SessionWindow.PRE_ASIA;
    }

    /** Window + prime + silver-bullet flags for {@code at}. */
    public static Classification classifyFull(Instant at) {
        SessionWindow w = classify(at);
        LocalTime t = etTime(at);
        return new Classification(at, w, isPrime(w, t), !w.blocksEntry() && isSilverBulletTime(t));
    }

    /** True inside a prime killzone (London 02-05, NY AM 09:45-11, NY PM 13:45-15:45 ET). */
    public static boolean isPrimeKillzone(Instant at) {
        return isPrime(classify(at), etTime(at));
    }

    /** True inside a Silver Bullet hour (03-04, 10-11, 14-15 ET) on an open market. */
    public static boolean isSilverBullet(Instant at) {
        return !classify(at).blocksEntry() && isSilverBulletTime(etTime(at));
    }

    /** True when the SACRED blocks (NO_ENTRY / WEEKEND) forbid a new entry. */
    public static boolean blocksEntry(Instant at) {
        return classify(at).blocksEntry();
    }

    private static boolean isPrime(SessionWindow w, LocalTime t) {
        switch (w) {
            case LONDON:  return in(t, LONDON_PRIME_START, LONDON_PRIME_END);
            case NY_AM:   return in(t, NY_AM_PRIME_START, NY_AM_PRIME_END);
            case NY_PM:   return in(t, NY_PM_PRIME_START, NY_PM_PRIME_END);
            default:      return false;
        }
    }

    // ── Pure time-of-day helpers (ET LocalTime) — used by KillzoneClock ─

    public static LocalTime etTime(Instant at) {
        return at.atZone(ET).toLocalTime();
    }

    public static DayOfWeek etDay(Instant at) {
        return at.atZone(ET).getDayOfWeek();
    }

    /** Half-open [start, end); wraps midnight when end is not after start. */
    public static boolean in(LocalTime t, LocalTime start, LocalTime end) {
        if (end.isAfter(start)) return !t.isBefore(start) && t.isBefore(end);
        return !t.isBefore(start) || t.isBefore(end);
    }

    public static boolean isSilverBulletTime(LocalTime t) {
        return in(t, SB_LONDON_START, SB_LONDON_END)
                || in(t, SB_NY_AM_START, SB_NY_AM_END)
                || in(t, SB_NY_PM_START, SB_NY_PM_END);
    }

    public static boolean isLegacyNyAmKillzone(LocalTime t) {
        return in(t, KZ_NY_AM_START, KZ_NY_AM_END);
    }

    public static boolean isLegacyNyPmKillzone(LocalTime t) {
        return in(t, KZ_NY_PM_START, KZ_NY_PM_END);
    }

    public static boolean isLegacyNyKillzone(Instant at) {
        LocalTime t = etTime(at);
        return isLegacyNyAmKillzone(t) || isLegacyNyPmKillzone(t);
    }

    public static boolean isLegacyLondonSession(LocalTime t) {
        return in(t, KZ_LONDON_SESSION_START, KZ_LONDON_SESSION_END);
    }

    public static boolean isLegacyAsianSession(LocalTime t) {
        return in(t, KZ_ASIAN_SESSION_START, KZ_ASIAN_SESSION_END);
    }
}
