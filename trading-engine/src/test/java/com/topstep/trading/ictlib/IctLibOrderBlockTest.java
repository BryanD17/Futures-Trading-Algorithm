package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §S7 ORDER BLOCKS — Appendix W5 as a candle sequence, in both zone modes,
 * through the full ACTIVE → TESTED → BREAKER → REMOVED lifecycle.
 *
 * <p>The sequence below is built so that NO intervening pivot high confirms
 * between the tracked swing and the break. That is not decoration: if a nearer
 * swing confirmed first it would replace the tracked one (§S7 keeps only the
 * most recent), the origin scan would start from the wrong bar, and the test
 * would be quietly checking a different rule.
 */
class IctLibOrderBlockTest {

    /**
     * W5 shape: a swing high at 21030 (bar 10), then four bars whose bodyBots
     * are 21024 / 21018 / 21021 / 21026, then a close at 21032 through the
     * swing (bar 15).
     */
    private static List<Candle> w5Sequence() {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            out.add(IctLibFixture.c(i, 21005, 21010, 21000, 21006));
        }
        out.add(IctLibFixture.c(10, 21008, 21030, 21005, 21010));  // the swing high
        out.add(IctLibFixture.c(11, 21024, 21028, 21022, 21027));  // bodyBot 21024
        out.add(IctLibFixture.c(12, 21018, 21022, 21016, 21021));  // bodyBot 21018  ← origin
        out.add(IctLibFixture.c(13, 21021, 21025, 21019, 21024));  // bodyBot 21021
        out.add(IctLibFixture.c(14, 21026, 21029, 21024, 21028));  // bodyBot 21026
        out.add(IctLibFixture.c(15, 21028, 21034, 21027, 21032));  // close through 21030
        return out;
    }

    private static List<Detection> bullishObs(IctLibFixture.Harness h) {
        return h.byTypeAndDirection(DetectionType.ORDER_BLOCK, DetectionDirection.BULLISH);
    }

    @Test
    @DisplayName("W5 body mode: origin is the LOWEST bodyBot between swing and break → zone [21018, 21021]")
    void originScanBodyMode() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        h.pushAll(w5Sequence());

        List<Detection> obs = bullishObs(h);
        assertThat(obs).hasSize(1);
        Detection ob = obs.get(0);
        assertThat(ob.priceBottom()).isEqualTo(21018.0);
        assertThat(ob.priceTop()).isEqualTo(21021.0);
        assertThat(ob.state()).isEqualTo(DetectionState.ACTIVE);
        assertThat(ob.meta()).containsEntry("brokenSwing", 21030.0);
        assertThat(ob.meta()).containsEntry("originBar", 12L);
        assertThat(ob.meta()).containsEntry("useBody", true);
    }

    @Test
    @DisplayName("Wick mode: the same break yields the full-range zone [21016, 21022]")
    void originScanWickMode() {
        IctLibFixture.Harness h = IctLibFixture.harness(
                IctLibConfig.defaults().withObUseBody(false));
        h.pushAll(w5Sequence());

        List<Detection> obs = bullishObs(h);
        assertThat(obs).hasSize(1);
        assertThat(obs.get(0).priceBottom()).isEqualTo(21016.0);
        assertThat(obs.get(0).priceTop()).isEqualTo(21022.0);
        assertThat(obs.get(0).meta()).containsEntry("useBody", false);
    }

    @Test
    @DisplayName("W5 lifecycle: trade in → TESTED, body-close through → BREAKER, reclaim → REMOVED")
    void fullLifecycle() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        h.pushAll(w5Sequence());

        // Trades into [21018, 21021] but its body closes well above it.
        h.push(IctLibFixture.c(16, 21030, 21031, 21020, 21029));
        assertThat(bullishObs(h).get(0).state()).isEqualTo(DetectionState.TESTED);

        // min(o,c) = 21016 < 21018 → polarity flips.
        h.push(IctLibFixture.c(17, 21020, 21021, 21014, 21016));
        Detection breaker = bullishObs(h).get(0);
        assertThat(breaker.state()).isEqualTo(DetectionState.BREAKER);
        assertThat(breaker.terminal()).isFalse();     // a breaker is still a live level

        // Close back above the near edge → spent.
        h.push(IctLibFixture.c(18, 21017, 21026, 21016, 21024.5));
        Detection removed = bullishObs(h).get(0);
        assertThat(removed.state()).isEqualTo(DetectionState.REMOVED);
        assertThat(removed.terminal()).isTrue();

        // Monotonic: nothing revives it.
        h.push(IctLibFixture.c(19, 21024, 21025, 21010, 21012));
        assertThat(bullishObs(h).get(0).state()).isEqualTo(DetectionState.REMOVED);
    }

    @Test
    @DisplayName("A wick through the zone that does not body-close is TESTED, not BREAKER")
    void wickThroughIsOnlyATest() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        h.pushAll(w5Sequence());

        // Low 21010 is far below the zone, but the BODY stays above 21018.
        h.push(IctLibFixture.c(16, 21030, 21031, 21010, 21029));
        assertThat(bullishObs(h).get(0).state()).isEqualTo(DetectionState.TESTED);
    }

    @Test
    @DisplayName("A swing is crossed once: a second close through the same swing creates no second block")
    void swingIsCrossedOnlyOnce() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        h.pushAll(w5Sequence());
        int after = bullishObs(h).size();

        h.push(IctLibFixture.c(16, 21032, 21033, 21031, 21032.5));
        h.push(IctLibFixture.c(17, 21032, 21033, 21031, 21032.5));
        assertThat(bullishObs(h)).hasSize(after);
    }

    @Test
    @DisplayName("Bearish mirror: a close below an uncrossed swing low picks the HIGHEST bodyTop")
    void bearishMirror() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        List<Candle> seq = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            seq.add(IctLibFixture.c(i, 21025, 21030, 21020, 21024));
        }
        seq.add(IctLibFixture.c(10, 21022, 21025, 21000, 21020));  // the swing low (21000)
        seq.add(IctLibFixture.c(11, 21006, 21008, 21002, 21007));  // bodyTop 21007
        seq.add(IctLibFixture.c(12, 21012, 21014, 21010, 21013));  // bodyTop 21013 ← origin
        seq.add(IctLibFixture.c(13, 21009, 21011, 21007, 21010));  // bodyTop 21010
        seq.add(IctLibFixture.c(14, 21004, 21006, 21002, 21005));  // bodyTop 21005
        seq.add(IctLibFixture.c(15, 21002, 21003, 20996, 20998));  // close below 21000
        h.pushAll(seq);

        List<Detection> obs =
                h.byTypeAndDirection(DetectionType.ORDER_BLOCK, DetectionDirection.BEARISH);
        assertThat(obs).hasSize(1);
        assertThat(obs.get(0).priceTop()).isEqualTo(21013.0);
        assertThat(obs.get(0).priceBottom()).isEqualTo(21012.0);
        assertThat(obs.get(0).meta()).containsEntry("brokenSwing", 21000.0);
    }

    @Test
    @DisplayName("Retention holds at 5 per side")
    void retentionCap() {
        IctLibFixture.Harness h = IctLibFixture.harness();
        int bar = 0;
        for (int cycle = 0; cycle < 14; cycle++) {
            double base = 21000 + cycle * 200;
            for (int i = 0; i < 10; i++) {
                h.push(IctLibFixture.c(bar++, base + 5, base + 10, base, base + 6));
            }
            h.push(IctLibFixture.c(bar++, base + 8, base + 30, base + 5, base + 10));
            h.push(IctLibFixture.c(bar++, base + 24, base + 28, base + 22, base + 27));
            h.push(IctLibFixture.c(bar++, base + 18, base + 22, base + 16, base + 21));
            h.push(IctLibFixture.c(bar++, base + 26, base + 29, base + 24, base + 28));
            h.push(IctLibFixture.c(bar++, base + 28, base + 34, base + 27, base + 32));
        }
        assertThat(h.byTypeAndDirection(DetectionType.ORDER_BLOCK, DetectionDirection.BULLISH))
                .hasSizeLessThanOrEqualTo(5);
        assertThat(h.byTypeAndDirection(DetectionType.ORDER_BLOCK, DetectionDirection.BEARISH))
                .hasSizeLessThanOrEqualTo(5);
    }
}
