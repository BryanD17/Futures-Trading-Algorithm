package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.MarketBias;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 Agent 05.6 — {@code bias.range.window} on synthetic 1m bars.
 *
 * <p>Fixture (a G2-shaped day, prices around 20,000, tick 0.25, minLeg 400
 * ticks = 100 pt, minRangePct 0.08 % = 16 pt):
 * <pre>
 *   Thu 18:00  open 20000 → 19:00 low 19900 → Fri 03:00 HIGH 20100 (overnight)
 *   03:00 → 09:29 drift to 20000
 *   09:30 → 09:40 rally to 20080 (RTH HH; 80 pt &lt; the 100 pt minimum)
 *   09:40 → 10:00 drop to 19950 (RTH LL; leg 130 pt ≥ 100 → BEARISH)
 *   Fri 18:00  next trading day (Asia) bar
 * </pre>
 * SESSION_DAY reads BULLISH [19900, 20100] all day (the overnight high is
 * the most recent extreme); RTH_FIRST / AUTO read the RTH impulse
 * HH 20080 → LL 19950, BEARISH, from the bar the leg reaches 100 pt.
 */
@DisplayName("V5 Agent 05.6 — dealing-range window SESSION_DAY | RTH_FIRST | AUTO (synthetic)")
class DealingRangeWindowTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final double MIN_LEG = 400 * 0.25;

    @AfterEach
    void clearProps() {
        System.clearProperty("bias.range.window");
        System.clearProperty("bias.range.minLegTicks");
        System.clearProperty("bias.range.minLegTicks.MGC");
    }

    private static Instant et(String iso) {
        return LocalDateTime.parse(iso).atZone(ET).toInstant();
    }

    /** Linear 1m bars from {@code from} (exclusive price) to {@code to}. */
    private static void walk(List<Candle> out, LocalDateTime start, int minutes, double from, double to) {
        double prev = from;
        for (int i = 1; i <= minutes; i++) {
            double px = from + (to - from) * i / minutes;
            Instant t = start.plusMinutes(i - 1).atZone(ET).toInstant();
            out.add(new Candle("MNQ", t, prev, Math.max(prev, px), Math.min(prev, px), px, 100));
            prev = px;
        }
    }

    /** The fixture bars, in time order, keyed for lookup by ET minute. */
    private static List<Candle> fixture() {
        List<Candle> b = new ArrayList<>();
        walk(b, LocalDateTime.parse("2026-09-24T18:00"), 60, 20000, 19900);   // 18:00-18:59 → 19900
        walk(b, LocalDateTime.parse("2026-09-24T19:00"), 480, 19900, 20100);  // → 20100 at 02:59
        walk(b, LocalDateTime.parse("2026-09-25T03:00"), 390, 20100, 20000);  // → 20000 at 09:29
        walk(b, LocalDateTime.parse("2026-09-25T09:30"), 10, 20000, 20080);   // 09:30-09:39 → 20080
        walk(b, LocalDateTime.parse("2026-09-25T09:40"), 20, 20080, 19950);   // 09:40-09:59 → 19950
        walk(b, LocalDateTime.parse("2026-09-25T10:00"), 30, 19950, 19990);   // pullback, inside
        walk(b, LocalDateTime.parse("2026-09-25T18:00"), 5, 19990, 19995);    // next trading day (Asia)
        return b;
    }

    private static DealingRangeTracker tracker(DealingRangeTracker.Window w) {
        return new DealingRangeTracker(0.08, 0.5, w, MIN_LEG);
    }

    /** Snapshot after feeding every fixture bar up to and including {@code at}. */
    private static DealingRangeTracker.Snapshot at(DealingRangeTracker.Window w, String at) {
        DealingRangeTracker t = tracker(w);
        Instant stop = et(at);
        for (Candle c : fixture()) {
            if (c.getTimestamp().isAfter(stop)) break;
            t.onCandle(c);
        }
        return t.snapshot();
    }

    @Test
    @DisplayName("SESSION_DAY: the overnight extremes govern all day (Agent 03 behaviour, A/B)")
    void sessionDay() {
        DealingRangeTracker.Snapshot s = at(DealingRangeTracker.Window.SESSION_DAY, "2026-09-25T10:29");
        System.out.println("[A-05.6] SESSION_DAY @10:29 " + s);
        assertThat(s.direction()).isEqualTo(MarketBias.BULLISH);
        assertThat(s.high()).isEqualTo(20100.0);
        assertThat(s.low()).isEqualTo(19900.0);
        assertThat(s.decisive()).isTrue();
        // The legacy 2-arg constructor is SESSION_DAY.
        assertThat(new DealingRangeTracker(0.08, 0.5).window()).isEqualTo(DealingRangeTracker.Window.SESSION_DAY);
    }

    @Test
    @DisplayName("all windows identical before 09:30 ET (Asia / London / pre-NY unaffected)")
    void identicalBeforeRthOpen() {
        for (String t : new String[] {"2026-09-24T20:00", "2026-09-25T03:30", "2026-09-25T09:29"}) {
            DealingRangeTracker.Snapshot sd = at(DealingRangeTracker.Window.SESSION_DAY, t);
            assertThat(at(DealingRangeTracker.Window.RTH_FIRST, t)).isEqualTo(sd);
            assertThat(at(DealingRangeTracker.Window.AUTO, t)).isEqualTo(sd);
        }
    }

    @Test
    @DisplayName("AUTO: session-day range until the RTH leg reaches minLegTicks, then the RTH impulse")
    void auto() {
        DealingRangeTracker.Snapshot early = at(DealingRangeTracker.Window.AUTO, "2026-09-25T09:39");
        assertThat(early).isEqualTo(at(DealingRangeTracker.Window.SESSION_DAY, "2026-09-25T09:39"));
        DealingRangeTracker.Snapshot s = at(DealingRangeTracker.Window.AUTO, "2026-09-25T10:29");
        System.out.println("[A-05.6] AUTO @09:39 " + early + " | @10:29 " + s);
        assertThat(s.direction()).isEqualTo(MarketBias.BEARISH);
        assertThat(s.high()).isEqualTo(20080.0);
        assertThat(s.low()).isEqualTo(19950.0);
        assertThat(s.equilibrium()).isEqualTo(20015.0);
        assertThat(s.decisive()).isTrue();
    }

    @Test
    @DisplayName("RTH_FIRST: RTH extremes from 09:30 (non-decisive below the minimum), then the RTH impulse")
    void rthFirst() {
        DealingRangeTracker.Snapshot early = at(DealingRangeTracker.Window.RTH_FIRST, "2026-09-25T09:39");
        System.out.println("[A-05.6] RTH_FIRST @09:39 " + early);
        assertThat(early.high()).isEqualTo(20080.0);
        assertThat(early.low()).isEqualTo(20000.0);
        assertThat(early.decisive()).isFalse();
        assertThat(early.direction()).isEqualTo(MarketBias.BULLISH); // carried from the session day
        DealingRangeTracker.Snapshot s = at(DealingRangeTracker.Window.RTH_FIRST, "2026-09-25T10:29");
        assertThat(s).isEqualTo(at(DealingRangeTracker.Window.AUTO, "2026-09-25T10:29"));
    }

    @Test
    @DisplayName("the leg becomes decisive on the bar it reaches minLegTicks, not before")
    void minimumLegBoundary() {
        // The 09:40-09:59 bars step 6.5 pt down from 20080: the 09:54 bar's low
        // 19982.50 spans 97.5 pt (< 100), the 09:55 bar's low 19976.00 spans 104.
        assertThat(at(DealingRangeTracker.Window.AUTO, "2026-09-25T09:54").direction()).isEqualTo(MarketBias.BULLISH);
        assertThat(at(DealingRangeTracker.Window.AUTO, "2026-09-25T09:55").direction()).isEqualTo(MarketBias.BEARISH);
    }

    @Test
    @DisplayName("the RTH leg resets at the 18:00 ET roll: the next Asia session uses the session-day range")
    void resetsAtRoll() {
        DealingRangeTracker.Snapshot sd = at(DealingRangeTracker.Window.SESSION_DAY, "2026-09-25T18:04");
        assertThat(at(DealingRangeTracker.Window.AUTO, "2026-09-25T18:04")).isEqualTo(sd);
        assertThat(at(DealingRangeTracker.Window.RTH_FIRST, "2026-09-25T18:04")).isEqualTo(sd);
    }

    @Test
    @DisplayName("config: bias.range.window default AUTO, override + invalid fallback; minLegTicks per symbol")
    void config() {
        assertThat(BiasConfig.rangeWindow()).isEqualTo(DealingRangeTracker.Window.AUTO);
        assertThat(BiasConfig.rangeMinLegTicks("MNQ")).isEqualTo(400);
        System.setProperty("bias.range.window", "session_day");
        assertThat(BiasConfig.rangeWindow()).isEqualTo(DealingRangeTracker.Window.SESSION_DAY);
        System.setProperty("bias.range.window", "bogus");
        assertThat(BiasConfig.rangeWindow()).isEqualTo(BiasConfig.DEFAULT_RANGE_WINDOW);
        System.setProperty("bias.range.minLegTicks", "300");
        System.setProperty("bias.range.minLegTicks.MGC", "120");
        assertThat(BiasConfig.rangeMinLegTicks("MNQ")).isEqualTo(300);
        assertThat(BiasConfig.rangeMinLegTicks("MGC")).isEqualTo(120);
        System.setProperty("bias.range.window", "RTH_FIRST");
        assertThat(DealingRangeTracker.fromConfig("MNQ", 0.25).window())
                .isEqualTo(DealingRangeTracker.Window.RTH_FIRST);
    }
}
