package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

@DisplayName("ImpulseLegTracker")
class ImpulseLegTrackerTest {

    private static final double TICK = 0.25;
    private static final Instant T = Instant.parse("2026-06-15T14:00:00Z");

    private static Candle candle(double open, double high, double low, double close) {
        return new Candle("MNQ", T, open, high, low, close, 100);
    }

    @Nested
    @DisplayName("arming + extremes")
    class Arming {

        @Test
        @DisplayName("unarmed tracker has no leg and reports zero extremes")
        void unarmed() {
            ImpulseLegTracker t = new ImpulseLegTracker();
            assertThat(t.isArmed()).isFalse();
            assertThat(t.hasValidLeg()).isFalse();
            assertThat(t.impulseLow()).isEqualTo(0.0);
            assertThat(t.impulseHigh()).isEqualTo(0.0);
            assertThat(t.oteBand()).isNull();
        }

        @Test
        @DisplayName("bullish: origin is the low, terminus the high")
        void bullishExtremes() {
            ImpulseLegTracker t = new ImpulseLegTracker();
            t.arm(true, 21012.0, 21040.0);
            assertThat(t.isArmed()).isTrue();
            assertThat(t.isBullish()).isTrue();
            assertThat(t.impulseLow()).isEqualTo(21012.0);
            assertThat(t.impulseHigh()).isEqualTo(21040.0);
            assertThat(t.hasValidLeg()).isTrue();
        }

        @Test
        @DisplayName("bearish: origin is the high, terminus the low")
        void bearishExtremes() {
            ImpulseLegTracker t = new ImpulseLegTracker();
            t.arm(false, 21040.0, 21012.0);
            assertThat(t.impulseLow()).isEqualTo(21012.0);
            assertThat(t.impulseHigh()).isEqualTo(21040.0);
            assertThat(t.hasValidLeg()).isTrue();
        }

        @Test
        @DisplayName("degenerate leg (origin == terminus) is not valid")
        void degenerateLeg() {
            ImpulseLegTracker t = new ImpulseLegTracker();
            t.arm(true, 21000.0, 21000.0);
            assertThat(t.hasValidLeg()).isFalse();
            assertThat(t.oteBand()).isNull();
        }
    }

    @Nested
    @DisplayName("onCandle extension + violation")
    class Extension {

        @Test
        @DisplayName("bullish terminus extends up but never down")
        void bullishTerminusExtends() {
            ImpulseLegTracker t = new ImpulseLegTracker();
            t.arm(true, 21012.0, 21040.0);
            t.onCandle(21046.0, 21030.0);
            assertThat(t.impulseHigh()).isEqualTo(21046.0);
            t.onCandle(21044.0, 21028.0); // lower high: no shrink
            assertThat(t.impulseHigh()).isEqualTo(21046.0);
            assertThat(t.impulseLow()).isEqualTo(21012.0); // origin fixed
            assertThat(t.isViolated()).isFalse();
        }

        @Test
        @DisplayName("bearish terminus extends down but never up")
        void bearishTerminusExtends() {
            ImpulseLegTracker t = new ImpulseLegTracker();
            t.arm(false, 21040.0, 21012.0);
            t.onCandle(21020.0, 21006.0);
            assertThat(t.impulseLow()).isEqualTo(21006.0);
            t.onCandle(21024.0, 21010.0);
            assertThat(t.impulseLow()).isEqualTo(21006.0);
            assertThat(t.isViolated()).isFalse();
        }

        @Test
        @DisplayName("bullish: trading below the origin flags violation")
        void bullishViolation() {
            ImpulseLegTracker t = new ImpulseLegTracker();
            t.arm(true, 21012.0, 21040.0);
            t.onCandle(21020.0, 21011.75); // one tick below origin
            assertThat(t.isViolated()).isTrue();
            assertThat(t.hasValidLeg()).isFalse();
            assertThat(t.oteBand()).isNull();
        }

        @Test
        @DisplayName("bearish: trading above the origin flags violation")
        void bearishViolation() {
            ImpulseLegTracker t = new ImpulseLegTracker();
            t.arm(false, 21040.0, 21012.0);
            t.onCandle(21040.25, 21030.0);
            assertThat(t.isViolated()).isTrue();
            assertThat(t.hasValidLeg()).isFalse();
        }

        @Test
        @DisplayName("onCandle before arming is a no-op")
        void onCandleBeforeArm() {
            ImpulseLegTracker t = new ImpulseLegTracker();
            t.onCandle(21050.0, 21000.0);
            assertThat(t.isArmed()).isFalse();
            assertThat(t.impulseHigh()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("OTE band")
    class Band {

        @Test
        @DisplayName("bullish band is [high - 0.79R, high - 0.62R]")
        void bullishBand() {
            ImpulseLegTracker t = new ImpulseLegTracker();
            t.arm(true, 21012.0, 21052.0); // range 40
            double[] band = t.oteBand();
            assertThat(band[0]).isCloseTo(21052.0 - 0.79 * 40, offset(1e-9)); // 21020.4
            assertThat(band[1]).isCloseTo(21052.0 - 0.62 * 40, offset(1e-9)); // 21027.2
            assertThat(band[0]).isLessThan(band[1]);
        }

        @Test
        @DisplayName("bearish band is [low + 0.62R, low + 0.79R]")
        void bearishBand() {
            ImpulseLegTracker t = new ImpulseLegTracker();
            t.arm(false, 21052.0, 21012.0); // range 40
            double[] band = t.oteBand();
            assertThat(band[0]).isCloseTo(21012.0 + 0.62 * 40, offset(1e-9)); // 21036.8
            assertThat(band[1]).isCloseTo(21012.0 + 0.79 * 40, offset(1e-9)); // 21043.6
            assertThat(band[0]).isLessThan(band[1]);
        }
    }

    @Nested
    @DisplayName("rejection reaction (reactionConfirmed source)")
    class Reaction {

        private ImpulseLegTracker bullishTracker() {
            ImpulseLegTracker t = new ImpulseLegTracker();
            t.arm(true, 21012.0, 21052.0); // band [21020.4, 21027.2]
            return t;
        }

        @Test
        @DisplayName("bullish: wick into band + bullish close + wick >= min confirms")
        void bullishConfirms() {
            ImpulseLegTracker t = bullishTracker();
            // open 21037, dip to 21024 (inside band), close 21038: wick 13 pts
            Candle c = candle(21037.0, 21038.0, 21024.0, 21038.0);
            assertThat(t.isRejectionReaction(c, TICK, 2)).isTrue();
        }

        @Test
        @DisplayName("bullish: no touch of the band → not confirmed")
        void bullishNoTouch() {
            ImpulseLegTracker t = bullishTracker();
            Candle c = candle(21037.0, 21040.0, 21030.0, 21039.0); // low above 21027.2
            assertThat(t.isRejectionReaction(c, TICK, 2)).isFalse();
        }

        @Test
        @DisplayName("bullish: bearish close → not confirmed")
        void bullishWrongClose() {
            ImpulseLegTracker t = bullishTracker();
            Candle c = candle(21037.0, 21038.0, 21024.0, 21030.0); // close < open? 21030 < 21037
            assertThat(t.isRejectionReaction(c, TICK, 2)).isFalse();
        }

        @Test
        @DisplayName("bullish: wick shorter than the minimum → not confirmed")
        void bullishWickTooShort() {
            ImpulseLegTracker t = bullishTracker();
            // open 21024.25, low 21024 → wick = 0.25 = 1 tick < 2 ticks
            Candle c = candle(21024.25, 21030.0, 21024.0, 21029.0);
            assertThat(t.isRejectionReaction(c, TICK, 2)).isFalse();
        }

        @Test
        @DisplayName("bearish: wick into band + bearish close + wick >= min confirms")
        void bearishConfirms() {
            ImpulseLegTracker t = new ImpulseLegTracker();
            t.arm(false, 21052.0, 21012.0); // band [21036.8, 21043.6]
            // open 21030, spike to 21040 (inside band), close 21028: upper wick 10 pts
            Candle c = candle(21030.0, 21040.0, 21026.0, 21028.0);
            assertThat(t.isRejectionReaction(c, TICK, 2)).isTrue();
        }

        @Test
        @DisplayName("violated leg never confirms")
        void violatedNeverConfirms() {
            ImpulseLegTracker t = bullishTracker();
            t.onCandle(21030.0, 21010.0); // below origin → violated
            Candle c = candle(21024.0, 21030.0, 21022.0, 21029.0);
            assertThat(t.isRejectionReaction(c, TICK, 2)).isFalse();
        }

        @Test
        @DisplayName("null candle never confirms")
        void nullCandle() {
            assertThat(bullishTracker().isRejectionReaction(null, TICK, 2)).isFalse();
        }
    }

    @Test
    @DisplayName("reset returns the tracker to the unarmed state")
    void reset() {
        ImpulseLegTracker t = new ImpulseLegTracker();
        t.arm(true, 21012.0, 21052.0);
        t.onCandle(21060.0, 21000.0); // extend + violate
        t.reset();
        assertThat(t.isArmed()).isFalse();
        assertThat(t.isViolated()).isFalse();
        assertThat(t.hasValidLeg()).isFalse();
        assertThat(t.impulseLow()).isEqualTo(0.0);
        assertThat(t.impulseHigh()).isEqualTo(0.0);
    }
}
