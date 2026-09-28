package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.IctHighConfluenceStrategy;
import com.topstep.trading.strategy.TradingStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V5 Agent 01, task 6 (PF-01 / D-09): strategy selection never falls back to
 * the legacy strategy silently.
 */
@DisplayName("StdvOteFactory fail-fast strategy selection (V5 Agent 01)")
class StdvOteFactoryFailFastTest {

    @AfterEach
    void cleanup() {
        System.clearProperty(StdvOteFactory.LEGACY_FALLBACK_PROPERTY);
        StdvOteRegistry.unregister("MNQ");
    }

    @Test
    @DisplayName("symbol NQ fails fast and the message names strategy.legacyFallback")
    void nqFailsFast() {
        assertThatThrownBy(() -> StdvOteFactory.build("NQ", "ES", new EventBus(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy.legacyFallback")
                .hasMessageContaining("'NQ'");
    }

    @Test
    @DisplayName("strategy.legacyFallback=true restores the explicit, logged legacy fallback")
    void explicitFallback() {
        System.setProperty(StdvOteFactory.LEGACY_FALLBACK_PROPERTY, "true");
        TradingStrategy s = StdvOteFactory.build("NQ", "ES", new EventBus(1));
        assertThat(s).isInstanceOf(IctHighConfluenceStrategy.class);
    }

    @Test
    @DisplayName("MNQ builds the StdvOteRunnerStrategy")
    void mnqBuildsRunner() {
        TradingStrategy s = StdvOteFactory.build("MNQ", "MES", new EventBus(1));
        assertThat(s).isInstanceOf(StdvOteRunnerStrategy.class);
        s.shutdown();
    }
}
