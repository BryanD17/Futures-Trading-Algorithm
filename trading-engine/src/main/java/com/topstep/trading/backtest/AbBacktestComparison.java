package com.topstep.trading.backtest;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.domain.Trade;
import com.topstep.trading.domain.TradingSessionManager;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.event.SynchronousEventBus;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.TradingStrategy;
import com.topstep.trading.strategy.stdvote.ScalpConfig;
import com.topstep.trading.strategy.stdvote.StdvOteFactory;

import java.io.File;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * SA5 A/B backtest harness: runs the SAME candle set through
 * (a) LEGACY extension mode ({@code scalpMode.enabled=false} — −2σ STDV
 * targets, {@code topstep50k()} risk profile) and
 * (b) SCALP mode ({@code scalpMode.enabled=true} — 1R-capped targets,
 * {@code topstep50kScalp()}, re-arm, strict raid gate), and prints a
 * side-by-side table: trade count, trades/day, win rate, avg RR, GROSS and
 * NET PnL (explicit commission + slippage via {@link BacktestCosts}), and
 * max drawdown.
 *
 * <p>Run with ONE command:
 * <pre>./gradlew :trading-engine:run --args="ABTEST"</pre>
 * Cost knobs: {@code -Dbacktest.commissionPerSide=1.55}
 * {@code -Dbacktest.slippageTicks=1}.
 *
 * <p>Determinism: uses {@link SynchronousEventBus} so signals are
 * risk-checked and submitted on the SAME candle that produced them (the
 * stock async bus can delay submission by many candles — fine live, fatal
 * for reproducible backtests).
 *
 * <p>Dollar PnL is computed by the harness from fill prices at the TRUE MNQ
 * point value ($2/pt): the sim engine's own dollar conversion treats a
 * full POINT of price movement as one TICK's dollar value (pre-existing
 * quirk, flagged in SA5_final_report.md) — trade prices and win/loss signs
 * are unaffected, so the harness converts prices itself.
 *
 * <p>Data: if {@code data/MNQ_1min.csv} exists it is used; otherwise a
 * deterministic synthetic multi-session fixture is generated
 * ({@link SyntheticScalpSessionGenerator}) and a PROMINENT warning is
 * printed — synthetic results demonstrate STRUCTURE (frequency, gating,
 * cost drag), not real-market performance.
 */
public final class AbBacktestComparison {

    private static final String SYMBOL = "MNQ";
    private static final int SYNTHETIC_DAYS = 3;
    /** MNQ: $2 per point per contract; 0.25-pt tick = $0.50 per tick. */
    private static final double MNQ_POINT_VALUE = 2.0;
    private static final double MNQ_TICK_VALUE = 0.50;

    private AbBacktestComparison() {}

    /** One arm's aggregated results (harness-level dollar math — see class doc). */
    public static final class ArmResult {
        public final String name;
        public final int tradeCount;
        public final double tradesPerDay;
        public final double winRate;
        public final double avgRr;
        public final double grossPnl;
        public final double costs;
        public final double netPnl;
        public final double grossMaxDrawdown;
        public final double netMaxDrawdown;

        ArmResult(String name, List<Trade> trades, List<StrategySignalEvent> signals,
                  int days, BacktestCosts costModel) {
            this.name = name;
            this.tradeCount = trades.size();
            this.tradesPerDay = (double) trades.size() / Math.max(1, days);

            // Per-trade dollar PnL from fill prices at true point value; R
            // multiples from the matching signal's stop distance (FIFO — one
            // position at a time in both modes).
            Deque<StrategySignalEvent> pending = new ArrayDeque<>(signals);
            List<Double> pnls = new ArrayList<>();
            List<Double> rMultiples = new ArrayList<>();
            for (Trade t : trades) {
                double dir = t.getSide() == OrderSide.BUY ? 1.0 : -1.0;
                double points = (t.getExitPrice() - t.getEntryPrice()) * dir;
                pnls.add(points * t.getQuantity() * MNQ_POINT_VALUE);
                StrategySignalEvent sig = pending.pollFirst();
                if (sig != null) {
                    double riskPts = Math.abs(sig.getEntryPrice() - sig.getStopPrice());
                    if (riskPts > 0) rMultiples.add(points / riskPts);
                }
            }
            long wins = pnls.stream().filter(p -> p > 0).count();
            this.winRate = trades.isEmpty() ? 0.0 : 100.0 * wins / trades.size();
            this.avgRr = rMultiples.stream().mapToDouble(Math::abs).average().orElse(0.0);
            this.grossPnl = pnls.stream().mapToDouble(Double::doubleValue).sum();
            this.costs = costModel.totalCosts(trades);
            this.netPnl = grossPnl - costs;

            double peakG = 0, cumG = 0, ddG = 0, peakN = 0, cumN = 0, ddN = 0;
            for (int i = 0; i < trades.size(); i++) {
                double g = pnls.get(i);
                double n = g - costModel.costOf(trades.get(i));
                cumG += g; peakG = Math.max(peakG, cumG); ddG = Math.max(ddG, peakG - cumG);
                cumN += n; peakN = Math.max(peakN, cumN); ddN = Math.max(ddN, peakN - cumN);
            }
            this.grossMaxDrawdown = ddG;
            this.netMaxDrawdown = ddN;
        }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println("A/B BACKTEST: LEGACY EXTENSION MODE vs SCALP MODE — same candles, both modes");
        System.out.println("=".repeat(78));

        // ── Data: real CSV if present, else the synthetic session fixture ──
        List<Candle> candles;
        boolean synthetic;
        File realCsv = new File("data", "MNQ_1min.csv");
        if (realCsv.exists()) {
            try {
                candles = new HistoricalDataProvider().loadFromCsv(realCsv.getAbsolutePath(), SYMBOL);
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to load " + realCsv.getPath(), e);
            }
            synthetic = false;
            System.out.println("Data: REAL historical CSV " + realCsv.getPath()
                    + " (" + candles.size() + " candles)");
        } else {
            candles = SyntheticScalpSessionGenerator.generateSessions(SYNTHETIC_DAYS);
            synthetic = true;
            System.out.println();
            System.out.println("!".repeat(78));
            System.out.println("!!  WARNING: NO REAL HISTORICAL DATA IN REPO (data/MNQ_1min.csv missing).  !!");
            System.out.println("!!  Running on a SYNTHETIC " + SYNTHETIC_DAYS
                    + "-session killzone fixture (sweep/displacement/   !!");
            System.out.println("!!  MSS/OTE sequences). Results demonstrate STRUCTURE — frequency, gates,  !!");
            System.out.println("!!  cost drag — NOT market performance. REAL-DATA VALIDATION OUTSTANDING.  !!");
            System.out.println("!".repeat(78));
            System.out.println();
        }
        int days = (int) candles.stream()
                .map(c -> c.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate())
                .distinct().count();
        System.out.println("Candles: " + candles.size() + " | sessions: " + days);

        BacktestCosts costModel = new BacktestCosts(MNQ_TICK_VALUE);
        System.out.println("Cost model: " + costModel + " (MNQ $" + MNQ_POINT_VALUE + "/pt)");
        System.out.println();

        // ── Arm A: legacy extension mode ──
        System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        ArmResult legacy = runArm("LEGACY (-2s ext)", candles, days, costModel);

        // ── Arm B: scalp mode ──
        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "true");
        ArmResult scalp;
        try {
            scalp = runArm("SCALP (1R cap)", candles, days, costModel);
        } finally {
            System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        }

        printSideBySide(legacy, scalp, synthetic);
    }

    /**
     * Run one arm with a completely fresh engine/account/strategy stack.
     *
     * <p>The candle loop mirrors {@link BacktestRunner} (session rollover,
     * PropFirmRiskEngine as the Topstep rail, ExecutionEngine fills) but
     * submits orders deterministically on the SAME candle via the
     * synchronous bus, and uses single-TP brackets
     * ({@code submitOrderEnhanced} with no partial ladder) in BOTH arms so
     * the A/B cost math compares one clean round trip per signal — the
     * scalp exit model is single-TP by design (SA3), and giving legacy the
     * same bracket keeps the comparison apples-to-apples.
     */
    private static ArmResult runArm(String name, List<Candle> candles, int days,
                                    BacktestCosts costModel) {
        System.out.println("-".repeat(78));
        System.out.println("RUNNING ARM: " + name + "  (scalpMode.enabled="
                + ScalpConfig.isEnabled() + ", riskLimits="
                + (ScalpConfig.isEnabled() ? "topstep50kScalp" : "topstep50k") + ")");
        System.out.println("-".repeat(78));

        AccountState account = new AccountState(50_000.0);
        RiskLimits limits = ScalpConfig.activeRiskLimits();
        SynchronousEventBus bus = new SynchronousEventBus(); // same-candle dispatch
        ExecutionEngine exec = new ExecutionEngine(account);
        exec.setEventBus(bus); // PositionClosedEvent funnel (scalp re-arm)
        PropFirmRiskEngine riskEngine = new PropFirmRiskEngine();
        TradingSessionManager sessions = new TradingSessionManager();
        List<StrategySignalEvent> signals = new ArrayList<>();

        // Risk-check + submit the order synchronously, on the candle that
        // produced the signal.
        bus.subscribe(StrategySignalEvent.class, (StrategySignalEvent sig) -> {
            signals.add(sig);
            RiskDecision decision = riskEngine.evaluate(sig, account, limits);
            if (!decision.isAllowed()) {
                System.out.println("  [A/B] Signal DENIED by PropFirmRiskEngine: "
                        + decision.getReason());
                return;
            }
            Order order = decision.getOrder();
            if (order == null) return;
            System.out.println("  [A/B] Order submitted: " + order.getSide() + " "
                    + order.getQuantity() + " @ limit "
                    + String.format("%.2f", order.getLimitPrice())
                    + " stop " + sig.getStopPrice() + " target " + sig.getTargetPrice());
            exec.submitOrderEnhanced(order, sig.getStopPrice(), sig.getTargetPrice(),
                    sig.getTier(), new double[0][]); // single TP, no partial ladder
        });

        TradingStrategy strategy = StdvOteFactory.build(SYMBOL, "MES", bus);
        strategy.initialize();
        DefaultStrategyContext context = new DefaultStrategyContext(account);
        for (Candle candle : candles) {
            if (sessions.hasNewSessionStarted(candle.getTimestamp())) {
                account.startNewTradingDay(sessions.getCurrentSessionDate());
            }
            strategy.onCandle(candle, context);
            exec.onNewCandle(candle);
        }
        strategy.onSessionEnd();
        strategy.shutdown();

        System.out.println("  [A/B] " + name + ": " + signals.size() + " signal(s), "
                + exec.getCompletedTrades().size() + " completed trade(s)");
        return new ArmResult(name, exec.getCompletedTrades(), signals, days, costModel);
    }

    private static void printSideBySide(ArmResult a, ArmResult b, boolean synthetic) {
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("A/B RESULT — SIDE BY SIDE (GROSS and NET; costs applied per side per contract)");
        System.out.println("=".repeat(78));
        String fmt = "| %-22s | %18s | %18s |%n";
        System.out.printf(fmt, "Metric", a.name, b.name);
        System.out.println("|" + "-".repeat(24) + "|" + "-".repeat(20) + "|" + "-".repeat(20) + "|");
        System.out.printf(fmt, "Trades", String.valueOf(a.tradeCount), String.valueOf(b.tradeCount));
        System.out.printf(fmt, "Trades/day",
                String.format("%.2f", a.tradesPerDay), String.format("%.2f", b.tradesPerDay));
        System.out.printf(fmt, "Win rate",
                String.format("%.1f%%", a.winRate), String.format("%.1f%%", b.winRate));
        System.out.printf(fmt, "Avg RR (realized |R|)",
                String.format("%.2f", a.avgRr), String.format("%.2f", b.avgRr));
        System.out.printf(fmt, "Gross PnL",
                String.format("$%.2f", a.grossPnl), String.format("$%.2f", b.grossPnl));
        System.out.printf(fmt, "Costs (comm+slip)",
                String.format("-$%.2f", a.costs), String.format("-$%.2f", b.costs));
        System.out.printf(fmt, "NET PnL",
                String.format("$%.2f", a.netPnl), String.format("$%.2f", b.netPnl));
        System.out.printf(fmt, "Max drawdown (gross)",
                String.format("$%.2f", a.grossMaxDrawdown), String.format("$%.2f", b.grossMaxDrawdown));
        System.out.printf(fmt, "Max drawdown (net)",
                String.format("$%.2f", a.netMaxDrawdown), String.format("$%.2f", b.netMaxDrawdown));
        System.out.println();
        if (synthetic) {
            System.out.println(">>> SYNTHETIC DATA — real-data validation is OUTSTANDING. <<<");
        }
        System.out.println("Note: legacy mode is one-emission-per-run by design (IN_TRADE is terminal");
        System.out.println("without scalp re-arm), so its trades/day on a multi-session set reflects");
        System.out.println("that structural limit, not a like-for-like frequency.");
    }
}
