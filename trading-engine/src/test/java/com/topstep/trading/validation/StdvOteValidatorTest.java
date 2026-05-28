package com.topstep.trading.validation;

import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.stdvote.OteZone;
import com.topstep.trading.strategy.stdvote.SetupContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * SA4 tests for {@link MandatoryConfluenceValidator#validateStdvOte}.
 *
 * <p>Each test starts from a happy-path {@link SetupContext}, mutates one
 * gate, and asserts the validator short-circuits at that gate. The happy
 * path tests that all M1..M9 pass together. The validator only cares about
 * the data in the context — it doesn't read the chart, killzone, or risk
 * engine itself.
 */
@DisplayName("MandatoryConfluenceValidator.validateStdvOte (M1..M9)")
class StdvOteValidatorTest {

    private MandatoryConfluenceValidator newValidator() {
        // The legacy validator constructor takes detectors we don't use for
        // the STDV-OTE path; mocks satisfy the type system.
        return new MandatoryConfluenceValidator(
                mock(com.topstep.trading.strategy.MultiTimeframeAnalyzer.class),
                mock(com.topstep.trading.strategy.DisplacementDetector.class),
                mock(com.topstep.trading.chartstate.ChartStateQueryAPI.class));
    }

    /**
     * Build a fully-populated, valid setup context. Each test mutates one
     * field to drive a single mandatory gate to fail.
     */
    private SetupContext happyMnqBullish() {
        SetupContext ctx = new SetupContext();
        ctx.symbol = "MNQ";
        ctx.htfBias = MarketBias.BULLISH;
        ctx.killzoneOpen = true;

        // M4 — bullish sweep (sellside), raid score 7 >= 5 threshold for MNQ.
        ctx.sweep = new LiquiditySweep(true, 19952.0, Instant.now(), true);
        ctx.raidScore = 7;

        // M5 — displacement candle + bullish FVG.
        ctx.displacement = true;
        ctx.fvg = new FairValueGap(true, 20120.0, 20115.0, Instant.now());

        // M6 — MSS confirmed.
        ctx.mss = true;

        // M7 — bullish OTE zone built on impulse 19952 → 20180 (range 228).
        // Using OteEntryCalculator-equivalent geometry:
        //   eq50 = 20066, f62 = 20038.64 → 20038.50,
        //   f705 = 20019.46 → 20019.50, f79 = 19999.88 → 19999.75
        // Use a hand-built zone for simplicity in the test:
        ctx.ote = new OteZone(
                19952.0, 20180.0, true,
                /* eq50 */ 20066.00,
                /* f62  */ 20038.50,
                /* f705 */ 20019.50,
                /* f79  */ 19999.75,
                /* one  */ 19952.00);
        ctx.pdArrayInOte = 20020.00; // inside [19999.75, 20038.50]
        ctx.entry = 20020.00;
        ctx.stop = 19951.00;          // 4-tick buffer below 19952 swept low
        // rr from entry 20020 to projected -2.0 ≈ 20180 + 1*range up to ~20408
        // Use a conservative figure that exceeds the 2.0 floor.
        ctx.rr = 5.5;

        // M8 — sized order at 12 micros, in [5, 20].
        ctx.sizeRequest = 12;

        // M9 — no prior gate failure logged.
        ctx.lastGateFailed = null;

        return ctx;
    }

    @Test
    @DisplayName("happy path: every gate passes")
    void happyPath() {
        ValidationResult r = newValidator().validateStdvOte(happyMnqBullish());
        assertThat(r.passed()).as("happy-path failures: %s", r.getFailures()).isTrue();
        assertThat(r.getSummary()).isEqualTo("STDV+OTE M1..M9 all passed");
        assertThat(r.getConfirmationCount()).isGreaterThanOrEqualTo(9);
    }

    @Test
    @DisplayName("M1: unknown instrument rejected with gate id M1")
    void m1UnknownInstrument() {
        SetupContext ctx = happyMnqBullish();
        ctx.symbol = "NQ"; // full-size mini, not in {MNQ, MES, MGC}
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M1");
        assertThat(r.getFailures().get(0)).contains("M1");
    }

    @Test
    @DisplayName("M1: null context rejected with M1")
    void m1NullContext() {
        ValidationResult r = newValidator().validateStdvOte(null);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M1");
    }

    @Test
    @DisplayName("M2: NEUTRAL bias rejected with M2")
    void m2NeutralBias() {
        SetupContext ctx = happyMnqBullish();
        ctx.htfBias = MarketBias.NEUTRAL;
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M2");
    }

    @Test
    @DisplayName("M2: trade direction (zone) mismatching HTF bias rejected with M2")
    void m2DirectionMismatch() {
        SetupContext ctx = happyMnqBullish();
        // Flip the zone direction without flipping bias.
        OteZone z = ctx.ote;
        ctx.ote = new OteZone(z.legLow(), z.legHigh(), /* bullish */ false,
                z.eq50(), z.f62(), z.f705(), z.f79(), z.one00());
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M2");
    }

    @Test
    @DisplayName("M3: outside killzone rejected with M3")
    void m3OutsideKillzone() {
        SetupContext ctx = happyMnqBullish();
        ctx.killzoneOpen = false;
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M3");
    }

    @Test
    @DisplayName("M4: missing sweep rejected with M4")
    void m4NoSweep() {
        SetupContext ctx = happyMnqBullish();
        ctx.sweep = null;
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M4");
    }

    @Test
    @DisplayName("M4: raid score below instrument minimum rejected with M4")
    void m4LowRaidScoreMnq() {
        SetupContext ctx = happyMnqBullish();
        ctx.raidScore = 4; // MNQ minimum is 5
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M4");
        assertThat(r.getFailures().get(0)).contains("4");
    }

    @Test
    @DisplayName("M4: MGC raid score below 6 (its stricter floor) rejected with M4")
    void m4MgcStricterFloor() {
        SetupContext ctx = happyMnqBullish();
        ctx.symbol = "MGC";
        ctx.raidScore = 5; // ok for MNQ/MES but BELOW MGC floor (6)
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M4");
    }

    @Test
    @DisplayName("M5: no displacement rejected with M5")
    void m5NoDisplacement() {
        SetupContext ctx = happyMnqBullish();
        ctx.displacement = false;
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M5");
    }

    @Test
    @DisplayName("M5: no FVG rejected with M5")
    void m5NoFvg() {
        SetupContext ctx = happyMnqBullish();
        ctx.fvg = null;
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M5");
    }

    @Test
    @DisplayName("M6: MSS not confirmed rejected with M6")
    void m6NoMss() {
        SetupContext ctx = happyMnqBullish();
        ctx.mss = false;
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M6");
    }

    @Test
    @DisplayName("M7: no OTE zone rejected with M7")
    void m7NoOteZone() {
        SetupContext ctx = happyMnqBullish();
        ctx.ote = null;
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M7");
    }

    @Test
    @DisplayName("M7: no PD array in zone (NaN) rejected with M7")
    void m7NoPdArray() {
        SetupContext ctx = happyMnqBullish();
        ctx.pdArrayInOte = Double.NaN;
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M7");
    }

    @Test
    @DisplayName("M7: planned entry outside OTE band rejected with M7")
    void m7EntryOutsideZone() {
        SetupContext ctx = happyMnqBullish();
        ctx.entry = 20140.00; // above f62 = 20038.50 — outside band
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M7");
    }

    @Test
    @DisplayName("M7: RR below 2.0 floor rejected with M7")
    void m7LowRr() {
        SetupContext ctx = happyMnqBullish();
        ctx.rr = 1.5;
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M7");
    }

    @Test
    @DisplayName("M8: size below 5 rejected with M8")
    void m8BelowFloor() {
        SetupContext ctx = happyMnqBullish();
        ctx.sizeRequest = 4;
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M8");
    }

    @Test
    @DisplayName("M8: size above 20 rejected with M8")
    void m8AboveCeiling() {
        SetupContext ctx = happyMnqBullish();
        ctx.sizeRequest = 21;
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M8");
    }

    @Test
    @DisplayName("M9: prior risk-engine failure surfaces as M9")
    void m9RiskPreFlightFlag() {
        SetupContext ctx = happyMnqBullish();
        ctx.lastGateFailed = "RISK: MLL cushion breached"; // strategy sets this earlier
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M9");
    }

    @Test
    @DisplayName("short-circuit: gate ordering means an M2 failure does not surface as M3")
    void shortCircuitOrdering() {
        SetupContext ctx = happyMnqBullish();
        ctx.htfBias = MarketBias.NEUTRAL; // M2 will fail
        ctx.killzoneOpen = false;          // M3 would also fail
        ValidationResult r = newValidator().validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary())
                .as("validator must report the FIRST failing gate (M2), not later ones")
                .isEqualTo("M2");
    }
}
