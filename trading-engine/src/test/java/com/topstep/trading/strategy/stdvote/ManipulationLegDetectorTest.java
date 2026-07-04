package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.stdvote.ManipulationLegDetector.Leg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ManipulationLegDetector (Judas swing from killzone open)")
class ManipulationLegDetectorTest {

    private static final double TICK = 0.25;
    private static final int MIN_LEG_TICKS = 8;
    private static final Instant OPEN = Instant.parse("2026-06-15T13:45:00Z");

    private static Candle c(int minute, double open, double high, double low, double close) {
        return new Candle("MNQ", OPEN.plus(minute, ChronoUnit.MINUTES), open, high, low, close, 100);
    }

    /**
     * Canonical bullish Judas: open 21032, push down to 21014, reclaim close
     * back above the open.
     */
    private static List<Candle> bullishJudas() {
        List<Candle> candles = new ArrayList<>();
        candles.add(c(0, 21032, 21034, 21030, 21031)); // killzone open bar
        candles.add(c(1, 21031, 21032, 21026, 21027)); // push down
        candles.add(c(2, 21027, 21028, 21020, 21021));
        candles.add(c(3, 21021, 21022, 21014, 21017)); // Judas low 21014
        candles.add(c(4, 21017, 21024, 21016, 21023)); // recovery
        candles.add(c(5, 21023, 21031, 21022, 21030)); // near open, no reclaim yet
        candles.add(c(6, 21030, 21035, 21029, 21034)); // RECLAIM: close 21034 > 21032
        return candles;
    }

    @Nested
    @DisplayName("bullish bias")
    class Bullish {

        @Test
        @DisplayName("completed Judas returns [dip low, pre-dip high]")
        void completedJudas() {
            Optional<Leg> leg = ManipulationLegDetector.detect(bullishJudas(), true, TICK, MIN_LEG_TICKS);
            assertThat(leg).isPresent();
            assertThat(leg.get().legLow()).isEqualTo(21014.0);
            // Highest high from the open up to the dip bar = the open bar's 21034.
            assertThat(leg.get().legHigh()).isEqualTo(21034.0);
        }

        @Test
        @DisplayName("no leg while the reclaim has not happened")
        void noReclaimYet() {
            List<Candle> inProgress = bullishJudas().subList(0, 6); // reclaim bar excluded
            assertThat(ManipulationLegDetector.detect(inProgress, true, TICK, MIN_LEG_TICKS)).isEmpty();
        }

        @Test
        @DisplayName("no leg when price never traded below the killzone open")
        void noExcursionBelowOpen() {
            List<Candle> rallyOnly = List.of(
                    c(0, 21032, 21034, 21032, 21033),
                    c(1, 21033, 21038, 21032.5, 21037),
                    c(2, 21037, 21042, 21036, 21041));
            assertThat(ManipulationLegDetector.detect(rallyOnly, true, TICK, MIN_LEG_TICKS)).isEmpty();
        }

        @Test
        @DisplayName("a close back above the open BEFORE the extreme is not a reclaim")
        void reclaimMustFollowExtreme() {
            List<Candle> candles = new ArrayList<>();
            candles.add(c(0, 21032, 21034, 21030, 21031));
            candles.add(c(1, 21031, 21036, 21030, 21035)); // closes above open (pre-dip)
            candles.add(c(2, 21035, 21036, 21014, 21016)); // dip AFTER that close
            candles.add(c(3, 21016, 21020, 21015, 21019)); // no reclaim since the dip
            assertThat(ManipulationLegDetector.detect(candles, true, TICK, MIN_LEG_TICKS)).isEmpty();
        }

        @Test
        @DisplayName("deeper low after a reclaim re-anchors: the new leg needs a new reclaim")
        void deeperLowReAnchors() {
            List<Candle> candles = new ArrayList<>(bullishJudas());
            candles.add(c(7, 21034, 21035, 21008, 21010)); // deeper low, no reclaim after it
            assertThat(ManipulationLegDetector.detect(candles, true, TICK, MIN_LEG_TICKS)).isEmpty();
            candles.add(c(8, 21010, 21036, 21009, 21035)); // reclaim after the deeper low
            Optional<Leg> leg = ManipulationLegDetector.detect(candles, true, TICK, MIN_LEG_TICKS);
            assertThat(leg).isPresent();
            assertThat(leg.get().legLow()).isEqualTo(21008.0);
            assertThat(leg.get().legHigh()).isEqualTo(21035.0); // 13:51 high included pre-dip
        }
    }

    @Nested
    @DisplayName("bearish bias (mirrored)")
    class Bearish {

        private List<Candle> bearishJudas() {
            List<Candle> candles = new ArrayList<>();
            candles.add(c(0, 21032, 21034, 21030, 21033)); // open bar
            candles.add(c(1, 21033, 21040, 21032, 21039)); // push up
            candles.add(c(2, 21039, 21050, 21038, 21044)); // Judas high 21050
            candles.add(c(3, 21044, 21045, 21036, 21038)); // roll over
            candles.add(c(4, 21038, 21039, 21028, 21030)); // RECLAIM: close 21030 < 21032
            return candles;
        }

        @Test
        @DisplayName("completed Judas returns [pre-spike low, spike high]")
        void completedJudas() {
            Optional<Leg> leg = ManipulationLegDetector.detect(bearishJudas(), false, TICK, MIN_LEG_TICKS);
            assertThat(leg).isPresent();
            assertThat(leg.get().legHigh()).isEqualTo(21050.0);
            // Lowest low from the open up to the spike bar = the open bar's 21030.
            assertThat(leg.get().legLow()).isEqualTo(21030.0);
        }

        @Test
        @DisplayName("no leg while price stays above the open")
        void noReclaimYet() {
            List<Candle> inProgress = bearishJudas().subList(0, 4);
            assertThat(ManipulationLegDetector.detect(inProgress, false, TICK, MIN_LEG_TICKS)).isEmpty();
        }
    }

    @Nested
    @DisplayName("degenerate inputs")
    class Degenerate {

        @Test
        @DisplayName("null / single-candle input yields no leg")
        void tooFewCandles() {
            assertThat(ManipulationLegDetector.detect(null, true, TICK, MIN_LEG_TICKS)).isEmpty();
            assertThat(ManipulationLegDetector.detect(List.of(), true, TICK, MIN_LEG_TICKS)).isEmpty();
            assertThat(ManipulationLegDetector.detect(
                    List.of(c(0, 21032, 21034, 21030, 21031)), true, TICK, MIN_LEG_TICKS)).isEmpty();
        }

        @Test
        @DisplayName("leg shorter than the minimum tick extent is rejected")
        void tooShortLeg() {
            // Excursion of 1 point = 4 ticks < 8-tick minimum.
            List<Candle> candles = List.of(
                    c(0, 21032, 21032.5, 21031.5, 21032),
                    c(1, 21032, 21032.25, 21031.0, 21031.25), // dip 1 pt below open
                    c(2, 21031.25, 21033, 21031, 21032.75));  // reclaim
            assertThat(ManipulationLegDetector.detect(candles, true, TICK, MIN_LEG_TICKS)).isEmpty();
        }

        @Test
        @DisplayName("min-leg gate disabled when tickSize is zero")
        void zeroTickSizeSkipsMinLeg() {
            List<Candle> candles = List.of(
                    c(0, 21032, 21032.5, 21031.5, 21032),
                    c(1, 21032, 21032.25, 21031.0, 21031.25),
                    c(2, 21031.25, 21033, 21031, 21032.75));
            assertThat(ManipulationLegDetector.detect(candles, true, 0.0, MIN_LEG_TICKS)).isPresent();
        }
    }
}
