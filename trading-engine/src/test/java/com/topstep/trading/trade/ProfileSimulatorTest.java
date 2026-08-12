package com.topstep.trading.trade;

import com.topstep.trading.confluence.ConfluenceField;
import com.topstep.trading.confluence.ConfluenceService;
import com.topstep.trading.confluence.ConfluenceSnapshot;
import com.topstep.trading.confluence.EngineFacts;
import com.topstep.trading.confluence.Tri;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.stdvote.OteZone;
import com.topstep.trading.strategy.stdvote.SetupContext;
import com.topstep.trading.strategy.stdvote.TradeableInstrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE SIMULATOR — always on, all profiles, whatever is active.
 */
class ProfileSimulatorTest {

    private static final String SYM = "MNQ";
    private static final Instant T = Instant.parse("2026-08-11T14:00:00Z");

    @TempDir
    Path tmp;

    @BeforeEach
    void reset() {
        ProfileSimulator.resetAll();
    }

    /** A setup that satisfies MINIMAL but not STANDARD (no bias, no PD array). */
    private static SetupContext minimalOnlySetup() {
        SetupContext ctx = new SetupContext();
        ctx.symbol = SYM;
        ctx.htfBias = MarketBias.NEUTRAL;
        ctx.killzoneOpen = true;
        ctx.sweep = new com.topstep.trading.strategy.LiquiditySweep(true, 20990.0, T, false);
        ctx.raidScore = 2;                       // below the instrument base
        ctx.mss = true;                          // structure is there
        ctx.ote = new OteZone(20900.0, 21000.0, true, 20950.0,
                20938.0, 20929.5, 20921.0, 20900.0);
        ctx.entry = ctx.ote.f705();              // inside the band
        ctx.stop = 20890.0;
        ctx.rr = 3.0;
        ctx.sizeRequest = TradeableInstrument.of(TradeableInstrument.Symbol.MNQ).minMicros();
        return ctx;
    }

    /** A confluence stack with nothing ictlib can confirm. */
    private static ConfluenceSnapshot emptyStack(boolean bullish) {
        Map<ConfluenceField, Tri> values = new EnumMap<>(ConfluenceField.class);
        for (ConfluenceField f : ConfluenceField.values()) values.put(f, Tri.UNKNOWN);
        return new ConfluenceSnapshot(SYM, bullish, T, values, Map.of(), 0, 0);
    }

    @Test
    @DisplayName("MINIMAL is satisfied where STANDARD is not — and the difference is named")
    void profileSetsGenuinelyDiffer() {
        SetupContext ctx = minimalOnlySetup();

        ProfileDecision minimal = ProfileEvaluator.evaluate(
                TradeProfile.MINIMAL, ctx, emptyStack(true), "M2");
        ProfileDecision standard = ProfileEvaluator.evaluate(
                TradeProfile.STANDARD, ctx, emptyStack(true), "M2");

        assertThat(minimal.satisfied()).isTrue();
        assertThat(standard.satisfied()).isFalse();
        assertThat(standard.blocking())
                .contains("bias", "raidScore<5", "pdArray");
    }

    @Test
    @DisplayName("Blocking lists name EVERY unmet requirement, not just the first")
    void blockingListsAreComplete() {
        SetupContext ctx = minimalOnlySetup();
        ctx.killzoneOpen = false;
        ctx.sweep = null;
        ctx.mss = false;

        ProfileDecision d = ProfileEvaluator.evaluate(
                TradeProfile.MINIMAL, ctx, emptyStack(true), "M3");
        assertThat(d.blocking()).contains("killzone", "sweep", "structure");
        assertThat(d.blocking().size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("A would-trade event is recorded when a profile is satisfied and the engine did NOT emit")
    void wouldTradeEventIsRecorded() {
        System.setProperty(ProfileSimulator.FILE_PROPERTY,
                tmp.resolve("sim.jsonl").toString());
        try {
            ProfileSimulator sim = ProfileSimulator.forSymbol(SYM);
            sim.evaluate(minimalOnlySetup(), "M2", false, emptyStack(true), T);

            assertThat(sim.wouldTrade(TradeProfile.MINIMAL)).isEqualTo(1);
            assertThat(sim.wouldTrade(TradeProfile.STANDARD)).isZero();

            List<WouldTradeEvent> events = sim.recentEvents(TradeProfile.MINIMAL);
            assertThat(events).hasSize(1);
            WouldTradeEvent e = events.get(0);
            assertThat(e.symbol()).isEqualTo(SYM);
            assertThat(e.direction()).isEqualTo("LONG");
            assertThat(e.activeProfile()).isEqualTo(TradeProfile.STRICT);
            assertThat(e.blockingGatesOfActiveProfile()).contains("M2");
            assertThat(e.entry()).isEqualTo(20929.5);
            assertThat(e.stop()).isEqualTo(20890.0);
            // Target is DERIVED from the setup's own geometry, never invented.
            assertThat(e.target()).isEqualTo(20929.5 + (20929.5 - 20890.0) * 3.0);
        } finally {
            System.clearProperty(ProfileSimulator.FILE_PROPERTY);
        }
    }

    @Test
    @DisplayName("No event when the engine DID emit — that is a trade, not a missed one")
    void noEventWhenTheEngineTraded() {
        System.setProperty(ProfileSimulator.FILE_PROPERTY, tmp.resolve("e.jsonl").toString());
        try {
            ProfileSimulator sim = ProfileSimulator.forSymbol(SYM);
            sim.evaluate(minimalOnlySetup(), null, true, emptyStack(true), T);
            assertThat(sim.wouldTrade(TradeProfile.MINIMAL)).isZero();
            assertThat(sim.wouldTrade(TradeProfile.STANDARD)).isZero();
        } finally {
            System.clearProperty(ProfileSimulator.FILE_PROPERTY);
        }
    }

    @Test
    @DisplayName("PERSISTENCE ROUND-TRIP: events survive being written and read back")
    void persistenceRoundTrip() {
        Path file = tmp.resolve("roundtrip.jsonl");
        System.setProperty(ProfileSimulator.FILE_PROPERTY, file.toString());
        try {
            ProfileSimulator sim = ProfileSimulator.forSymbol(SYM);
            sim.evaluate(minimalOnlySetup(), "M2", false, emptyStack(true), T);
            // The latch means a SECOND event needs the condition to lapse and
            // return — one opportunity is one event, however long it persists.
            SetupContext lapsed = minimalOnlySetup();
            lapsed.mss = false;
            sim.evaluate(lapsed, "M2", false, emptyStack(true), T.plusSeconds(300));
            sim.evaluate(minimalOnlySetup(), "M4", false, emptyStack(true), T.plusSeconds(900));

            // A restart loses in-memory counters; the FILE is what survives.
            ProfileSimulator.resetAll();

            List<Map<String, Object>> loaded = ProfileSimulator.loadPersisted();
            assertThat(loaded).hasSize(2);
            assertThat(loaded.get(0)).containsEntry("symbol", SYM)
                    .containsEntry("profile", "MINIMAL")
                    .containsEntry("activeProfile", "STRICT")
                    .containsEntry("direction", "LONG");
            assertThat(loaded.get(0).get("blockingGates").toString()).contains("M2");
            assertThat(loaded.get(1).get("blockingGates").toString()).contains("M4");
            assertThat(loaded.get(0)).containsKeys("timestamp", "entry", "stop", "target", "rr");
        } finally {
            System.clearProperty(ProfileSimulator.FILE_PROPERTY);
        }
    }

    @Test
    @DisplayName("The ACTIVE profile's blocking gates are ranked by frequency — the 'why no trade' table")
    void blockingGatesAreRanked() {
        System.setProperty(ProfileSimulator.FILE_PROPERTY, tmp.resolve("r.jsonl").toString());
        try {
            ProfileSimulator sim = ProfileSimulator.forSymbol(SYM);
            for (int i = 0; i < 5; i++) {
                sim.evaluate(minimalOnlySetup(), "M2", false, emptyStack(true), T);
            }
            // STRICT is active, so its own failing gate is what gets ranked.
            List<Map.Entry<String, Long>> ranked = sim.rankedBlockingGates();
            assertThat(ranked).isNotEmpty();
            assertThat(ranked.get(0).getKey()).isEqualTo("M2");
            assertThat(ranked.get(0).getValue()).isEqualTo(5L);
            assertThat(sim.evaluations()).isEqualTo(5);
        } finally {
            System.clearProperty(ProfileSimulator.FILE_PROPERTY);
        }
    }

    @Test
    @DisplayName("[PROFILE] line carries the documented shape")
    void profileLineShape() {
        System.setProperty(ProfileSimulator.FILE_PROPERTY, tmp.resolve("p.jsonl").toString());
        try {
            ProfileSimulator sim = ProfileSimulator.forSymbol(SYM);
            sim.evaluate(minimalOnlySetup(), "M2", false, emptyStack(true), T);
            assertThat(sim.logLine())
                    .isEqualTo("[PROFILE MNQ] active=STRICT wouldTrade: STANDARD=0 MINIMAL=1 (session)");
        } finally {
            System.clearProperty(ProfileSimulator.FILE_PROPERTY);
        }
    }

    @Test
    @DisplayName("API counters expose per-profile totals, recent events and the ranked gates")
    void apiCounters() {
        System.setProperty(ProfileSimulator.FILE_PROPERTY, tmp.resolve("a.jsonl").toString());
        try {
            ProfileSimulator sim = ProfileSimulator.forSymbol(SYM);
            sim.evaluate(minimalOnlySetup(), "M2", false, emptyStack(true), T);

            Map<String, Object> api = sim.toApiMap();
            assertThat(api).containsEntry("activeProfile", "STRICT");
            assertThat(api).containsKeys("evaluations", "sessionDate", "profiles", "blockingGates");

            @SuppressWarnings("unchecked")
            Map<String, Object> profiles = (Map<String, Object>) api.get("profiles");
            assertThat(profiles).containsKeys("STRICT", "STANDARD", "MINIMAL");
            @SuppressWarnings("unchecked")
            Map<String, Object> minimal = (Map<String, Object>) profiles.get("MINIMAL");
            assertThat(minimal).containsEntry("wouldTrade", 1L);
            assertThat((List<?>) minimal.get("recentEvents")).hasSize(1);
        } finally {
            System.clearProperty(ProfileSimulator.FILE_PROPERTY);
        }
    }

    @Test
    @DisplayName("The simulator runs on ALL profiles even while STRICT is active — that is the point")
    void allProfilesEvaluatedRegardlessOfActive() {
        System.setProperty(ProfileSimulator.FILE_PROPERTY, tmp.resolve("all.jsonl").toString());
        try {
            assertThat(TradeProfile.active()).isEqualTo(TradeProfile.STRICT);
            ProfileSimulator sim = ProfileSimulator.forSymbol(SYM);
            sim.evaluate(minimalOnlySetup(), "M2", false, emptyStack(true), T);

            // STRICT failed (M2), STANDARD is unsatisfied, MINIMAL is satisfied —
            // and the engine learned that without anyone flipping a switch.
            assertThat(sim.satisfied(TradeProfile.MINIMAL)).isEqualTo(1);
            assertThat(sim.satisfied(TradeProfile.STANDARD)).isZero();
            assertThat(sim.satisfied(TradeProfile.STRICT)).isZero();
        } finally {
            System.clearProperty(ProfileSimulator.FILE_PROPERTY);
        }
    }

    @Test
    @DisplayName("RISING EDGE: a profile that stays satisfied is ONE opportunity, not many")
    void satisfiedConditionCountsOnce() {
        System.setProperty(ProfileSimulator.FILE_PROPERTY, tmp.resolve("edge.jsonl").toString());
        try {
            ProfileSimulator sim = ProfileSimulator.forSymbol(SYM);
            for (int i = 0; i < 8; i++) {
                sim.evaluate(minimalOnlySetup(), "M2", false, emptyStack(true),
                        T.plusSeconds(60L * i), false);
            }
            assertThat(sim.evaluations()).isEqualTo(8);
            assertThat(sim.emissionEvaluations())
                    .as("periodic samples are not emission evaluations")
                    .isZero();
            assertThat(sim.wouldTrade(TradeProfile.MINIMAL))
                    .as("eight samples of one persistent condition is ONE would-trade")
                    .isEqualTo(1);

            // Let it lapse, then return: that is a genuinely new opportunity.
            SetupContext lapsed = minimalOnlySetup();
            lapsed.killzoneOpen = false;
            sim.evaluate(lapsed, "M3", false, emptyStack(true), T.plusSeconds(600), false);
            sim.evaluate(minimalOnlySetup(), "M2", false, emptyStack(true),
                    T.plusSeconds(660), false);
            assertThat(sim.wouldTrade(TradeProfile.MINIMAL)).isEqualTo(2);
        } finally {
            System.clearProperty(ProfileSimulator.FILE_PROPERTY);
        }
    }

    @Test
    @DisplayName("A null confluence stack degrades to UNKNOWN rather than throwing")
    void nullStackIsSafe() {
        System.setProperty(ProfileSimulator.FILE_PROPERTY, tmp.resolve("n.jsonl").toString());
        try {
            ProfileSimulator sim = ProfileSimulator.forSymbol(SYM);
            sim.evaluate(minimalOnlySetup(), "M2", false, null, T);
            assertThat(sim.evaluations()).isEqualTo(1);

            ConfluenceService service = new ConfluenceService();
            service.publish(SYM, EngineFacts.cold(T));
            assertThat(ProfileSimulator.snapshotFor(service, SYM, minimalOnlySetup())).isNotNull();
            assertThat(ProfileSimulator.snapshotFor(null, SYM, minimalOnlySetup())).isNull();
        } finally {
            System.clearProperty(ProfileSimulator.FILE_PROPERTY);
        }
    }
}
