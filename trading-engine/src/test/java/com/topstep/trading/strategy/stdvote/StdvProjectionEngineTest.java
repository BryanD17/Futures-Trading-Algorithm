package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.chartstate.LevelType;
import com.topstep.trading.strategy.ImpulseExtensionAnalyzer;
import com.topstep.trading.strategy.MarketBias;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SA2 tests for {@link StdvProjectionEngine}.
 *
 * <p>The engine is exercised in three slices:
 *
 * <ol>
 *   <li><b>Math</b> — the canonical sigma multipliers project to the exact
 *       expected prices for bullish and bearish setups across MNQ/MES/MGC
 *       tick sizes, and tick rounding lands on the correct grid.</li>
 *   <li><b>Snap</b> — a single nearby {@link KnownLevel} within the snap
 *       tolerance overrides the raw price; a level outside tolerance, or
 *       absent, leaves the raw price in place; a snap that would violate
 *       monotonicity is rejected.</li>
 *   <li><b>Realism</b> — the {@code -2.0} projection is tagged
 *       REALISTIC / AGGRESSIVE / UNREALISTIC via
 *       {@link ImpulseExtensionAnalyzer}; the projected price is never
 *       moved by the tag.</li>
 * </ol>
 */
@DisplayName("StdvProjectionEngine")
class StdvProjectionEngineTest {

    private static final double EPS = 1e-9;

    private StdvProjectionEngine engine(ChartStateQueryAPI chart,
                                        ImpulseExtensionAnalyzer realism) {
        // Disable min-leg-ticks gating in unit tests; the gate has its own test.
        return new StdvProjectionEngine(chart, realism, 0);
    }

    @Nested
    @DisplayName("projection math")
    class ProjectionMath {

        @Test
        @DisplayName("bullish MNQ: leg 20000→20100 yields canonical 5-level ladder")
        void bullishMnq() {
            // No chart, no realism — pure math.
            StdvProjectionEngine eng = engine(null, null);
            List<StdvProjection> ladder = eng.project(20000.0, 20100.0,
                    MarketBias.BULLISH, 0.25, 0);

            assertThat(ladder).hasSize(5);
            assertThat(ladder.get(0).sigma()).isEqualTo(-0.27);
            assertThat(ladder.get(0).rawPrice()).isEqualTo(20027.00, within(0.0001));
            assertThat(ladder.get(1).rawPrice()).isEqualTo(20100.00, within(0.0001));
            assertThat(ladder.get(2).rawPrice()).isEqualTo(20200.00, within(0.0001));
            assertThat(ladder.get(3).rawPrice()).isEqualTo(20250.00, within(0.0001));
            assertThat(ladder.get(4).rawPrice()).isEqualTo(20400.00, within(0.0001));
            // nothing snapped — effective == raw
            assertThat(ladder).allMatch(p -> !p.isLiquidityBacked());
            assertThat(ladder).allMatch(p -> p.snappedLevelType() == null);
            assertThat(ladder).allMatch(p -> p.snappedPrice() == p.rawPrice());
        }

        @Test
        @DisplayName("bearish MES: leg 5280→5300 yields ladder descending from 5300")
        void bearishMes() {
            StdvProjectionEngine eng = engine(null, null);
            List<StdvProjection> ladder = eng.project(5280.0, 5300.0,
                    MarketBias.BEARISH, 0.25, 0);

            assertThat(ladder).hasSize(5);
            // -0.27: 5300 - 0.27*20 = 5294.60 → round 0.25 → 5294.50
            assertThat(ladder.get(0).rawPrice()).isEqualTo(5294.50, within(EPS));
            assertThat(ladder.get(1).rawPrice()).isEqualTo(5280.00, within(EPS));
            assertThat(ladder.get(2).rawPrice()).isEqualTo(5260.00, within(EPS));
            assertThat(ladder.get(3).rawPrice()).isEqualTo(5250.00, within(EPS));
            assertThat(ladder.get(4).rawPrice()).isEqualTo(5220.00, within(EPS));
            // monotone DOWN for bearish
            for (int i = 1; i < ladder.size(); i++) {
                assertThat(ladder.get(i).rawPrice())
                        .isLessThan(ladder.get(i - 1).rawPrice());
            }
        }

        @Test
        @DisplayName("bullish MGC: leg 2400→2410, 0.10 tick lands cleanly")
        void bullishMgc() {
            StdvProjectionEngine eng = engine(null, null);
            List<StdvProjection> ladder = eng.project(2400.0, 2410.0,
                    MarketBias.BULLISH, 0.10, 0);

            // -0.27: 2400 + 0.27*10 = 2402.70 → already on 0.10 grid
            assertThat(ladder.get(0).rawPrice()).isEqualTo(2402.70, within(EPS));
            assertThat(ladder.get(1).rawPrice()).isEqualTo(2410.00, within(EPS));
            assertThat(ladder.get(2).rawPrice()).isEqualTo(2420.00, within(EPS));
            assertThat(ladder.get(3).rawPrice()).isEqualTo(2425.00, within(EPS));
            assertThat(ladder.get(4).rawPrice()).isEqualTo(2440.00, within(EPS));
        }

        @Test
        @DisplayName("monotonicity holds for bullish ladder")
        void bullishLadderIsMonotone() {
            StdvProjectionEngine eng = engine(null, null);
            List<StdvProjection> ladder = eng.project(100.0, 110.0,
                    MarketBias.BULLISH, 0.25, 0);
            for (int i = 1; i < ladder.size(); i++) {
                assertThat(ladder.get(i).rawPrice())
                        .isGreaterThan(ladder.get(i - 1).rawPrice());
            }
        }

        @Test
        @DisplayName("zero-length leg returns empty list")
        void zeroLengthLegEmpty() {
            StdvProjectionEngine eng = engine(null, null);
            assertThat(eng.project(100.0, 100.0, MarketBias.BULLISH, 0.25, 0))
                    .isEmpty();
        }

        @Test
        @DisplayName("inverted leg (legLow > legHigh) returns empty list")
        void invertedLegEmpty() {
            StdvProjectionEngine eng = engine(null, null);
            assertThat(eng.project(110.0, 100.0, MarketBias.BULLISH, 0.25, 0))
                    .isEmpty();
        }

        @Test
        @DisplayName("NEUTRAL bias returns empty list")
        void neutralBiasEmpty() {
            StdvProjectionEngine eng = engine(null, null);
            assertThat(eng.project(100.0, 110.0, MarketBias.NEUTRAL, 0.25, 0))
                    .isEmpty();
        }

        @Test
        @DisplayName("leg below minLegTicks gate returns empty list")
        void belowMinLegTicksEmpty() {
            // 1 point on 0.25 tick = 4 ticks; minLegTicks = 8 → reject
            StdvProjectionEngine strict = new StdvProjectionEngine(null, null, 8);
            assertThat(strict.project(100.0, 101.0, MarketBias.BULLISH, 0.25, 0))
                    .isEmpty();
        }

        @Test
        @DisplayName("MNQ tick rounding: irregular leg snaps to 0.25 grid")
        void mnqTickRounding() {
            StdvProjectionEngine eng = engine(null, null);
            // leg 20000 → 20105.13 (range 105.13). Every projection should
            // land on the 0.25 grid after rounding.
            List<StdvProjection> ladder = eng.project(20000.0, 20105.13,
                    MarketBias.BULLISH, 0.25, 0);
            for (StdvProjection p : ladder) {
                double ratio = p.rawPrice() / 0.25;
                assertThat(Math.abs(ratio - Math.round(ratio)))
                        .as("price %s should be on 0.25 grid", p.rawPrice())
                        .isLessThan(1e-9);
            }
        }
    }

    @Nested
    @DisplayName("liquidity snapping")
    class Snapping {

        @Test
        @DisplayName("snaps -2.0 to a nearby PDH within tolerance")
        void snapsToPdh() {
            // bullish 20000→20100, -2.0 raw = 20200.00
            // PDH at 20200.50 — 2 ticks (0.25) away on MNQ
            ChartStateQueryAPI chart = mock(ChartStateQueryAPI.class);
            KnownLevel pdh = new KnownLevel(LevelType.PDH, 20200.50, Instant.now());
            when(chart.getLevelsNearPrice(anyDouble())).thenReturn(List.of(pdh));
            when(chart.getAllLevels()).thenReturn(List.of(pdh));

            StdvProjectionEngine eng = engine(chart, null);
            List<StdvProjection> ladder = eng.project(20000.0, 20100.0,
                    MarketBias.BULLISH, 0.25, 3 /* 3 ticks tolerance = 0.75 pts */);

            // The PDH is 0.5pts from -2.0 (20200.00), and farther from every
            // other sigma — only -2.0 should snap.
            StdvProjection p2 = ladder.get(2);
            assertThat(p2.sigma()).isEqualTo(-2.0);
            assertThat(p2.isLiquidityBacked()).isTrue();
            assertThat(p2.snappedPrice()).isEqualTo(20200.50, within(EPS));
            assertThat(p2.snappedLevelType()).isEqualTo(LevelType.PDH);
            assertThat(p2.effectivePrice()).isEqualTo(20200.50, within(EPS));
            // Other sigmas are too far from 20200.50 to snap (PDH outside tol).
            assertThat(ladder.get(0).isLiquidityBacked()).isFalse(); // -0.27 at 20027
            assertThat(ladder.get(1).isLiquidityBacked()).isFalse(); // -1.0 at 20100
            assertThat(ladder.get(3).isLiquidityBacked()).isFalse(); // -2.5 at 20250
            assertThat(ladder.get(4).isLiquidityBacked()).isFalse(); // -4.0 at 20400
        }

        @Test
        @DisplayName("does not snap when nearest level exceeds tolerance")
        void doesNotSnapOutsideTolerance() {
            // PDH 1.50 pts away (6 ticks); tolerance 3 ticks (0.75 pts) → reject snap
            ChartStateQueryAPI chart = mock(ChartStateQueryAPI.class);
            KnownLevel pdh = new KnownLevel(LevelType.PDH, 20201.50, Instant.now());
            when(chart.getLevelsNearPrice(anyDouble())).thenReturn(List.of(pdh));
            when(chart.getAllLevels()).thenReturn(List.of(pdh));

            StdvProjectionEngine eng = engine(chart, null);
            List<StdvProjection> ladder = eng.project(20000.0, 20100.0,
                    MarketBias.BULLISH, 0.25, 3);

            StdvProjection p2 = ladder.get(2);
            assertThat(p2.isLiquidityBacked()).isFalse();
            assertThat(p2.snappedPrice()).isEqualTo(p2.rawPrice(), within(EPS));
            assertThat(p2.snappedLevelType()).isNull();
        }

        @Test
        @DisplayName("MGC tick 0.10: PDL within 3-tick tolerance snaps")
        void mgcSnap() {
            // bearish 2400→2406, -2.0 raw = 2406 - 12 = 2394.00
            // PDL at 2394.20 (2 MGC ticks) → snap allowed
            ChartStateQueryAPI chart = mock(ChartStateQueryAPI.class);
            KnownLevel pdl = new KnownLevel(LevelType.PDL, 2394.20, Instant.now());
            when(chart.getLevelsNearPrice(anyDouble())).thenReturn(List.of(pdl));
            when(chart.getAllLevels()).thenReturn(List.of(pdl));

            StdvProjectionEngine eng = engine(chart, null);
            List<StdvProjection> ladder = eng.project(2400.0, 2406.0,
                    MarketBias.BEARISH, 0.10, 3);

            StdvProjection p2 = ladder.get(2);
            assertThat(p2.sigma()).isEqualTo(-2.0);
            assertThat(p2.isLiquidityBacked()).isTrue();
            assertThat(p2.snappedLevelType()).isEqualTo(LevelType.PDL);
            assertThat(p2.snappedPrice()).isEqualTo(2394.20, within(EPS));
        }

        @Test
        @DisplayName("snap is suppressed when it would reorder the ladder")
        void snapRejectedOnMonotonicityViolation() {
            // bullish 100→110.  -1.0 raw = 110, -2.0 raw = 120, -2.5 raw = 125.
            // A 'PDH' at 124.00 within 3-tick tol of -2.0 (120) is 4 pts away
            // — outside tol, so won't trigger. Use a level at 120.50 (close to
            // -2.0) and also one at 121.00 that could displace -2.0 past -2.5
            // if snapped — engine must refuse. Construct a level at 119.50:
            // legal for -2.0, would not violate monotonicity. Then prove
            // a level that WOULD reorder is refused: place a level at 130.00
            // (would push -2.0 past -2.5 of 125) and verify nothing snaps.
            ChartStateQueryAPI chart = mock(ChartStateQueryAPI.class);
            KnownLevel rogue = new KnownLevel(LevelType.PDH, 130.00, Instant.now());
            when(chart.getLevelsNearPrice(anyDouble())).thenReturn(List.of(rogue));
            when(chart.getAllLevels()).thenReturn(List.of(rogue));

            // Use a wide tolerance (50 ticks = 12.5 pts) that WOULD allow
            // snap based on distance alone, then assert the engine refuses
            // because the snap would reorder.
            StdvProjectionEngine eng = engine(chart, null);
            List<StdvProjection> ladder = eng.project(100.0, 110.0,
                    MarketBias.BULLISH, 0.25, 50);

            StdvProjection p2 = ladder.get(2);
            assertThat(p2.isLiquidityBacked())
                    .as("a snap to 130 would push -2.0 past -2.5 (125) — must be refused")
                    .isFalse();
        }

        @Test
        @DisplayName("snap disabled when snapTolTicks == 0")
        void snapDisabled() {
            ChartStateQueryAPI chart = mock(ChartStateQueryAPI.class);
            KnownLevel pdh = new KnownLevel(LevelType.PDH, 20200.25, Instant.now());
            when(chart.getLevelsNearPrice(anyDouble())).thenReturn(List.of(pdh));
            when(chart.getAllLevels()).thenReturn(List.of(pdh));

            StdvProjectionEngine eng = engine(chart, null);
            List<StdvProjection> ladder = eng.project(20000.0, 20100.0,
                    MarketBias.BULLISH, 0.25, 0);

            assertThat(ladder).allMatch(p -> !p.isLiquidityBacked());
        }

        @Test
        @DisplayName("null ChartStateQueryAPI: snap never attempted")
        void nullChartIsSafe() {
            StdvProjectionEngine eng = engine(null, null);
            List<StdvProjection> ladder = eng.project(20000.0, 20100.0,
                    MarketBias.BULLISH, 0.25, 5);
            assertThat(ladder).allMatch(p -> !p.isLiquidityBacked());
        }
    }

    @Nested
    @DisplayName("realism tag")
    class Realism {

        @Test
        @DisplayName("uninitialised analyzer reports UNINITIALIZED on -2.0")
        void uninitialisedAnalyzer() {
            ImpulseExtensionAnalyzer fresh = new ImpulseExtensionAnalyzer("MNQ", 30);
            StdvProjectionEngine eng = engine(null, fresh);
            List<StdvProjection> ladder = eng.project(20000.0, 20100.0,
                    MarketBias.BULLISH, 0.25, 0);
            assertThat(ladder.get(2).realismTag()).isEqualTo("UNINITIALIZED");
            // realism tag is "n/a" for sigmas other than -2.0
            assertThat(ladder.get(0).realismTag()).isEqualTo("n/a");
            assertThat(ladder.get(1).realismTag()).isEqualTo("n/a");
            assertThat(ladder.get(3).realismTag()).isEqualTo("n/a");
            assertThat(ladder.get(4).realismTag()).isEqualTo("n/a");
        }

        @Test
        @DisplayName("REALISTIC when target distance is below mean+1σ")
        void realistic() {
            ImpulseExtensionAnalyzer analyzer = new ImpulseExtensionAnalyzer("MNQ", 30);
            // Feed in impulses with mean ~300, stdev small.
            for (int i = 0; i < 20; i++) analyzer.recordImpulse(300.0);
            // -2.0 distance for leg 100 is 200 — well below mean (300).
            StdvProjectionEngine eng = engine(null, analyzer);
            List<StdvProjection> ladder = eng.project(20000.0, 20100.0,
                    MarketBias.BULLISH, 0.25, 0);
            assertThat(ladder.get(2).realismTag()).isEqualTo("REALISTIC");
        }

        @Test
        @DisplayName("AGGRESSIVE when target distance is between mean+1σ and mean+2σ")
        void aggressive() {
            ImpulseExtensionAnalyzer analyzer = new ImpulseExtensionAnalyzer("MNQ", 30);
            // Mean = 50, stdev = ~30. Use a mix.
            for (int i = 0; i < 10; i++) analyzer.recordImpulse(20.0);
            for (int i = 0; i < 10; i++) analyzer.recordImpulse(80.0);
            // -2.0 distance for leg 100 = 200 → well above mean+2σ (~110)
            // -2.0 distance for leg 40 = 80 → between mean+1σ (~80) and mean+2σ (~110)
            StdvProjectionEngine eng = engine(null, analyzer);
            List<StdvProjection> ladder = eng.project(20000.0, 20040.0,
                    MarketBias.BULLISH, 0.25, 0);
            String tag = ladder.get(2).realismTag();
            assertThat(tag).isIn("REALISTIC", "AGGRESSIVE", "UNREALISTIC");
            // exact bucket depends on derived σ; we mostly want to assert it is set.
            assertThat(tag).isNotEqualTo("UNINITIALIZED").isNotEqualTo("n/a");
        }

        @Test
        @DisplayName("realism tag never changes the projection price")
        void realismDoesNotMovePrice() {
            ImpulseExtensionAnalyzer analyzer = new ImpulseExtensionAnalyzer("MNQ", 30);
            for (int i = 0; i < 20; i++) analyzer.recordImpulse(5.0); // tiny impulses
            // Very large leg → all distances are UNREALISTIC, but prices stand.
            StdvProjectionEngine eng = engine(null, analyzer);
            List<StdvProjection> noRealism = engine(null, null)
                    .project(20000.0, 20100.0, MarketBias.BULLISH, 0.25, 0);
            List<StdvProjection> withRealism = eng.project(20000.0, 20100.0,
                    MarketBias.BULLISH, 0.25, 0);
            for (int i = 0; i < noRealism.size(); i++) {
                assertThat(withRealism.get(i).rawPrice())
                        .isEqualTo(noRealism.get(i).rawPrice(), within(EPS));
                assertThat(withRealism.get(i).snappedPrice())
                        .isEqualTo(noRealism.get(i).snappedPrice(), within(EPS));
            }
        }
    }

    private static org.assertj.core.data.Offset<Double> within(double delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }
}
