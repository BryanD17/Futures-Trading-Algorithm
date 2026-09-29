package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.MarketBias;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 Agent 05.7 — the dealing range CARRIES across the 18:00 ET reopen
 * ({@code bias.range.carryAcrossReopen}) until the new session prints an
 * impulse leg of {@code bias.range.minLegTicks} (MNQ 400 ticks = 100 pt).
 *
 * <p>Fixture = the LIVE PRAC defect of 2026-09-28 (log
 * engine-live-20260928-164649.log), MNQ, tick 0.25, minRangePct 0.08 %:
 * <pre>
 *   Sun 18:00 → Mon 09:29  overnight 30700 → 30680 → 30700 (inside the RTH range)
 *   09:30 → 09:54  rally to 30759.25 (RTH HH)
 *   09:55 → 12:29  drop to 30356.75 (RTH LL; impulse 402.5 pt → BEARISH)
 *   12:30 → 14:52  retrace to 30640 (no new low after it: no re-anchor)
 *   14:53 → 16:59  close weak at 30537
 *   Mon 18:00 → 18:59  REOPEN: the 78.25-pt up-move 30537.00 → 30615.25
 *   19:00 → 19:29  down to 30521.50 (93.75 pt below the micro high: &lt; 100)
 *   19:30 → 20:59  down to 30400 (the Asia impulse reaches 100 pt at 30515.25)
 * </pre>
 * Flag false (A/B) reproduces the live read: {@code BULLISH[30537.0-30615.25]}
 * for the first hour, then {@code BEARISH[30521.5-30615.25]}. Flag true keeps
 * {@code BEARISH[30356.75-30759.25]} until the ≥ 100-pt Asia leg forms.
 */
@DisplayName("V5 Agent 05.7 — dealing range carries across the 18:00 ET reopen")
class DealingRangeCarryTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final double TICK = 0.25;
    private static final double MIN_LEG = 400 * TICK;

    @AfterEach
    void clearProps() {
        System.clearProperty("bias.range.carryAcrossReopen");
        System.clearProperty("bias.range.window");
    }

    /** Linear 1m bars from {@code from} to {@code to} (the last bar closes at {@code to}). */
    private static void walk(List<Candle> out, String start, int minutes, double from, double to) {
        LocalDateTime s = LocalDateTime.parse(start);
        double prev = from;
        for (int i = 1; i <= minutes; i++) {
            double px = from + (to - from) * i / minutes;
            out.add(new Candle("MNQ", s.plusMinutes(i - 1).atZone(ET).toInstant(),
                    prev, Math.max(prev, px), Math.min(prev, px), px, 100));
            prev = px;
        }
    }

    /** Sunday-evening open through the Monday 16:59 close (the RTH session of G1). */
    private static List<Candle> monday() {
        List<Candle> b = new ArrayList<>();
        walk(b, "2026-09-27T18:00", 60, 30700.00, 30680.00);
        walk(b, "2026-09-27T19:00", 869, 30680.00, 30700.00);   // → 09:28
        walk(b, "2026-09-28T09:29", 1, 30700.00, 30700.00);
        walk(b, "2026-09-28T09:30", 25, 30700.00, 30759.25);   // RTH HH 09:54
        walk(b, "2026-09-28T09:55", 155, 30759.25, 30356.75);  // RTH LL 12:29
        walk(b, "2026-09-28T12:30", 143, 30356.75, 30640.00);  // retrace → 14:52
        walk(b, "2026-09-28T14:53", 127, 30640.00, 30537.00);  // weak close 16:59
        return b;
    }

    /** Tonight's reopen: the 78-pt micro up-move, then the Asia down-leg. */
    private static List<Candle> reopen() {
        List<Candle> b = new ArrayList<>();
        walk(b, "2026-09-28T18:00", 60, 30537.00, 30615.25);   // → 18:59
        walk(b, "2026-09-28T19:00", 30, 30615.25, 30521.50);   // → 19:29
        walk(b, "2026-09-28T19:30", 90, 30521.50, 30400.00);   // → 20:59
        return b;
    }

    private static DealingRangeTracker tracker(DealingRangeTracker.Window w, boolean carry) {
        return new DealingRangeTracker(0.08, 0.5, w, MIN_LEG, carry);
    }

    private static List<Candle> tape() {
        List<Candle> all = monday();
        all.addAll(reopen());
        return all;
    }

    /** Feed {@code bars} up to and including ET minute {@code at}; return the snapshot. */
    private static DealingRangeTracker.Snapshot feed(DealingRangeTracker t, List<Candle> bars, String at) {
        java.time.Instant stop = LocalDateTime.parse(at).atZone(ET).toInstant();
        for (Candle c : bars) {
            if (c.getTimestamp().isAfter(stop)) break;
            t.onCandle(c);
        }
        return t.snapshot();
    }

    @Test
    @DisplayName("close of the RTH session: BEARISH [30356.75, 30759.25] in every mode")
    void rthCloseIsBearish() {
        for (boolean carry : new boolean[] {true, false}) {
            DealingRangeTracker.Snapshot s = feed(tracker(DealingRangeTracker.Window.AUTO, carry), tape(), "2026-09-28T16:59");
            assertThat(s.direction()).isEqualTo(MarketBias.BEARISH);
            assertThat(s.high()).isEqualTo(30759.25);
            assertThat(s.low()).isEqualTo(30356.75);
        }
    }

    @Test
    @DisplayName("LIVE 2026-09-28 reopen: the 78-pt up-move keeps BEARISH [30356.75, 30759.25] until a ≥100-pt Asia leg")
    void reopenCarriesTheRthRange() {
        List<Candle> bars = tape();
        StringBuilder log = new StringBuilder();
        for (String at : new String[] {"2026-09-28T18:00", "2026-09-28T18:30", "2026-09-28T18:59",
                "2026-09-28T19:29"}) {
            DealingRangeTracker t = tracker(DealingRangeTracker.Window.AUTO, true);
            DealingRangeTracker.Snapshot s = feed(t, bars, at);
            log.append("\n[A-05.7] carry=true  @").append(at.substring(11)).append(' ').append(s);
            assertThat(s.direction()).as("bias at %s", at).isEqualTo(MarketBias.BEARISH);
            assertThat(s.high()).as("high at %s", at).isEqualTo(30759.25);
            assertThat(s.low()).as("low at %s", at).isEqualTo(30356.75);
            assertThat(s.decisive()).isTrue();
            assertThat(s.equilibrium()).isEqualTo(30558.0);
            assertThat(t.carryingPreviousRange()).isTrue();
            assertThat(t.direction()).isEqualTo(MarketBias.BEARISH);
        }
        System.out.println(log);
    }

    @Test
    @DisplayName("A/B carryAcrossReopen=false reproduces the live bullish micro-range read")
    void flagOffReproducesLiveBullishRead() {
        List<Candle> bars = tape();
        DealingRangeTracker.Snapshot s1859 = feed(tracker(DealingRangeTracker.Window.AUTO, false), bars, "2026-09-28T18:59");
        DealingRangeTracker.Snapshot s1929 = feed(tracker(DealingRangeTracker.Window.AUTO, false), bars, "2026-09-28T19:29");
        System.out.println("[A-05.7] carry=false @18:59 " + s1859 + "\n[A-05.7] carry=false @19:29 " + s1929);
        assertThat(s1859.direction()).isEqualTo(MarketBias.BULLISH);
        assertThat(s1859.low()).isEqualTo(30537.00);
        assertThat(s1859.high()).isEqualTo(30615.25);
        assertThat(s1929.direction()).isEqualTo(MarketBias.BEARISH);
        assertThat(s1929.low()).isEqualTo(30521.50);
        assertThat(s1929.high()).isEqualTo(30615.25);
        // The legacy / 4-arg constructors keep the flag off.
        assertThat(new DealingRangeTracker(0.08, 0.5, DealingRangeTracker.Window.AUTO, MIN_LEG).carryAcrossReopen()).isFalse();
        assertThat(new DealingRangeTracker().carryAcrossReopen()).isFalse();
    }

    @Test
    @DisplayName("hand-over: the bar the Asia leg reaches 100 pt the range becomes exactly the flag-off range")
    void handsOverOnTheNewImpulse() {
        List<Candle> bars = tape();
        DealingRangeTracker on = tracker(DealingRangeTracker.Window.AUTO, true);
        DealingRangeTracker off = tracker(DealingRangeTracker.Window.AUTO, false);
        java.time.Instant reopen = LocalDateTime.parse("2026-09-28T18:00").atZone(ET).toInstant();
        Candle handover = null;
        for (Candle c : bars) {
            on.onCandle(c);
            off.onCandle(c);
            if (c.getTimestamp().isBefore(reopen)) {
                assertThat(on.snapshot()).isEqualTo(off.snapshot()); // identical before the roll
                continue;
            }
            if (handover == null && !on.carryingPreviousRange()) {
                handover = c;
            }
            if (handover == null) {
                assertThat(on.snapshot().high()).isEqualTo(30759.25);
                assertThat(on.snapshot().low()).isEqualTo(30356.75);
                assertThat(on.snapshot().direction()).isEqualTo(MarketBias.BEARISH);
            } else {
                assertThat(on.snapshot()).isEqualTo(off.snapshot());
            }
        }
        assertThat(handover).isNotNull();
        // The first bar whose low reaches 30615.25 - 100 = 30515.25.
        assertThat(handover.getLow()).isLessThanOrEqualTo(30515.25);
        assertThat(handover.getLow() + (30521.50 - 30400.00) / 90).isGreaterThan(30515.25);
        System.out.println("[A-05.7] hand-over at " + handover.getTimestamp().atZone(ET).toLocalDateTime() + " (snapshot after 20:59)"
                + " low=" + handover.getLow() + " -> " + on.snapshot());
        assertThat(on.snapshot().direction()).isEqualTo(MarketBias.BEARISH);
        assertThat(on.snapshot().high()).isEqualTo(30615.25);
    }

    @Test
    @DisplayName("a reopen print beyond the carried range EXTENDS it; the direction does not flip on it")
    void extendsBeyondTheCarriedRange() {
        // Close near the high, then gap 11 pt above the carried high at 18:00.
        List<Candle> bars = monday();
        List<Candle> gapUp = new ArrayList<>(bars.subList(0, bars.size() - 127));
        walk(gapUp, "2026-09-28T14:53", 127, 30640.00, 30750.00); // strong close, no close > 30759.25
        walk(gapUp, "2026-09-28T18:00", 10, 30760.00, 30770.00);  // new high 30770, leg 10 pt
        DealingRangeTracker t = tracker(DealingRangeTracker.Window.AUTO, true);
        DealingRangeTracker.Snapshot s = feed(t, gapUp, "2026-09-28T18:09");
        System.out.println("[A-05.7] gap-up extension " + s);
        assertThat(s.direction()).isEqualTo(MarketBias.BEARISH);
        assertThat(s.high()).isEqualTo(30770.00);
        assertThat(s.low()).isEqualTo(30356.75);
        assertThat(t.carryingPreviousRange()).isTrue();

        // Weak close near the low, then a new low below the carried low.
        List<Candle> gapDown = new ArrayList<>(bars.subList(0, bars.size() - 127 - 143));
        walk(gapDown, "2026-09-28T12:30", 270, 30356.75, 30380.00);
        walk(gapDown, "2026-09-28T18:00", 10, 30370.00, 30340.00);
        DealingRangeTracker t2 = tracker(DealingRangeTracker.Window.AUTO, true);
        DealingRangeTracker.Snapshot s2 = feed(t2, gapDown, "2026-09-28T18:09");
        assertThat(s2.direction()).isEqualTo(MarketBias.BEARISH);
        assertThat(s2.high()).isEqualTo(30759.25);
        assertThat(s2.low()).isEqualTo(30340.00);
    }

    @Test
    @DisplayName("an opposite Asia impulse >= 100 pt replaces the carried direction (normal rule)")
    void oppositeImpulseFlips() {
        List<Candle> bars = monday();
        walk(bars, "2026-09-28T18:00", 60, 30537.00, 30650.00); // 113 pt up
        DealingRangeTracker on = tracker(DealingRangeTracker.Window.AUTO, true);
        DealingRangeTracker off = tracker(DealingRangeTracker.Window.AUTO, false);
        feed(on, bars, "2026-09-28T18:59");
        feed(off, bars, "2026-09-28T18:59");
        assertThat(on.carryingPreviousRange()).isFalse();
        assertThat(on.snapshot()).isEqualTo(off.snapshot());
        assertThat(on.snapshot().direction()).isEqualTo(MarketBias.BULLISH);
    }

    @Test
    @DisplayName("SESSION_DAY window carries the previous session-day range")
    void sessionDayWindowCarries() {
        DealingRangeTracker t = tracker(DealingRangeTracker.Window.SESSION_DAY, true);
        DealingRangeTracker.Snapshot s = feed(t, tape(), "2026-09-28T18:59");
        assertThat(s.direction()).isEqualTo(MarketBias.BEARISH);
        assertThat(s.high()).isEqualTo(30759.25);
        assertThat(s.low()).isEqualTo(30356.75);
        DealingRangeTracker off = tracker(DealingRangeTracker.Window.SESSION_DAY, false);
        assertThat(feed(off, tape(), "2026-09-28T18:59").direction()).isEqualTo(MarketBias.BULLISH);
    }

    @Test
    @DisplayName("reset() and a cold start carry nothing")
    void coldStartCarriesNothing() {
        DealingRangeTracker t = tracker(DealingRangeTracker.Window.AUTO, true);
        feed(t, reopen(), "2026-09-28T18:59");
        assertThat(t.carryingPreviousRange()).isFalse();
        assertThat(t.snapshot().direction()).isEqualTo(MarketBias.BULLISH);
        DealingRangeTracker t2 = tracker(DealingRangeTracker.Window.AUTO, true);
        feed(t2, tape(), "2026-09-28T18:30");
        assertThat(t2.carryingPreviousRange()).isTrue();
        t2.reset();
        assertThat(t2.carryingPreviousRange()).isFalse();
        assertThat(t2.snapshot()).isEqualTo(DealingRangeTracker.Snapshot.EMPTY);
    }

    @Test
    @DisplayName("config: default true, false via bias.range.carryAcrossReopen, read by fromConfig")
    void config() {
        assertThat(BiasConfig.rangeCarryAcrossReopen()).isTrue();
        assertThat(DealingRangeTracker.fromConfig("MNQ", TICK).carryAcrossReopen()).isTrue();
        System.setProperty("bias.range.carryAcrossReopen", "false");
        assertThat(BiasConfig.rangeCarryAcrossReopen()).isFalse();
        assertThat(DealingRangeTracker.fromConfig("MNQ", TICK).carryAcrossReopen()).isFalse();
    }
}
