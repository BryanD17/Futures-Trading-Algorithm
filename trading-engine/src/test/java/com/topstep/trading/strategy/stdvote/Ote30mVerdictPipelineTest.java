package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chart.OteState;
import com.topstep.trading.chart.OteZoneSnapshot;
import com.topstep.trading.strategy.stdvote.Ote30mConfluenceGate.Decision;
import com.topstep.trading.strategy.stdvote.Ote30mConfluenceGate.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3 Agent 06 — the 30m OTE verdict pipeline: persistent agreement stats
 * (round-trip, checkpoint overwrite safety, boot reminder) and the M7b
 * confluence gate (GATE semantics, ABSTAIN doctrine, OFF no-op).
 */
@DisplayName("30m OTE verdict pipeline (V3 Agent 06)")
class Ote30mVerdictPipelineTest {

    @TempDir
    Path tmp;

    private Path statsFile;

    @BeforeEach
    void isolateStore() {
        statsFile = tmp.resolve("ote_agreement_stats.jsonl");
        System.setProperty(OteAgreementStatsStore.FILE_PROPERTY, statsFile.toString());
        OteAgreementStatsStore.resetForTest();
        OteAgreementStats.resetRegistryForTest();
    }

    @AfterEach
    void cleanUp() {
        System.clearProperty(OteAgreementStatsStore.FILE_PROPERTY);
        OteAgreementStatsStore.resetForTest();
        OteAgreementStats.resetRegistryForTest();
    }

    /** Candle time inside the 2026-06-24 session (Wed). */
    private static final Instant SESSION_A = Instant.parse("2026-06-24T14:00:00Z");
    /** Candle time inside the 2026-06-25 session (Thu). */
    private static final Instant SESSION_B = Instant.parse("2026-06-25T14:00:00Z");

    // ── persistence ──────────────────────────────────────────────────────

    @Test
    @DisplayName("round-trip: write, restart-load, lifetime totals match")
    void persistenceRoundTrip() {
        OteAgreementStats s = OteAgreementStats.forSymbol("MNQ");
        s.recordMachineEmittedChartAgreed(SESSION_A);
        s.recordMachineEmittedChartAgreed(SESSION_A);
        s.recordMachineEmittedChartDisagreed(SESSION_A);
        s.recordChartReactedMachineSilent(SESSION_A);
        OteAgreementStatsStore.checkpoint("MNQ", SESSION_A);
        assertThat(statsFile).exists();

        // Simulated restart: session counters wiped, store cache dropped.
        OteAgreementStats.resetRegistryForTest();
        OteAgreementStatsStore.resetForTest();

        OteAgreementStatsStore.Lifetime lt =
                OteAgreementStatsStore.lifetimeExcluding("MNQ", LocalDate.of(2026, 7, 1));
        assertThat(lt.agreed()).isEqualTo(2);
        assertThat(lt.disagreed()).isEqualTo(1);
        assertThat(lt.chartOnly()).isEqualTo(1);
        assertThat(lt.sessions()).isEqualTo(1);

        // The API view: fresh session (0s) + restored lifetime.
        var api = OteAgreementStats.forSymbol("MNQ").toApiMap();
        @SuppressWarnings("unchecked")
        var lifetime = (java.util.Map<String, Object>) api.get("lifetime");
        assertThat(lifetime.get("machineEmitted_chartAgreed")).isEqualTo(2L);
        assertThat(lifetime.get("machineEmitted_chartDisagreed")).isEqualTo(1L);
    }

    @Test
    @DisplayName("checkpoint overwrite safety: same-session re-checkpoints never double count")
    void checkpointOverwriteSafety() {
        OteAgreementStats s = OteAgreementStats.forSymbol("MNQ");
        s.recordMachineEmittedChartAgreed(SESSION_A);
        OteAgreementStatsStore.checkpoint("MNQ", SESSION_A);   // agreed=1
        s.recordMachineEmittedChartAgreed(SESSION_A);
        OteAgreementStatsStore.checkpoint("MNQ", SESSION_A);   // agreed=2 (same session)
        s.recordMachineEmittedChartAgreed(SESSION_B);
        OteAgreementStatsStore.checkpoint("MNQ", SESSION_B);   // next session, agreed=3? no —
        // session counters are JVM-cumulative here; what matters is the
        // loader takes the LAST line per (symbol, session date).

        OteAgreementStatsStore.resetForTest();
        OteAgreementStatsStore.Lifetime lt =
                OteAgreementStatsStore.lifetimeExcluding("MNQ", LocalDate.of(2026, 7, 1));
        assertThat(lt.sessions()).isEqualTo(2);
        // Session A contributes its LAST checkpoint (2), not 1+2.
        // Session B's last checkpoint recorded 3 (cumulative JVM counters).
        assertThat(lt.agreed()).isEqualTo(2 + 3);
    }

    @Test
    @DisplayName("boot reminder fires once the evaluation threshold is met")
    void bootReminderAtThreshold() throws Exception {
        StringBuilder lines = new StringBuilder();
        for (int i = 1; i <= OteAgreementStatsStore.EVALUATION_SESSIONS; i++) {
            lines.append(String.format(
                    "{\"date\":\"2026-06-%02d\",\"symbol\":\"MNQ\",\"mode\":\"LOG\","
                    + "\"machineEmitted_chartAgreed\":%d,"
                    + "\"machineEmitted_chartDisagreed\":1,"
                    + "\"chartReacted_machineSilent\":0,\"sessionsCounted\":%d}%n",
                    i, i, i));
        }
        Files.writeString(statsFile, lines.toString());
        OteAgreementStatsStore.resetForTest();

        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            OteAgreementStatsStore.lifetimeExcluding("MNQ", LocalDate.of(2026, 7, 1));
        } finally {
            System.setOut(original);
        }
        assertThat(captured.toString())
                .contains("[OTE-VERDICT]")
                .contains("sessions collected — evaluation due");
    }

    // ── M7b gate semantics ───────────────────────────────────────────────

    private static OteZoneSnapshot zone(boolean bullish, OteState state) {
        return new OteZoneSnapshot("MNQ", bullish, 19950, 20150,
                SESSION_A, SESSION_A, state, SESSION_A);
    }

    @Test
    @DisplayName("GATE: passes on REACTED same-direction, blocks otherwise")
    void gateModeSemantics() {
        Ote30mConfluenceGate g = new Ote30mConfluenceGate("MNQ", Mode.GATE, false);
        g.setZoneSource(() -> Optional.of(zone(true, OteState.REACTED)));
        assertThat(g.gateCheck(true).passed()).isTrue();
        // Wrong direction blocks.
        assertThat(g.gateCheck(false).passed()).isFalse();
        // ARMED blocks unless acceptArmed.
        g.setZoneSource(() -> Optional.of(zone(true, OteState.ARMED)));
        assertThat(g.gateCheck(true).passed()).isFalse();
        Ote30mConfluenceGate armedOk = new Ote30mConfluenceGate("MNQ", Mode.GATE, true);
        armedOk.setZoneSource(() -> Optional.of(zone(true, OteState.ARMED)));
        assertThat(armedOk.gateCheck(true).passed()).isTrue();
        // FORMING blocks in GATE mode.
        g.setZoneSource(() -> Optional.of(zone(true, OteState.FORMING)));
        Decision d = g.gateCheck(true);
        assertThat(d.passed()).isFalse();
        assertThat(d.reason()).contains("FORMING");
        assertThat(g.blockedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("ABSTAIN: no zone tracked (or no chart wired) always passes")
    void abstainOnNoZone() {
        Ote30mConfluenceGate g = new Ote30mConfluenceGate("MNQ", Mode.GATE, false);
        // No chart engine wired at all.
        assertThat(g.gateCheck(true).passed()).isTrue();
        // Chart wired but no active zone.
        g.setZoneSource(Optional::empty);
        assertThat(g.gateCheck(false).passed()).isTrue();
        assertThat(g.abstainCount()).isEqualTo(2);
        assertThat(g.blockedCount()).isZero();
    }

    @Test
    @DisplayName("LOG (default): unfavorable zone counts WOULD-BLOCK but passes")
    void logModeCountsAndPasses() {
        Ote30mConfluenceGate g = new Ote30mConfluenceGate("MNQ", Mode.LOG, false);
        g.setZoneSource(() -> Optional.of(zone(false, OteState.REACTED)));
        assertThat(g.gateCheck(true).passed()).isTrue(); // wrong direction, LOG
        assertThat(g.wouldBlockCount()).isEqualTo(1);
        assertThat(g.gatesToken()).startsWith("m7b=WOULD-BLOCK");
    }

    @Test
    @DisplayName("OFF: byte-identical — zero evaluations, always passes")
    void offModeNoInvocation() {
        Ote30mConfluenceGate g = new Ote30mConfluenceGate("MNQ", Mode.OFF, false);
        g.setZoneSource(() -> Optional.of(zone(false, OteState.REACTED)));
        assertThat(g.gateCheck(true).passed()).isTrue();
        assertThat(g.evaluationCount()).isZero();
        assertThat(g.gatesToken()).isEqualTo("m7b=OFF");
        assertThat(Ote30mConfluenceGate.parseMode("banana")).isEqualTo(Mode.LOG);
    }
}
