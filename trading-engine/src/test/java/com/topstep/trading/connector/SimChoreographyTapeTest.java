package com.topstep.trading.connector;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.BarAggregationManager;
import com.topstep.trading.strategy.BarAggregationManager.Timeframe;
import com.topstep.trading.strategy.DisplacementDetector;
import com.topstep.trading.strategy.KillzoneClock;
import com.topstep.trading.strategy.stdvote.ImpulseLegTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level checks that the SIM tape actually POSES the question the engine
 * is built to answer (V4 follow-up).
 *
 * <p>These exist because iterating on the tape through full SIM runs took ten
 * minutes per attempt and told you only "still no trade". Each assertion here
 * corresponds to one stall reason the funnel census reported, so a regression
 * in the fixture names itself in seconds instead of hiding behind an engine
 * that is behaving correctly.
 */
class SimChoreographyTapeTest {

    private static final String SYM = "MNQ";
    /** 2026-08-11 (a Tuesday) 09:30 ET = 13:30Z — just before the AM killzone. */
    private static final Instant START = Instant.parse("2026-08-11T13:30:00Z");

    /** One 1m candle per minute across {@code minutes}, continuing from 20000. */
    private static List<Candle> tape(int minutes) {
        SimChoreographyTape gen = new SimChoreographyTape(42L);
        List<Candle> out = new ArrayList<>(minutes);
        double price = 20000.0;
        for (int i = 0; i < minutes; i++) {
            Candle c = gen.next(SYM, START.plusSeconds(60L * i), price);
            out.add(c);
            price = c.getClose();
        }
        return out;
    }

    private static List<Candle> aggregate(List<Candle> oneMinute, Timeframe tf) {
        BarAggregationManager bars = new BarAggregationManager(SYM, 5000);
        List<Candle> out = new ArrayList<>();
        for (Candle c : oneMinute) {
            Candle done = bars.processCandle(c).get(tf);
            if (done != null) out.add(done);
        }
        return out;
    }

    @Test
    @DisplayName("The tape trades inside the engine's TRADING killzone")
    void actRunsInsideAKillzone() {
        KillzoneClock kz = new KillzoneClock();
        List<Candle> t = tape(240);
        long inside = t.stream().filter(c -> kz.isInKillzone(c.getTimestamp())).count();
        assertThat(inside)
                .as("the act must run while the engine will actually accept entries")
                .isGreaterThan(60);
    }

    @Test
    @DisplayName("Displacement is detectable ON THE DETECTOR TIMEFRAME, not just on 1m")
    void displacementFiresOnTheDetectorSeries() {
        List<Candle> fiveMinute = aggregate(tape(300), Timeframe.M5);
        assertThat(fiveMinute).hasSizeGreaterThan(20);

        DisplacementDetector det = new DisplacementDetector(20, 1.5, 0.65, SYM);
        int fired = 0;
        Instant last = null;
        for (Candle c : fiveMinute) {
            det.update(c);
            DisplacementDetector.Displacement d = det.getLastDisplacement();
            if (d != null && d.getTimestamp() != null && !d.getTimestamp().equals(last)) {
                last = d.getTimestamp();
                fired++;
            }
        }
        assertThat(fired)
                .as("the scripted expansion must survive aggregation to the 5m series")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("Consecutive expansion leaves a three-candle FVG on the detector series")
    void displacementLeavesAnFvg() {
        List<Candle> f = aggregate(tape(300), Timeframe.M5);
        int gaps = 0;
        for (int i = 2; i < f.size(); i++) {
            if (f.get(i).getLow() > f.get(i - 2).getHigh()) gaps++;      // bullish
            if (f.get(i).getHigh() < f.get(i - 2).getLow()) gaps++;      // bearish
        }
        assertThat(gaps)
                .as("no FVG means the funnel stalls with no-fvg-for-displacement")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("The retrace prints a REJECTION at the OTE band, not just a visit to it")
    void retracePrintsARejectionReaction() {
        List<Candle> t = tape(400);

        // Find the scripted impulse: the longest run of same-direction candles,
        // in EITHER direction — the act's direction follows the session trend,
        // so hunting only for up-runs finds drift noise on a bearish day and
        // tests nothing.
        int bestStart = -1;
        int bestLen = 0;
        boolean bestUp = true;
        for (boolean up : new boolean[]{true, false}) {
            for (int i = 1; i < t.size(); i++) {
                int len = 0;
                while (i + len < t.size()
                        && (t.get(i + len).getClose() > t.get(i + len).getOpen()) == up
                        && t.get(i + len).getClose() != t.get(i + len).getOpen()) {
                    len++;
                }
                if (len > bestLen) {
                    bestLen = len;
                    bestStart = i;
                    bestUp = up;
                }
                i += Math.max(0, len);
            }
        }
        assertThat(bestLen)
                .as("the tape must contain a sustained impulse to retrace into")
                .isGreaterThan(8);

        // Arm exactly as the runner does at MSS confirmation: origin at the
        // extreme the move started from, terminus at the extreme it reached.
        double origin = bestUp ? t.get(bestStart).getLow() : t.get(bestStart).getHigh();
        double terminus = bestUp
                ? t.get(bestStart + bestLen - 1).getHigh()
                : t.get(bestStart + bestLen - 1).getLow();
        ImpulseLegTracker tracker = new ImpulseLegTracker();
        tracker.arm(bestUp, origin, terminus);

        boolean reacted = false;
        for (int i = bestStart + bestLen; i < t.size(); i++) {
            if (tracker.isRejectionReaction(t.get(i), 0.25, 2)) {
                reacted = true;
                break;
            }
        }
        assertThat(reacted)
                .as("without a with-trend close leaving a rejection wick inside the "
                        + "0.62-0.79 band, the funnel stalls forever at "
                        + "MSS_CONFIRMED:no-reaction-at-band")
                .isTrue();
    }

    @Test
    @DisplayName("Determinism: the same seed replays the same tape")
    void deterministic() {
        assertThat(tape(120)).isEqualTo(tape(120));
    }

    @Test
    @DisplayName("RANDOM mode still available for tests that want unstructured noise")
    void modeSwitch() {
        String prev = System.getProperty(SimChoreographyTape.MODE_PROPERTY);
        try {
            System.setProperty(SimChoreographyTape.MODE_PROPERTY, "RANDOM");
            assertThat(SimChoreographyTape.enabled()).isFalse();
            System.clearProperty(SimChoreographyTape.MODE_PROPERTY);
            assertThat(SimChoreographyTape.enabled()).isTrue();
        } finally {
            if (prev == null) System.clearProperty(SimChoreographyTape.MODE_PROPERTY);
            else System.setProperty(SimChoreographyTape.MODE_PROPERTY, prev);
        }
    }
}
