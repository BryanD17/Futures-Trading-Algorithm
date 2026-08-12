package com.topstep.trading.ictlib;

import com.topstep.trading.chartstate.CandleSeries;
import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.chartstate.LevelType;
import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The §S6 → {@code LevelEngine} adapter: proves a clustered pool becomes a
 * level the raid pipeline can actually fire on (Appendix E8, one level
 * universe).
 */
class IctLibLevelAdapterTest {

    private LevelEngine newLevelEngine() {
        return new LevelEngine(IctLibFixture.SYM, new CandleSeries(IctLibFixture.SYM, 500));
    }

    private static List<Candle> pivotSeries(double base, double... pivotHighs) {
        List<Candle> out = new java.util.ArrayList<>();
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
    @DisplayName("INTEGRATION: a clustered pool becomes a raid-able EQUAL_HIGH level")
    void poolBecomesARaidableLevel() {
        LevelEngine levels = newLevelEngine();
        IctLibEngine lib = new IctLibEngine(IctLibConfig.defaults());
        lib.attachLevelEngine(IctLibFixture.SYM, levels);

        assertThat(levels.getLevel(LevelType.EQUAL_HIGH)).isEmpty();

        for (Candle c : pivotSeries(21040, 21050.0, 21048.5, 21051.0)) {
            lib.onCandle(c);
        }

        Optional<KnownLevel> level = levels.getLevel(LevelType.EQUAL_HIGH);
        assertThat(level).isPresent();
        assertThat(level.get().getPrice()).isEqualTo(21049.75, within(1e-9));
        assertThat(level.get().getClusterSize()).isEqualTo(3);
        assertThat(level.get().getSource()).isEqualTo(IctLibLevelAdapter.SOURCE);

        // Raid-able means: unraided, and reachable through the queries the raid
        // pipeline uses to find its targets.
        assertThat(level.get().isRaided()).isFalse();
        assertThat(levels.getUnraidedLevels()).extracting(KnownLevel::getType)
                .contains(LevelType.EQUAL_HIGH);
        assertThat(levels.getNearestUnraidedLevelAbove(21000.0))
                .isPresent()
                .get().extracting(KnownLevel::getSource)
                .isEqualTo(IctLibLevelAdapter.SOURCE);
    }

    @Test
    @DisplayName("Symbols with no LevelEngine attached are silently skipped — never an error")
    void unattachedSymbolIsSkipped() {
        IctLibEngine lib = new IctLibEngine(IctLibConfig.defaults());
        assertThat(lib.levelAdapter().isAttached(IctLibFixture.SYM)).isFalse();

        for (Candle c : pivotSeries(21040, 21050.0, 21048.5, 21051.0)) {
            lib.onCandle(c);
        }
        // Pools still form inside ictlib; they simply are not published.
        assertThat(lib.registry(IctLibFixture.SYM).byType(DetectionType.LIQUIDITY_POOL))
                .isNotEmpty();
    }

    @Test
    @DisplayName("The adapter is one-directional: it never marks a level raided")
    void adapterNeverTouchesRaidState() {
        LevelEngine levels = newLevelEngine();
        IctLibEngine lib = new IctLibEngine(IctLibConfig.defaults());
        lib.attachLevelEngine(IctLibFixture.SYM, levels);

        for (Candle c : pivotSeries(21040, 21050.0, 21048.5, 21051.0)) {
            lib.onCandle(c);
        }
        // Sweep the pool inside ictlib: closes above both boundaries.
        for (int i = 0; i < 6; i++) {
            lib.onCandle(IctLibFixture.c(200 + i, 21055, 21060, 21054, 21058));
        }

        List<Detection> pools =
                lib.registry(IctLibFixture.SYM).byType(DetectionType.LIQUIDITY_POOL);
        assertThat(pools).isNotEmpty();
        assertThat(pools.get(0).state()).isIn(DetectionState.PARTIAL, DetectionState.SWEPT);

        // …and the LevelEngine's raid state is untouched. Raid detection stays
        // the raid pipeline's job; ictlib only ever registers.
        assertThat(levels.getLevel(LevelType.EQUAL_HIGH)).isPresent();
        assertThat(levels.getLevel(LevelType.EQUAL_HIGH).get().isRaided()).isFalse();
    }

    @Test
    @DisplayName("Natively created levels keep the NATIVE source tag")
    void nativeLevelsAreUntagged() {
        LevelEngine levels = newLevelEngine();
        levels.addEqualLevel(LevelType.EQUAL_LOW, 20950.0, 3, IctLibFixture.T0);
        assertThat(levels.getLevel(LevelType.EQUAL_LOW).get().getSource()).isEqualTo("NATIVE");
    }
}
