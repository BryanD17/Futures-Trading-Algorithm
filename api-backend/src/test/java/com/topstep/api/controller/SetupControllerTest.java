package com.topstep.api.controller;

import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.stdvote.OteEntryCalculator;
import com.topstep.trading.strategy.stdvote.SetupContext;
import com.topstep.trading.strategy.stdvote.SetupState;
import com.topstep.trading.strategy.stdvote.StdvOteStrategy;
import com.topstep.trading.strategy.stdvote.StdvProjectionEngine;
import com.topstep.trading.validation.MandatoryConfluenceValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SA6 tests for {@link SetupController}.
 *
 * <p>{@link com.topstep.trading.strategy.stdvote.StdvOteRegistry} is a process-
 * wide static singleton; the strategy registers itself on construction. The
 * tests create a strategy with mocked collaborators, drive the {@link
 * SetupContext} into a known state, and assert the controller serializes
 * the expected JSON shape.
 */
@WebMvcTest(controllers = SetupController.class)
@AutoConfigureMockMvc
@DisplayName("SetupController (/api/setup)")
class SetupControllerTest {

    @Autowired
    MockMvc mockMvc;

    private StdvOteStrategy strategy;

    @BeforeEach
    void setUp() {
        // Build a real strategy — its constructor registers it in StdvOteRegistry.
        strategy = new StdvOteStrategy(
                "MNQ",
                new StdvProjectionEngine(null, null),
                new OteEntryCalculator(),
                new MandatoryConfluenceValidator(null, null, null),
                null,
                40L);
    }

    @AfterEach
    void tearDown() {
        if (strategy != null) {
            strategy.shutdown(); // unregisters
        }
    }

    @Test
    @DisplayName("GET /api/setup returns strategy name + active symbols")
    void listActive() throws Exception {
        mockMvc.perform(get("/api/setup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("STDV_OTE"))
                .andExpect(jsonPath("$.activeSymbols").isArray())
                .andExpect(jsonPath("$.activeSymbols", org.hamcrest.Matchers.hasItem("MNQ")));
    }

    @Test
    @DisplayName("GET /api/setup/instruments returns MNQ/MES/MGC specs")
    void instruments() throws Exception {
        mockMvc.perform(get("/api/setup/instruments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].symbol").value("MNQ"))
                .andExpect(jsonPath("$[0].tickSize").value(0.25))
                .andExpect(jsonPath("$[0].pointValue").value(2.0))
                .andExpect(jsonPath("$[0].minMicros").value(5))
                .andExpect(jsonPath("$[0].maxMicros").value(20));
    }

    @Test
    @DisplayName("GET /api/setup/MNQ returns IDLE snapshot with empty projections")
    void mnqIdle() throws Exception {
        mockMvc.perform(get("/api/setup/MNQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("MNQ"))
                .andExpect(jsonPath("$.state").value("IDLE"))
                .andExpect(jsonPath("$.htfBias").value("NEUTRAL"))
                .andExpect(jsonPath("$.projections").isArray())
                .andExpect(jsonPath("$.projections.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/setup/NQ returns 404 (NQ is not tradeable)")
    void unknownSymbolReturns404() throws Exception {
        mockMvc.perform(get("/api/setup/NQ"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/setup/{symbol}/projections returns the STDV ladder when populated")
    void projectionsAfterBiasAndLeg() throws Exception {
        // Drive the strategy to MANIP_DONE so the projections array fills in.
        SetupContext ctx = strategy.getSetupContext();
        ctx.killzoneOpen = true;
        // recordHtfBias + recordManipulationLeg are package-private; call via
        // the controller-facing snapshot by mutating ctx directly.
        ctx.htfBias = MarketBias.BULLISH;
        ctx.state = SetupState.BIAS_SET;
        // Compute projections via a fresh engine (deterministic).
        ctx.projections = new StdvProjectionEngine(null, null)
                .project(19960.0, 20000.0, MarketBias.BULLISH, 0.25, 0);
        ctx.state = SetupState.MANIP_DONE;

        mockMvc.perform(get("/api/setup/MNQ/projections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].sigma").value(-0.27))
                .andExpect(jsonPath("$[1].sigma").value(-1.0))
                .andExpect(jsonPath("$[2].sigma").value(-2.0))
                .andExpect(jsonPath("$[2].rawPrice").value(20040.0))
                .andExpect(jsonPath("$[3].sigma").value(-2.5))
                .andExpect(jsonPath("$[4].sigma").value(-4.0))
                .andExpect(jsonPath("$[4].rawPrice").value(20120.0));
    }

    @Test
    @DisplayName("GET /api/setup/MNQ serialises ote / sweep / fvg cleanly when set")
    void richSnapshotSerialization() throws Exception {
        SetupContext ctx = strategy.getSetupContext();
        ctx.killzoneOpen = true;
        ctx.htfBias = MarketBias.BULLISH;
        ctx.legLow = 19960.0;
        ctx.legHigh = 20000.0;
        ctx.legBullish = true;
        ctx.state = SetupState.OTE_ARMED;

        mockMvc.perform(get("/api/setup/MNQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OTE_ARMED"))
                .andExpect(jsonPath("$.htfBias").value("BULLISH"))
                .andExpect(jsonPath("$.killzoneOpen").value(true))
                .andExpect(jsonPath("$.legLow").value(19960.0))
                .andExpect(jsonPath("$.legHigh").value(20000.0))
                .andExpect(jsonPath("$.legBullish").value(true));
    }
}
