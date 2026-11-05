package com.topstep.trading.v6;

import com.topstep.trading.EngineFacade;
import com.topstep.trading.LiveEngineRunner;
import com.topstep.trading.LiveTradingRemovedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V6 Agent 05.1 (R6 / I9): no Java code path can place a Topstep order. */
class LiveRemovedTest {

    @Test
    @DisplayName("the exact V6 message")
    void exactMessage() {
        assertThat(LiveTradingRemovedException.MESSAGE).isEqualTo(
                "Topstep removed in V6 — live trading runs on TradingView (see RUNBOOK_TRADINGVIEW.md)");
    }

    @Test
    @DisplayName("constructing the LIVE runner fails loud before any credential / connection")
    void liveRunnerFailsLoud() {
        assertThatThrownBy(LiveEngineRunner::new)
                .isInstanceOf(LiveTradingRemovedException.class)
                .hasMessage(LiveTradingRemovedException.MESSAGE);
    }

    @Test
    @DisplayName("EngineFacade.startLive fails loud")
    void facadeStartLiveFailsLoud() {
        EngineFacade facade = EngineFacade.getInstance();
        assertThatThrownBy(facade::startLive)
                .isInstanceOf(LiveTradingRemovedException.class)
                .hasMessage(LiveTradingRemovedException.MESSAGE);
    }

    @Test
    @DisplayName("no compiled source references the Topstep connector or credentials")
    void noRuntimeReference() throws IOException {
        Path main = Path.of("src/main/java");
        assertThat(main).isDirectory();
        try (Stream<Path> files = Files.walk(main)) {
            List<Path> hits = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String s = Files.readString(p);
                    return s.contains("TopstepConnector") || s.contains("TopstepCredentials");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).toList();
            assertThat(hits).isEmpty();
        }
    }
}
