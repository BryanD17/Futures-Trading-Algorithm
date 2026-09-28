package com.topstep.trading.validation;

import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.session.SessionConfig;
import com.topstep.trading.strategy.session.SessionWindow;
import com.topstep.trading.strategy.stdvote.OteZone;
import com.topstep.trading.strategy.stdvote.SetupContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * V5 Agent 02 — M3 re-classified BLOCKING → SCORING (RC-02). SCORING passes
 * in every window but NO_ENTRY / WEEKEND (SACRED in both modes); BLOCKING
 * still requires the legacy killzone.
 */
@DisplayName("M3 session gate: SCORING vs BLOCKING, NO_ENTRY/WEEKEND sacred")
class StdvOteValidatorM3SessionTest {

    @AfterEach
    void clear() {
        System.clearProperty(SessionConfig.GATE_MODE);
    }

    private static MandatoryConfluenceValidator validator() {
        return new MandatoryConfluenceValidator(
                mock(com.topstep.trading.strategy.MultiTimeframeAnalyzer.class),
                mock(com.topstep.trading.strategy.DisplacementDetector.class),
                mock(com.topstep.trading.chartstate.ChartStateQueryAPI.class));
    }

    /** Same happy-path geometry as StdvOteValidatorTest, stamped with a session. */
    private static SetupContext happy(SessionWindow w, boolean killzoneOpen) {
        SetupContext ctx = new SetupContext();
        ctx.symbol = "MNQ";
        ctx.htfBias = MarketBias.BULLISH;
        ctx.killzoneOpen = killzoneOpen;
        ctx.sessionWindow = w.name();
        ctx.primeKillzone = false;
        ctx.sweep = new LiquiditySweep(true, 19952.0, Instant.parse("2026-09-28T01:00:00Z"), true);
        ctx.raidScore = 7;
        ctx.displacement = true;
        ctx.fvg = new FairValueGap(true, 20120.0, 20115.0, Instant.parse("2026-09-28T01:05:00Z"));
        ctx.mss = true;
        ctx.ote = new OteZone(19952.0, 20180.0, true, 20066.00, 20038.50, 20019.50, 19999.75, 19952.00);
        ctx.pdArrayInOte = 20020.00;
        ctx.entry = 20020.00;
        ctx.stop = 19951.00;
        ctx.rr = 5.5;
        ctx.sizeRequest = 12;
        ctx.lastGateFailed = null;
        return ctx;
    }

    @ParameterizedTest(name = "SCORING passes M3 in {0} with the legacy killzone CLOSED")
    @EnumSource(value = SessionWindow.class, names = {"ASIA", "LONDON", "PRE_NY", "NY_AM", "NY_LUNCH", "NY_PM", "PRE_ASIA"})
    void scoringPassesEveryOpenWindow(SessionWindow w) {
        ValidationResult r = validator().validateStdvOte(happy(w, false));
        assertThat(r.passed()).as("failures: %s", r.getFailures()).isTrue();
        assertThat(r.getConfirmations()).anyMatch(c -> c.startsWith("M3: session=" + w));
    }

    @ParameterizedTest(name = "{0} blocks M3 in SCORING (sacred)")
    @EnumSource(value = SessionWindow.class, names = {"NO_ENTRY", "WEEKEND"})
    void sacredWindowsBlockInScoring(SessionWindow w) {
        ValidationResult r = validator().validateStdvOte(happy(w, true));
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M3");
        assertThat(r.getFailures().get(0)).startsWith("M3: " + w + " window blocks entries");
    }

    @ParameterizedTest(name = "{0} blocks M3 in BLOCKING too, even with killzoneOpen=true")
    @EnumSource(value = SessionWindow.class, names = {"NO_ENTRY", "WEEKEND"})
    void sacredWindowsBlockInBlocking(SessionWindow w) {
        System.setProperty(SessionConfig.GATE_MODE, "BLOCKING");
        ValidationResult r = validator().validateStdvOte(happy(w, true));
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M3");
    }

    @Test
    @DisplayName("BLOCKING (A/B): ASIA with the killzone closed fails M3 exactly like pre-V5")
    void blockingRequiresKillzone() {
        System.setProperty(SessionConfig.GATE_MODE, "BLOCKING");
        ValidationResult r = validator().validateStdvOte(happy(SessionWindow.ASIA, false));
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M3");
        assertThat(r.getFailures()).containsExactly("M3: outside killzone");
        assertThat(validator().validateStdvOte(happy(SessionWindow.NY_AM, true)).passed()).isTrue();
    }
}
