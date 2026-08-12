package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §S2 FAIR VALUE GAP — Appendix W2 turned into tests verbatim, plus the
 * lifecycle, the consecutive-gap merge, the IFVG flag and the retention cap.
 *
 * <p>The mandatory test in this class is {@link #noDisplacementNoGap()}: it is
 * the one assertion that proves ictlib is not just a second copy of the legacy
 * three-candle detector.
 */
class IctLibFvgTest {

    /** W2's i-2 candle. Body 10 — deliberately below the warmup mean of 11.6. */
    private static Candle w2Context() {
        return IctLibFixture.c(5, 20990, 21001, 20988, 21000);
    }

    /** W2's i-1 candle: body 15 > mean 11.2, wicks 1.0 → displacementUp. */
    private static Candle w2Displacement() {
        return IctLibFixture.c(6, 21000, 21016, 20999, 21015);
    }

    /** W2's i candle: l[i] = 21004 > h[i-2] = 21001. */
    private static Candle w2Gap() {
        return IctLibFixture.c(7, 21015, 21020, 21004, 21018);
    }

    private static IctLibFixture.Harness primed() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        h.pushAll(IctLibFixture.warmupBars());
        return h;
    }

    @Test
    @DisplayName("W2 positive: displacement at i-1 + l[i] > h[i-2] → bullish FVG [21001, 21004]")
    void w2Positive() {
        IctLibFixture.Harness h = primed();
        h.push(w2Context());
        h.push(w2Displacement());
        h.push(w2Gap());

        List<Detection> bull = h.fvgs(DetectionDirection.BULLISH);
        assertThat(bull).hasSize(1);
        Detection fvg = bull.get(0);
        assertThat(fvg.priceBottom()).isEqualTo(21001.0);
        assertThat(fvg.priceTop()).isEqualTo(21004.0);
        assertThat(fvg.state()).isEqualTo(DetectionState.ACTIVE);
        assertThat(fvg.timeframe()).isEqualTo("1m");
    }

    @Test
    @DisplayName("MANDATORY NEGATIVE: identical prices, i-1 body 6.0 (no displacement) → NO gap")
    void noDisplacementNoGap() {
        IctLibFixture.Harness h = primed();
        h.push(w2Context());
        // Same open/low, body 6.0 instead of 15.0 → below the 11.2 mean.
        h.push(IctLibFixture.c(6, 21000, 21007, 20999, 21006));
        h.push(w2Gap());

        // The naive three-candle rule the legacy detector uses DOES fire here…
        assertThat(IctLibDiffStats.legacyBullishGap(w2Gap(), w2Context())).isTrue();
        // …and §S2 correctly refuses it. This is the whole upgrade.
        assertThat(h.registry.byType(DetectionType.FVG)).isEmpty();
    }

    @Test
    @DisplayName("Lifecycle: l=21003.50 → TOUCHED; l=21000.75 → FILLED (terminal, monotonic)")
    void lifecycleTouchedThenFilled() {
        IctLibFixture.Harness h = primed();
        h.push(w2Context());
        h.push(w2Displacement());
        h.push(w2Gap());

        h.push(IctLibFixture.c(8, 21010, 21012, 21003.50, 21008));
        assertThat(h.fvgs(DetectionDirection.BULLISH).get(0).state())
                .isEqualTo(DetectionState.TOUCHED);

        h.push(IctLibFixture.c(9, 21008, 21009, 21000.75, 21002));
        Detection filled = h.fvgs(DetectionDirection.BULLISH).get(0);
        assertThat(filled.state()).isEqualTo(DetectionState.FILLED);
        assertThat(filled.terminal()).isTrue();

        // Monotonic: a later bar far above the zone cannot un-fill it.
        h.push(IctLibFixture.c(10, 21002, 21050, 21001.5, 21048));
        assertThat(h.fvgs(DetectionDirection.BULLISH).get(0).state())
                .isEqualTo(DetectionState.FILLED);
    }

    @Test
    @DisplayName("A gap is never TOUCHED by the bar that created it")
    void creationBarDoesNotTouchItsOwnGap() {
        IctLibFixture.Harness h = primed();
        h.push(w2Context());
        h.push(w2Displacement());
        // l[i] = 21004 sits ON the zone top; only LATER bars may touch it.
        h.push(w2Gap());
        assertThat(h.fvgs(DetectionDirection.BULLISH).get(0).state())
                .isEqualTo(DetectionState.ACTIVE);
    }

    @Test
    @DisplayName("Consecutive-gap merge: second same-direction gap widens the first, no duplicate")
    void consecutiveGapsMerge() {
        IctLibFixture.Harness h = primed();
        h.push(w2Context());                                        // i-2, body 10
        h.push(w2Displacement());                                   // i-1, displacement
        h.push(IctLibFixture.c(7, 21016, 21034, 21015, 21033));     // gap #1 + displacement
        h.push(IctLibFixture.c(8, 21033, 21038, 21020, 21036));     // gap #2 → merges

        List<Detection> bull = h.fvgs(DetectionDirection.BULLISH);
        assertThat(bull).hasSize(1);
        assertThat(bull.get(0).priceBottom()).isEqualTo(21001.0);
        assertThat(bull.get(0).priceTop()).isEqualTo(21020.0);
        assertThat(bull.get(0).meta()).containsEntry("merged", Boolean.TRUE);
    }

    @Test
    @DisplayName("IFVG mode inverts the gap comparison; the displacement requirement stays")
    void ifvgMode() {
        IctLibFixture.Harness h = IctLibFixture.harness(
                IctLibConfig.defaults().withGapMode(IctLibConfig.GapMode.IFVG));
        h.pushAll(IctLibFixture.warmupBars());
        h.push(w2Context());
        h.push(w2Displacement());
        // l[i] = 20995 < h[i-2] = 21001 → the inverted (overlap) read.
        h.push(IctLibFixture.c(7, 21015, 21020, 20995, 21018));

        List<Detection> bull = h.fvgs(DetectionDirection.BULLISH);
        assertThat(bull).hasSize(1);
        assertThat(bull.get(0).priceBottom()).isEqualTo(20995.0);
        assertThat(bull.get(0).priceTop()).isEqualTo(21001.0);
        assertThat(bull.get(0).meta()).containsEntry("mode", "IFVG");

        // Same bars in FVG mode produce nothing — the modes are genuinely different.
        IctLibFixture.Harness plain = IctLibFixture.harness();
        plain.pushAll(IctLibFixture.warmupBars());
        plain.push(w2Context());
        plain.push(w2Displacement());
        plain.push(IctLibFixture.c(7, 21015, 21020, 20995, 21018));
        assertThat(plain.registry.byType(DetectionType.FVG)).isEmpty();
    }

    @Test
    @DisplayName("Bearish mirror: displacementDown at i-1 + h[i] < l[i-2]")
    void bearishMirror() {
        IctLibFixture.Harness h = primed();
        h.push(IctLibFixture.c(5, 21000, 21012, 20999, 21010));       // i-2, body 10
        h.push(IctLibFixture.c(6, 21010, 21011, 20994, 20995));       // i-1, displacementDown
        h.push(IctLibFixture.c(7, 20995, 20996, 20988, 20990));       // h[i]=20996 < l[i-2]=20999

        List<Detection> bear = h.fvgs(DetectionDirection.BEARISH);
        assertThat(bear).hasSize(1);
        assertThat(bear.get(0).priceBottom()).isEqualTo(20996.0);
        assertThat(bear.get(0).priceTop()).isEqualTo(20999.0);

        // Consumed from below: a later high inside the zone TOUCHES it.
        h.push(IctLibFixture.c(8, 20990, 20997.5, 20989, 20996));
        assertThat(h.fvgs(DetectionDirection.BEARISH).get(0).state())
                .isEqualTo(DetectionState.TOUCHED);
        // …and a high above the top FILLS it.
        h.push(IctLibFixture.c(9, 20996, 21001, 20995, 21000));
        assertThat(h.fvgs(DetectionDirection.BEARISH).get(0).state())
                .isEqualTo(DetectionState.FILLED);
    }
}
