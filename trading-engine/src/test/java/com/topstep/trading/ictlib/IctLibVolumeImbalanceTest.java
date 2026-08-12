package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §S4 VOLUME IMBALANCE — Appendix W3 verbatim, plus a near-miss negative for
 * EVERY one of the five inequalities, in both directions.
 *
 * <p>Ten negatives for one detector looks excessive until you notice that §S4
 * is five chained comparisons: flip any single sign and the detector still
 * compiles, still fires most of the time, and silently marks the wrong zones.
 * That is risk G-R4, and this is the only way to close it. (Condition (3)
 * turns out to be implied by (5) for geometrically valid candles — see
 * {@link #bullNegativeCondition3()} — so its negative asserts the outcome
 * rather than an isolated cause.)
 */
class IctLibVolumeImbalanceTest {

    // ── W3 BULLISH POSITIVE ────────────────────────────────────────────────
    // i-1 = (20995, 21003, 20993, 21002)   bodyTop[i-1] = 21002, h[i-1] = 21003
    // i   = (21005, 21012, 21002.5, 21011) bodyBot[i]   = 21005
    private static final Candle W3_PREV = IctLibFixture.c(0, 20995, 21003, 20993, 21002);
    private static final Candle W3_CUR = IctLibFixture.c(1, 21005, 21012, 21002.5, 21011);

    private IctLibFixture.Harness feed(Candle prev, Candle cur) {
        IctLibFixture.Harness h = IctLibFixture.harness();
        h.push(prev);
        h.push(cur);
        return h;
    }

    private List<Detection> vis(IctLibFixture.Harness h) {
        return h.registry.byType(DetectionType.VOLUME_IMBALANCE);
    }

    @Test
    @DisplayName("W3 positive: all five hold → bullish VI zone [21002.00, 21005.00]")
    void w3Positive() {
        IctLibFixture.Harness h = feed(W3_PREV, W3_CUR);
        List<Detection> found = vis(h);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).direction()).isEqualTo(DetectionDirection.BULLISH);
        assertThat(found.get(0).priceBottom()).isEqualTo(21002.00);
        assertThat(found.get(0).priceTop()).isEqualTo(21005.00);
        assertThat(found.get(0).state()).isEqualTo(DetectionState.ACTIVE);
        assertThat(found.get(0).meta()).containsEntry("projectBars", 3);
    }

    @Test
    @DisplayName("W3 negative (1'): o[i]=21001.5 is not above c[i-1]=21002 → no VI")
    void bullNegativeCondition1() {
        assertThat(vis(feed(W3_PREV, IctLibFixture.c(1, 21001.5, 21012, 21002.5, 21011))))
                .isEmpty();
    }

    @Test
    @DisplayName("W3 negative (2'): c[i]=21001 is not above c[i-1]=21002 → no VI")
    void bullNegativeCondition2() {
        assertThat(vis(feed(W3_PREV, IctLibFixture.c(1, 21005, 21012, 21002.5, 21001))))
                .isEmpty();
    }

    @Test
    @DisplayName("W3 negative (3'): o[i-1]=21006 makes o[i]=21005 not above it → no VI")
    void bullNegativeCondition3() {
        // NOTE (spec observation, not a deviation): for geometrically valid
        // candles, condition (3) cannot be the SOLE failure. o[i-1] <= h[i-1],
        // and (5) requires h[i-1] < bodyBot[i] <= o[i], so (5) already implies
        // o[i] > o[i-1]. Raising o[i-1] to 21006 therefore breaks (5) as well.
        // The condition is still evaluated in spec order; the assertion is the
        // one that matters — the flip yields nothing.
        assertThat(vis(feed(IctLibFixture.c(0, 21006, 21007, 20993, 21002), W3_CUR)))
                .isEmpty();
    }

    @Test
    @DisplayName("W3 negative (4'): l[i]=21003.5 leaves no wick overlap — that is an opening gap")
    void bullNegativeCondition4() {
        assertThat(vis(feed(W3_PREV, IctLibFixture.c(1, 21005, 21012, 21003.5, 21011))))
                .isEmpty();
    }

    @Test
    @DisplayName("W3 negative (5'): h[i-1]=21006 pierces the current body → no VI")
    void bullNegativeCondition5() {
        assertThat(vis(feed(IctLibFixture.c(0, 20995, 21006, 20993, 21002), W3_CUR)))
                .isEmpty();
    }

    // ── BEARISH MIRROR ─────────────────────────────────────────────────────
    // i-1 = (21005, 21012, 21002.5, 21011)  bodyBot[i-1] = 21005, l[i-1] = 21002.5
    // i   = (21000, 21003, 20993, 20994)    bodyTop[i]   = 21000
    private static final Candle B_PREV = IctLibFixture.c(0, 21005, 21012, 21002.5, 21011);
    private static final Candle B_CUR = IctLibFixture.c(1, 21000, 21003, 20993, 20994);

    @Test
    @DisplayName("Bearish positive: mirror of W3 → zone [21000.00, 21005.00]")
    void bearishPositive() {
        List<Detection> found = vis(feed(B_PREV, B_CUR));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).direction()).isEqualTo(DetectionDirection.BEARISH);
        assertThat(found.get(0).priceBottom()).isEqualTo(21000.00);
        assertThat(found.get(0).priceTop()).isEqualTo(21005.00);
    }

    @Test
    @DisplayName("Bearish negative (1'): o[i] not below c[i-1] → no VI")
    void bearNegativeCondition1() {
        assertThat(vis(feed(B_PREV, IctLibFixture.c(1, 21011.5, 21012.5, 20993, 20994))))
                .isEmpty();
    }

    @Test
    @DisplayName("Bearish negative (2'): c[i] not below c[i-1] → no VI")
    void bearNegativeCondition2() {
        assertThat(vis(feed(B_PREV, IctLibFixture.c(1, 21000, 21013, 20993, 21011.5))))
                .isEmpty();
    }

    @Test
    @DisplayName("Bearish negative (3'): o[i] not below o[i-1] → no VI")
    void bearNegativeCondition3() {
        // Same redundancy as the bullish (3'), mirrored: l[i-1] <= o[i-1] and
        // (5) requires l[i-1] > bodyTop[i] >= o[i].
        assertThat(vis(feed(IctLibFixture.c(0, 20999, 21012, 20998, 21011), B_CUR)))
                .isEmpty();
    }

    @Test
    @DisplayName("Bearish negative (4'): no wick overlap → opening gap, not VI")
    void bearNegativeCondition4() {
        assertThat(vis(feed(B_PREV, IctLibFixture.c(1, 21000, 21002, 20993, 20994))))
                .isEmpty();
    }

    @Test
    @DisplayName("Bearish negative (5'): l[i-1] pierces the current body → no VI")
    void bearNegativeCondition5() {
        assertThat(vis(feed(IctLibFixture.c(0, 21005, 21012, 20999, 21011), B_CUR)))
                .isEmpty();
    }

    // ── LIFECYCLE + RETENTION ──────────────────────────────────────────────

    @Test
    @DisplayName("FILLED only when a later candle's RANGE fully covers the zone")
    void filledNeedsFullCoverage() {
        IctLibFixture.Harness h = feed(W3_PREV, W3_CUR);

        // Pokes in but does not cover [21002, 21005] — still ACTIVE.
        h.push(IctLibFixture.c(2, 21011, 21012, 21003, 21010));
        assertThat(vis(h).get(0).state()).isEqualTo(DetectionState.ACTIVE);

        h.push(IctLibFixture.c(3, 21004, 21006, 21001, 21004));
        Detection filled = vis(h).get(0);
        assertThat(filled.state()).isEqualTo(DetectionState.FILLED);
        assertThat(filled.terminal()).isTrue();
    }

    @Test
    @DisplayName("Retention holds at 6 across a long feed")
    void retentionCap() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        for (int i = 0; i < 40; i++) {
            double base = 21000 + i * 20;
            h.push(IctLibFixture.c(2 * i, base - 5, base + 3, base - 7, base + 2));
            h.push(IctLibFixture.c(2 * i + 1, base + 5, base + 12, base + 2.5, base + 11));
        }
        assertThat(h.registry.count(DetectionType.VOLUME_IMBALANCE))
                .isLessThanOrEqualTo(6);
    }
}
