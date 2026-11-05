package com.topstep.trading.strategy.stdvote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.chartstate.LiquidityRaid;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.domain.Trade;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.PositionClosedEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.risk.LiveRiskPath;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.strategy.DefaultStrategyContext;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6 AGENT 00 (Fable) — GOLDEN SIGNAL ORACLE.
 *
 * <p>Replays the owner's REAL tape ({@code src/test/resources/tape/real_MNQ_1m.json},
 * 7,830 one-minute bars, 2026-09-21 → 2026-09-28 ET, with {@code real_MES_1m.json}
 * as the SMT feed) through the REAL runner path a SIM/LIVE session uses —
 * {@link StdvOteRunnerStrategy} (every detector, gate and window) →
 * {@link StrategySignalEvent} on a started {@link EventBus} →
 * {@link LiveRiskPath} / {@link PropFirmRiskEngine} with the ACTIVE
 * {@link RiskLimits} → {@link ExecutionEngine} candle-driven fills — with the
 * EFFECTIVE configuration (classpath {@code engine-defaults.properties}, no
 * {@code -D} flags; the test JVM pins {@code engine.props=NONE}).
 *
 * <p>Outputs (repo-root {@code parity/}, override with {@code GOLDEN_OUT}):
 * <ul>
 *   <li>{@code java_signals.csv} — one row per emitted signal:
 *       {@code ts_et, side, entry, stop, t1, t2, size_micros, raid_level_type,
 *       raid_score, killzone, bias, gate_proof} (+ diagnostic columns after).</li>
 *   <li>{@code bars_mnq_1m.csv}, {@code bars_mes_1m.csv} — the tape bars, so the
 *       owner can diff TradingView's exported bars against the oracle input.</li>
 * </ul>
 * The export is deterministic: two runs produce byte-identical CSVs (sha256 is
 * printed as {@code [GOLDEN] sha256 ...}). Zero signals = V5 regression → the
 * test FAILS (V6 Agent 00 SUCCESS criterion: ≥ 1 signal).
 *
 * <p>Run: {@code ./gradlew :trading-engine:test --tests '*GoldenSignalExport*'}
 */
class GoldenSignalExport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter TS_ET = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TS_UTC = DateTimeFormatter.ISO_INSTANT;
    private static final String SYMBOL = "MNQ";
    private static final String SMT = "MES";
    private static final double TICK = 0.25;

    static final String SIGNAL_HEADER = "ts_et,side,entry,stop,t1,t2,size_micros,raid_level_type,raid_score,killzone,bias,gate_proof,"
            // diagnostic columns (not part of the parity key)
            + "ts_utc,session,t3,final_target,rr_t1,tier,size_requested,risk_decision,pd_array_kind,pd_array_price,"
            + "entry_model,ote_low,ote_high,range_low,range_high,fill_price,fill_ts_et,exit_price,exit_ts_et,realized_pnl,r_multiple";

    @Test
    void exportGoldenSignals() throws Exception {
        List<Candle> mnq = load("real_MNQ_1m.json", SYMBOL);
        List<Candle> mes = load("real_MES_1m.json", SMT);
        Path out = outDir();
        Files.createDirectories(out);

        writeBars(out.resolve("bars_mnq_1m.csv"), mnq);
        writeBars(out.resolve("bars_mes_1m.csv"), mes);

        List<String> rows = replay(mnq, mes);

        Path csv = out.resolve("java_signals.csv");
        StringBuilder sb = new StringBuilder(SIGNAL_HEADER).append('\n');
        for (String r : rows) sb.append(r).append('\n');
        Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);

        String sha = sha256(csv);
        System.out.println("[GOLDEN] tape " + SYMBOL + " bars=" + mnq.size() + " smt(" + SMT + ") bars=" + mes.size()
                + " first=" + TS_ET.format(mnq.get(0).getTimestamp().atZone(ET)) + " ET last="
                + TS_ET.format(mnq.get(mnq.size() - 1).getTimestamp().atZone(ET)) + " ET");
        System.out.println("[GOLDEN] signals=" + rows.size() + " -> " + csv.toAbsolutePath());
        System.out.println("[GOLDEN] sha256 java_signals.csv = " + sha);
        System.out.println("[GOLDEN] sha256 bars_mnq_1m.csv  = " + sha256(out.resolve("bars_mnq_1m.csv")));
        System.out.println("[GOLDEN] sha256 bars_mes_1m.csv  = " + sha256(out.resolve("bars_mes_1m.csv")));
        for (String r : rows) System.out.println("[GOLDEN] " + r.substring(0, Math.min(r.length(), 160)));

        // V6 Agent 00 SUCCESS: >= 1 signal on the proven tape. 0 = V5 regression (⛔).
        assertThat(rows).as("V6 Agent 00: the proven V5 engine must emit >= 1 signal on the real tape "
                + "(0 = V5 regression; open ⛔ with this run's output)").isNotEmpty();
    }

    /** The REAL runner path, exactly as FunnelAutopsyHarness / SimEngineRunner wire it. */
    static List<String> replay(List<Candle> primary, List<Candle> smtBars) throws Exception {
        EventBus bus = new EventBus();
        AccountState account = new AccountState(50_000.0);
        RiskLimits limits = ScalpConfig.activeRiskLimits();
        PropFirmRiskEngine risk = new PropFirmRiskEngine();
        LiveRiskPath livePath = com.topstep.trading.LiveEngineRunner.newLiveRiskPath();
        ExecutionEngine exec = new ExecutionEngine(account);
        exec.setEventBus(bus);
        List<Object[]> fills = new ArrayList<>();   // {Instant, side, px, qty}
        Instant[] barNow = {null};
        exec.setExecutionListener(new ExecutionEngine.ExecutionListener() {
            @Override public void onPositionOpened(String s, com.topstep.trading.domain.OrderSide side, double px, int q) {
                fills.add(new Object[] {barNow[0], side, px, q});
            }
            @Override public void onPositionClosed(String s, double pnl, boolean win) { livePath.onPositionClosed(pnl); }
        });
        DefaultStrategyContext context = new DefaultStrategyContext(account);
        context.setRiskBudgetProvider(livePath.budgetProviderOrNull());
        ConcurrentLinkedQueue<StrategySignalEvent> signals = new ConcurrentLinkedQueue<>();
        bus.subscribe(StrategySignalEvent.class, signals::add);
        bus.start();
        StdvOteRunnerStrategy runner = new StdvOteRunnerStrategy(SYMBOL, SMT, bus);
        runner.initialize();
        SetupContext ctx = runner.getSetupContext();
        ChartStateQueryAPI chartState = chartStateOf(runner);

        System.out.println("[GOLDEN] riskLimits: DLL=" + limits.getMaxDailyLoss() + " MLL=" + limits.getMaxLossLimit()
                + " maxContracts=" + limits.getMaxContracts() + " maxTotal=" + limits.getMaxTotalContracts()
                + " riskPerTrade=" + limits.getRiskPerTrade() + " rr=[" + limits.getSignalMinRr() + ","
                + limits.getSignalMaxRr() + "] " + livePath.bootLine(limits));

        List<String[]> pending = new ArrayList<>();   // rows awaiting fill/exit data: {rowPrefix, Instant signalTs}
        List<Object[]> openRows = new ArrayList<>();  // {StringBuilder row, Instant signalTs, boolean filled}
        List<String> rows = new ArrayList<>();
        int completedSeen = 0;
        int fillsSeen = 0;
        int si = 0;
        for (int bar = 0; bar < primary.size(); bar++) {
            Candle c = primary.get(bar);
            Instant now = c.getTimestamp();
            barNow[0] = now;
            while (si < smtBars.size() && !smtBars.get(si).getTimestamp().isAfter(now)) {
                runner.onCandle(smtBars.get(si++), context);
            }
            SetupState before = ctx.state;
            context.setCurrentTime(now);
            livePath.onCandle(now, account);
            exec.onNewCandle(c);
            bus.awaitIdle(5_000);
            runner.onCandle(c, context);
            bus.awaitIdle(5_000);
            SetupState after = ctx.state;

            boolean enteredTrade = after == SetupState.IN_TRADE && before != SetupState.IN_TRADE;
            StrategySignalEvent sig = enteredTrade ? awaitSignal(signals) : signals.poll();
            if (sig != null) {
                LiveRiskPath.Decision rd = livePath.evaluate(sig, account, limits, risk);
                RiskDecision d = rd.riskDecision() != null ? rd.riskDecision() : RiskDecision.deny(rd.reason());
                int sizeMicros = 0;
                String riskCol;
                if (d.isAllowed()) {
                    Order o = d.getOrder();
                    exec.submitOrder(o, sig.getStopPrice(), sig.getTargetPrice());
                    sizeMicros = o.getQuantity();
                    riskCol = "ALLOW " + d.getReason();
                } else {
                    riskCol = "DENY " + d.getReason();
                    bus.publish(new PositionClosedEvent(SYMBOL, 0.0, false, now));
                }
                bus.awaitIdle(5_000);
                String session = FunnelAutopsyHarness.session(now);
                String vote = BiasVoteEngine.get(SYMBOL).map(BiasVoteEngine::gatesToken).orElse("vote=?");
                String raidType = raidLevelType(chartState, ctx);
                String proof = String.join("|",
                        "session=" + session,
                        "window=" + ctx.sessionWindow,
                        "primeKZ=" + ctx.primeKillzone,
                        "kzOpen=" + ctx.killzoneOpen,
                        "bias=" + ctx.htfBias,
                        vote.replace(',', ';'),
                        "range=[" + num(ctx.rangeLow) + ";" + num(ctx.rangeHigh) + "] eq=" + num(ctx.rangeEq),
                        "sweep=" + (ctx.sweep == null ? "none" : (ctx.sweep.isBullish() ? "LOW@" : "HIGH@") + ctx.sweep.getSweptLevel()),
                        "sweepExtreme=" + num(ctx.sweepExtreme),
                        "raidScore=" + ctx.raidScore,
                        "smt=" + ctx.smtState,
                        "disp=" + ctx.displacement + (ctx.displacementAt == null ? "" : "@" + TS_ET.format(ctx.displacementAt.atZone(ET))),
                        "fvg=" + (ctx.fvg == null ? "none" : ctx.fvg.getBottom() + "-" + ctx.fvg.getTop()),
                        "m5Link=" + ctx.m5LinkKind,
                        "mss=" + ctx.mss + (ctx.mssAt == null ? "" : "@" + TS_ET.format(ctx.mssAt.atZone(ET))),
                        "ote=" + (ctx.ote == null ? "none" : "[" + ctx.ote.f62() + ";" + ctx.ote.f79() + "] inv=" + ctx.ote.one00()),
                        "oteArmed=" + (ctx.oteArmedAt == null ? "" : TS_ET.format(ctx.oteArmedAt.atZone(ET))),
                        "oteAlarm=" + (ctx.oteAlarmAt == null ? "" : TS_ET.format(ctx.oteAlarmAt.atZone(ET))),
                        "pdArray=" + ctx.pdArrayKind + "@" + num(ctx.pdArrayInOte),
                        "entryModel=" + ctx.oteEntryModel,
                        "impulse=" + (ctx.impulseLegVerdict == null ? "" : ctx.impulseLegVerdict.replace(',', ';')),
                        "anchor=" + ctx.oteAnchorMode + "/" + ctx.oteAnchorSource,
                        "tier=" + (sig.getTier() == null ? "" : sig.getTier().name()),
                        "rrT1=" + fmt(ctx.rrT1),
                        "signalReason=" + (sig.getReason() == null ? "" : sig.getReason().replace(',', ';').replace('|', '/')),
                        "risk=" + riskCol.replace(',', ';'));
                StringBuilder row = new StringBuilder();
                row.append(TS_ET.format(now.atZone(ET))).append(',')
                   .append(sig.getSide()).append(',')
                   .append(px(sig.getEntryPrice())).append(',')
                   .append(px(sig.getStopPrice())).append(',')
                   .append(px(ctx.t1)).append(',')
                   .append(px(ctx.t2)).append(',')
                   .append(sizeMicros).append(',')
                   .append(raidType).append(',')
                   .append(ctx.raidScore).append(',')
                   .append(ctx.sessionWindow == null ? session : ctx.sessionWindow).append(',')
                   .append(ctx.htfBias).append(',')
                   .append(q(proof)).append(',')
                   .append(TS_UTC.format(now)).append(',')
                   .append(session).append(',')
                   .append(px(ctx.t3)).append(',')
                   .append(px(ctx.finalTarget)).append(',')
                   .append(fmt(ctx.rrT1)).append(',')
                   .append(sig.getTier() == null ? "" : sig.getTier().name()).append(',')
                   .append(sig.getQuantity()).append(',')
                   .append(q(riskCol)).append(',')
                   .append(ctx.pdArrayKind == null ? "" : ctx.pdArrayKind).append(',')
                   .append(num(ctx.pdArrayInOte)).append(',')
                   .append(ctx.oteEntryModel == null ? "" : ctx.oteEntryModel).append(',')
                   .append(ctx.ote == null ? "" : px(Math.min(ctx.ote.f62(), ctx.ote.f79()))).append(',')
                   .append(ctx.ote == null ? "" : px(Math.max(ctx.ote.f62(), ctx.ote.f79()))).append(',')
                   .append(num(ctx.rangeLow)).append(',')
                   .append(num(ctx.rangeHigh));
                openRows.add(new Object[] {row, now, d.isAllowed()});
            }

            // fills / exits are appended to the row they belong to (one position per symbol).
            while (fillsSeen < fills.size()) {
                Object[] f = fills.get(fillsSeen++);
                Object[] owner = lastAllowed(openRows, (Instant) f[0], 3);
                if (owner != null) {
                    owner[3] = f;   // fill
                }
            }
            List<Trade> done = exec.getCompletedTrades();
            while (completedSeen < done.size()) {
                Trade t = done.get(completedSeen++);
                Object[] owner = lastAllowed(openRows, t.getEntryTime() == null ? now : t.getEntryTime(), 4);
                if (owner != null) owner[4] = t;
            }
        }
        Thread.sleep(200);
        runner.shutdown();
        bus.stop();

        for (Object[] r : openRows) {
            StringBuilder row = (StringBuilder) r[0];
            Object[] f = r.length > 3 ? (Object[]) r[3] : null;
            Trade t = r.length > 4 ? (Trade) r[4] : null;
            row.append(',').append(f == null ? "" : px((Double) f[2]))
               .append(',').append(f == null ? "" : TS_ET.format(((Instant) f[0]).atZone(ET)))
               .append(',').append(t == null ? "" : px(t.getExitPrice()))
               .append(',').append(t == null || t.getExitTime() == null ? "" : TS_ET.format(t.getExitTime().atZone(ET)))
               .append(',').append(t == null ? "" : fmt(t.getRealizedPnL()))
               .append(',').append(t == null ? "" : fmt(t.getRMultiple()));
            rows.add(row.toString());
        }
        return rows;
    }

    /**
     * The last ALLOWED signal row at/before {@code at} that has no value in slot {@code slot} yet.
     * A signal fills at the earliest on the same bar's next step, so a row at the fill bar is eligible.
     */
    private static Object[] lastAllowed(List<Object[]> rows, Instant at, int slot) {
        Object[] best = null;
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            if (!(Boolean) r[2]) continue;
            if (((Instant) r[1]).isAfter(at)) continue;
            if (r.length <= slot) {
                Object[] grown = new Object[5];
                System.arraycopy(r, 0, grown, 0, r.length);
                rows.set(i, grown);
                r = grown;
            }
            if (r[slot] == null) best = r;
        }
        return best;
    }

    /** Level type of the swept level: the best active raid's target, else the nearest KnownLevel within 2 ticks, else OTE_BAND. */
    private static String raidLevelType(ChartStateQueryAPI cs, SetupContext ctx) {
        if (ctx.sweep == null) return "NONE";
        double swept = ctx.sweep.getSweptLevel();
        if (cs != null) {
            Optional<LiquidityRaid> best = cs.getBestActiveRaid();
            if (best.isPresent() && best.get().getTargetLevel() != null
                    && Math.abs(best.get().getTargetLevel().getPrice() - swept) <= TICK) {
                return best.get().getTargetLevel().getType().name();
            }
            KnownLevel nearest = null;
            for (KnownLevel l : cs.getAllLevels()) {
                if (Math.abs(l.getPrice() - swept) <= 2 * TICK
                        && (nearest == null || Math.abs(l.getPrice() - swept) < Math.abs(nearest.getPrice() - swept))) {
                    nearest = l;
                }
            }
            if (nearest != null) return nearest.getType().name();
        }
        // IMPULSE_LEG model: the sweep is a raid of the dealing range's OTE band (0.705+), not a KnownLevel.
        return "OTE_BAND";
    }

    private static void writeBars(Path file, List<Candle> bars) throws Exception {
        StringBuilder sb = new StringBuilder("ts_et,ts_utc,open,high,low,close,volume\n");
        for (Candle c : bars) {
            sb.append(TS_ET.format(c.getTimestamp().atZone(ET))).append(',')
              .append(TS_UTC.format(c.getTimestamp())).append(',')
              .append(px(c.getOpen())).append(',').append(px(c.getHigh())).append(',')
              .append(px(c.getLow())).append(',').append(px(c.getClose())).append(',')
              .append(c.getVolume()).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Repo-root {@code parity/} (walk up to settings.gradle), or {@code GOLDEN_OUT}. */
    static Path outDir() {
        String env = System.getenv("GOLDEN_OUT");
        if (env != null && !env.isBlank()) return Path.of(env);
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            // the ROOT project: settings.gradle next to the trading-engine module directory
            if (Files.exists(p.resolve("settings.gradle")) && Files.isDirectory(p.resolve("trading-engine"))) {
                return p.resolve("parity");
            }
        }
        return Path.of("build/parity");
    }

    static String sha256(Path file) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static StrategySignalEvent awaitSignal(ConcurrentLinkedQueue<StrategySignalEvent> q) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            StrategySignalEvent s = q.poll();
            if (s != null) return s;
            Thread.sleep(10);
        }
        return null;
    }

    private static ChartStateQueryAPI chartStateOf(StdvOteRunnerStrategy r) {
        try {
            java.lang.reflect.Field f = StdvOteRunnerStrategy.class.getDeclaredField("chartState");
            f.setAccessible(true);
            return (ChartStateQueryAPI) f.get(r);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String px(double d) {
        if (d == 0 || Double.isNaN(d)) return "";
        return String.format(Locale.ROOT, "%.2f", d);
    }

    private static String num(double d) { return Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.2f", d); }

    private static String fmt(double d) { return String.format(Locale.ROOT, "%.2f", d); }

    private static String q(String s) {
        if (s == null) return "";
        return '"' + s.replace('"', '\'').replace('\n', ' ') + '"';
    }

    static List<Candle> load(String res, String symbol) throws Exception {
        try (InputStream in = GoldenSignalExport.class.getResourceAsStream("/tape/" + res)) {
            if (in == null) throw new IllegalStateException("tape resource missing: /tape/" + res);
            JsonNode arr = MAPPER.readTree(in);
            List<Candle> out = new ArrayList<>(arr.size());
            for (JsonNode b : arr) {
                out.add(new Candle(symbol,
                        java.time.OffsetDateTime.parse(b.get("t").asText()).toInstant(),
                        b.get("o").asDouble(), b.get("h").asDouble(),
                        b.get("l").asDouble(), b.get("c").asDouble(),
                        b.get("v").asLong()));
            }
            return out;
        }
    }
}
