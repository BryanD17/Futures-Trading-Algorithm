package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.ImpulseExtensionAnalyzer;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.validation.MandatoryConfluenceValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-07-27 funnel fix — the M7 PD-array fallback scan. The spec (L4)
 * accepts ANY PD array inside the OTE band; the implementation used to test
 * only the displacement's own FVG, which routinely exits the band as the
 * post-MSS terminus extends (30 "M7: no PD array" hits in one live hour).
 */
@DisplayName("M7 PD-array fallback (any in-zone FVG qualifies)")
class StdvOtePdArrayFallbackTest {

    private StdvOteStrategy driveToMssConfirmed(FairValueGap displacementFvg) {
        StdvOteStrategy core = new StdvOteStrategy("MNQ",
                new StdvProjectionEngine(null, new ImpulseExtensionAnalyzer("MNQ", 30)),
                new OteEntryCalculator(),
                new MandatoryConfluenceValidator(null, null, null),
                null, 40L);
        core.getSetupContext().killzoneOpen = true;
        core.recordHtfBias(MarketBias.BULLISH);
        core.recordManipulationLeg(19960.0, 20000.0, 0.25, 0);
        core.recordSweep(new LiquiditySweep(true, 19952.0, Instant.now(), true), 7);
        core.recordDisplacement(displacementFvg);
        core.recordMss();
        assertThat(core.getSetupContext().state).isEqualTo(SetupState.MSS_CONFIRMED);
        return core;
    }

    /** Displacement FVG far ABOVE the band of the 19952→20180 impulse. */
    private static FairValueGap outOfZoneFvg() {
        return new FairValueGap(true, 20120.0, 20115.0, Instant.now());
    }

    @Test
    @DisplayName("no candidates: historical behavior — M7 hint, stays MSS_CONFIRMED")
    void withoutCandidatesUnchanged() {
        StdvOteStrategy core = driveToMssConfirmed(outOfZoneFvg());
        core.recordOteImpulse(19952.0, 20180.0, 0.25, true);
        SetupContext ctx = core.getSetupContext();
        assertThat(ctx.state).isEqualTo(SetupState.MSS_CONFIRMED);
        assertThat(ctx.lastGateFailed).contains("M7: no PD array");
        core.shutdown();
    }

    @Test
    @DisplayName("an in-zone unfilled FVG from the candidate list arms the setup")
    void inZoneCandidateArms() {
        StdvOteStrategy core = driveToMssConfirmed(outOfZoneFvg());
        // Band of the bullish 19952→20180 leg is [19999.75, 20038.50].
        FairValueGap inZone = new FairValueGap(true, 20020.0, 20010.0, Instant.now());
        FairValueGap wrongDirection = new FairValueGap(false, 20030.0, 20025.0, Instant.now());
        core.setCandidatePdArrays(List.of(wrongDirection, inZone));
        core.recordOteImpulse(19952.0, 20180.0, 0.25, true);
        SetupContext ctx = core.getSetupContext();
        assertThat(ctx.state).isEqualTo(SetupState.OTE_ARMED);
        assertThat(ctx.pdArrayKind).isEqualTo("FVG-alt");
        assertThat(ctx.pdArrayInOte).isEqualTo(20020.0);
        assertThat(ctx.fvg.getTop()).isEqualTo(20020.0); // swapped to the in-zone array
        core.shutdown();
    }

    @Test
    @DisplayName("displacement FVG already in zone: no fallback, kind stays FVG")
    void primaryFvgStillPreferred() {
        FairValueGap primaryInZone = new FairValueGap(true, 20020.0, 20010.0, Instant.now());
        StdvOteStrategy core = driveToMssConfirmed(primaryInZone);
        core.setCandidatePdArrays(List.of(new FairValueGap(true, 20030.0, 20025.0, Instant.now())));
        core.recordOteImpulse(19952.0, 20180.0, 0.25, true);
        SetupContext ctx = core.getSetupContext();
        assertThat(ctx.state).isEqualTo(SetupState.OTE_ARMED);
        assertThat(ctx.pdArrayKind).isEqualTo("FVG");
        assertThat(ctx.pdArrayInOte).isEqualTo(20020.0);
        core.shutdown();
    }
}
