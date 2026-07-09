package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.Event;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.MarketBias;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIELD BUG regression (2026-07-09 LIVE): a setup invalidated by a source
 * that fires AFTER the scalp re-arm step within the same candle — here the
 * HTF bias hook flipping to NEUTRAL on a 15m close — used to stay
 * INVALIDATED forever, because the re-arm's lastSeenState edge-detector
 * could never see the transition (lastSeenState was already INVALIDATED by
 * the time the next candle's re-arm step ran). The LIVE engine sat dead
 * for 7.5 hours through London + NY AM with zero trades while the 30m
 * chart logged 13 tradeable patterns.
 *
 * <p>This test drives the REAL path: bias set via the core, then a 15m
 * bar close whose (structureless) HTF evaluation is NEUTRAL invalidates
 * the in-flight setup mid-candle. The machine must re-arm within the
 * cooldown instead of staying INVALIDATED.
 */
@DisplayName("Scalp re-arm: late-in-candle invalidations must not stick (field bug 2026-07-09)")
class StdvOteScalpRearmStuckTest {

    static final class NullBus extends EventBus {
        @Override public void publish(Event event) { /* drop */ }
    }

    /** Wednesday 14:00Z = 10:00 ET — inside NY AM killzone (re-arm gate). */
    private static final Instant T0 = Instant.parse("2026-07-08T14:00:00Z");

    @BeforeEach
    void enableScalpMode() {
        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "true");
    }

    @AfterEach
    void cleanup() {
        System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        StdvOteRegistry.unregister("MNQ");
    }

    private static Candle flat(Instant ts, double p) {
        return new Candle("MNQ", ts, p, p, p, p, 100);
    }

    @Test
    void biasHookInvalidationReArmsInsteadOfStickingForever() {
        StdvOteRunnerStrategy runner = new StdvOteRunnerStrategy("MNQ", null, new NullBus());
        StdvOteStrategy core = StdvOteRegistry.get("MNQ").orElseThrow();
        SetupContext ctx = runner.getSetupContext();

        // Candle 1: establishes the runner's per-candle state (no 15m close).
        runner.onCandle(flat(T0, 20000), null);

        // In-flight setup: bias set directly on the core (the runner's own
        // hook would read the structureless trend as NEUTRAL).
        core.recordHtfBias(MarketBias.BULLISH);
        assertThat(ctx.state).isEqualTo(SetupState.BIAS_SET);

        // Candle 2 crosses the 15m boundary: the bias hook (which runs
        // AFTER the re-arm step in the same candle) evaluates the empty
        // structure as NEUTRAL and invalidates the in-flight setup.
        runner.onCandle(flat(T0.plus(Duration.ofMinutes(15)), 20000), null);
        assertThat(ctx.state).isEqualTo(SetupState.INVALIDATED);
        assertThat(ctx.lastGateFailed).isEqualTo("HTF bias became NEUTRAL");

        // Ten more 1m candles inside the killzone (no further 15m closes).
        // The re-arm engine must detect the dead setup, run its cooldown
        // (default 5 bars), and reset to IDLE — the OLD code stayed
        // INVALIDATED here forever.
        for (int i = 1; i <= 10; i++) {
            runner.onCandle(flat(T0.plus(Duration.ofMinutes(15 + i)), 20000), null);
        }

        assertThat(ctx.state)
                .as("a late-in-candle invalidation must re-arm after the cooldown, never stick")
                .isNotEqualTo(SetupState.INVALIDATED);
        assertThat(ctx.state).isEqualTo(SetupState.IDLE);
    }
}
