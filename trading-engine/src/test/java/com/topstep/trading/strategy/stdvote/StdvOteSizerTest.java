package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SA5 tests for {@link StdvOteSizer}.
 *
 * <p>The sizer must enforce four invariants under all inputs:
 * <ol>
 *   <li>output is in {0} ∪ [5, 20];</li>
 *   <li>the tier cap and the Topstep micro cap both bind (the lower wins);</li>
 *   <li>the news multiplier rounds DOWN and may push a sized order below
 *       the floor, in which case the sizer returns 0;</li>
 *   <li>the instrument's pointValue is the only price-to-dollars converter.</li>
 * </ol>
 *
 * <p>The test vectors mirror Appendix W.2 of the master prompt.
 */
@DisplayName("StdvOteSizer")
class StdvOteSizerTest {

    private final StdvOteSizer sizer = new StdvOteSizer();

    private TradeableInstrument.Spec mnq() {
        return TradeableInstrument.of(TradeableInstrument.Symbol.MNQ);
    }
    private TradeableInstrument.Spec mes() {
        return TradeableInstrument.of(TradeableInstrument.Symbol.MES);
    }
    private TradeableInstrument.Spec mgc() {
        return TradeableInstrument.of(TradeableInstrument.Symbol.MGC);
    }

    @Nested
    @DisplayName("buffer formula (Appendix W.2 cases)")
    class BufferFormula {

        @Test
        @DisplayName("Case A: MNQ healthy room, stop 8 pts -> clamps DOWN under cap")
        void caseAHealthyRoomMnq() {
            // equity 52,000 ; floor 49,500 ; cushion 300 -> room 2,200
            // risk$ = 2,200 * 0.12 = 264 ; stop 8 pts ; perContract = 8*2 = 16
            // raw = floor(264/16) = 16 -> within [5,20] -> 16
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    20100.0, 20092.0, mnq(), TradeTier.TIER_3);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    52_000, 49_500, 300, 0.12, 1.0, 20);
            assertThat(sizer.size(req, ctx)).isEqualTo(16);
        }

        @Test
        @DisplayName("Case B: MNQ tight room clamps DOWN to floor 5 isn't triggered; floor=5")
        void caseBTightRoomMnq() {
            // room 600 ; risk$ = 72 ; stop 6 pts ; perContract = 12
            // raw = floor(72/12) = 6 -> clamp -> 6
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    20100.0, 20094.0, mnq(), TradeTier.TIER_2);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    49_900, 49_000, 300, 0.12, 1.0, 20);
            assertThat(sizer.size(req, ctx)).isEqualTo(6);
        }

        @Test
        @DisplayName("Case C: MNQ too-tight room cannot fund 5 micros -> SKIP")
        void caseCCannotFundFiveMnq() {
            // room 300 ; risk$ = 36 ; stop 6 pts ; perContract = 12
            // raw = floor(36/12) = 3 -> below floor 5 -> SKIP
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    20100.0, 20094.0, mnq(), TradeTier.TIER_2);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    49_600, 49_000, 300, 0.12, 1.0, 20);
            StdvOteSizer.SizingDecision d = sizer.decide(req, ctx);
            assertThat(d.contracts()).isEqualTo(0);
            assertThat(d.reason()).isEqualTo(StdvOteSizer.SkipReason.BELOW_FLOOR);
        }

        @Test
        @DisplayName("Case D: MES big room clamps to TIER_4 cap=20")
        void caseDBigRoomMes() {
            // room 9,000 ; risk$ = 1,080 ; stop 5 pts ; perContract = 25
            // raw = floor(1080/25) = 43 -> clamp to TIER_4 cap (20)
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    5000.0, 4995.0, mes(), TradeTier.TIER_4);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    58_300, 49_000, 300, 0.12, 1.0, 20);
            assertThat(sizer.size(req, ctx)).isEqualTo(20);
        }

        @Test
        @DisplayName("Case E: MGC 2.0 pts stop with 1500 room sizes 9 contracts")
        void caseEMgc() {
            // room 1,500 ; risk$ = 180 ; stop 2.0 pts ; perContract = 2*10 = 20
            // raw = floor(180/20) = 9 -> within [5,20] -> 9
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    2400.0, 2398.0, mgc(), TradeTier.TIER_3);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    50_800, 49_000, 300, 0.12, 1.0, 20);
            assertThat(sizer.size(req, ctx)).isEqualTo(9);
        }
    }

    @Nested
    @DisplayName("tier-cap interaction")
    class TierCap {

        @Test
        @DisplayName("TIER_1 caps at 8 even when raw allows more")
        void tier1CapsAt8() {
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    20100.0, 20094.0, mnq(), TradeTier.TIER_1);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    60_000, 49_000, 300, 0.12, 1.0, 20);
            assertThat(sizer.size(req, ctx)).isEqualTo(8);
        }

        @Test
        @DisplayName("TIER_2 caps at 12")
        void tier2CapsAt12() {
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    20100.0, 20094.0, mnq(), TradeTier.TIER_2);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    60_000, 49_000, 300, 0.12, 1.0, 20);
            assertThat(sizer.size(req, ctx)).isEqualTo(12);
        }

        @Test
        @DisplayName("TIER_3 caps at 16")
        void tier3CapsAt16() {
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    20100.0, 20094.0, mnq(), TradeTier.TIER_3);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    60_000, 49_000, 300, 0.12, 1.0, 20);
            assertThat(sizer.size(req, ctx)).isEqualTo(16);
        }

        @Test
        @DisplayName("null tier defaults to instrument ceiling (20)")
        void nullTier() {
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    20100.0, 20094.0, mnq(), /* tier */ null);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    60_000, 49_000, 300, 0.12, 1.0, 20);
            assertThat(sizer.size(req, ctx)).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("Topstep contract cap")
    class TopstepCap {

        @Test
        @DisplayName("Topstep cap < tier cap wins")
        void topstepCapWins() {
            // Tier 4 would allow 20; Topstep cap 15 should bind first.
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    20100.0, 20094.0, mnq(), TradeTier.TIER_4);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    60_000, 49_000, 300, 0.12, 1.0, 15);
            assertThat(sizer.size(req, ctx)).isEqualTo(15);
        }

        @Test
        @DisplayName("Topstep cap <= 0 disables the secondary cap (instrument ceiling wins)")
        void zeroDisablesCap() {
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    20100.0, 20094.0, mnq(), TradeTier.TIER_4);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    60_000, 49_000, 300, 0.12, 1.0, 0);
            assertThat(sizer.size(req, ctx)).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("news multiplier")
    class News {

        @Test
        @DisplayName("0.5x: 12 -> 6 (still >= floor)")
        void halfMultiplier() {
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    20100.0, 20094.0, mnq(), TradeTier.TIER_2);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    60_000, 49_000, 300, 0.12, 0.5, 20);
            assertThat(sizer.size(req, ctx)).isEqualTo(6);
        }

        @Test
        @DisplayName("0.3x: 12 -> 3 -> below floor -> SKIP (NEWS_MULTIPLIER_TOO_LOW)")
        void thirtyPercentSkips() {
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    20100.0, 20094.0, mnq(), TradeTier.TIER_2);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    60_000, 49_000, 300, 0.12, 0.3, 20);
            StdvOteSizer.SizingDecision d = sizer.decide(req, ctx);
            assertThat(d.contracts()).isEqualTo(0);
            assertThat(d.reason()).isEqualTo(StdvOteSizer.SkipReason.NEWS_MULTIPLIER_TOO_LOW);
        }

        @Test
        @DisplayName("multiplier 1.0 (default) leaves size unchanged")
        void unityMultiplier() {
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    20100.0, 20094.0, mnq(), TradeTier.TIER_2);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    60_000, 49_000, 300, 0.12, 1.0, 20);
            assertThat(sizer.size(req, ctx)).isEqualTo(12);
        }
    }

    @Nested
    @DisplayName("guards and skip reasons")
    class Guards {

        @Test
        @DisplayName("available room <= 0 -> SKIP with NO_ROOM")
        void noRoom() {
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    20100.0, 20094.0, mnq(), TradeTier.TIER_2);
            // equity below mllFloor + cushion -> negative room
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    49_200, 49_000, 300, 0.12, 1.0, 20);
            StdvOteSizer.SizingDecision d = sizer.decide(req, ctx);
            assertThat(d.contracts()).isZero();
            assertThat(d.reason()).isEqualTo(StdvOteSizer.SkipReason.NO_ROOM);
        }

        @Test
        @DisplayName("stop equals entry -> SKIP with DEGENERATE_GEOMETRY")
        void degenerateGeometry() {
            StdvOteSizer.SizeRequest req = new StdvOteSizer.SizeRequest(
                    20100.0, 20100.0, mnq(), TradeTier.TIER_2);
            StdvOteSizer.SizeContext ctx = new StdvOteSizer.SizeContext(
                    60_000, 49_000, 300, 0.12, 1.0, 20);
            StdvOteSizer.SizingDecision d = sizer.decide(req, ctx);
            assertThat(d.contracts()).isZero();
            assertThat(d.reason()).isEqualTo(StdvOteSizer.SkipReason.DEGENERATE_GEOMETRY);
        }

        @Test
        @DisplayName("null request -> SKIP with DEGENERATE_GEOMETRY")
        void nullRequest() {
            StdvOteSizer.SizingDecision d = sizer.decide(null,
                    new StdvOteSizer.SizeContext(60_000, 49_000, 300, 0.12, 1.0, 20));
            assertThat(d.contracts()).isZero();
            assertThat(d.reason()).isEqualTo(StdvOteSizer.SkipReason.DEGENERATE_GEOMETRY);
        }

        @Test
        @DisplayName("output is always 0 or in [5,20] across a randomized grid")
        void outputAlwaysValid() {
            for (TradeableInstrument.Spec spec : TradeableInstrument.all()) {
                for (TradeTier tier : TradeTier.values()) {
                    for (int equity : new int[] {49_500, 50_000, 55_000, 70_000}) {
                        for (double stopPts : new double[] {1.0, 3.0, 8.0, 20.0, 50.0}) {
                            double entry = 1000.0;
                            double stop = entry - stopPts;
                            StdvOteSizer.SizingDecision d = sizer.decide(
                                    new StdvOteSizer.SizeRequest(entry, stop, spec, tier),
                                    new StdvOteSizer.SizeContext(equity, 49_000, 300, 0.12, 1.0, 20));
                            int n = d.contracts();
                            assertThat(n == 0 || (n >= 5 && n <= 20))
                                    .as("instrument=%s tier=%s equity=%d stop=%s -> %d",
                                            spec.symbol(), tier, equity, stopPts, n)
                                    .isTrue();
                        }
                    }
                }
            }
        }
    }
}
