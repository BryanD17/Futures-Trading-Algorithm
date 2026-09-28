package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chart.OteState;
import com.topstep.trading.chart.OteZoneSnapshot;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventType;
import com.topstep.trading.event.OteAlarmEvent;
import com.topstep.trading.event.OteArmedEvent;
import com.topstep.trading.event.OteInvalidatedEvent;
import com.topstep.trading.strategy.BarAggregationManager;
import com.topstep.trading.strategy.BarAggregationManager.Timeframe;
import com.topstep.trading.strategy.MarketStructureShiftDetector;
import com.topstep.trading.strategy.TradeTier;
import com.topstep.trading.strategy.TradeTierVariant;
import com.topstep.trading.strategy.VariantSelector;
import com.topstep.trading.strategy.stdvote.PdArrayLocator.PdArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.topstep.trading.strategy.stdvote.OteGoldenReplay.et;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** V5 Agent 04 — unit tests for the new OTE components. */
@DisplayName("V5 Agent 04 OTE components")
class OteV5ComponentsTest {

    private static final Instant T0 = Instant.parse("2026-06-15T13:00:00Z");

    private static Candle bar(int i, double o, double h, double l, double c) {
        return new Candle("MNQ", T0.plusSeconds(300L * i), o, h, l, c, 100);
    }

    @Nested
    @DisplayName("MSS — the ONE source (forStdvOte)")
    class Mss {

        @Test
        @DisplayName("G1: 15:10 5m close 30576.75 breaks the 14:35 swing low 30578.75; legacy rule misses it")
        void g1MssOnTape() throws Exception {
            MarketStructureShiftDetector v5 = MarketStructureShiftDetector.forStdvOte();
            MarketStructureShiftDetector legacy = new MarketStructureShiftDetector(50, 2);
            assertThat(v5.isCloseBeyondRecentSwingRule()).isTrue();
            assertThat(legacy.isCloseBeyondRecentSwingRule()).isFalse();
            BarAggregationManager agg = new BarAggregationManager("MNQ", 500);
            MarketStructureShiftDetector.MSS v5At1510 = null;
            MarketStructureShiftDetector.MSS legacyAt1510 = null;
            for (Candle c : OteGoldenReplay.loadTape("MNQ")) {
                if (c.getTimestamp().isBefore(et("2026-09-28T09:00"))) continue;
                if (c.getTimestamp().isAfter(et("2026-09-28T15:20"))) break;
                Candle b = agg.processCandle(c).get(Timeframe.M5);
                if (b == null) continue;
                MarketStructureShiftDetector.MSS m1 = v5.update(b);
                MarketStructureShiftDetector.MSS m2 = legacy.update(b);
                if (b.getTimestamp().equals(et("2026-09-28T15:10"))) {
                    v5At1510 = m1;
                    legacyAt1510 = m2;
                }
            }
            assertThat(v5At1510).isNotNull();
            assertThat(v5At1510.isBullish).isFalse();
            assertThat(v5At1510.breakLevel).isEqualTo(30578.75);
            assertThat(legacyAt1510).isNull();
            System.out.println("MEAS G1 MSS: v5=" + v5At1510 + " legacy(15:10)=" + legacyAt1510);
        }

        @Test
        @DisplayName("each swing breaks once; ictlib shadow uses the same factory")
        void swingBreaksOnce() {
            MarketStructureShiftDetector d = MarketStructureShiftDetector.forStdvOte();
            double[][] bars = {
                    {100, 101, 99, 100}, {100, 102, 99, 101}, {101, 105, 100, 104}, // swing high 105 @2
                    {104, 104, 101, 102}, {102, 103, 100, 101}, {101, 104, 100, 103},
                    {103, 107, 102, 106}, // close 106 > 105 → bullish MSS
                    {106, 108, 105, 107}, // no second MSS on the same swing
            };
            int mss = 0;
            for (int i = 0; i < bars.length; i++) {
                if (d.update(bar(i, bars[i][0], bars[i][1], bars[i][2], bars[i][3])) != null) mss++;
            }
            assertThat(mss).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("OteAnchorRangeTracker")
    class Range {

        @Test
        @DisplayName("G1 at 15:05 ET: NY-session short leg [30356.75, 30759.25] → 0.618 30605.50 / 0.786 30673.00")
        void g1Leg() throws Exception {
            OteAnchorRangeTracker t = new OteAnchorRangeTracker();
            for (Candle c : OteGoldenReplay.loadTape("MNQ")) {
                if (c.getTimestamp().isAfter(et("2026-09-28T15:05"))) break;
                t.onCandle(c);
            }
            OteAnchorRangeTracker.Leg leg = t.leg(et("2026-09-28T15:05"), false,
                    OteAnchorMode.DEALING_RANGE, 100).orElseThrow();
            assertThat(leg.high()).isEqualTo(30759.25);
            assertThat(leg.low()).isEqualTo(30356.75);
            assertThat(leg.source()).isEqualTo("SESSION");
            OteZone z = new OteEntryCalculator().buildZone(leg.low(), leg.high(), false, 0.25).orElseThrow();
            assertThat(z.f62()).isEqualTo(30605.50);
            assertThat(z.f79()).isEqualTo(30673.00);
            assertThat(z.eq50()).isEqualTo(30558.00);
            OteAnchorRangeTracker.Leg day = t.leg(et("2026-09-28T15:05"), false,
                    OteAnchorMode.TRADING_DAY, 100).orElseThrow();
            assertThat(day.high()).isEqualTo(30898.25); // Sunday-evening high — not the owner's anchor
        }

        @Test
        @DisplayName("session boundaries (ET) and trading-day roll")
        void sessions() {
            assertThat(OteAnchorRangeTracker.sessionStart(et("2026-09-28T15:05"))).isEqualTo(et("2026-09-28T09:30"));
            assertThat(OteAnchorRangeTracker.sessionStart(et("2026-09-28T06:30"))).isEqualTo(et("2026-09-28T02:00"));
            assertThat(OteAnchorRangeTracker.sessionStart(et("2026-09-28T01:00"))).isEqualTo(et("2026-09-27T18:00"));
            assertThat(OteAnchorRangeTracker.sessionStart(et("2026-09-28T19:00"))).isEqualTo(et("2026-09-28T18:00"));
            assertThat(OteAnchorRangeTracker.tradingDayStart(et("2026-09-28T15:05"))).isEqualTo(et("2026-09-27T18:00"));
        }

        /** Stand-in for Agent 03's SetupContext fields. */
        public static final class WithRange {
            public double rangeHigh = 30759.25;
            public double rangeLow = 30356.75;
        }

        @Test
        @DisplayName("Agent 03 rangeHigh/rangeLow read defensively (absent on this branch → empty)")
        void contextFallback() {
            assertThat(OteAnchorRangeTracker.fromContext(new SetupContext())).isEmpty();
            SetupContext withRange = new SetupContext();
            withRange.rangeHigh = 30759.25;   // Agent 03 (#156) field — preferred anchor
            withRange.rangeLow = 30356.75;
            assertThat(OteAnchorRangeTracker.fromContext(withRange).orElseThrow().source())
                    .isEqualTo("CONTEXT(Agent03)");
            Optional<OteAnchorRangeTracker.Leg> leg = OteAnchorRangeTracker.fromObject(new WithRange());
            assertThat(leg).isPresent();
            assertThat(leg.get().high()).isEqualTo(30759.25);
            WithRange zero = new WithRange();
            zero.rangeHigh = 0;
            assertThat(OteAnchorRangeTracker.fromObject(zero)).isEmpty();
        }
    }

    @Nested
    @DisplayName("PdArrayLocator — overlap, entry level, ranking")
    class PdArrays {

        private final OteZone shortZone =
                new OteEntryCalculator().buildZone(30356.75, 30759.25, false, 0.25).orElseThrow();

        @Test
        @DisplayName("overlap (not containment) qualifies; entry clamped into the band")
        void overlap() {
            PdArray fvgPartly = new PdArray("FVG", false, 30590.0, 30610.0, T0);   // straddles 0.618
            PdArray outside = new PdArray("FVG", false, 30560.0, 30600.0, T0);
            assertThat(PdArrayLocator.overlaps(fvgPartly, shortZone)).isTrue();
            assertThat(PdArrayLocator.overlaps(outside, shortZone)).isFalse();
            assertThat(PdArrayLocator.entryLevel(fvgPartly, shortZone)).isEqualTo(30605.50);
        }

        @Test
        @DisplayName("G1 candidates: OB mean threshold 30635.50 beats FVG bottom 30620.75 (nearest 0.705)")
        void g1Selection() {
            PdArray ob = new PdArray("OB", false, 30621.0, 30650.0, T0);
            PdArray fvg = new PdArray("FVG", false, 30620.75, 30630.0, T0);
            PdArray best = PdArrayLocator.bestInBand(List.of(fvg, ob), shortZone).orElseThrow();
            assertThat(best.kind()).isEqualTo("OB");
            assertThat(PdArrayLocator.entryLevel(best, shortZone)).isEqualTo(30635.50);
            assertThat(best.farEdge()).isEqualTo(30650.0);
        }

        @Test
        @DisplayName("linked FVG: gap created BY the displacement (middle candle); OB behind it")
        void linkage() {
            PdArrayLocator loc = new PdArrayLocator();
            loc.onBar(bar(0, 30632.75, 30650.00, 30621.00, 30638.25));   // 14:50 up-close (OB)
            loc.onBar(bar(1, 30638.00, 30649.75, 30630.00, 30637.75));   // 14:55
            long disp = loc.onBar(bar(2, 30639.25, 30641.25, 30597.25, 30615.75)); // 15:00 displacement
            assertThat(loc.linkedFvg(disp, false, 3)).isEmpty();           // c3 not printed yet
            loc.onBar(bar(3, 30614.75, 30620.75, 30593.50, 30594.50));   // 15:05
            PdArray g = loc.linkedFvg(disp, false, 3).orElseThrow();
            assertThat(g.bottom()).isEqualTo(30620.75);
            assertThat(g.top()).isEqualTo(30630.00);
            assertThat(g.at()).isEqualTo(T0.plusSeconds(600));
            PdArray ob = loc.orderBlock(disp, false, 3).orElseThrow();
            assertThat(ob.bottom()).isEqualTo(30621.00);
            assertThat(ob.top()).isEqualTo(30650.00);
        }
    }

    @Nested
    @DisplayName("ARM / ALARM reaction rule")
    class Reaction {
        private final OteZone z =
                new OteEntryCalculator().buildZone(30356.75, 30759.25, false, 0.25).orElseThrow();

        @Test
        void rules() {
            assertThat(OteSetupDriver.reaction(z, bar(0, 30600, 30604, 30590, 30595))).isNull();         // no touch
            assertThat(OteSetupDriver.reaction(z, bar(0, 30600, 30605.75, 30597, 30603.25)))
                    .isEqualTo("close-back-below-0.618");                                              // G1 15:26
            assertThat(OteSetupDriver.reaction(z, bar(0, 30610, 30620, 30606, 30608))).isEqualTo("down-close-in-band");
            assertThat(OteSetupDriver.reaction(z, bar(0, 30610, 30620, 30606, 30615))).isNull();      // up-close inside
            assertThat(OteSetupDriver.reaction(z, bar(0, 30630, 30641, 30625, 30640))).isEqualTo("limit@0.705");
        }
    }

    @Nested
    @DisplayName("Tier resolution (VariantSelector)")
    class Tiers {

        @Test
        @DisplayName("TOTAL: every input maps to a tier — never 'no trade'; TIER_1 has a real variant")
        void totalAndMonotonic() {
            for (int rs = 0; rs <= 10; rs++) {
                for (int opt = 0; opt <= 6; opt++) {
                    for (boolean smt : new boolean[] {false, true}) {
                        TradeTier t = VariantSelector.resolveStdvOteTier(rs, opt, smt);
                        assertThat(t).isNotNull();
                        // monotonic in raid score and optional count
                        if (rs < 10) assertThat(VariantSelector.resolveStdvOteTier(rs + 1, opt, smt).getLevel())
                                .isGreaterThanOrEqualTo(t.getLevel());
                        if (opt < 6) assertThat(VariantSelector.resolveStdvOteTier(rs, opt + 1, smt).getLevel())
                                .isGreaterThanOrEqualTo(t.getLevel());
                    }
                }
            }
            assertThat(VariantSelector.resolveStdvOteTier(5, 0, false)).isEqualTo(TradeTier.TIER_1);
            assertThat(VariantSelector.resolveStdvOteTier(8, 4, true)).isEqualTo(TradeTier.TIER_4);
            VariantSelector.SelectionResult r = VariantSelector.selectVariant(new VariantSelector.SelectionContext(
                    1, 0, 5, VariantSelector.KillzonePhase.MID, 0, 1.0,
                    VariantSelector.HtfMomentum.MODERATE, "MNQ"));
            assertThat(r.variant).isEqualTo(TradeTierVariant.TIER_2A_SCALP);
            assertThat(r.reason).doesNotContain("Unknown");
        }
    }

    @Nested
    @DisplayName("M7b SCORING (RC-18)")
    class M7b {

        private OteZoneSnapshot zone(boolean bullish, OteState state) {
            return new OteZoneSnapshot("MNQ", bullish, 19950, 20150, T0, T0, state, T0);
        }

        @Test
        @DisplayName("default mode is SCORING: never blocks, confluent verdict scores, kill share measured")
        void scoring() {
            System.clearProperty("ote30m.mode");
            System.clearProperty("ote30m.confluence");
            Ote30mConfluenceGate g = Ote30mConfluenceGate.install("MNQ");
            assertThat(g.mode()).isEqualTo(Ote30mConfluenceGate.Mode.SCORING);
            g.setZoneSource(() -> Optional.of(zone(false, OteState.REACTED)));
            assertThat(g.gateCheck(true).passed()).isTrue();     // would block → passes (SCORING)
            assertThat(g.lastConfluent()).isFalse();
            assertThat(g.gateCheck(false).passed()).isTrue();    // confluent
            assertThat(g.lastConfluent()).isTrue();
            assertThat(g.killShare()).isCloseTo(0.5, within(1e-9));
        }

        @Test
        @DisplayName("ote30m.mode=GATE re-blocks")
        void reblock() {
            System.setProperty("ote30m.mode", "GATE");
            try {
                Ote30mConfluenceGate g = Ote30mConfluenceGate.install("MNQ");
                g.setZoneSource(() -> Optional.of(zone(false, OteState.REACTED)));
                assertThat(g.gateCheck(true).passed()).isFalse();
            } finally {
                System.clearProperty("ote30m.mode");
            }
        }
    }

    @Nested
    @DisplayName("OTE events + config")
    class EventsAndConfig {

        @Test
        void eventTypes() {
            assertThat(new OteArmedEvent("MNQ", T0, false, "DEALING_RANGE", 1, 2, 3, 4, 5, 6, 7).getType())
                    .isEqualTo(EventType.OTE_ARMED);
            assertThat(new OteAlarmEvent("MNQ", T0, false, "OB", 1, 2, 3, 4, 5, "x").getType())
                    .isEqualTo(EventType.OTE_ALARM);
            assertThat(new OteInvalidatedEvent("MNQ", T0, false, "r", 1, 2, 3, 4).getType())
                    .isEqualTo(EventType.OTE_INVALIDATED);
        }

        @Test
        void configKeysAndLegacyAliases() {
            assertThat(OteConfig.displacementAtrMult()).isEqualTo(1.2);
            assertThat(OteConfig.displacementBodyPct()).isEqualTo(0.50);
            assertThat(OteConfig.displacementRecentBars()).isEqualTo(12);
            assertThat(OteConfig.anchorMode()).isEqualTo(OteAnchorMode.DEALING_RANGE);
            assertThat(OteConfig.fib62()).isEqualTo(0.618);
            assertThat(OteConfig.fib705()).isEqualTo(0.705);
            assertThat(OteConfig.fib79()).isEqualTo(0.786);
            System.setProperty("stdvote.displacement.bodyPct", "0.55");   // owner's legacy key
            System.setProperty("stdvOte.oteWindowBars", "11");
            try {
                assertThat(OteConfig.displacementBodyPct()).isEqualTo(0.55);
                assertThat(OteConfig.oteWindowBars()).isEqualTo(11);
                System.setProperty("displacement.bodyPct", "0.6");         // new key wins
                assertThat(OteConfig.displacementBodyPct()).isEqualTo(0.6);
            } finally {
                System.clearProperty("stdvote.displacement.bodyPct");
                System.clearProperty("displacement.bodyPct");
                System.clearProperty("stdvOte.oteWindowBars");
            }
        }
    }
}
