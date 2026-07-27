package com.topstep.trading.strategy;

import com.topstep.trading.chart.ChartEngine;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.BarAggregationManager.SeedResult;
import com.topstep.trading.strategy.BarAggregationManager.Timeframe;
import com.topstep.trading.strategy.stdvote.StdvOteRunnerStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3 Agent 04 — H4/D1 session-aware bucketing (CME 18:00 ET trading day),
 * the two-tier HTF seeding API, and the ISOLATION guarantee (Critical
 * Rule 8: seeding never touches the 1m listener-path state).
 */
@DisplayName("H4/D1 ladder + HTF seeding (V3 Agent 04)")
class BarAggregationHtfTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static Instant et(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ET).toInstant();
    }

    private static Candle c1m(Instant ts, double px) {
        return new Candle("MNQ", ts, px, px + 1, px - 1, px, 100);
    }

    // ── Session calendar: DST, Sunday open, maintenance, Friday close ────

    @Test
    @DisplayName("DST spring-forward: ET wall-clock H4 anchors hold; UTC offset shifts")
    void dstSpringForwardAnchors() {
        // Friday before the 2026-03-08 spring-forward: 02:00 anchor is EST.
        Instant friBucket = TradingSessionCalendar.h4BucketStart(et(2026, 3, 6, 3, 30));
        assertThat(friBucket).isEqualTo(et(2026, 3, 6, 2, 0));
        assertThat(friBucket).isEqualTo(Instant.parse("2026-03-06T07:00:00Z")); // EST = UTC-5

        // Monday after: same 02:00 ET wall-clock anchor, now EDT (UTC-4).
        Instant monBucket = TradingSessionCalendar.h4BucketStart(et(2026, 3, 9, 3, 30));
        assertThat(monBucket).isEqualTo(et(2026, 3, 9, 2, 0));
        assertThat(monBucket).isEqualTo(Instant.parse("2026-03-09T06:00:00Z")); // EDT = UTC-4

        // The gap day itself (02:00 does not exist on 2026-03-08): both the
        // 03:30 and 05:59 candles resolve to the SAME (gap-shifted) bucket.
        Instant a = TradingSessionCalendar.h4BucketStart(et(2026, 3, 8, 3, 30));
        Instant b = TradingSessionCalendar.h4BucketStart(et(2026, 3, 8, 5, 59));
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("DST fall-back: 22:00 anchor absorbs the repeated hour; wall-clock holds")
    void dstFallBackAnchors() {
        // Friday before the 2026-11-01 fall-back (EDT, UTC-4).
        assertThat(TradingSessionCalendar.h4BucketStart(et(2026, 10, 30, 3, 30)))
                .isEqualTo(Instant.parse("2026-10-30T06:00:00Z"));
        // Monday after (EST, UTC-5) — same ET wall-clock anchor.
        assertThat(TradingSessionCalendar.h4BucketStart(et(2026, 11, 2, 3, 30)))
                .isEqualTo(Instant.parse("2026-11-02T07:00:00Z"));
        // 01:30 on the fall-back morning belongs to the previous 22:00 bucket.
        assertThat(TradingSessionCalendar.h4BucketStart(et(2026, 11, 1, 1, 30)))
                .isEqualTo(et(2026, 10, 31, 22, 0));
    }

    @Test
    @DisplayName("Sunday 18:00 ET opens MONDAY's session; Friday closes its own")
    void sundayAndFridayAttribution() {
        // Sunday 2026-06-21 18:30 ET -> Monday's daily bar.
        assertThat(TradingSessionCalendar.sessionDate(et(2026, 6, 21, 18, 30)))
                .isEqualTo(LocalDate.of(2026, 6, 22));
        assertThat(TradingSessionCalendar.d1BucketStart(et(2026, 6, 21, 18, 30)))
                .isEqualTo(et(2026, 6, 21, 18, 0));
        // Monday 19:30 ET -> TUESDAY's daily bar (session-date keying).
        assertThat(TradingSessionCalendar.sessionDate(et(2026, 6, 22, 19, 30)))
                .isEqualTo(LocalDate.of(2026, 6, 23));
        // Friday 16:30 ET is still Friday's session (closes 17:00).
        assertThat(TradingSessionCalendar.sessionDate(et(2026, 6, 26, 16, 30)))
                .isEqualTo(LocalDate.of(2026, 6, 26));
        assertThat(TradingSessionCalendar.d1BucketStart(et(2026, 6, 26, 16, 30)))
                .isEqualTo(et(2026, 6, 25, 18, 0));
    }

    @Test
    @DisplayName("maintenance hour (17:00-18:00 ET) belongs to NO D1/H4 bucket")
    void maintenanceHourExcluded() {
        BarAggregationManager mgr = new BarAggregationManager("MNQ", 500);
        mgr.processCandle(c1m(et(2026, 6, 24, 16, 58), 20000));
        mgr.processCandle(c1m(et(2026, 6, 24, 16, 59), 20005));
        // Stray bar inside the break: must not extend or complete anything.
        Candle stray = c1m(et(2026, 6, 24, 17, 30), 99999);
        var completed = mgr.processCandle(stray);
        assertThat(completed.containsKey(Timeframe.D1)).isFalse();
        assertThat(completed.containsKey(Timeframe.H4)).isFalse();
        // First bar of the new session completes the OLD session's bucket —
        // untouched by the stray bar's absurd price.
        var afterOpen = mgr.processCandle(c1m(et(2026, 6, 24, 18, 1), 20010));
        Candle d1 = afterOpen.get(Timeframe.D1);
        assertThat(d1).isNotNull();
        assertThat(d1.getTimestamp()).isEqualTo(et(2026, 6, 23, 18, 0));
        assertThat(d1.getClose()).isEqualTo(20005.0);
        assertThat(d1.getHigh()).isLessThan(30000.0);
    }

    @Test
    @DisplayName("D1 completion at the session boundary carries the full session OHLC")
    void d1SessionComposition() {
        BarAggregationManager mgr = new BarAggregationManager("MNQ", 500);
        // Wednesday session = Tue 18:00 -> Wed 17:00.
        mgr.processCandle(c1m(et(2026, 6, 23, 18, 0), 20000));  // session open
        mgr.processCandle(c1m(et(2026, 6, 24, 9, 30), 20100));  // NY morning
        mgr.processCandle(c1m(et(2026, 6, 24, 16, 59), 20050)); // last bar
        var out = mgr.processCandle(c1m(et(2026, 6, 24, 18, 0), 20060)); // Thursday opens
        Candle d1 = out.get(Timeframe.D1);
        assertThat(d1).isNotNull();
        assertThat(d1.getTimestamp()).isEqualTo(et(2026, 6, 23, 18, 0));
        assertThat(d1.getOpen()).isEqualTo(20000.0);
        assertThat(d1.getHigh()).isEqualTo(20101.0);
        assertThat(d1.getLow()).isEqualTo(19999.0);
        assertThat(d1.getClose()).isEqualTo(20050.0);
    }

    // ── Seeding API ──────────────────────────────────────────────────────

    /** Hourly H1 bars [startInclusive, endExclusive), skipping 17:00 ET. */
    private static List<Candle> h1Series(Instant start, Instant end, double px) {
        List<Candle> out = new ArrayList<>();
        for (Instant t = start; t.isBefore(end); t = t.plus(1, ChronoUnit.HOURS)) {
            if (t.atZone(ET).getHour() == 17) continue;
            out.add(new Candle("MNQ", t, px, px + 2, px - 2, px + 1, 1000));
        }
        return out;
    }

    @Test
    @DisplayName("seed -> live seam: seeded sessions precede, live extends, no duplicates")
    void seedThenLiveSeam() {
        BarAggregationManager mgr = new BarAggregationManager("MNQ", 500);
        // Seed Mon 18:00 ET -> Thu 08:00 ET (sessions Tue, Wed, partial Thu).
        SeedResult res = mgr.seedHigherTimeframe(
                h1Series(et(2026, 6, 22, 18, 0), et(2026, 6, 25, 8, 0), 20000));
        assertThat(res.d1Derived()).isEqualTo(3);
        assertThat(res.h1Seeded()).isGreaterThan(50);
        assertThat(res.refused()).isZero();

        // Live 1m resumes inside Thursday's session, then Friday opens.
        mgr.processCandle(c1m(et(2026, 6, 25, 9, 30), 20500));
        mgr.processCandle(c1m(et(2026, 6, 25, 10, 0), 20510));
        mgr.processCandle(c1m(et(2026, 6, 25, 18, 5), 20520));

        List<Candle> d1 = mgr.getCandlesSnapshot(Timeframe.D1, 100);
        // Tue, Wed, Thu — strictly ascending, unique period starts.
        assertThat(d1).hasSize(3);
        for (int i = 1; i < d1.size(); i++) {
            assertThat(d1.get(i).getTimestamp()).isAfter(d1.get(i - 1).getTimestamp());
        }
        // The Thursday bucket in the SERIES merged seed + live: seed's
        // session open kept, live's high and close taken in.
        Candle thursday = d1.get(2);
        assertThat(thursday.getTimestamp()).isEqualTo(et(2026, 6, 24, 18, 0));
        assertThat(thursday.getOpen()).isEqualTo(20000.0);   // seeded session open
        assertThat(thursday.getHigh()).isEqualTo(20511.0);   // live 1m high
        assertThat(thursday.getClose()).isEqualTo(20510.0);  // last live bar of session
    }

    @Test
    @DisplayName("seed de-dup + watermark refusal: live-era bars never overwritten")
    void seedDedupAndWatermarkRefusal() {
        BarAggregationManager mgr = new BarAggregationManager("MNQ", 500);
        // Live first — watermark at Thu 10:00.
        mgr.processCandle(c1m(et(2026, 6, 25, 9, 59), 20500));
        mgr.processCandle(c1m(et(2026, 6, 25, 10, 0), 20510));

        List<Candle> seed = new ArrayList<>(
                h1Series(et(2026, 6, 24, 18, 0), et(2026, 6, 25, 8, 0), 20000));
        seed.add(seed.get(seed.size() - 1)); // duplicate timestamp
        seed.add(new Candle("MNQ", et(2026, 6, 25, 12, 0), 1, 2, 0, 1, 1)); // AFTER watermark

        SeedResult res = mgr.seedHigherTimeframe(seed);
        assertThat(res.refused()).isEqualTo(2);
        // The live path owns Thursday's D1 bucket (its first live bucket) —
        // the seeded partial-Thursday bucket must NOT displace or precede it
        // as a duplicate: no two D1 entries share a period start.
        List<Candle> d1 = mgr.getCandlesSnapshot(Timeframe.D1, 100);
        long distinct = d1.stream().map(Candle::getTimestamp).distinct().count();
        assertThat(distinct).isEqualTo(d1.size());
    }

    @Test
    @DisplayName("ring capacity: >= 540 H4 and >= 90 D1 bars at runner settings")
    void ringCapacity() {
        BarAggregationManager mgr = new BarAggregationManager("MNQ", 500);
        assertThat(mgr.capacityFor(Timeframe.H4)).isGreaterThanOrEqualTo(540);
        assertThat(mgr.capacityFor(Timeframe.D1)).isGreaterThanOrEqualTo(90);
        assertThat(mgr.capacityFor(Timeframe.M15)).isEqualTo(500); // unchanged
    }

    @Test
    @DisplayName("determinism: identical feed + seed -> identical D1/H4 series")
    void determinism() {
        BarAggregationManager a = new BarAggregationManager("MNQ", 500);
        BarAggregationManager b = new BarAggregationManager("MNQ", 500);
        List<Candle> seed = h1Series(et(2026, 6, 22, 18, 0), et(2026, 6, 25, 8, 0), 20000);
        a.seedHigherTimeframe(seed);
        b.seedHigherTimeframe(seed);
        for (int i = 0; i < 120; i++) {
            Candle c = c1m(et(2026, 6, 25, 9, 30).plus(i, ChronoUnit.MINUTES), 20500 + i);
            a.processCandle(c);
            b.processCandle(c);
        }
        assertThat(toStrings(a.getCandlesSnapshot(Timeframe.D1, 100)))
                .isEqualTo(toStrings(b.getCandlesSnapshot(Timeframe.D1, 100)));
        assertThat(toStrings(a.getCandlesSnapshot(Timeframe.H4, 1000)))
                .isEqualTo(toStrings(b.getCandlesSnapshot(Timeframe.H4, 1000)));
    }

    private static List<String> toStrings(List<Candle> candles) {
        List<String> out = new ArrayList<>();
        for (Candle c : candles) {
            out.add(c.getTimestamp() + "|" + c.getOpen() + "|" + c.getHigh()
                    + "|" + c.getLow() + "|" + c.getClose() + "|" + c.getVolume());
        }
        return out;
    }

    // ── ISOLATION (Critical Rule 8 — the load-bearing test) ─────────────

    @Test
    @DisplayName("ISOLATION: seeding touches no 1m-path state (chart, 1m/15m/30m series)")
    void seedingIsolation() {
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy("MNQ", "MES", new EventBus());
        ChartEngine chart = new ChartEngine();
        chart.registerInstrument("MNQ", 0.25);
        s.setChartEngine(chart);

        // Live 1m feed (listener path): runner + chart engine, as the tap does.
        Instant t0 = et(2026, 6, 25, 9, 30);
        for (int i = 0; i < 45; i++) {
            Candle c = c1m(t0.plus(i, ChronoUnit.MINUTES), 20000 + i);
            s.onCandle(c, null);
            chart.onCandle(c);
        }

        BarAggregationManager mgr = HtfSeriesRegistry.get("MNQ").orElseThrow();
        long chartBarsBefore = chart.snapshot("MNQ", 10).oneMinuteBarsIngested();
        List<String> m1Before = toStrings(mgr.getCandlesSnapshot(Timeframe.M1, 5000));
        List<String> m15Before = toStrings(mgr.getCandlesSnapshot(Timeframe.M15, 5000));
        List<String> m30Before = toStrings(mgr.getCandlesSnapshot(Timeframe.M30, 5000));
        int d1Before = mgr.getCandlesSnapshot(Timeframe.D1, 1000).size();

        // Seed 10 days of H1 through the SEEDING API only.
        SeedResult res = mgr.seedHigherTimeframe(
                h1Series(et(2026, 6, 15, 18, 0), et(2026, 6, 25, 8, 0), 19800));
        assertThat(res.d1Derived()).isGreaterThan(0);

        // The 1m pipeline state is BYTE-IDENTICAL: chart ingest count and
        // the 1m/15m/30m series are untouched; only H4/D1 depth grew.
        assertThat(chart.snapshot("MNQ", 10).oneMinuteBarsIngested())
                .isEqualTo(chartBarsBefore);
        assertThat(toStrings(mgr.getCandlesSnapshot(Timeframe.M1, 5000))).isEqualTo(m1Before);
        assertThat(toStrings(mgr.getCandlesSnapshot(Timeframe.M15, 5000))).isEqualTo(m15Before);
        assertThat(toStrings(mgr.getCandlesSnapshot(Timeframe.M30, 5000))).isEqualTo(m30Before);
        assertThat(mgr.getCandlesSnapshot(Timeframe.D1, 1000).size())
                .isGreaterThan(d1Before);
    }

    // ── SIM tier-2 generator sanity ──────────────────────────────────────

    @Test
    @DisplayName("SimWarmBoot.generateHourly: deterministic, no maintenance/weekend bars")
    void syntheticHourlyGenerator() {
        Instant end = et(2026, 6, 26, 12, 0);
        List<Candle> a = com.topstep.trading.connector.SimWarmBoot
                .generateHourly("MNQ", 20000, 30, 42L, end);
        List<Candle> b = com.topstep.trading.connector.SimWarmBoot
                .generateHourly("MNQ", 20000, 30, 42L, end);
        assertThat(toStrings(a)).isEqualTo(toStrings(b)); // deterministic
        assertThat(a.size()).isGreaterThan(400);
        for (Candle c : a) {
            ZonedDateTime z = c.getTimestamp().atZone(ET);
            assertThat(z.getHour()).isNotEqualTo(17);
            assertThat(z.getDayOfWeek()).isNotEqualTo(java.time.DayOfWeek.SATURDAY);
        }
        // Final close anchors to the base price (seam with the 1m tier).
        assertThat(a.get(a.size() - 1).getClose()).isEqualTo(20000.0);
    }
}
