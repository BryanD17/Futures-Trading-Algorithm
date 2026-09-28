package com.topstep.trading.risk;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.event.StrategySignalEvent.SignalType;
import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SA3 tests for the scalp risk profile:
 *
 * <ul>
 *   <li>{@code RiskLimits.topstep50kScalp()} values (Topstep rails untouched,
 *       scalp RR band, $150 risk, frequency gates).</li>
 *   <li>{@code RiskLimits.topstep50k()} byte-for-byte unchanged, including
 *       the new fields' legacy-neutral defaults.</li>
 *   <li>{@code PropFirmRiskEngine} frequency gates: trade #7 of the day is
 *       rejected and the trade after 3 consecutive losses is rejected on the
 *       scalp profile — and the legacy profile enforces NEITHER.</li>
 * </ul>
 */
@DisplayName("Scalp risk profile (topstep50kScalp + PropFirmRiskEngine gates)")
class ScalpRiskProfileTest {

    private final PropFirmRiskEngine engine = new PropFirmRiskEngine();

    /** MNQ scalp-shaped signal: risk 10 pts, target 10 pts → RR exactly 1.0. */
    private static StrategySignalEvent scalpSignal() {
        return new StrategySignalEvent(
                SignalType.LONG_ENTRY, "MNQ", OrderSide.BUY,
                21000.0, 20990.0, 21010.0, "scalp test",
                TradeTier.TIER_1, 6, 1.0, new double[][] {{1.0, 1.0}}, false);
    }

    /** MNQ legacy-shaped signal: risk 10 pts, target 30 pts → RR exactly 3.0. */
    private static StrategySignalEvent legacySignal() {
        return new StrategySignalEvent(
                SignalType.LONG_ENTRY, "MNQ", OrderSide.BUY,
                21000.0, 20990.0, 21030.0, "legacy test",
                TradeTier.TIER_1, 6);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Profile values
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RiskLimits profiles")
    class Profiles {

        @Test
        @DisplayName("topstep50kScalp(): Topstep rails unchanged, scalp knobs set")
        void scalpProfileValues() {
            RiskLimits s = RiskLimits.topstep50kScalp();
            // Topstep rails — identical to topstep50k(), never weakened.
            assertThat(s.getMaxDailyLoss()).isEqualTo(1000.0);
            assertThat(s.getMaxLossLimit()).isEqualTo(2000.0);
            assertThat(s.getProfitTarget()).isEqualTo(3000.0);
            assertThat(s.getTrailingDrawdown()).isEqualTo(2000.0);
            assertThat(s.getFlattenByTime()).isEqualTo(LocalTime.of(15, 10));
            assertThat(s.isAllowWeekendTrading()).isFalse();
            // Scalp knobs.
            assertThat(s.getRiskPerTrade()).isEqualTo(150.0);
            // V5 ONE band (RC-13): floor 0.8 (scalp), ceiling 5.0; the
            // signal* getters are aliases of the same two numbers.
            assertThat(s.getRrFloor()).isEqualTo(0.8);
            assertThat(s.getRrCeiling()).isEqualTo(5.0);
            assertThat(s.getMinRiskRewardRatio()).isEqualTo(0.8);
            assertThat(s.getMaxRiskRewardRatio()).isEqualTo(5.0);
            assertThat(s.getSignalMinRr()).isEqualTo(0.8);
            assertThat(s.getSignalMaxRr()).isEqualTo(5.0);
            assertThat(s.getMaxContracts()).isEqualTo(20);
            assertThat(s.getMaxTotalContracts()).isEqualTo(20);
            assertThat(s.getMaxTradesPerDay()).isEqualTo(6);
            assertThat(s.getMaxConsecutiveLosses()).isEqualTo(3);
        }

        @Test
        @DisplayName("topstep50k() is unchanged — legacy values plus legacy-neutral new-field defaults")
        void legacyProfileUnchanged() {
            RiskLimits l = RiskLimits.topstep50k();
            assertThat(l.getMaxDailyLoss()).isEqualTo(1000.0);
            assertThat(l.getMaxLossLimit()).isEqualTo(2000.0);
            assertThat(l.getProfitTarget()).isEqualTo(3000.0);
            assertThat(l.getTrailingDrawdown()).isEqualTo(2000.0);
            assertThat(l.getMaxContracts()).isEqualTo(5);
            assertThat(l.getMaxTotalContracts()).isEqualTo(10);
            assertThat(l.getRiskPerTrade()).isEqualTo(250.0);
            // V5 ONE band (RC-13): legacy floor 1.0, ceiling 5.0 (was a
            // second, disagreeing band [3.0, 6.0] in the risk engine).
            assertThat(l.getMinRiskRewardRatio()).isEqualTo(1.0);
            assertThat(l.getMaxRiskRewardRatio()).isEqualTo(5.0);
            assertThat(l.getFlattenByTime()).isEqualTo(LocalTime.of(15, 10));
            assertThat(l.isAllowWeekendTrading()).isFalse();
            // The validator reads the SAME band; frequency gates disabled.
            assertThat(l.getSignalMinRr()).isEqualTo(1.0);
            assertThat(l.getSignalMaxRr()).isEqualTo(5.0);
            assertThat(l.getMaxTradesPerDay()).isZero();
            assertThat(l.getMaxConsecutiveLosses()).isZero();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // PropFirmRiskEngine frequency gates
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PropFirmRiskEngine frequency gates")
    class FrequencyGates {

        @Test
        @DisplayName("scalp profile: trades 1-6 allowed, trade #7 of the day rejected")
        void seventhTradeOfDayRejected() {
            RiskLimits scalp = RiskLimits.topstep50kScalp();
            AccountState account = new AccountState(50_000.0);

            for (int i = 1; i <= 6; i++) {
                RiskDecision d = engine.evaluate(scalpSignal(), account, scalp);
                assertThat(d.isAllowed())
                        .as("trade #%d must be allowed (reason: %s)", i, d.getReason())
                        .isTrue();
                account.recordTradeCompleted(+50.0); // winning scalps — DLL untouched
            }
            assertThat(account.getTradesToday()).isEqualTo(6);

            RiskDecision seventh = engine.evaluate(scalpSignal(), account, scalp);
            assertThat(seventh.isAllowed()).isFalse();
            assertThat(seventh.getReason()).contains("Max trades per day");
        }

        @Test
        @DisplayName("scalp profile: the trade after 3 consecutive losses is rejected")
        void tradeAfterThreeConsecutiveLossesRejected() {
            RiskLimits scalp = RiskLimits.topstep50kScalp();
            AccountState account = new AccountState(50_000.0);

            // Two losses: still allowed.
            account.recordTradeCompleted(-150.0);
            account.recordTradeCompleted(-150.0);
            assertThat(engine.evaluate(scalpSignal(), account, scalp).isAllowed()).isTrue();

            // Third consecutive loss trips the gate.
            account.recordTradeCompleted(-150.0);
            assertThat(account.getConsecutiveLosses()).isEqualTo(3);
            RiskDecision blocked = engine.evaluate(scalpSignal(), account, scalp);
            assertThat(blocked.isAllowed()).isFalse();
            assertThat(blocked.getReason()).contains("Max consecutive losses");
        }

        @Test
        @DisplayName("a win resets the consecutive-loss streak")
        void winResetsLossStreak() {
            RiskLimits scalp = RiskLimits.topstep50kScalp();
            AccountState account = new AccountState(50_000.0);

            account.recordTradeCompleted(-150.0);
            account.recordTradeCompleted(-150.0);
            account.recordTradeCompleted(+120.0); // win resets
            account.recordTradeCompleted(-150.0);
            assertThat(account.getConsecutiveLosses()).isEqualTo(1);
            assertThat(engine.evaluate(scalpSignal(), account, scalp).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("day rollover resets tradesToday (consecutive losses persist)")
        void dayRolloverResetsTradesToday() {
            RiskLimits scalp = RiskLimits.topstep50kScalp();
            AccountState account = new AccountState(50_000.0);
            LocalDate today = account.getCurrentTradingDay();

            for (int i = 0; i < 6; i++) {
                account.recordTradeCompleted(+50.0, today);
            }
            assertThat(engine.evaluate(scalpSignal(), account, scalp).isAllowed()).isFalse();

            // Next trading day: counter resets, trading allowed again.
            account.resetDailyCounters(today.plusDays(1));
            assertThat(account.getTradesToday()).isZero();
            assertThat(engine.evaluate(scalpSignal(), account, scalp).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("legacy profile enforces NEITHER gate (7+ trades, 3+ losses still allowed)")
        void legacyProfileEnforcesNeither() {
            RiskLimits legacy = RiskLimits.topstep50k();
            AccountState account = new AccountState(50_000.0);

            // 8 completed trades including 4 consecutive losses. Keep the
            // total day PnL above -DLL so only the frequency gates could
            // possibly block (they must not, on the legacy profile).
            account.recordTradeCompleted(+100.0);
            account.recordTradeCompleted(+100.0);
            account.recordTradeCompleted(+100.0);
            account.recordTradeCompleted(+100.0);
            account.recordTradeCompleted(-50.0);
            account.recordTradeCompleted(-50.0);
            account.recordTradeCompleted(-50.0);
            account.recordTradeCompleted(-50.0);
            assertThat(account.getTradesToday()).isEqualTo(8);
            assertThat(account.getConsecutiveLosses()).isEqualTo(4);

            RiskDecision d = engine.evaluate(legacySignal(), account, legacy);
            assertThat(d.isAllowed())
                    .as("legacy profile must not enforce frequency gates (reason: %s)",
                            d.getReason())
                    .isTrue();
        }

        @Test
        @DisplayName("V5: the engine accepts RR 1.0 AND RR 3.0 (no second ceiling) and still rejects below the floor")
        void scalpRrBandEnforced() {
            RiskLimits scalp = RiskLimits.topstep50kScalp();
            AccountState account = new AccountState(50_000.0);

            assertThat(engine.evaluate(scalpSignal(), account, scalp).isAllowed()).isTrue();

            // V5 RC-13: the "R:R too high (unrealistic)" ceiling is GONE from
            // the risk engine — a 3R signal is allowed (the band's 5.0
            // ceiling is enforced once, by the validator).
            RiskDecision threeR = engine.evaluate(legacySignal(), account, scalp);
            assertThat(threeR.isAllowed()).as(threeR.getReason()).isTrue();
            // The floor is still enforced, from RiskLimits.rrFloor.
            RiskLimits strict = scalp.toBuilder().rrFloor(4.0).build();
            RiskDecision tooLow = engine.evaluate(legacySignal(), account, strict);
            assertThat(tooLow.isAllowed()).isFalse();
            assertThat(tooLow.getReason()).contains("R:R too low");
        }
    }
}
