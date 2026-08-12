package com.topstep.trading.ictlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * §S1 DISPLACEMENT — Appendix W1 turned into tests verbatim.
 */
class IctLibDisplacementTest {

    /** W1's exact prior bodies: 8, 10, 9, 7, 11 → meanBody = 9.0. */
    private static List<com.topstep.trading.domain.Candle> w1Priors() {
        return List.of(
                IctLibFixture.c(0, 20950, 20958, 20950, 20958),  // body 8
                IctLibFixture.c(1, 20958, 20968, 20958, 20968),  // body 10
                IctLibFixture.c(2, 20968, 20977, 20968, 20977),  // body 9
                IctLibFixture.c(3, 20977, 20984, 20977, 20984),  // body 7
                IctLibFixture.c(4, 20984, 20995, 20984, 20995)); // body 11
    }

    @Test
    @DisplayName("W1 positive: body 15 > meanBody 9, both wicks 1.0 < 5.4 → displacementUp")
    void w1Positive() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        h.pushAll(w1Priors());
        h.push(IctLibFixture.c(5, 21000.00, 21016.00, 20999.00, 21015.00));

        assertThat(DisplacementRule.meanBody(h.series, 0, 5)).isEqualTo(9.0, within(1e-9));

        List<Detection> found = h.registry.byType(DetectionType.DISPLACEMENT);
        assertThat(found).hasSize(1);
        Detection d = found.get(0);
        assertThat(d.direction()).isEqualTo(DetectionDirection.BULLISH);
        assertThat(d.state()).isEqualTo(DetectionState.POINT);
        assertThat(d.meta()).containsEntry("body", 15.0);
        assertThat(d.priceTop()).isEqualTo(21016.00);
        assertThat(d.priceBottom()).isEqualTo(20999.00);
    }

    @Test
    @DisplayName("W1 negative: same bar with high 21022 → wickTop 7.0 > 5.4 → NOT displacement")
    void w1WickNegative() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        h.pushAll(w1Priors());
        h.push(IctLibFixture.c(5, 21000.00, 21022.00, 20999.00, 21015.00));

        assertThat(h.registry.byType(DetectionType.DISPLACEMENT)).isEmpty();
        assertThat(DisplacementRule.isDisplacementUp(h.series, 0, 5, 0.36)).isFalse();
    }

    @Test
    @DisplayName("ABSTAIN: fewer than meanLen prior bars → no detection, no exception")
    void abstainsWhileCold() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        h.push(IctLibFixture.c(0, 21000, 21016, 20999, 21015));
        h.push(IctLibFixture.c(1, 21015, 21031, 21014, 21030));

        assertThat(DisplacementRule.meanBody(h.series, 0, 5)).isNaN();
        assertThat(h.registry.byType(DetectionType.DISPLACEMENT)).isEmpty();
    }

    @Test
    @DisplayName("Bearish mirror: down bar with dominant body and small wicks")
    void bearishMirror() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        h.pushAll(w1Priors());
        h.push(IctLibFixture.c(5, 21015.00, 21016.00, 20999.00, 21000.00));

        List<Detection> found = h.registry.byType(DetectionType.DISPLACEMENT);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).direction()).isEqualTo(DetectionDirection.BEARISH);
    }
}
