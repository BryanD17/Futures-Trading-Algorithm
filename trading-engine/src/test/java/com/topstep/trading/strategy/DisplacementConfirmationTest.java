package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single-candle displacement confirmation defect (V4 follow-up).
 *
 * <p>{@code detectDisplacement} used to apply {@code totalMove >=
 * minDisplacementMove} to the single-candle case as well as the multi-candle
 * one. For a single candle {@code totalMove} is the BODY, while
 * {@code minDisplacementMove} is {@code avgRange * multiplier} — a threshold
 * derived from average RANGE. Since body &lt;= range, that check SUBSUMED the
 * expansion test already performed in {@code isStrongCandle}, and the effective
 * bar became:
 *
 * <pre>
 *   body >= 1.5 * avgRange     and     body >= 0.65 * range
 *   =>  range >= ~2.3x the 14-bar average range
 * </pre>
 *
 * against a documented intent of 1.5x ATR. Measured on the SIM tape, the
 * detector fired on 0.63% of 5m bars — once or twice per session — so the
 * SWEEP_DONE -&gt; DISPLACED transition essentially never happened and the funnel
 * could not reach an entry.
 */
class DisplacementConfirmationTest {

    private static final String SYM = "MNQ";
    private static final Instant T = Instant.parse("2026-08-11T14:00:00Z");

    private static Candle c(int i, double o, double h, double l, double close) {
        return new Candle(SYM, T.plusSeconds(60L * i), o, h, l, close, 100L);
    }

    /** 14 quiet bars of range 4.0, so avgRange = 4.0 and the 1.5x bar is 6.0. */
    private static List<Candle> quietBackdrop() {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            out.add(c(i, 21000, 21002, 20998, 21001));
        }
        return out;
    }

    @Test
    @DisplayName("A single expansion candle at 1.75x average range with a 78% body IS displacement")
    void singleExpansionCandleQualifies() {
        DisplacementDetector d = new DisplacementDetector(20, 1.5, 0.65, SYM);
        for (Candle bar : quietBackdrop()) d.update(bar);

        // range 7.0 (1.75x the 4.0 average), body 5.5 -> bodyRatio 79%.
        // Body alone is 5.5, BELOW the old 6.0 body-vs-avgRange bar, so this
        // exact candle is what the defect used to reject.
        d.update(c(14, 21000.5, 21007.0, 21000.0, 21006.0));

        assertThat(d.hasRecentDisplacement(1, true))
                .as("an expansion candle dominated by its body is displacement")
                .isTrue();
    }

    @Test
    @DisplayName("The expansion test still bites: a candle below the range multiple is NOT displacement")
    void weakCandleStillRejected() {
        DisplacementDetector d = new DisplacementDetector(20, 1.5, 0.65, SYM);
        for (Candle bar : quietBackdrop()) d.update(bar);

        // range 5.0 = 1.25x average — under the 1.5x bar.
        d.update(c(14, 21000.5, 21005.5, 21000.5, 21004.8));

        assertThat(d.hasRecentDisplacement(1)).isFalse();
    }

    @Test
    @DisplayName("The body-ratio test still bites: a big range with a small body is NOT displacement")
    void wickyCandleStillRejected() {
        DisplacementDetector d = new DisplacementDetector(20, 1.5, 0.65, SYM);
        for (Candle bar : quietBackdrop()) d.update(bar);

        // range 10.0 (2.5x average) but body only 2.0 -> bodyRatio 20%.
        d.update(c(14, 21001.0, 21008.0, 20998.0, 21003.0));

        assertThat(d.hasRecentDisplacement(1))
                .as("an expansion bar with a huge rejection wick is not displacement")
                .isFalse();
    }

    @Test
    @DisplayName("The MULTI-candle branch keeps its distance test — a weak run does not qualify")
    void multiCandleRunStillNeedsDistance() {
        DisplacementDetector d = new DisplacementDetector(20, 1.5, 0.65, SYM);
        for (Candle bar : quietBackdrop()) d.update(bar);

        // Two consecutive strong-ish candles whose COMBINED move is still
        // under the threshold: the multi-candle path must reject it.
        d.update(c(14, 21000.0, 21001.2, 20999.9, 21001.0));
        d.update(c(15, 21001.0, 21002.2, 21000.9, 21002.0));

        assertThat(d.hasRecentDisplacement(1)).isFalse();
    }

    @Test
    @DisplayName("Direction is respected")
    void directionRespected() {
        DisplacementDetector d = new DisplacementDetector(20, 1.5, 0.65, SYM);
        for (Candle bar : quietBackdrop()) d.update(bar);
        d.update(c(14, 21006.0, 21006.5, 20999.5, 21000.5));   // strong DOWN bar

        assertThat(d.hasRecentDisplacement(1, false)).isTrue();
        assertThat(d.hasRecentDisplacement(1, true)).isFalse();
    }
}
