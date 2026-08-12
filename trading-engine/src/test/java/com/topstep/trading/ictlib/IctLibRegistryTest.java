package com.topstep.trading.ictlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bounded store itself: retention caps, terminal-first eviction, and the
 * query API the chart and the confluence stack read through.
 */
class IctLibRegistryTest {

    private static final Instant T = IctLibFixture.T0;

    private DetectionRegistry registryWithFvgCap(int cap) {
        return new DetectionRegistry(IctLibFixture.SYM,
                IctLibConfig.defaults().withRetainFvgPerSide(cap).retentions());
    }

    private MutableDetection gap(DetectionRegistry r, DetectionDirection dir,
                                 double bottom, double top, long bar) {
        return r.create(DetectionType.FVG, IctLibEngine.TF_1M, dir, bottom, top,
                bar, T.plusSeconds(60L * bar), DetectionState.ACTIVE);
    }

    @Test
    @DisplayName("Retention cap is per side: 2 bullish + 2 bearish survive independently")
    void capIsPerSide() {
        DetectionRegistry r = registryWithFvgCap(2);
        for (int i = 0; i < 5; i++) {
            gap(r, DetectionDirection.BULLISH, 21000 + i, 21005 + i, i);
            gap(r, DetectionDirection.BEARISH, 21100 + i, 21105 + i, i);
        }
        assertThat(r.activeByType(DetectionType.FVG, DetectionDirection.BULLISH)).hasSize(2);
        assertThat(r.activeByType(DetectionType.FVG, DetectionDirection.BEARISH)).hasSize(2);
        assertThat(r.count(DetectionType.FVG)).isEqualTo(4);
    }

    @Test
    @DisplayName("Eviction takes the oldest TERMINAL detection before any live one")
    void terminalEvictedFirst() {
        DetectionRegistry r = registryWithFvgCap(2);
        MutableDetection a = gap(r, DetectionDirection.BULLISH, 21000, 21005, 0);
        MutableDetection b = gap(r, DetectionDirection.BULLISH, 21010, 21015, 1);
        // 'a' is older, but 'b' is the one that got consumed.
        b.advanceTo(DetectionState.FILLED, T, 2);

        gap(r, DetectionDirection.BULLISH, 21020, 21025, 3);

        List<Detection> kept = r.byType(DetectionType.FVG);
        assertThat(kept).hasSize(2);
        assertThat(kept).extracting(Detection::id).contains(a.id());
        assertThat(kept).extracting(Detection::id).doesNotContain(b.id());
    }

    @Test
    @DisplayName("With nothing terminal, eviction falls back to the oldest")
    void oldestEvictedWhenAllLive() {
        DetectionRegistry r = registryWithFvgCap(2);
        MutableDetection a = gap(r, DetectionDirection.BULLISH, 21000, 21005, 0);
        gap(r, DetectionDirection.BULLISH, 21010, 21015, 1);
        gap(r, DetectionDirection.BULLISH, 21020, 21025, 2);

        assertThat(r.byType(DetectionType.FVG)).extracting(Detection::id)
                .doesNotContain(a.id());
    }

    @Test
    @DisplayName("Ids are deterministic and unique: type:timeframe:side:sequence")
    void idsAreDeterministic() {
        DetectionRegistry r = registryWithFvgCap(10);
        assertThat(gap(r, DetectionDirection.BULLISH, 21000, 21005, 0).id())
                .isEqualTo("fvg:1m:bullish:1");
        assertThat(gap(r, DetectionDirection.BULLISH, 21010, 21015, 1).id())
                .isEqualTo("fvg:1m:bullish:2");
        assertThat(gap(r, DetectionDirection.BEARISH, 21100, 21105, 2).id())
                .isEqualTo("fvg:1m:bearish:1");
    }

    @Test
    @DisplayName("Queries: inZone / nearestAbove / nearestBelow / recent skip terminal zones")
    void queries() {
        DetectionRegistry r = registryWithFvgCap(10);
        gap(r, DetectionDirection.BULLISH, 21000, 21010, 0);
        gap(r, DetectionDirection.BULLISH, 21030, 21040, 1);
        MutableDetection dead = gap(r, DetectionDirection.BEARISH, 21100, 21110, 2);
        dead.advanceTo(DetectionState.FILLED, T, 3);

        assertThat(r.inZone(21005)).hasSize(1);
        assertThat(r.inZone(21105)).isEmpty();                       // terminal ignored
        assertThat(r.nearestAbove(21020)).isPresent()
                .get().extracting(Detection::priceBottom).isEqualTo(21030.0);
        assertThat(r.nearestBelow(21020)).isPresent()
                .get().extracting(Detection::priceTop).isEqualTo(21010.0);
        assertThat(r.recent(DetectionType.FVG, 2)).hasSize(2);
        assertThat(r.activeByType(DetectionType.FVG)).hasSize(2);
    }

    @Test
    @DisplayName("Snapshots are immutable copies — a later transition cannot mutate them")
    void snapshotsAreDetached() {
        DetectionRegistry r = registryWithFvgCap(10);
        MutableDetection d = gap(r, DetectionDirection.BULLISH, 21000, 21010, 0);
        Detection before = r.snapshot().get(0);

        d.advanceTo(DetectionState.FILLED, T, 1);

        assertThat(before.state()).isEqualTo(DetectionState.ACTIVE);
        assertThat(r.snapshot().get(0).state()).isEqualTo(DetectionState.FILLED);
    }

    @Test
    @DisplayName("Unknown families still get a bounded default slot")
    void unknownFamilyIsStillBounded() {
        DetectionRegistry r = new DetectionRegistry(IctLibFixture.SYM, Map.of());
        for (int i = 0; i < 40; i++) {
            r.create(DetectionType.DISPLACEMENT, IctLibEngine.TF_1M,
                    DetectionDirection.BULLISH, 1, 2, i, T, DetectionState.POINT);
        }
        assertThat(r.size()).isLessThanOrEqualTo(20);
    }
}
