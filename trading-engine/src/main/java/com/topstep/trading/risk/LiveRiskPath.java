package com.topstep.trading.risk;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.lifecycle.RiskZone;
import com.topstep.trading.strategy.TradeTier;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * AGENT-05.10 (V5) — the LIVE signal handler's risk section, extracted so the
 * LIVE runner and the tape harness run the SAME code (R5: proof is the real
 * tape through the real runner path).
 *
 * <p><b>{@code risk.phaseAware=false} (default) — STATIC, the proven path:</b>
 * {@code riskEngine.evaluate(signal, account, limits)} with the static
 * {@code riskPerTrade}. No {@link PhaseAwareRiskCalculator}, no setup-quality
 * gate, no zone multiplier. Every DLL / MLL / max-contract / RR check inside
 * {@link PropFirmRiskEngine} runs unchanged.
 *
 * <p><b>{@code risk.phaseAware=true} — PHASE_AWARE:</b> the pre-05.10 LIVE
 * layer: {@link PhaseAwareRiskCalculator#calculateRisk} on the lifecycle
 * (quality gate, zone x quality multiplier, self-imposed daily buffer, trade
 * frequency) and, if it allows, {@code riskEngine.evaluate(signal, account,
 * limits, dynamicRisk)}. The strategy sizes the request from
 * {@link #perTradeBudget} — the SAME dynamic budget — so a request is never
 * "requested 5 &gt; max 1" because the sizer used a stale $250.
 *
 * <p><b>Lifecycle inputs:</b> the zone comes from the engine's OWN tracked
 * P&amp;L since start ({@link #trackedPnlSinceStart}) against the profile's
 * starting-balance baseline — never the broker balance (a $150,620 PRAC
 * account under the 50K profile is NORMAL at boot, not CRUISE). The zone and
 * its inputs are logged at boot and on every change.
 *
 * <p><b>{@code risk.haltOnProfitTarget}</b> (LIVE default true): new entries
 * are refused once the tracked REALIZED P&amp;L since engine start reaches the
 * profile's profit target — never the broker balance.
 */
public final class LiveRiskPath {

    public static final String PHASE_AWARE_KEY = "risk.phaseAware";

    /** The layer that denied (NONE = approved). */
    public enum DeniedBy { NONE, PROFIT_TARGET_HALT, PHASE_AWARE_GATE, PROP_FIRM_ENGINE }

    /**
     * One risk decision. {@code riskDecision} is the PropFirmRiskEngine result
     * (null when an earlier layer denied); {@code effectiveBudget} is the
     * per-trade $ the risk engine was given (before its DLL / MLL room caps).
     */
    public record Decision(boolean allowed, RiskDecision riskDecision, String reason, String path, DeniedBy deniedBy,
                           double effectiveBudget, RiskZone zone, int setupQuality,
                           PhaseAwareRiskCalculator.RiskCalculation calculation) {
        public boolean deniedBeforeEngine() {
            return !allowed && riskDecision == null;
        }
    }

    private static final ZoneId CT = ZoneId.of("America/Chicago");

    private final AccountLifecycle lifecycle;
    private final RiskProfile profile;
    private final PhaseAwareRiskCalculator calculator;
    private final boolean phaseAware;
    private final boolean haltOnProfitTarget;
    private final Consumer<String> log;

    private volatile int tradesToday = 0;
    private volatile LocalDate lastTradingDate = null;
    private volatile RiskZone lastZone = null;

    public LiveRiskPath(AccountLifecycle lifecycle, RiskProfile profile, PhaseAwareRiskCalculator calculator,
                        boolean phaseAware, boolean haltOnProfitTarget, Consumer<String> log) {
        this.lifecycle = lifecycle;
        this.profile = profile;
        this.calculator = calculator;
        this.phaseAware = phaseAware;
        this.haltOnProfitTarget = haltOnProfitTarget;
        this.log = log != null ? log : s -> { };
    }

    /**
     * The LIVE wiring (Topstep 50K lifecycle + risk profile, a fresh
     * calculator, {@code risk.phaseAware} and the LIVE
     * {@code risk.haltOnProfitTarget} default). LiveEngineRunner AND the tape
     * harness both build their risk path here.
     */
    public static LiveRiskPath topstep50kLive(Consumer<String> log) {
        return new LiveRiskPath(AccountLifecycle.topstep50kEvaluation(), RiskProfile.topstep50kEvaluation(),
                new PhaseAwareRiskCalculator(), phaseAwareEnabled(), RiskConfig.haltOnProfitTarget(true), log);
    }

    /** {@code risk.phaseAware} (default false = STATIC, the proven path). */
    public static boolean phaseAwareEnabled() {
        return com.topstep.trading.config.EngineConfig.current().getBoolean(PHASE_AWARE_KEY, false);
    }

    /** Engine-booked P&amp;L since start: realized + unrealized (never the broker balance). */
    public static double trackedPnlSinceStart(AccountState account) {
        return account.getRealizedPnL() + account.getUnrealizedPnL();
    }

    /**
     * Setup quality (0-10) from the tier the signal carries (the pre-05.10
     * LiveEngineRunner mapping): TIER_4 9, TIER_3 7, TIER_2 5, TIER_1 3, null 5.
     */
    public static int qualityOf(TradeTier tier) {
        if (tier == null) return 5;
        switch (tier) {
            case TIER_4: return 9;
            case TIER_3: return 7;
            case TIER_2: return 5;
            case TIER_1: return 3;
            default:     return 5;
        }
    }

    // ── lifecycle inputs ────────────────────────────────────────────────

    /**
     * Per-candle lifecycle housekeeping (LIVE candleHousekeeping): CT-date
     * rollover ({@code onDayEnd(netDailyPnl)}, trades-today reset), equity
     * sync from the TRACKED P&amp;L since start, intraday P&amp;L; logs a zone change.
     */
    public void onCandle(Instant ts, AccountState account) {
        LocalDate candleDate = ts.atZone(CT).toLocalDate();
        if (lastTradingDate == null || !candleDate.equals(lastTradingDate)) {
            if (lastTradingDate != null) {
                double dayPnl = account.getNetDailyPnl();
                lifecycle.onDayEnd(dayPnl);
                log.accept("[LIFECYCLE] New trading day detected. Previous day PnL: $"
                        + String.format(Locale.ROOT, "%.2f", dayPnl));
            }
            tradesToday = 0;
            lastTradingDate = candleDate;
        }
        lifecycle.syncTrackedPnl(trackedPnlSinceStart(account));
        lifecycle.updateIntradayPnl(account.getNetDailyPnl());
        logZoneIfChanged("change");
    }

    /** A completed trade (LIVE notifyPositionClosed). */
    public void onPositionClosed(double pnl) {
        lifecycle.recordTrade(pnl);
        tradesToday++;
        log.accept(String.format(Locale.ROOT,
                "[LIFECYCLE] Trade recorded: $%.2f | ConsecLoss=%d | TradesToday=%d | Zone=%s",
                pnl, lifecycle.getConsecutiveLosses(), tradesToday, lifecycle.getCurrentRiskZone()));
        logZoneIfChanged("change");
    }

    /** Logs the zone and its inputs (boot: always; otherwise only when the zone changed). */
    public void logZone(String when) {
        lastZone = lifecycle.getCurrentRiskZone();
        log.accept("[LIFECYCLE ZONE] " + when + ": " + lifecycle.zoneInputs()
                + " | broker balance NOT used (zone from tracked P&L since engine start)");
    }

    private void logZoneIfChanged(String when) {
        RiskZone z = lifecycle.getCurrentRiskZone();
        RiskZone prev = lastZone;
        if (prev != z) {
            lastZone = z;
            log.accept("[LIFECYCLE ZONE] " + when + " " + (prev == null ? "(none)" : prev) + " -> " + z + ": "
                    + lifecycle.zoneInputs() + " | broker balance NOT used");
        }
    }

    // ── the budget the strategy sizes from ──────────────────────────────

    /**
     * The per-trade $ budget for a signal of this tier (BEFORE the DLL / MLL
     * room caps both the sizer and the risk engine apply). STATIC: the static
     * riskPerTrade. PHASE_AWARE: the calculator's dynamic budget; when the
     * calculator blocks, the static budget (the gate then denies with its reason).
     */
    public double perTradeBudget(String symbol, TradeTier tier, double staticRiskPerTrade) {
        if (!phaseAware) return staticRiskPerTrade;
        PhaseAwareRiskCalculator.RiskCalculation c =
                calculator.calculateRisk(lifecycle, profile, qualityOf(tier), tradesToday);
        return c.isTradingAllowed() ? c.getRiskDollars() : staticRiskPerTrade;
    }

    /** The provider the strategy context uses: null in STATIC mode (the proven path, untouched). */
    public RiskBudgetProvider budgetProviderOrNull() {
        return phaseAware ? this::perTradeBudget : null;
    }

    // ── the decision ────────────────────────────────────────────────────

    /** The LIVE handler's risk section (STEP 2 + STEP 3). */
    public Decision evaluate(StrategySignalEvent signal, AccountState account, RiskLimits limits,
                             PropFirmRiskEngine engine) {
        int quality = qualityOf(signal.getTier());
        RiskZone zone = lifecycle.getCurrentRiskZone();

        if (haltOnProfitTarget && limits.getProfitTarget() > 0
                && engine.hasMetProfitTarget(account, limits)) {
            String why = String.format(Locale.ROOT,
                    "RISK: profit target reached: tracked realized P&L since engine start $%.2f >= $%.2f"
                            + " (risk.haltOnProfitTarget=true; broker balance not used)",
                    account.getRealizedPnL(), limits.getProfitTarget());
            return new Decision(false, null, why, pathName(), DeniedBy.PROFIT_TARGET_HALT, Double.NaN,
                    zone, quality, null);
        }

        if (!phaseAware) {
            RiskDecision d = engine.evaluate(signal, account, limits);
            return new Decision(d.isAllowed(), d, d.getReason(), pathName(),
                    d.isAllowed() ? DeniedBy.NONE : DeniedBy.PROP_FIRM_ENGINE,
                    limits.getRiskPerTrade(), zone, quality, null);
        }

        log.accept(String.format(Locale.ROOT,
                "[LIFECYCLE] Phase=%s Zone=%s Target=%.1f%% DD=%.1f%% ConsecLoss=%d Budget=$%.0f DLLRoom=$%.0f"
                        + " trackedPnL=$%.2f",
                lifecycle.getCurrentPhase(), zone, lifecycle.targetCompletionPct() * 100,
                lifecycle.drawdownUsagePct() * 100, lifecycle.getConsecutiveLosses(),
                lifecycle.riskBudgetRemaining(), lifecycle.dailyLossRoomRemaining(),
                lifecycle.trackedPnlSinceStart()));
        PhaseAwareRiskCalculator.RiskCalculation calc =
                calculator.calculateRisk(lifecycle, profile, quality, tradesToday);
        log.accept("[DYNAMIC RISK] " + calc);
        if (!calc.isTradingAllowed()) {
            return new Decision(false, null, "RISK: PhaseAwareRiskCalculator — " + calc.getBlockReason(),
                    pathName(), DeniedBy.PHASE_AWARE_GATE, calc.getRiskDollars(), zone, quality, calc);
        }
        double dynamicRisk = calc.getRiskDollars();
        RiskDecision d = engine.evaluate(signal, account, limits, dynamicRisk);
        return new Decision(d.isAllowed(), d, d.getReason(), pathName(),
                d.isAllowed() ? DeniedBy.NONE : DeniedBy.PROP_FIRM_ENGINE,
                dynamicRisk, zone, quality, calc);
    }

    /** The boot line: {@code RISK PATH: STATIC (proven) | PHASE_AWARE} and the effective budget. */
    public String bootLine(RiskLimits limits) {
        if (!phaseAware) {
            return String.format(Locale.ROOT,
                    "RISK PATH: STATIC (proven) - effective per-trade budget $%.2f (RiskLimits.riskPerTrade),"
                            + " capped by DLL room / MLL room; no PhaseAwareRiskCalculator, no quality gate,"
                            + " no zone multiplier (risk.phaseAware=false) | haltOnProfitTarget=%s (tracked P&L)",
                    limits.getRiskPerTrade(), haltOnProfitTarget);
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "RISK PATH: PHASE_AWARE - base $%.2f (%.2f%% of profile $%.0f) x zone %s x quality;"
                        + " effective budget now:",
                lifecycle.getStartingBalance() * profile.getBaseRiskPct(), profile.getBaseRiskPct() * 100,
                lifecycle.getStartingBalance(), lifecycle.getCurrentRiskZone()));
        for (TradeTier t : new TradeTier[] {TradeTier.TIER_4, TradeTier.TIER_3, TradeTier.TIER_2, TradeTier.TIER_1}) {
            PhaseAwareRiskCalculator.RiskCalculation c =
                    calculator.calculateRisk(lifecycle, profile, qualityOf(t), tradesToday);
            sb.append(" ").append(t.name()).append("(q").append(qualityOf(t)).append(")=")
              .append(c.isTradingAllowed() ? String.format(Locale.ROOT, "$%.2f", c.getRiskDollars())
                                           : "BLOCKED[" + c.getBlockReason() + "]");
        }
        sb.append(" | haltOnProfitTarget=").append(haltOnProfitTarget).append(" (tracked P&L)");
        return sb.toString();
    }

    /** {@code STATIC} or {@code PHASE_AWARE}. */
    public String pathName() {
        return phaseAware ? "PHASE_AWARE" : "STATIC";
    }

    // ── accessors ──
    public AccountLifecycle lifecycle() { return lifecycle; }
    public RiskProfile profile() { return profile; }
    public PhaseAwareRiskCalculator calculator() { return calculator; }
    public boolean phaseAware() { return phaseAware; }
    public boolean haltOnProfitTarget() { return haltOnProfitTarget; }
    public int tradesToday() { return tradesToday; }
}
