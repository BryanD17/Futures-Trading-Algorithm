package com.topstep.trading.strategy.session;

import com.topstep.trading.strategy.KillzoneClock;
import com.topstep.trading.strategy.SilverBulletClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static com.topstep.trading.strategy.session.SessionWindow.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * V5 Agent 02 task 6 — DST + boundary table for {@link SessionClassifier}.
 *
 * <p>Every row is an absolute UTC instant (what a candle carries) so the
 * table proves the tz-database conversion, not a hand-coded offset:
 * EDT = UTC-4 (2026-03-08 .. 2026-11-01), EST = UTC-5 otherwise. A few rows
 * are written in CT or PT to prove the 14:45 CT block and the owner's
 * PT chart line up with the ET windows.
 */
@DisplayName("SessionClassifier — DST + boundary table (V5 Agent 02)")
class SessionClassifierTest {

    private static Instant utc(String iso) {
        return Instant.parse(iso);
    }

    private static Instant at(java.time.ZoneId zone, int y, int mo, int d, int h, int mi) {
        return LocalDate.of(y, mo, d).atTime(LocalTime.of(h, mi)).atZone(zone).toInstant();
    }

    static Stream<Arguments> table() {
        java.time.ZoneId CT = SessionClassifier.CT;
        java.time.ZoneId PT = SessionClassifier.PT;
        return Stream.of(
            // name, instant, window, prime, silverBullet
            // ── G1 (owner golden case) ─────────────────────────────────────
            arguments("G1 2026-09-28 15:05 EDT = 19:05Z", utc("2026-09-28T19:05:00Z"), NY_PM, true, false),
            arguments("G1 same instant from the owner's PT chart 12:05", at(PT, 2026, 9, 28, 12, 5), NY_PM, true, false),
            // ── regular weekday boundaries (Mon 2026-09-28, EDT) ─────────
            arguments("Mon 00:30 EDT ASIA (after midnight)", utc("2026-09-28T04:30:00Z"), ASIA, false, false),
            arguments("Mon 01:59 EDT ASIA last minute", utc("2026-09-28T05:59:00Z"), ASIA, false, false),
            arguments("Mon 02:00 EDT LONDON open = prime", utc("2026-09-28T06:00:00Z"), LONDON, true, false),
            arguments("Mon 03:30 EDT LONDON prime + London SB", utc("2026-09-28T07:30:00Z"), LONDON, true, true),
            arguments("Mon 04:59 EDT LONDON prime last minute", utc("2026-09-28T08:59:00Z"), LONDON, true, false),
            arguments("Mon 05:00 EDT LONDON non-prime", utc("2026-09-28T09:00:00Z"), LONDON, false, false),
            arguments("Mon 07:59 EDT LONDON last minute", utc("2026-09-28T11:59:00Z"), LONDON, false, false),
            arguments("Mon 08:00 EDT PRE_NY", utc("2026-09-28T12:00:00Z"), PRE_NY, false, false),
            arguments("Mon 09:29 EDT PRE_NY last minute", utc("2026-09-28T13:29:00Z"), PRE_NY, false, false),
            arguments("Mon 09:30 EDT NY_AM open, not prime", utc("2026-09-28T13:30:00Z"), NY_AM, false, false),
            arguments("Mon 09:45 EDT NY_AM prime start", utc("2026-09-28T13:45:00Z"), NY_AM, true, false),
            arguments("Mon 10:30 EDT NY_AM prime + NY AM SB", utc("2026-09-28T14:30:00Z"), NY_AM, true, true),
            arguments("Mon 11:00 EDT NY_AM prime ended", utc("2026-09-28T15:00:00Z"), NY_AM, false, false),
            arguments("Mon 12:00 EDT NY_LUNCH", utc("2026-09-28T16:00:00Z"), NY_LUNCH, false, false),
            arguments("Mon 13:29 EDT NY_LUNCH last minute", utc("2026-09-28T17:29:00Z"), NY_LUNCH, false, false),
            arguments("Mon 13:30 EDT NY_PM open, not prime", utc("2026-09-28T17:30:00Z"), NY_PM, false, false),
            arguments("Mon 13:45 EDT NY_PM prime start", utc("2026-09-28T17:45:00Z"), NY_PM, true, false),
            arguments("Mon 14:30 EDT NY_PM prime + NY PM SB", utc("2026-09-28T18:30:00Z"), NY_PM, true, true),
            arguments("Mon 14:59 EDT NY_PM prime + SB last minute", utc("2026-09-28T18:59:00Z"), NY_PM, true, true),
            arguments("Mon 15:00 EDT NY_PM still prime (SB over)", utc("2026-09-28T19:00:00Z"), NY_PM, true, false),
            arguments("Mon 14:44 CT = 15:44 ET NY_PM last entry minute, prime", at(CT, 2026, 9, 28, 14, 44), NY_PM, true, false),
            arguments("Mon 14:45 CT = 15:45 ET NO_ENTRY block start", at(CT, 2026, 9, 28, 14, 45), NO_ENTRY, false, false),
            arguments("Mon 15:10 CT flatten time NO_ENTRY", at(CT, 2026, 9, 28, 15, 10), NO_ENTRY, false, false),
            arguments("Mon 17:00 ET NO_ENTRY (Globex halt)", utc("2026-09-28T21:00:00Z"), NO_ENTRY, false, false),
            arguments("Mon 17:59 ET NO_ENTRY last minute", utc("2026-09-28T21:59:00Z"), NO_ENTRY, false, false),
            arguments("Mon 16:59 CT NO_ENTRY last minute (CT view)", at(CT, 2026, 9, 28, 16, 59), NO_ENTRY, false, false),
            arguments("Mon 18:00 ET = 17:00 CT reopen PRE_ASIA", utc("2026-09-28T22:00:00Z"), PRE_ASIA, false, false),
            arguments("Mon 18:59 ET PRE_ASIA last minute", utc("2026-09-28T22:59:00Z"), PRE_ASIA, false, false),
            arguments("Mon 19:00 ET ASIA open", utc("2026-09-28T23:00:00Z"), ASIA, false, false),
            // ── weekly open / close ──────────────────────────────────────
            arguments("Fri 2026-09-25 15:44 ET NY_PM prime", utc("2026-09-25T19:44:00Z"), NY_PM, true, false),
            arguments("Fri 15:45 ET NO_ENTRY", utc("2026-09-25T19:45:00Z"), NO_ENTRY, false, false),
            arguments("Fri 16:59 ET NO_ENTRY last minute", utc("2026-09-25T20:59:00Z"), NO_ENTRY, false, false),
            arguments("Fri 17:00 ET WEEKEND (weekly close)", utc("2026-09-25T21:00:00Z"), WEEKEND, false, false),
            arguments("Fri 19:30 ET WEEKEND (no Friday Asia)", utc("2026-09-25T23:30:00Z"), WEEKEND, false, false),
            arguments("Sat 2026-09-26 03:30 ET WEEKEND (no SB on weekend)", utc("2026-09-26T07:30:00Z"), WEEKEND, false, false),
            arguments("Sun 2026-09-27 17:59 ET WEEKEND last minute", utc("2026-09-27T21:59:00Z"), WEEKEND, false, false),
            arguments("Sun 18:00 ET weekly open PRE_ASIA", utc("2026-09-27T22:00:00Z"), PRE_ASIA, false, false),
            arguments("Sun 17:00 CT weekly open (CT view)", at(CT, 2026, 9, 27, 17, 0), PRE_ASIA, false, false),
            arguments("Sun 19:00 ET ASIA", utc("2026-09-27T23:00:00Z"), ASIA, false, false),
            // ── DST spring forward 2026-03-08 (EST UTC-5 -> EDT UTC-4) ───
            arguments("Fri 2026-03-06 17:00 EST weekly close = 22:00Z", utc("2026-03-06T22:00:00Z"), WEEKEND, false, false),
            arguments("Fri 2026-03-06 16:59 EST = 21:59Z NO_ENTRY", utc("2026-03-06T21:59:00Z"), NO_ENTRY, false, false),
            arguments("Sun 2026-03-08 03:00 EDT (the skipped hour) WEEKEND", utc("2026-03-08T07:00:00Z"), WEEKEND, false, false),
            arguments("Sun 2026-03-08 17:59 EDT = 21:59Z WEEKEND", utc("2026-03-08T21:59:00Z"), WEEKEND, false, false),
            arguments("Sun 2026-03-08 18:00 EDT = 22:00Z open (was 23:00Z a week earlier)", utc("2026-03-08T22:00:00Z"), PRE_ASIA, false, false),
            arguments("Sun 2026-03-01 22:00Z = 17:00 EST still WEEKEND", utc("2026-03-01T22:00:00Z"), WEEKEND, false, false),
            arguments("Sun 2026-03-01 23:00Z = 18:00 EST open", utc("2026-03-01T23:00:00Z"), PRE_ASIA, false, false),
            arguments("Mon 2026-03-09 13:30Z = 09:30 EDT NY_AM", utc("2026-03-09T13:30:00Z"), NY_AM, false, false),
            arguments("Fri 2026-03-06 14:30Z = 09:30 EST NY_AM", utc("2026-03-06T14:30:00Z"), NY_AM, false, false),
            arguments("Mon 2026-03-09 19:45Z = 15:45 EDT NO_ENTRY (14:45 CDT)", utc("2026-03-09T19:45:00Z"), NO_ENTRY, false, false),
            arguments("Fri 2026-03-06 20:45Z = 15:45 EST NO_ENTRY (14:45 CST)", utc("2026-03-06T20:45:00Z"), NO_ENTRY, false, false),
            arguments("Fri 2026-03-06 19:45Z = 14:45 EST NY_PM prime (naive-offset trap)", utc("2026-03-06T19:45:00Z"), NY_PM, true, true),
            // ── DST fall back 2026-11-01 (EDT UTC-4 -> EST UTC-5) ────────
            arguments("Fri 2026-10-30 21:00Z = 17:00 EDT weekly close", utc("2026-10-30T21:00:00Z"), WEEKEND, false, false),
            arguments("Sun 2026-11-01 05:30Z = 01:30 EDT (first pass) WEEKEND", utc("2026-11-01T05:30:00Z"), WEEKEND, false, false),
            arguments("Sun 2026-11-01 06:30Z = 01:30 EST (repeated hour) WEEKEND", utc("2026-11-01T06:30:00Z"), WEEKEND, false, false),
            arguments("Sun 2026-11-01 22:00Z = 17:00 EST still WEEKEND", utc("2026-11-01T22:00:00Z"), WEEKEND, false, false),
            arguments("Sun 2026-11-01 23:00Z = 18:00 EST weekly open", utc("2026-11-01T23:00:00Z"), PRE_ASIA, false, false),
            arguments("Mon 2026-11-02 13:30Z = 08:30 EST PRE_NY", utc("2026-11-02T13:30:00Z"), PRE_NY, false, false),
            arguments("Mon 2026-11-02 14:30Z = 09:30 EST NY_AM", utc("2026-11-02T14:30:00Z"), NY_AM, false, false),
            arguments("Mon 2026-11-02 20:45Z = 15:45 EST NO_ENTRY", utc("2026-11-02T20:45:00Z"), NO_ENTRY, false, false),
            arguments("Mon 2026-11-02 23:00Z = 18:00 EST PRE_ASIA", utc("2026-11-02T23:00:00Z"), PRE_ASIA, false, false),
            arguments("Mon 2026-11-02 07:00Z = 02:00 EST LONDON prime", utc("2026-11-02T07:00:00Z"), LONDON, true, false)
        );
    }

    @ParameterizedTest(name = "[{index}] {0} -> {2} prime={3} sb={4}")
    @MethodSource("table")
    void classifiesEveryBoundary(String name, Instant at, SessionWindow window,
                                 boolean prime, boolean silverBullet) {
        SessionClassifier.Classification c = SessionClassifier.classifyFull(at);
        assertThat(c.window()).as(name + " window").isEqualTo(window);
        assertThat(c.primeKillzone()).as(name + " prime").isEqualTo(prime);
        assertThat(c.silverBullet()).as(name + " silverBullet").isEqualTo(silverBullet);
        assertThat(SessionClassifier.classify(at)).isEqualTo(window);
        assertThat(SessionClassifier.isPrimeKillzone(at)).isEqualTo(prime);
        assertThat(c.blocksEntry()).as(name + " blocksEntry")
                .isEqualTo(window == NO_ENTRY || window == WEEKEND);
    }

    @Test
    @DisplayName("table has >= 40 instants")
    void tableSize() {
        assertThat(table().count()).isGreaterThanOrEqualTo(40);
    }

    @Test
    @DisplayName("G1: 2026-09-28 15:05 ET -> NY_PM, prime=true")
    void g1Instant() {
        Instant g1 = LocalDate.of(2026, 9, 28).atTime(15, 5).atZone(SessionClassifier.ET).toInstant();
        assertThat(g1).isEqualTo(Instant.parse("2026-09-28T19:05:00Z"));
        SessionClassifier.Classification c = SessionClassifier.classifyFull(g1);
        assertThat(c.window()).isEqualTo(SessionWindow.NY_PM);
        assertThat(c.primeKillzone()).isTrue();
        assertThat(c.blocksEntry()).isFalse();
    }

    @Test
    @DisplayName("NO_ENTRY is exactly 14:45-17:00 CT and CT = ET - 1h every minute of 2026")
    void noEntryIsTheCtBlockAllYear() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2027-01-01T00:00:00Z");
        while (t.isBefore(end)) {
            java.time.ZonedDateTime ct = t.atZone(SessionClassifier.CT);
            java.time.ZonedDateTime et = t.atZone(SessionClassifier.ET);
            if (SessionClassifier.classify(t) != WEEKEND) {
                // Only the DST transition hour (Sunday ~02:00, WEEKEND) breaks CT = ET - 1h.
                assertThat(et.toLocalDateTime().minusHours(1)).as(t.toString()).isEqualTo(ct.toLocalDateTime());
            }
            boolean ctBlock = !ct.toLocalTime().isBefore(LocalTime.of(14, 45))
                    && ct.toLocalTime().isBefore(LocalTime.of(17, 0))
                    && ct.getDayOfWeek() != java.time.DayOfWeek.SATURDAY
                    && ct.getDayOfWeek() != java.time.DayOfWeek.SUNDAY;
            if (ctBlock && ct.getDayOfWeek() != java.time.DayOfWeek.FRIDAY) {
                assertThat(SessionClassifier.classify(t)).as(t.toString()).isEqualTo(NO_ENTRY);
            }
            if (ctBlock) {
                assertThat(SessionClassifier.blocksEntry(t)).as(t.toString()).isTrue();
            }
            t = t.plusSeconds(60);
        }
    }

    @Test
    @DisplayName("KillzoneClock + SilverBulletClock routed through SessionClassifier keep their public answers")
    void legacyClocksUnchanged() {
        KillzoneClock kz = new KillzoneClock();
        SilverBulletClock sb = new SilverBulletClock();
        Instant t = Instant.parse("2026-09-21T00:00:00Z");
        for (int i = 0; i < 7 * 24 * 60; i++, t = t.plusSeconds(60)) {
            LocalTime et = t.atZone(SessionClassifier.ET).toLocalTime();
            boolean nyAm = !et.isBefore(LocalTime.of(9, 45)) && et.isBefore(LocalTime.of(12, 30));
            boolean nyPm = !et.isBefore(LocalTime.of(13, 45)) && et.isBefore(LocalTime.of(16, 0));
            boolean london = !et.isBefore(LocalTime.of(3, 0)) && et.isBefore(LocalTime.of(12, 0));
            boolean asia = !et.isBefore(LocalTime.of(19, 0)) || et.isBefore(LocalTime.of(4, 0));
            boolean sbw = (!et.isBefore(LocalTime.of(3, 0)) && et.isBefore(LocalTime.of(4, 0)))
                    || (!et.isBefore(LocalTime.of(10, 0)) && et.isBefore(LocalTime.of(11, 0)))
                    || (!et.isBefore(LocalTime.of(14, 0)) && et.isBefore(LocalTime.of(15, 0)));
            assertThat(kz.isInKillzone(t)).isEqualTo(nyAm || nyPm);
            assertThat(kz.isInLondonSession(t)).isEqualTo(london);
            assertThat(kz.isInAsianSession(t)).isEqualTo(asia);
            assertThat(sb.isInSilverBulletWindow(t)).isEqualTo(sbw);
        }
        assertThat(kz.getKillzoneName(Instant.parse("2026-09-28T14:00:00Z"))).isEqualTo("NY_AM_KILLZONE");
        assertThat(kz.getKillzoneName(Instant.parse("2026-09-28T07:00:00Z"))).isEqualTo("LONDON_SESSION");
        assertThat(kz.getKillzoneName(Instant.parse("2026-09-28T01:00:00Z"))).isEqualTo("ASIAN_SESSION");
        assertThat(kz.isTradingDay(Instant.parse("2026-09-27T12:00:00Z"))).isFalse();
        assertThat(ZoneOffset.UTC).isNotNull();
    }
}
