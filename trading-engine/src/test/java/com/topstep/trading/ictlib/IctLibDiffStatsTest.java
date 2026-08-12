package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.FvgDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [ICTLIB-DIFF] — the measurement that lets ictlib and the gate detector
 * coexist without either one silently winning (V4 anti-pattern C3).
 */
class IctLibDiffStatsTest {

    @BeforeEach
    void reset() {
        IctLibDiffStats.resetAll();
    }

    private static List<Candle> mixedFeed(int bars) {
        List<Candle> out = new ArrayList<>(bars);
        long state = 7L;
        double price = 21000.0;
        for (int i = 0; i < bars; i++) {
            state = (state * 6364136223846793005L + 1442695040888963407L);
            int step = (int) ((state >>> 33) % 61) - 30;
            double open = price;
            double close = open + step * 0.25;
            double high = Math.max(open, close) + ((i % 4 == 0) ? 0.25 : 3.0);
            double low = Math.min(open, close) - ((i % 3 == 0) ? 0.25 : 3.0);
            out.add(new Candle(IctLibFixture.SYM,
                    IctLibFixture.T0.plusSeconds(60L * i), open, high, low, close, 100L));
            price = close;
        }
        return out;
    }

    @Test
    @DisplayName("The mirrored legacy rule matches the real strategy.FvgDetector bar for bar")
    void mirrorMatchesLegacyFvgDetector() {
        List<Candle> feed = mixedFeed(120);

        FvgDetector legacy = new FvgDetector(10_000);
        for (Candle c : feed) legacy.update(c);
        List<FairValueGap> legacyGaps = legacy.getAllFvgs();

        int mirrored = 0;
        for (int i = 2; i < feed.size(); i++) {
            Candle c0 = feed.get(i);
            Candle c2 = feed.get(i - 2);
            if (IctLibDiffStats.legacyBullishGap(c0, c2)) mirrored++;
            if (IctLibDiffStats.legacyBearishGap(c0, c2)) mirrored++;
        }

        assertThat(mirrored)
                .as("the mirror must not drift from strategy/FvgDetector.java:57-77")
                .isEqualTo(legacyGaps.size());
        assertThat(mirrored).isGreaterThan(0);
    }

    @Test
    @DisplayName("§S2 is a strict subset of the legacy rule: ictlib <= existing, overlap == ictlib")
    void ictlibIsStrictlyFewerAndFullyOverlapping() {
        IctLibEngine engine = new IctLibEngine(IctLibConfig.defaults());
        for (Candle c : mixedFeed(400)) engine.onCandle(c);

        IctLibDiffStats stats = IctLibDiffStats.forSymbol(IctLibFixture.SYM);
        assertThat(stats.legacyFvg()).isGreaterThan(0);
        assertThat(stats.ictlibFvg()).isLessThan(stats.legacyFvg());
        assertThat(stats.overlapFvg()).isEqualTo(stats.ictlibFvg());
    }

    @Test
    @DisplayName("The [ICTLIB-DIFF] line carries the exact documented shape")
    void logLineShape() {
        IctLibDiffStats stats = IctLibDiffStats.forSymbol("MNQ");
        stats.record(true, true);
        stats.record(false, true);
        stats.record(false, true);

        assertThat(stats.rollup()).isEqualTo("fvg: ictlib=1 existing=3 overlap=1");
        assertThat(stats.logLine()).isEqualTo("[ICTLIB-DIFF MNQ] fvg: ictlib=1 existing=3 overlap=1");
    }

    @Test
    @DisplayName("Counters are session-scoped and reset cleanly")
    void sessionReset() {
        IctLibDiffStats stats = IctLibDiffStats.forSymbol("MES");
        stats.record(true, true);
        stats.resetSession();
        assertThat(stats.rollup()).isEqualTo("fvg: ictlib=0 existing=0 overlap=0");
    }
}
