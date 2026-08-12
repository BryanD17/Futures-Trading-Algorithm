package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.TradingSessionCalendar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §S5 OPENING GAPS — Appendix W8, plus the holiday-shifted week that risk G-R7
 * is about.
 *
 * <p>All timestamps are built in {@code America/New_York} through
 * {@link TradingSessionCalendar#ET}, never as fixed UTC offsets, so these tests
 * would still pass either side of a DST switch.
 */
class IctLibOpeningGapTest {

    private final IctLibFixture.Harness h = IctLibFixture.harness();
    private int seq = 0;

    /** A 1m candle at an ET wall-clock time on a given date. */
    private Candle et(LocalDate date, int hour, int minute,
                      double o, double high, double low, double close) {
        Instant ts = ZonedDateTime.of(date, LocalTime.of(hour, minute),
                TradingSessionCalendar.ET).toInstant();
        seq++;
        return new Candle(IctLibFixture.SYM, ts, o, high, low, close, 100L);
    }

    private List<Detection> daily() {
        return h.registry.byType(DetectionType.OPENING_GAP_DAILY);
    }

    private List<Detection> weekly() {
        return h.registry.byType(DetectionType.OPENING_GAP_WEEKLY);
    }

    // Tuesday 2026-08-11 and its neighbours.
    private static final LocalDate MON = LocalDate.of(2026, 8, 10);
    private static final LocalDate TUE = LocalDate.of(2026, 8, 11);
    private static final LocalDate FRI_PREV = LocalDate.of(2026, 8, 7);

    @Test
    @DisplayName("W8 daily: 17:00 close 21020.0 → 18:00 open 21031.5 = NDOG [21020, 21031.5], mid 21025.75")
    void w8DailyGap() {
        // Session that closes at 16:59 ET on Monday.
        h.push(et(MON, 16, 58, 21018, 21022, 21016, 21019));
        h.push(et(MON, 16, 59, 21019, 21022, 21018, 21020.0));
        // 18:00 ET opens the NEXT session (Tuesday's).
        h.push(et(MON, 18, 0, 21031.5, 21034, 21030, 21033));

        List<Detection> gaps = daily();
        assertThat(gaps).hasSize(1);
        Detection g = gaps.get(0);
        assertThat(g.priceBottom()).isEqualTo(21020.0);
        assertThat(g.priceTop()).isEqualTo(21031.5);
        assertThat(g.meta()).containsEntry("midline", 21025.75);
        assertThat(g.meta()).containsEntry("sessionDate", TUE.toString());
        assertThat(g.direction()).isEqualTo(DetectionDirection.BULLISH);
        assertThat(g.state()).isEqualTo(DetectionState.ACTIVE);
    }

    @Test
    @DisplayName("W8 lifecycle: entering the zone TOUCHES it, and it never terminates on fill")
    void gapTouchesButNeverTerminates() {
        w8DailyGap();

        h.push(et(MON, 18, 1, 21033, 21035, 21027, 21029));
        assertThat(daily().get(0).state()).isEqualTo(DetectionState.TOUCHED);

        // Trade all the way through it — still TOUCHED, never FILLED: these are
        // persistent magnets, evicted by retention only.
        h.push(et(MON, 18, 2, 21029, 21032, 21012, 21015));
        Detection g = daily().get(0);
        assertThat(g.state()).isEqualTo(DetectionState.TOUCHED);
        assertThat(g.terminal()).isFalse();
    }

    @Test
    @DisplayName("Maintenance break (17:00–18:00 ET) supplies neither a close nor an open")
    void maintenanceBreakIsNotASession() {
        h.push(et(MON, 16, 59, 21019, 21022, 21018, 21020.0));
        // A stray bar inside the break must not become the session's close…
        h.push(et(MON, 17, 30, 21100, 21101, 21099, 21100));
        h.push(et(MON, 18, 0, 21031.5, 21034, 21030, 21033));

        // …so the gap still spans 21020.0 → 21031.5, not 21100 → 21031.5.
        assertThat(daily()).hasSize(1);
        assertThat(daily().get(0).priceBottom()).isEqualTo(21020.0);
        assertThat(daily().get(0).priceTop()).isEqualTo(21031.5);
    }

    @Test
    @DisplayName("HOLIDAY MONDAY: the weekly gap keys on the first SESSION that trades, not on the calendar Monday")
    void holidayMondayWeeklyGap() {
        // Previous week's final session closes Friday at 21000.0.
        h.push(et(FRI_PREV, 15, 58, 20995, 21002, 20994, 20999));
        h.push(et(FRI_PREV, 16, 0, 20999, 21003, 20998, 21000.0));

        // Monday is a holiday: no Sunday-evening open, no Monday session at
        // all. The week's first candles arrive Tuesday morning.
        h.push(et(TUE, 9, 30, 21040.0, 21045, 21038, 21044));

        List<Detection> wk = weekly();
        assertThat(wk).hasSize(1);
        assertThat(wk.get(0).priceBottom()).isEqualTo(21000.0);
        assertThat(wk.get(0).priceTop()).isEqualTo(21040.0);
        assertThat(wk.get(0).meta()).containsEntry("midline", 21020.0);
        assertThat(wk.get(0).meta()).containsEntry("sessionDate", TUE.toString());

        // The daily gap forms on the same boundary, spanning the same prices.
        assertThat(daily()).hasSize(1);
        assertThat(daily().get(0).priceTop()).isEqualTo(21040.0);
    }

    @Test
    @DisplayName("A session boundary INSIDE one week creates only a daily gap")
    void midWeekBoundaryIsDailyOnly() {
        h.push(et(MON, 9, 30, 21000, 21005, 20998, 21002));      // Monday session
        h.push(et(MON, 18, 0, 21010, 21012, 21008, 21011));      // Tuesday session opens
        assertThat(daily()).hasSize(1);
        assertThat(weekly()).isEmpty();
    }

    @Test
    @DisplayName("No untraded band (open == previous close) → no detection at all")
    void zeroWidthGapIsNotADetection() {
        h.push(et(MON, 16, 59, 21019, 21022, 21018, 21020.0));
        h.push(et(MON, 18, 0, 21020.0, 21024, 21019, 21023));
        assertThat(daily()).isEmpty();
    }

    @Test
    @DisplayName("Retention: at most 2 daily and 3 weekly gaps survive")
    void retentionCaps() {
        double price = 21000;
        for (int d = 0; d < 12; d++) {
            LocalDate day = MON.plusDays(d);
            h.push(et(day, 9, 30, price, price + 4, price - 4, price + 1));
            price += 25;
            h.push(et(day, 18, 0, price, price + 4, price - 4, price + 1));
            price += 25;
        }
        assertThat(h.registry.count(DetectionType.OPENING_GAP_DAILY)).isLessThanOrEqualTo(2);
        assertThat(h.registry.count(DetectionType.OPENING_GAP_WEEKLY)).isLessThanOrEqualTo(3);
    }
}
