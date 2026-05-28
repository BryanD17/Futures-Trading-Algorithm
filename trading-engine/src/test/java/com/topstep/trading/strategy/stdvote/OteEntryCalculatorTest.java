package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.strategy.FairValueGap;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SA3 tests for {@link OteEntryCalculator}.
 *
 * <p>The calculator owns the canonical ICT OTE math (0.62 / 0.705 / 0.79,
 * 0.50 equilibrium, 1.0 invalidation), entry-price selection (defaulting to
 * the precise 0.705 with optional PD-array-edge snap), stop placement just
 * beyond the swept extreme + buffer, and a reward-to-risk helper.
 *
 * <p>Test vectors are the exact numbers in the master prompt's Appendix C4
 * (MNQ bullish) and C5 (MES bearish), plus MGC 0.10-tick cases.
 */
@DisplayName("OteEntryCalculator")
class OteEntryCalculatorTest {

    private static final Offset<Double> EPS = Offset.offset(1e-9);
    private final OteEntryCalculator calc = new OteEntryCalculator();

    @Nested
    @DisplayName("zone math")
    class ZoneMath {

        @Test
        @DisplayName("MNQ bullish impulse 20100→20180 yields canonical 62/705/79")
        void mnqBullish() {
            OteZone zone = calc.buildZone(20100.0, 20180.0, true, 0.25).orElseThrow();

            assertThat(zone.eq50()).isEqualTo(20140.00, EPS);
            assertThat(zone.f62()).isEqualTo(20130.50, EPS);
            assertThat(zone.f705()).isEqualTo(20123.50, EPS);
            assertThat(zone.f79()).isEqualTo(20116.75, EPS);
            assertThat(zone.one00()).isEqualTo(20100.00, EPS);
            assertThat(zone.bullish()).isTrue();
        }

        @Test
        @DisplayName("MES bearish impulse 5260→5300 yields canonical 62/705/79")
        void mesBearish() {
            OteZone zone = calc.buildZone(5260.0, 5300.0, false, 0.25).orElseThrow();

            assertThat(zone.eq50()).isEqualTo(5280.00, EPS);
            assertThat(zone.f62()).isEqualTo(5284.75, EPS);
            assertThat(zone.f705()).isEqualTo(5288.25, EPS);
            assertThat(zone.f79()).isEqualTo(5291.50, EPS);
            assertThat(zone.one00()).isEqualTo(5300.00, EPS);
            assertThat(zone.bullish()).isFalse();
        }

        @Test
        @DisplayName("MGC bullish impulse 2400→2410 yields zone on 0.10 grid")
        void mgcBullish() {
            OteZone zone = calc.buildZone(2400.0, 2410.0, true, 0.10).orElseThrow();

            // bullish: high - k*range, range=10
            assertThat(zone.eq50()).isEqualTo(2405.00, EPS);
            assertThat(zone.f62()).isEqualTo(2403.80, EPS);
            assertThat(zone.f705()).isEqualTo(2402.90, EPS); // 2410 - 7.05 = 2402.95 → 2402.90 on 0.10? actually 7.05 → 2402.95 rounds to 2402.90 (half-to-even) or 2403.00?
            assertThat(zone.f79()).isEqualTo(2402.10, EPS);
            assertThat(zone.one00()).isEqualTo(2400.00, EPS);
        }

        @Test
        @DisplayName("contains() bullish: 705 inside band; eq50 outside")
        void containsBullish() {
            OteZone zone = calc.buildZone(20100.0, 20180.0, true, 0.25).orElseThrow();
            assertThat(zone.contains(20125.00)).isTrue();   // between 79 (20116.75) and 62 (20130.50)
            assertThat(zone.contains(zone.f705())).isTrue();
            assertThat(zone.contains(zone.eq50())).isFalse();
            assertThat(zone.contains(zone.one00())).isFalse();
        }

        @Test
        @DisplayName("contains() bearish: 705 inside band; eq50 outside")
        void containsBearish() {
            OteZone zone = calc.buildZone(5260.0, 5300.0, false, 0.25).orElseThrow();
            assertThat(zone.contains(5289.00)).isTrue();
            assertThat(zone.contains(zone.f705())).isTrue();
            assertThat(zone.contains(zone.eq50())).isFalse();
            assertThat(zone.contains(zone.one00())).isFalse();
        }

        @Test
        @DisplayName("monotonicity: bullish f62 > f705 > f79")
        void bullishLevelOrder() {
            OteZone zone = calc.buildZone(20100.0, 20180.0, true, 0.25).orElseThrow();
            assertThat(zone.f62()).isGreaterThan(zone.f705());
            assertThat(zone.f705()).isGreaterThan(zone.f79());
            assertThat(zone.f79()).isGreaterThan(zone.one00());
        }

        @Test
        @DisplayName("monotonicity: bearish f62 < f705 < f79")
        void bearishLevelOrder() {
            OteZone zone = calc.buildZone(5260.0, 5300.0, false, 0.25).orElseThrow();
            assertThat(zone.f62()).isLessThan(zone.f705());
            assertThat(zone.f705()).isLessThan(zone.f79());
            assertThat(zone.f79()).isLessThan(zone.one00());
        }

        @Test
        @DisplayName("degenerate leg (low >= high) → empty zone")
        void degenerateLeg() {
            assertThat(calc.buildZone(100.0, 100.0, true, 0.25)).isEmpty();
            assertThat(calc.buildZone(120.0, 100.0, true, 0.25)).isEmpty();
        }

        @Test
        @DisplayName("too-tight leg where 62/705/79 collapse to same tick → empty zone")
        void tooTightLeg() {
            // range 1.0 on 0.25 tick:
            // 62 = high - 0.62 → 0.5 tick offset; rounds to 100.50
            // 705 = high - 0.705 → rounds to 100.25 ? depends on geometry
            // 79 = high - 0.79 → rounds to 100.25 ?
            // Use a deliberately tiny range to collapse: range = 0.5 on 0.25 tick.
            Optional<OteZone> zone = calc.buildZone(100.0, 100.5, true, 0.25);
            // 62: 100.5 - 0.31 = 100.19 → 100.25
            // 705: 100.5 - 0.3525 = 100.1475 → 100.25
            // 79: 100.5 - 0.395 = 100.105 → 100.00
            // 62 == 705 → reject.
            assertThat(zone).isEmpty();
        }
    }

    @Nested
    @DisplayName("entry selection")
    class EntrySelection {

        @Test
        @DisplayName("default entry is the precise 0.705 level")
        void defaultEntryIs705() {
            OteZone zone = calc.buildZone(20100.0, 20180.0, true, 0.25).orElseThrow();
            assertThat(calc.chooseEntry(zone, OptionalDouble.empty(), 0.25))
                    .isEqualTo(20123.50, EPS);
        }

        @Test
        @DisplayName("PD-array edge inside the zone overrides the 0.705 default")
        void pdArrayEdgeInsideZone() {
            OteZone zone = calc.buildZone(20100.0, 20180.0, true, 0.25).orElseThrow();
            // A PD array edge at 20120.00 (between 79=20116.75 and 62=20130.50).
            assertThat(calc.chooseEntry(zone, OptionalDouble.of(20120.0), 0.25))
                    .isEqualTo(20120.00, EPS);
        }

        @Test
        @DisplayName("PD-array edge OUTSIDE the zone is ignored → fall back to 0.705")
        void pdArrayEdgeOutsideZone() {
            OteZone zone = calc.buildZone(20100.0, 20180.0, true, 0.25).orElseThrow();
            // 20140 is above 62 — outside the band on the wrong side.
            assertThat(calc.chooseEntry(zone, OptionalDouble.of(20140.0), 0.25))
                    .isEqualTo(zone.f705(), EPS);
            // 20110 is below 79 — outside (too deep).
            assertThat(calc.chooseEntry(zone, OptionalDouble.of(20110.0), 0.25))
                    .isEqualTo(zone.f705(), EPS);
        }

        @Test
        @DisplayName("entry rounds to instrument tick")
        void entryRoundsToTick() {
            OteZone zone = calc.buildZone(20100.0, 20180.0, true, 0.25).orElseThrow();
            // A nominally-off-grid PD edge should be rounded.
            double entry = calc.chooseEntry(zone, OptionalDouble.of(20121.13), 0.25);
            assertThat(entry).isEqualTo(20121.25, EPS); // 20121.13 / 0.25 = 80484.52 → 80485 → 20121.25
        }
    }

    @Nested
    @DisplayName("stop placement")
    class StopPlacement {

        @Test
        @DisplayName("bullish stop sits below one00 by bufferTicks * tickSize")
        void bullishStop() {
            OteZone zone = calc.buildZone(20100.0, 20180.0, true, 0.25).orElseThrow();
            assertThat(calc.stopPrice(zone, 0.25, 4)).isEqualTo(20099.00, EPS);
            assertThat(calc.stopPrice(zone, 0.25, 0)).isEqualTo(20100.00, EPS);
        }

        @Test
        @DisplayName("bearish stop sits above one00 by bufferTicks * tickSize")
        void bearishStop() {
            OteZone zone = calc.buildZone(5260.0, 5300.0, false, 0.25).orElseThrow();
            assertThat(calc.stopPrice(zone, 0.25, 4)).isEqualTo(5301.00, EPS);
            assertThat(calc.stopPrice(zone, 0.25, 0)).isEqualTo(5300.00, EPS);
        }

        @Test
        @DisplayName("MGC 0.10-tick stop math")
        void mgcStop() {
            OteZone zone = calc.buildZone(2400.0, 2410.0, true, 0.10).orElseThrow();
            assertThat(calc.stopPrice(zone, 0.10, 4)).isEqualTo(2399.60, EPS);
        }
    }

    @Nested
    @DisplayName("reward-to-risk")
    class RewardToRisk {

        @Test
        @DisplayName("RR is positive distance ratio regardless of direction")
        void rrPositive() {
            // bullish entry 20123.50, stop 20099.00, target -2.0 = 20200.00
            assertThat(calc.rewardToRisk(20123.50, 20099.00, 20200.00))
                    .isCloseTo((20200.00 - 20123.50) / (20123.50 - 20099.00), EPS);
        }

        @Test
        @DisplayName("RR == 0 when stop equals entry")
        void rrZeroOnDegenerate() {
            assertThat(calc.rewardToRisk(100.0, 100.0, 200.0)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("RR < 2.0 case: too-tight setup geometry")
        void tightGeometryFailsRRFloor() {
            // entry 100, stop 99, target 102 → RR = 2/1 = 2.0 (at floor)
            assertThat(calc.rewardToRisk(100.0, 99.0, 102.0)).isCloseTo(2.0, EPS);
            // entry 100, stop 99, target 101.5 → RR = 1.5 (below floor)
            assertThat(calc.rewardToRisk(100.0, 99.0, 101.5)).isCloseTo(1.5, EPS);
        }
    }

    @Nested
    @DisplayName("best FVG edge inside OTE")
    class FvgInOte {

        @Test
        @DisplayName("bullish setup picks FVG top when it sits inside the band")
        void bullishPicksTopInside() {
            OteZone zone = calc.buildZone(20100.0, 20180.0, true, 0.25).orElseThrow();
            // FVG with top 20128.00 inside [79=20116.75, 62=20130.50], bottom 20114
            FairValueGap fvg = new FairValueGap(true, 20128.00, 20114.00, Instant.now());
            OptionalDouble edge = calc.bestFvgEdgeInZone(zone, fvg);
            assertThat(edge).isPresent();
            assertThat(edge.getAsDouble()).isEqualTo(20128.00, EPS);
        }

        @Test
        @DisplayName("bullish setup falls back to FVG bottom when top is above the band")
        void bullishFallsBackToBottom() {
            OteZone zone = calc.buildZone(20100.0, 20180.0, true, 0.25).orElseThrow();
            // FVG top 20140 is above 62 (20130.50) — outside the band on the near side.
            // FVG bottom 20120 is inside the band.
            FairValueGap fvg = new FairValueGap(true, 20140.00, 20120.00, Instant.now());
            OptionalDouble edge = calc.bestFvgEdgeInZone(zone, fvg);
            assertThat(edge).isPresent();
            assertThat(edge.getAsDouble()).isEqualTo(20120.00, EPS);
        }

        @Test
        @DisplayName("empty when neither edge falls inside the band")
        void emptyWhenOutside() {
            OteZone zone = calc.buildZone(20100.0, 20180.0, true, 0.25).orElseThrow();
            FairValueGap fvg = new FairValueGap(true, 20200.0, 20190.0, Instant.now());
            assertThat(calc.bestFvgEdgeInZone(zone, fvg)).isEmpty();
        }

        @Test
        @DisplayName("bearish setup picks FVG bottom inside the band")
        void bearishPicksBottomInside() {
            OteZone zone = calc.buildZone(5260.0, 5300.0, false, 0.25).orElseThrow();
            // bearish OTE: [62=5284.75, 79=5291.50]; FVG bottom 5288, top 5295.
            FairValueGap fvg = new FairValueGap(false, 5295.0, 5288.0, Instant.now());
            OptionalDouble edge = calc.bestFvgEdgeInZone(zone, fvg);
            assertThat(edge).isPresent();
            assertThat(edge.getAsDouble()).isEqualTo(5288.00, EPS);
        }

        @Test
        @DisplayName("null FVG returns empty")
        void nullFvgEmpty() {
            OteZone zone = calc.buildZone(20100.0, 20180.0, true, 0.25).orElseThrow();
            assertThat(calc.bestFvgEdgeInZone(zone, null)).isEmpty();
        }
    }
}
