package com.topstep.api.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V5 Agent 01: GET /api/status exposes the EFFECTIVE engine configuration. */
@WebMvcTest(controllers = StatusController.class)
@AutoConfigureMockMvc
@DisplayName("StatusController (/api/status) effectiveConfig")
class StatusControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("effectiveConfig lists every key with value + source, and the mode lines")
    void effectiveConfig() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveConfig.keys['backfill.days'].value").value("7"))
                .andExpect(jsonPath("$.effectiveConfig.keys['backfill.days'].source").value("DEFAULT"))
                .andExpect(jsonPath("$.effectiveConfig.keys['session.gateMode'].value").value("SCORING"))
                .andExpect(jsonPath("$.effectiveConfig.keys['scalp.enabled'].value").value("false"))
                .andExpect(jsonPath("$.effectiveConfig.keys['displacement.recentBars'].value").value("12")) // V5 Agent 04 product default
                .andExpect(jsonPath("$.effectiveConfig.modes").isArray())
                .andExpect(jsonPath("$.telemetry.errorCounts").exists());
    }
}
