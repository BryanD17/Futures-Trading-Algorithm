package com.topstep.trading.chartstate;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 Agent 05.3 (item 4): level iteration and best-raid selection are
 * deterministic. The enum-keyed ConcurrentHashMap iterated in identity-hash
 * order, so the 2026-09-24 12:16 two-raid tie ("NY PM Session Low 30671.25"
 * vs "Asia Session Low 30642.75", both score 9, same bar) flipped between runs.
 */
class LevelIterationDeterminismTest {

    private static final Instant T = Instant.parse("2026-09-24T16:16:00Z");

    @Test
    void levelsIterateInLevelTypeOrdinalOrderWhateverTheInsertionOrder() {
        LevelType[] types = {LevelType.NY_PM_LOW, LevelType.EQUAL_LOW, LevelType.ASIA_LOW,
                LevelType.PDL, LevelType.LONDON_HIGH, LevelType.SESSION_LOW};
        List<LevelType> expected = java.util.Arrays.stream(types).sorted().collect(Collectors.toList());
        for (int rotation = 0; rotation < types.length; rotation++) {
            LevelEngine engine = new LevelEngine("MNQ", new CandleSeries("MNQ"));
            for (int i = 0; i < types.length; i++) {
                LevelType t = types[(i + rotation) % types.length];
                engine.addEqualLevel(t, 30_000 + i, 2, T);
                new Object().hashCode(); // allocation noise must not matter
            }
            List<LevelType> order = engine.getAllLevels().stream().map(KnownLevel::getType).collect(Collectors.toList());
            assertThat(order).as("rotation " + rotation).isEqualTo(expected);
        }
    }

    private static LiquidityRaid raid(LevelType type, double price, int score) {
        KnownLevel level = new KnownLevel(type, price, T.minusSeconds(3600));
        LiquidityRaid r = new LiquidityRaid("MNQ", level, RaidDirection.LOW_SWEEP, T, 100,
                price + 40, price - 3, price + 20, price + 30, 12, 0.25);
        r.setQualityScore(score, List.of("test"));
        return r;
    }

    @Test
    void theTwoRaidTieOf0924IsBrokenByPriceInEveryOrder() {
        LiquidityRaid nyPm = raid(LevelType.NY_PM_LOW, 30671.25, 9);
        LiquidityRaid asia = raid(LevelType.ASIA_LOW, 30642.75, 9);
        for (List<LiquidityRaid> order : List.of(List.of(nyPm, asia), List.of(asia, nyPm))) {
            Optional<LiquidityRaid> best = order.stream().min(RaidDetector.BEST_RAID_ORDER);
            assertThat(best).contains(nyPm); // same score, same bar -> the higher swept price
            Optional<LiquidityRaid> recent = order.stream()
                    .min(Comparator.comparingInt(LiquidityRaid::getBarsSinceRaid).thenComparing(RaidDetector.BEST_RAID_ORDER));
            assertThat(recent).contains(nyPm);
        }
    }

    @Test
    void bestRaidOrderIsScoreThenRecencyThenPrice() {
        LiquidityRaid low = raid(LevelType.ASIA_LOW, 30600.0, 8);
        LiquidityRaid high = raid(LevelType.PDL, 30500.0, 10);
        LiquidityRaid older = raid(LevelType.LONDON_LOW, 30700.0, 10);
        older.incrementBarsSinceRaid();
        List<LiquidityRaid> all = new ArrayList<>(List.of(low, older, high));
        assertThat(all.stream().min(RaidDetector.BEST_RAID_ORDER)).contains(high); // score 10 + fresher
        java.util.Collections.reverse(all);
        assertThat(all.stream().min(RaidDetector.BEST_RAID_ORDER)).contains(high);
    }
}
