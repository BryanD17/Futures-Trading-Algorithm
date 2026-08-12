package com.topstep.trading.trade;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.strategy.TradeTier;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.stdvote.OteZone;
import com.topstep.trading.strategy.stdvote.SetupContext;
import com.topstep.trading.strategy.stdvote.TradeableInstrument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RISK IS PROFILE-INDEPENDENT — the load-bearing criterion of V4 Agent 08 and
 * the mitigation for risk G-R2, the one unforgivable failure class on a funded
 * account.
 *
 * <p>Every test here is parameterised across ALL THREE profiles. A profile
 * selects which STRATEGY confluences are required; it can never widen a sizing
 * bound, borrow DLL headroom, trade past the flatten-by window or reach around
 * the kill switch.
 *
 * <p>The strongest evidence is structural rather than behavioural:
 * {@link PropFirmRiskEngine} is never passed a profile and has no way to obtain
 * one, so there is no code path by which a profile could influence it. These
 * tests pin that, so the day someone threads a profile into the risk layer,
 * a test goes red.
 */
class ProfileRiskIndependenceTest {

    private static final String SYM = "MNQ";

    private static SetupContext viableSetup() {
        SetupContext ctx = new SetupContext();
        ctx.symbol = SYM;
        ctx.htfBias = MarketBias.BULLISH;
        ctx.killzoneOpen = true;
        ctx.sweep = new com.topstep.trading.strategy.LiquiditySweep(
                true, 20990.0, java.time.Instant.parse("2026-08-11T14:00:00Z"), false);
        ctx.raidScore = 9;
        ctx.displacement = true;
        ctx.mss = true;
        ctx.ote = new OteZone(20900.0, 21000.0, true, 20950.0,
                20938.0, 20929.5, 20921.0, 20900.0);
        ctx.entry = ctx.ote.f705();
        ctx.stop = 20890.0;
        ctx.rr = 3.0;
        ctx.sizeRequest = TradeableInstrument.of(TradeableInstrument.Symbol.MNQ).minMicros();
        ctx.lastGateFailed = null;
        return ctx;
    }

    // ── M8: SIZING BOUNDS ──────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(TradeProfile.class)
    @DisplayName("An OVERSIZED entry is rejected in every profile")
    void oversizedEntryRejectedInEveryProfile(TradeProfile profile) {
        TradeableInstrument.Spec spec = TradeableInstrument.of(TradeableInstrument.Symbol.MNQ);
        SetupContext ctx = viableSetup();
        ctx.sizeRequest = spec.maxMicros() + 1;

        ProfileDecision d = ProfileEvaluator.evaluate(profile, ctx, null, "M8");
        assertThat(d.satisfied())
                .as("%s must not let an oversized entry through", profile)
                .isFalse();
        if (profile != TradeProfile.STRICT) {
            assertThat(d.blocking()).contains("M8:size>max");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TradeProfile.class)
    @DisplayName("An UNDERSIZED entry is rejected in every profile")
    void undersizedEntryRejectedInEveryProfile(TradeProfile profile) {
        TradeableInstrument.Spec spec = TradeableInstrument.of(TradeableInstrument.Symbol.MNQ);
        SetupContext ctx = viableSetup();
        ctx.sizeRequest = spec.minMicros() - 1;

        ProfileDecision d = ProfileEvaluator.evaluate(profile, ctx, null, "M8");
        assertThat(d.satisfied()).isFalse();
        if (profile != TradeProfile.STRICT) {
            assertThat(d.blocking()).contains("M8:size<min");
        }
    }

    // ── M9: RISK PRE-FLIGHT ────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(TradeProfile.class)
    @DisplayName("A failed risk pre-flight blocks every profile")
    void riskPreflightBlocksEveryProfile(TradeProfile profile) {
        SetupContext ctx = viableSetup();
        ctx.lastGateFailed = "RISK: daily loss limit headroom exhausted";

        ProfileDecision d = ProfileEvaluator.evaluate(profile, ctx, null, "M9");
        assertThat(d.satisfied()).isFalse();
        if (profile != TradeProfile.STRICT) {
            assertThat(d.blocking()).contains("M9:riskPreflight");
        }
    }

    // ── KILLZONE: NOT A RISK CONTROL, BUT EQUALLY UNIVERSAL ────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(TradeProfile.class)
    @DisplayName("NO profile trades outside the TRADING killzone")
    void noProfileTradesOutsideTheKillzone(TradeProfile profile) {
        SetupContext ctx = viableSetup();
        ctx.killzoneOpen = false;

        ProfileDecision d = ProfileEvaluator.evaluate(profile, ctx, null, "M3");
        assertThat(d.satisfied())
                .as("%s must never enter outside a trading killzone", profile)
                .isFalse();
        if (profile != TradeProfile.STRICT) {
            assertThat(d.blocking()).contains("killzone");
        }
    }

    // ── THE RISK ENGINE ITSELF ─────────────────────────────────────────────

    @Test
    @DisplayName("PropFirmRiskEngine returns a BYTE-IDENTICAL verdict under every profile")
    void riskEngineVerdictIsIdenticalAcrossProfiles() {
        String previous = System.getProperty(TradeProfile.PROPERTY);
        Map<TradeProfile, String> verdicts = new EnumMap<>(TradeProfile.class);
        try {
            for (TradeProfile profile : TradeProfile.values()) {
                System.setProperty(TradeProfile.PROPERTY, profile.name());
                assertThat(TradeProfile.active()).isEqualTo(profile);

                // A grossly oversized order against a fresh account, evaluated
                // identically under each profile.
                AccountState account = new AccountState(50_000.0);
                RiskLimits limits = RiskLimits.topstep50k();
                PropFirmRiskEngine engine = new PropFirmRiskEngine();
                StrategySignalEvent oversized = new StrategySignalEvent(
                        StrategySignalEvent.SignalType.LONG_ENTRY, SYM, OrderSide.BUY,
                        21000.0, 20900.0, 21300.0, "risk-independence probe",
                        TradeTier.TIER_2, 500);

                RiskDecision decision = engine.evaluate(oversized, account, limits);
                verdicts.put(profile, decision.isAllowed() + "|"
                        + (decision.getOrder() == null ? "none" : decision.getOrder().getQuantity())
                        + "|" + decision.getReason());
            }
        } finally {
            if (previous == null) System.clearProperty(TradeProfile.PROPERTY);
            else System.setProperty(TradeProfile.PROPERTY, previous);
        }

        assertThat(verdicts.get(TradeProfile.STANDARD)).isEqualTo(verdicts.get(TradeProfile.STRICT));
        assertThat(verdicts.get(TradeProfile.MINIMAL)).isEqualTo(verdicts.get(TradeProfile.STRICT));

        // …and the size control genuinely bit: the engine clamps 500 contracts
        // to the account's allowance rather than passing them through. Whatever
        // it clamps to, it clamps to the SAME number in all three profiles.
        String strict = verdicts.get(TradeProfile.STRICT);
        String qty = strict.split("\\|")[1];
        assertThat(qty).isNotEqualTo("none");
        assertThat(Integer.parseInt(qty))
                .as("500 contracts must not survive the risk engine")
                .isLessThan(500);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TradeProfile.class)
    @DisplayName("A profile is never even visible to the risk layer")
    void riskLayerCannotSeeAProfile(TradeProfile profile) {
        // Structural, not behavioural: no risk class references TradeProfile,
        // so no risk decision can depend on one. If someone threads a profile
        // into the risk layer, this assertion is the tripwire.
        for (Class<?> riskClass : new Class<?>[]{
                PropFirmRiskEngine.class, RiskLimits.class, RiskDecision.class,
                com.topstep.trading.risk.TradingRiskManager.class,
                com.topstep.trading.risk.PhaseAwareRiskCalculator.class}) {
            for (java.lang.reflect.Method m : riskClass.getDeclaredMethods()) {
                for (Class<?> param : m.getParameterTypes()) {
                    assertThat(param)
                            .as("%s.%s must not accept a TradeProfile", riskClass.getSimpleName(), m.getName())
                            .isNotEqualTo(TradeProfile.class);
                }
            }
            for (java.lang.reflect.Field f : riskClass.getDeclaredFields()) {
                assertThat(f.getType())
                        .as("%s must not hold a TradeProfile", riskClass.getSimpleName())
                        .isNotEqualTo(TradeProfile.class);
            }
        }
        assertThat(profile).isNotNull();
    }
}
