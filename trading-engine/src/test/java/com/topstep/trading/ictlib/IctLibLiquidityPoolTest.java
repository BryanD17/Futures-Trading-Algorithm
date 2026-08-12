package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * §S6 LIQUIDITY POOLS — Appendix W4 as a direct test of the cluster
 * arithmetic, plus pivot confirmation, ATR-scaled tolerance, the two-phase
 * break lifecycle, update-not-duplicate, and the retention cap.
 */
class IctLibLiquidityPoolTest {

    private static SwingPivots.Pivot swing(long bar, double price) {
        return new SwingPivots.Pivot(bar, price, IctLibFixture.T0.plusSeconds(60L * bar), true);
    }

    // ── W4: THE CLUSTER ARITHMETIC ─────────────────────────────────────────

    @Test
    @DisplayName("W4 positive: swings 21050.0 / 21048.5 / 21051.0 with tolerance 3.2 → pool 21049.75")
    void w4Cluster() {
        List<SwingPivots.Pivot> swings = List.of(
                swing(10, 21050.0), swing(20, 21048.5), swing(30, 21051.0));

        LiquidityPoolDetector.Cluster c =
                LiquidityPoolDetector.cluster(swings, 3.2, 3);

        assertThat(c).isNotNull();
        assertThat(c.size()).isEqualTo(3);
        assertThat(c.minPrice()).isEqualTo(21048.5);
        assertThat(c.maxPrice()).isEqualTo(21051.0);
        assertThat(c.poolPrice()).isEqualTo(21049.75, within(1e-9));
        assertThat(c.oldest().bar()).isEqualTo(10L);       // anchored at the OLDEST

        // The zone the detector derives from it.
        assertThat(c.poolPrice() - 3.2).isEqualTo(21046.55, within(1e-9));
        assertThat(c.poolPrice() + 3.2).isEqualTo(21052.95, within(1e-9));
    }

    @Test
    @DisplayName("W4 negative: a swing 5.0 away breaks the run → cluster of 2 → no pool")
    void w4NegativeTooFar() {
        List<SwingPivots.Pivot> swings = List.of(
                swing(10, 21046.0), swing(20, 21050.0), swing(30, 21051.0));
        assertThat(LiquidityPoolDetector.cluster(swings, 3.2, 3)).isNull();
    }

    @Test
    @DisplayName("The run must be CONTIGUOUS in recency: an old in-range swing behind an out-of-range one does not count")
    void clusterIsContiguous() {
        List<SwingPivots.Pivot> swings = List.of(
                swing(5, 21050.5),    // in range, but…
                swing(10, 21070.0),   // …this one ends the run
                swing(20, 21050.0),
                swing(30, 21051.0));
        assertThat(LiquidityPoolDetector.cluster(swings, 3.2, 3)).isNull();
    }

    @Test
    @DisplayName("Tolerance scales with ATR: the same swings cluster at high ATR and not at low ATR")
    void toleranceScalesWithAtr() {
        List<SwingPivots.Pivot> swings = List.of(
                swing(10, 21050.0), swing(20, 21048.5), swing(30, 21051.0));
        assertThat(LiquidityPoolDetector.cluster(swings, 8.0 / 2.5, 3)).isNotNull();
        assertThat(LiquidityPoolDetector.cluster(swings, 2.0 / 2.5, 3)).isNull();
    }

    // ── PIVOT CONFIRMATION + END-TO-END FORMATION ──────────────────────────

    /**
     * Bars whose highs are flat at {@code base} except at the pivot indices,
     * where they rise to the given prices. swingLen=5 + 1-bar confirmation
     * means a pivot needs 5 lower bars before it and 1 after.
     */
    private static List<Candle> pivotSeries(double base, double... pivotHighs) {
        List<Candle> out = new ArrayList<>();
        int bar = 0;
        for (int i = 0; i < 5; i++) {
            out.add(IctLibFixture.c(bar++, base - 2, base, base - 6, base - 1));
        }
        for (double ph : pivotHighs) {
            out.add(IctLibFixture.c(bar++, base - 2, ph, base - 6, base - 1));
            for (int i = 0; i < 5; i++) {
                out.add(IctLibFixture.c(bar++, base - 2, base, base - 6, base - 1));
            }
        }
        return out;
    }

    @Test
    @DisplayName("Three clustered confirmed pivot highs form ONE buyside pool, published to the listener")
    void poolFormsAndPublishes() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        h.pushAll(pivotSeries(21040, 21050.0, 21048.5, 21051.0));

        List<Detection> pools = h.registry.byType(DetectionType.LIQUIDITY_POOL);
        assertThat(pools).hasSize(1);
        Detection p = pools.get(0);
        assertThat(p.direction()).isEqualTo(DetectionDirection.BULLISH);
        assertThat(p.meta()).containsEntry("side", "BUYSIDE");
        assertThat(p.meta()).containsEntry("clusterSize", 3);
        assertThat((Double) p.meta().get("poolPrice")).isEqualTo(21049.75, within(1e-9));
        assertThat(p.state()).isEqualTo(DetectionState.ACTIVE);

        assertThat(h.pools).isNotEmpty();
        assertThat(h.pools.get(h.pools.size() - 1).meta())
                .containsEntry("side", "BUYSIDE");
    }

    @Test
    @DisplayName("A fourth clustered pivot UPDATES the same pool instead of duplicating it")
    void fourthPivotUpdatesInPlace() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        h.pushAll(pivotSeries(21040, 21050.0, 21048.5, 21051.0, 21049.0));

        List<Detection> pools = h.registry.byType(DetectionType.LIQUIDITY_POOL);
        assertThat(pools).hasSize(1);
        assertThat(pools.get(0).meta()).containsEntry("clusterSize", 4);
    }

    @Test
    @DisplayName("Two-phase break: close past the near boundary → PARTIAL, past the far one → SWEPT")
    void twoPhaseBreak() {
        DetectionRegistry registry = new DetectionRegistry(
                IctLibFixture.SYM, IctLibConfig.defaults().retentions());
        TimeframeSeries series = new TimeframeSeries(IctLibEngine.TF_1M);
        LiquidityPoolDetector detector =
                new LiquidityPoolDetector(IctLibConfig.defaults(), null);

        series.push(IctLibFixture.c(0, 21040, 21042, 21038, 21040));
        MutableDetection pool = registry.create(DetectionType.LIQUIDITY_POOL,
                IctLibEngine.TF_1M, DetectionDirection.BULLISH,
                21046.55, 21052.95, 0, IctLibFixture.T0, DetectionState.ACTIVE);
        pool.putMeta("brokenLow", Boolean.FALSE);
        pool.putMeta("brokenHigh", Boolean.FALSE);

        // W4: close 21047.0 is above zone.low → PARTIAL (being raided).
        series.push(IctLibFixture.c(1, 21044, 21048, 21043, 21047.0));
        detector.onBar(series, registry);
        assertThat(registry.byType(DetectionType.LIQUIDITY_POOL).get(0).state())
                .isEqualTo(DetectionState.PARTIAL);

        // W4: close 21053.5 is above zone.high → SWEPT (consumed, terminal).
        series.push(IctLibFixture.c(2, 21047, 21055, 21046, 21053.5));
        detector.onBar(series, registry);
        Detection swept = registry.byType(DetectionType.LIQUIDITY_POOL).get(0);
        assertThat(swept.state()).isEqualTo(DetectionState.SWEPT);
        assertThat(swept.terminal()).isTrue();

        // Monotonic: dropping back below cannot un-sweep it.
        series.push(IctLibFixture.c(3, 21053, 21054, 21040, 21041));
        detector.onBar(series, registry);
        assertThat(registry.byType(DetectionType.LIQUIDITY_POOL).get(0).state())
                .isEqualTo(DetectionState.SWEPT);
    }

    @Test
    @DisplayName("Sellside mirror: a pool below price is swept downward")
    void sellsideMirror() {
        DetectionRegistry registry = new DetectionRegistry(
                IctLibFixture.SYM, IctLibConfig.defaults().retentions());
        TimeframeSeries series = new TimeframeSeries(IctLibEngine.TF_1M);
        LiquidityPoolDetector detector =
                new LiquidityPoolDetector(IctLibConfig.defaults(), null);

        series.push(IctLibFixture.c(0, 21060, 21062, 21058, 21060));
        MutableDetection pool = registry.create(DetectionType.LIQUIDITY_POOL,
                IctLibEngine.TF_1M, DetectionDirection.BEARISH,
                21046.55, 21052.95, 0, IctLibFixture.T0, DetectionState.ACTIVE);
        pool.putMeta("brokenLow", Boolean.FALSE);
        pool.putMeta("brokenHigh", Boolean.FALSE);

        series.push(IctLibFixture.c(1, 21058, 21059, 21050, 21052.0));
        detector.onBar(series, registry);
        assertThat(registry.byType(DetectionType.LIQUIDITY_POOL).get(0).state())
                .isEqualTo(DetectionState.PARTIAL);

        series.push(IctLibFixture.c(2, 21052, 21053, 21044, 21045.0));
        detector.onBar(series, registry);
        assertThat(registry.byType(DetectionType.LIQUIDITY_POOL).get(0).state())
                .isEqualTo(DetectionState.SWEPT);
    }

    @Test
    @DisplayName("ABSTAIN while ATR is undefined: a cold series forms no pools and throws nothing")
    void abstainsWhileCold() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        for (int i = 0; i < 5; i++) {
            h.push(IctLibFixture.c(i, 21040, 21050, 21030, 21045));
        }
        assertThat(h.registry.byType(DetectionType.LIQUIDITY_POOL)).isEmpty();
    }

    @Test
    @DisplayName("Retention holds at 4 per side")
    void retentionCap() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        double base = 21040;
        for (int group = 0; group < 12; group++) {
            // Each group builds its own 3-swing cluster at a fresh price shelf.
            double shelf = base + group * 60;
            h.pushAll(shiftBars(pivotSeries(shelf, shelf + 10, shelf + 8.5, shelf + 11),
                    group * 40));
        }
        assertThat(h.registry.activeByType(DetectionType.LIQUIDITY_POOL,
                DetectionDirection.BULLISH).size() + countTerminalBuyside(h))
                .isLessThanOrEqualTo(4);
    }

    private static int countTerminalBuyside(IctLibFixture.Harness h) {
        int n = 0;
        for (Detection d : h.registry.byType(DetectionType.LIQUIDITY_POOL)) {
            if (d.terminal() && d.direction() == DetectionDirection.BULLISH) n++;
        }
        return n;
    }

    private static List<Candle> shiftBars(List<Candle> bars, int offsetMinutes) {
        List<Candle> out = new ArrayList<>(bars.size());
        for (Candle c : bars) {
            out.add(new Candle(c.getSymbol(), c.getTimestamp().plusSeconds(60L * offsetMinutes),
                    c.getOpen(), c.getHigh(), c.getLow(), c.getClose(), c.getVolume()));
        }
        return out;
    }
}
