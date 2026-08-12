package com.topstep.trading.ictlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §S3 BALANCED PRICE RANGE.
 *
 * <p>The gaps are seeded directly into the registry rather than grown from a
 * candle sequence: §S3 is a statement about two OPPOSING active gaps, and
 * building tape that leaves one of each with a chosen overlap would test the
 * §S2 detector all over again instead of the region arithmetic under test.
 */
class IctLibBprTest {

    private final DetectionRegistry registry =
            new DetectionRegistry(IctLibFixture.SYM, IctLibConfig.defaults().retentions());
    private final TimeframeSeries series = new TimeframeSeries(IctLibEngine.TF_1M);
    private final BprDetector detector = new BprDetector();

    private void bar(int minute, double o, double h, double l, double c) {
        series.push(IctLibFixture.c(minute, o, h, l, c));
        detector.onBar(series, registry);
    }

    private void seedGap(DetectionDirection direction, double bottom, double top) {
        registry.create(DetectionType.FVG, IctLibEngine.TF_1M, direction,
                bottom, top, series.barIndex(),
                IctLibFixture.T0.plusSeconds(60L * series.barIndex()),
                DetectionState.ACTIVE);
    }

    @Test
    @DisplayName("Overlapping active bull + bear gaps form a BPR at the intersection")
    void regionIsTheIntersection() {
        series.push(IctLibFixture.c(0, 21000, 21012, 20998, 21008));
        seedGap(DetectionDirection.BEARISH, 21000, 21010);
        detector.onBar(series, registry);
        assertThat(registry.byType(DetectionType.BPR)).isEmpty(); // one side only

        series.push(IctLibFixture.c(1, 21008, 21022, 21007, 21020));
        seedGap(DetectionDirection.BULLISH, 21005, 21015);
        detector.onBar(series, registry);

        List<Detection> bprs = registry.byType(DetectionType.BPR);
        assertThat(bprs).hasSize(1);
        Detection r = bprs.get(0);
        assertThat(r.priceBottom()).isEqualTo(21005.0);   // max of the bottoms
        assertThat(r.priceTop()).isEqualTo(21010.0);      // min of the tops
        assertThat(r.direction()).isEqualTo(DetectionDirection.BULLISH); // later gap
        assertThat(r.meta()).containsEntry("pos", 1);     // close 21020 above the region
        assertThat(r.state()).isEqualTo(DetectionState.ACTIVE);
    }

    @Test
    @DisplayName("Same gap pair never produces a second region")
    void pairIsDeduplicated() {
        regionIsTheIntersection();
        bar(2, 21020, 21024, 21018, 21022);
        bar(3, 21022, 21026, 21020, 21024);
        assertThat(registry.byType(DetectionType.BPR)).hasSize(1);
    }

    @Test
    @DisplayName("Lifecycle from above: re-entry → TOUCHED, close below the low → BROKEN")
    void touchedThenBroken() {
        regionIsTheIntersection();

        bar(2, 21020, 21021, 21009, 21014);   // trades into the region
        assertThat(registry.byType(DetectionType.BPR).get(0).state())
                .isEqualTo(DetectionState.TOUCHED);

        bar(3, 21014, 21015, 21000, 21002);   // closes below 21005
        Detection r = registry.byType(DetectionType.BPR).get(0);
        assertThat(r.state()).isEqualTo(DetectionState.BROKEN);
        assertThat(r.terminal()).isTrue();

        bar(4, 21002, 21030, 21001, 21028);   // monotonic: cannot revert
        assertThat(registry.byType(DetectionType.BPR).get(0).state())
                .isEqualTo(DetectionState.BROKEN);
    }

    @Test
    @DisplayName("Orientation follows the LATER-created gap; pos=-1 breaks upward")
    void bearishOrientationBreaksUpward() {
        series.push(IctLibFixture.c(0, 21010, 21016, 21008, 21012));
        seedGap(DetectionDirection.BULLISH, 21005, 21015);
        detector.onBar(series, registry);

        series.push(IctLibFixture.c(1, 21012, 21013, 20998, 21000));
        seedGap(DetectionDirection.BEARISH, 21008, 21020);
        detector.onBar(series, registry);

        Detection r = registry.byType(DetectionType.BPR).get(0);
        assertThat(r.direction()).isEqualTo(DetectionDirection.BEARISH);
        assertThat(r.priceBottom()).isEqualTo(21008.0);
        assertThat(r.priceTop()).isEqualTo(21015.0);
        assertThat(r.meta()).containsEntry("pos", -1);    // close 21000 below the region

        series.push(IctLibFixture.c(2, 21000, 21009, 20999, 21005));
        detector.onBar(series, registry);
        assertThat(registry.byType(DetectionType.BPR).get(0).state())
                .isEqualTo(DetectionState.TOUCHED);

        series.push(IctLibFixture.c(3, 21005, 21020, 21004, 21018));
        detector.onBar(series, registry);
        assertThat(registry.byType(DetectionType.BPR).get(0).state())
                .isEqualTo(DetectionState.BROKEN);
    }

    @Test
    @DisplayName("Non-overlapping gaps produce no region")
    void disjointGapsProduceNothing() {
        series.push(IctLibFixture.c(0, 21000, 21012, 20998, 21008));
        seedGap(DetectionDirection.BEARISH, 21000, 21004);
        series.push(IctLibFixture.c(1, 21008, 21022, 21007, 21020));
        seedGap(DetectionDirection.BULLISH, 21010, 21015);
        detector.onBar(series, registry);

        assertThat(registry.byType(DetectionType.BPR)).isEmpty();
    }

    @Test
    @DisplayName("Only literally-ACTIVE gaps pair up — a TOUCHED gap is already being spent")
    void touchedGapDoesNotPair() {
        series.push(IctLibFixture.c(0, 21000, 21012, 20998, 21008));
        seedGap(DetectionDirection.BEARISH, 21000, 21010);
        List<MutableDetection> gaps =
                registry.mutableView(DetectionType.FVG, IctLibEngine.TF_1M);
        gaps.get(0).advanceTo(DetectionState.TOUCHED, IctLibFixture.T0, 0);

        series.push(IctLibFixture.c(1, 21008, 21022, 21007, 21020));
        seedGap(DetectionDirection.BULLISH, 21005, 21015);
        detector.onBar(series, registry);

        assertThat(registry.byType(DetectionType.BPR)).isEmpty();
    }
}
