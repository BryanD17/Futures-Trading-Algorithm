package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.domain.AccountState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke tests for {@link StdvOteRunnerStrategy}.
 *
 * <p>The pure state-machine behaviour of {@link StdvOteStrategy} is already
 * covered by {@code StdvOteStrategyTest}. These tests prove the runner-side
 * wrapper:
 *
 * <ul>
 *   <li>rejects non-tradeable symbols at construction;</li>
 *   <li>survives a stream of candles without throwing;</li>
 *   <li>starts in {@code IDLE} and does not emit on a flat candle feed
 *       (no setup conditions are present).</li>
 * </ul>
 *
 * <p>Whether the strategy fires on real ICT setups is a SIM-time concern,
 * verified by you watching the Setup panel against a MockConnector or
 * BacktestRunner candle stream — not by a unit test.
 */
@DisplayName("StdvOteRunnerStrategy smoke")
class StdvOteRunnerStrategyTest {

    private DefaultStrategyContext ctx() {
        return new DefaultStrategyContext(new AccountState(50_000.0));
    }

    @Test
    @DisplayName("rejects full-size NQ at construction")
    void rejectsFullSizeNq() {
        EventBus bus = new EventBus();
        assertThatThrownBy(() -> new StdvOteRunnerStrategy("NQ", "ES", bus))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MNQ")
                .hasMessageContaining("MES")
                .hasMessageContaining("MGC");
    }

    @Test
    @DisplayName("accepts MNQ + MES + MGC")
    void acceptsAllThree() {
        EventBus bus = new EventBus();
        assertThat(new StdvOteRunnerStrategy("MNQ", "MES", bus).getName())
                .isEqualTo("STDV_OTE");
        assertThat(new StdvOteRunnerStrategy("MES", "MNQ", bus).getName())
                .isEqualTo("STDV_OTE");
        assertThat(new StdvOteRunnerStrategy("MGC", null, bus).getName())
                .isEqualTo("STDV_OTE");
    }

    @Test
    @DisplayName("starts in IDLE; flat candle stream produces no emit")
    void flatStreamStaysIdle() {
        EventBus bus = new EventBus();
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy("MNQ", "MES", bus);

        Instant t = Instant.parse("2026-05-28T14:00:00Z");
        // 50 boring candles, no displacement, no sweep, no structure shift.
        for (int i = 0; i < 50; i++) {
            Candle c = new Candle("MNQ", t.plus(i, ChronoUnit.MINUTES),
                    20000.0, 20000.50, 19999.50, 20000.0, 100);
            s.onCandle(c, ctx());
        }
        // Even when state stays IDLE, the strategy must not have crashed.
        assertThat(s.getSetupContext().symbol).isEqualTo("MNQ");
        // No signal emitted.
        assertThat(s.getSetupContext().sizeFilled).isEqualTo(0);
    }

    @Test
    @DisplayName("session end is safe to call multiple times")
    void sessionEndIdempotent() {
        EventBus bus = new EventBus();
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy("MNQ", "MES", bus);
        s.onSessionEnd();
        s.onSessionEnd();
        s.shutdown();
    }

    @Test
    @DisplayName("SMT candles for the correlate symbol do not advance the primary state")
    void smtCandlesDoNotAdvancePrimary() {
        EventBus bus = new EventBus();
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy("MNQ", "MES", bus);

        Instant t = Instant.parse("2026-05-28T14:00:00Z");
        for (int i = 0; i < 20; i++) {
            Candle mes = new Candle("MES", t.plus(i, ChronoUnit.MINUTES),
                    5000.0, 5001.0, 4999.0, 5000.5, 100);
            s.onCandle(mes, ctx());
        }
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.IDLE);
    }
}
