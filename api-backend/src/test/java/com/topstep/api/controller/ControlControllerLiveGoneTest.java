package com.topstep.api.controller;

import com.topstep.trading.LiveTradingRemovedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V6 Agent 05.6: every LIVE control answers 410 Gone with the V6 message. */
@WebMvcTest(controllers = ControlController.class)
@AutoConfigureMockMvc
@DisplayName("ControlController LIVE controls -> 410 Gone (V6)")
class ControlControllerLiveGoneTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("POST /api/control/start?mode=LIVE -> 410 + V6 message")
    void startLiveGone() throws Exception {
        mockMvc.perform(post("/api/control/start").param("mode", "LIVE"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value("GONE"))
                .andExpect(jsonPath("$.message").value(LiveTradingRemovedException.MESSAGE));
    }

    @Test
    @DisplayName("POST /api/control/killswitch and /flatten -> 410")
    void liveOnlyControlsGone() throws Exception {
        mockMvc.perform(post("/api/control/killswitch"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(LiveTradingRemovedException.MESSAGE));
        mockMvc.perform(post("/api/control/flatten"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(LiveTradingRemovedException.MESSAGE));
    }

    @Test
    @DisplayName("GET /api/control/status carries the research-mode banner")
    void statusBanner() throws Exception {
        mockMvc.perform(get("/api/control/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.researchMode").value(true))
                .andExpect(jsonPath("$.banner").value("Research mode — live trading on TradingView"));
    }
}
