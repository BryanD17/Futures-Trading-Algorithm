package com.topstep.trading.strategy.stdvote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.strategy.DefaultStrategyContext;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * V5 AGENT 00 — FUNNEL AUTOPSY over the owner's REAL tape.
 *
 * <p>Drives real 1m bars through the REAL runner path a SIM/LIVE session uses
 * for one symbol: {@link StdvOteRunnerStrategy} (every detector, gate and
 * window exactly as live) → {@link StrategySignalEvent} on a started
 * {@link EventBus} → {@link PropFirmRiskEngine#evaluate} with the ACTIVE
 * {@link RiskLimits} → {@link ExecutionEngine#submitOrder} → candle-driven
 * fills / stops / targets → completed {@link Trade}s. Every candle writes one
 * CSV row with the state machine, the bias vote, the killzone flag, the
 * sweep/raid/displacement/FVG/MSS/OTE facts, the planned geometry, the gate
 * that is HOLDING the machine on that bar, and the post-signal outcome.
 *
 * <p>Configuration is by environment variables (the test JVM inherits them;
 * trading-engine/build.gradle forwards only a fixed -D list and this harness
 * must not edit that file — it is Agent 01's):
 * <pre>
 *   AUTOPSY_DIR        directory holding real_MNQ_1m.json (+ real_MES_1m.json)
 *                      (falls back to -Dfunnel.data.dir)
 *   AUTOPSY_CONFIG     A | B | C   (see {@link #applyConfig}) — default A
 *   AUTOPSY_PROPS      extra "key=value;key=value" system properties
 *   AUTOPSY_OUT        output dir (default build/autopsy/&lt;config&gt;)
 *   AUTOPSY_TRANSCRIPT ET window "yyyy-MM-ddTHH:mm,yyyy-MM-ddTHH:mm" whose
 *                      bars are printed bar-by-bar (golden-case transcript)
 * </pre>
 * Skipped entirely when no tape directory is configured. This is a
 * MEASUREMENT tool — it always passes; the numbers are the product.
 */
class FunnelAutopsyHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ── Session windows (ET) per TRADE_FLOW_UNBLOCK_MASTER_PROMPT_V5 Agent 02 ──
    static String session(Instant at) {
        ZonedDateTime z = at.atZone(ET);
        DayOfWeek d = z.getDayOfWeek();
        LocalTime t = z.toLocalTime();
        if (d == DayOfWeek.SATURDAY) return "WEEKEND";
        if (d == DayOfWeek.SUNDAY && t.isBefore(LocalTime.of(18, 0))) return "WEEKEND";
        if (d == DayOfWeek.FRIDAY && !t.isBefore(LocalTime.of(17, 0))) return "WEEKEND";
        if (!t.isBefore(LocalTime.of(19, 0)) || t.isBefore(LocalTime.of(2, 0))) return "ASIA";
        if (t.isBefore(LocalTime.of(8, 0))) return "LONDON";
        if (t.isBefore(LocalTime.of(9, 30))) return "PRE_NY";
        if (t.isBefore(LocalTime.of(12, 0))) return "NY_AM";
        if (t.isBefore(LocalTime.of(13, 30))) return "NY_LUNCH";
        if (t.isBefore(LocalTime.of(15, 45))) return "NY_PM";
        if (t.isBefore(LocalTime.of(18, 0))) return "NO_ENTRY";
        return "PRE_ASIA";
    }

    private static final List<String> SESSIONS = List.of("ASIA", "LONDON", "PRE_NY", "NY_AM",
            "NY_LUNCH", "NY_PM", "PRE_ASIA", "NO_ENTRY", "WEEKEND");

    /** The three configurations Agent 00 must compare. */
    static String applyConfig(String cfg) {
        switch (cfg == null ? "A" : cfg.trim().toUpperCase()) {
            case "A":
                // bootRun-equivalent: no -D flags at all (owner's reality).
                return "C-A bootRun-equivalent (no -D flags: legacy target model, NY killzones only)";
            case "B":
                // The FunnelReplayHarness / PR #139-#150 diagnosis flags.
                System.setProperty("scalpMode.enabled", "true");
                System.setProperty("scalp.minRaidScore", "5");
                System.setProperty("bias.hysteresis.enabled", "true");
                return "C-B harness-equivalent (scalpMode.enabled=true scalp.minRaidScore=5 bias.hysteresis.enabled=true)";
            case "C":
                System.setProperty("scalpMode.enabled", "true");
                System.setProperty("scalp.allSessions", "true");
                System.setProperty("bias.hysteresis.enabled", "true");
                System.setProperty("bias.vote.mode", "VOTE");
                return "C-C intended all-sessions (scalpMode.enabled=true scalp.allSessions=true bias.hysteresis.enabled=true bias.vote.mode=VOTE)";
            default:
                return "custom (" + cfg + ")";
        }
    }

    @Test
    void autopsy() throws Exception {
        String dirRaw = System.getenv("AUTOPSY_DIR");
        if (dirRaw == null || dirRaw.isBlank()) dirRaw = System.getProperty("funnel.data.dir");
        if (dirRaw == null || dirRaw.isBlank()) {
            System.out.println("[AUTOPSY] skipped — set AUTOPSY_DIR (or -Dfunnel.data.dir)");
            return;
        }
        String cfgName = System.getenv().getOrDefault("AUTOPSY_CONFIG", "A");
        String cfgDesc = applyConfig(cfgName);
        String extra = System.getenv("AUTOPSY_PROPS");
        if (extra != null && !extra.isBlank()) {
            for (String kv : extra.split(";")) {
                int i = kv.indexOf('=');
                if (i > 0) System.setProperty(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
            }
        }
        Path dir = Path.of(dirRaw);
        String symbol = System.getProperty("funnel.symbol", "MNQ");
        String smt = "MNQ".equals(symbol) ? "MES" : null;
        List<Candle> primary = load(dir.resolve("real_" + symbol + "_1m.json"), symbol);
        Path smtFile = smt == null ? null : dir.resolve("real_" + smt + "_1m.json");
        List<Candle> smtBars = (smtFile != null && Files.exists(smtFile)) ? load(smtFile, smt) : List.of();
        String outRaw = System.getenv("AUTOPSY_OUT");
        Path out = Path.of(outRaw != null && !outRaw.isBlank() ? outRaw : "build/autopsy/" + cfgName.toUpperCase());
        Files.createDirectories(out);
        Instant[] transcript = parseWindow(System.getenv("AUTOPSY_TRANSCRIPT"));

        System.out.println("[AUTOPSY] config=" + cfgDesc);
        System.out.println("[AUTOPSY] tape " + symbol + " bars=" + primary.size()
                + " smt(" + smt + ") bars=" + smtBars.size() + " out=" + out.toAbsolutePath());

        // ── The real post-signal chain, exactly as SimEngineRunner wires it ──
        EventBus bus = new EventBus();
        AccountState account = new AccountState(50_000.0);
        RiskLimits limits = ScalpConfig.activeRiskLimits();
        PropFirmRiskEngine risk = new PropFirmRiskEngine();
        ExecutionEngine exec = new ExecutionEngine(account);
        exec.setEventBus(bus);
        // V5 Agent 05.3: count fills at the fill itself (a fill + stop inside
        // one bar used to show fills=0 because the position was sampled at bar close).
        int[] fillEvents = {0};
        List<String> barNotes = new ArrayList<>();   // fills this bar (transcript)
        exec.setExecutionListener(new ExecutionEngine.ExecutionListener() {
            @Override public void onPositionOpened(String s, com.topstep.trading.domain.OrderSide side, double px, int q) {
                fillEvents[0]++;
                barNotes.add("FILL " + side + " " + q + " @ " + px);
            }
            @Override public void onPositionClosed(String s, double pnl, boolean win) { }
        });
        DefaultStrategyContext context = new DefaultStrategyContext(account);
        ConcurrentLinkedQueue<StrategySignalEvent> signals = new ConcurrentLinkedQueue<>();
        bus.subscribe(StrategySignalEvent.class, signals::add);
        // V5 Agent 05.3: order-lifecycle decisions (setup-end cancels, TTL, flatten) into the log.
        ConcurrentLinkedQueue<com.topstep.trading.event.GateDecisionEvent> orderGates = new ConcurrentLinkedQueue<>();
        bus.subscribe(com.topstep.trading.event.GateDecisionEvent.class, g -> {
            // V5 Agent 05.4: plus the armed-but-not-emitted reasons (one per reason per episode).
            if (java.util.Set.of("ORDER", "ORDER_TTL", "FLATTEN", "SIZE", "ALARM", "NO_ENTRY",
                    "POSITION", "TIER", "EMIT", "BIAS").contains(g.getGate())) {
                orderGates.add(g);
            }
        });
        bus.start();
        StdvOteRunnerStrategy runner = new StdvOteRunnerStrategy(symbol, smt, bus);
        runner.initialize();
        SetupContext ctx = runner.getSetupContext();
        FunnelTelemetry funnel = FunnelTelemetry.forSymbol(symbol);

        System.out.println("[AUTOPSY] riskLimits: DLL=" + limits.getMaxDailyLoss()
                + " MLL=" + limits.getMaxLossLimit() + " maxContracts=" + limits.getMaxContracts()
                + " maxTotal=" + limits.getMaxTotalContracts() + " riskPerTrade=" + limits.getRiskPerTrade()
                + " riskEngineRR=[" + limits.getMinRiskRewardRatio() + "," + limits.getMaxRiskRewardRatio()
                + "] validatorRR=[" + limits.getSignalMinRr() + "," + limits.getSignalMaxRr() + "]");

        // ── Aggregates ──
        Map<String, Map<String, Long>> holdingBySession = new TreeMap<>();   // session → gate → bars
        Map<String, Map<String, Long>> deathsBySession = new TreeMap<>();    // session → invalidation reason → n
        Map<String, Map<String, Long>> stallsBySession = new TreeMap<>();    // session → stall reason → n
        Map<String, Map<String, Long>> deepestBySession = new TreeMap<>();   // session → deepest state at death
        Map<String, long[]> counts = new TreeMap<>(); // bars, kzOpen, biasNonNeutral, episodes, signals, denied, orders, fills, closed, wins, losses
        for (String s : SESSIONS) {
            holdingBySession.put(s, new TreeMap<>());
            deathsBySession.put(s, new TreeMap<>());
            stallsBySession.put(s, new TreeMap<>());
            deepestBySession.put(s, new TreeMap<>());
            counts.put(s, new long[11]);
        }
        Map<String, Long> riskDenials = new TreeMap<>();
        Map<String, Long> stateArrivals = new TreeMap<>();
        List<String> signalLog = new ArrayList<>();
        int firstNonNeutralBar = -1;
        String firstNonNeutralAt = null;
        int firstVoteDecisiveBar = -1;
        Map<String, Long> stallsBefore = funnel.stalls();
        SetupState prev = SetupState.IDLE;
        int deepest = 0;
        int episodes = 0;
        int completedSeen = 0;
        int fillsSeen = 0;
        boolean hadOpenPosition = false;
        String[] lastImpulseVerdict = {null};   // V5 Agent 05.2: print the verdict only when it changes

        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("candles.csv")));
             PrintWriter tr = new PrintWriter(Files.newBufferedWriter(out.resolve("transcript.txt")))) {
            csv.println("bar,ts_ET,session,state_before,state_after,holding_gate,bias,vote,kzOpen,sweep,raidScore,"
                    + "disp,fvg,mss,ote62,ote79,ote100,pdArrayInOte,entry,stop,rr,sizeReq,lastGateFailed,"
                    + "stall,death,signal,risk,orders,positions,closedTrades,close,"
                    + "entryModel,impulseLeg,impDispRangeAtr,impDispBody,impMssSwing,impMssClose,impulseVerdict");
            int si = 0;
            for (int bar = 0; bar < primary.size(); bar++) {
                Candle c = primary.get(bar);
                Instant now = c.getTimestamp();
                while (si < smtBars.size() && !smtBars.get(si).getTimestamp().isAfter(now)) {
                    runner.onCandle(smtBars.get(si++), context);
                }
                String sess = session(now);
                barNotes.clear();
                int logMark = signalLog.size();
                SetupState before = ctx.state;
                context.setCurrentTime(now);
                exec.onNewCandle(c);          // fills / stops / targets on THIS bar (SimEngineRunner order)
                // V5 Agent 05.3 (determinism): every handler of the events this
                // step published (PositionClosedEvent -> strategy latch, ...) has
                // run before the next step — as it has live, one minute later.
                bus.awaitIdle(5_000);
                runner.onCandle(c, context);
                bus.awaitIdle(5_000);         // SetupCancelledEvent -> ExecutionEngine cancel
                SetupState after = ctx.state;

                // ── post-signal chain (the SIM handler), synchronous here ──
                String signalCol = "";
                String riskCol = "";
                // V5 Agent 05.7 (measurement): a signal published on a bar
                // whose state reads IN_TRADE -> IN_TRADE (a re-arm after a
                // close that emits in the same step) was never drained, so it
                // sat on the queue and was submitted at the NEXT transition
                // with stale prices. Every signal on the bus is handled on the
                // bar it is published, as the SIM/LIVE handler does.
                boolean enteredTrade = after == SetupState.IN_TRADE && before != SetupState.IN_TRADE;
                StrategySignalEvent sig = enteredTrade ? awaitSignal(signals) : signals.poll();
                {
                    if (sig == null) {
                        if (enteredTrade) signalCol = "IN_TRADE-but-no-signal-on-bus";
                    } else {
                        counts.get(sess)[4]++;
                        signalCol = sig.getSignalType() + " e=" + sig.getEntryPrice() + " s=" + sig.getStopPrice()
                                + " t=" + sig.getTargetPrice() + " rr=" + fmt(sig.getActualRR()) + " q=" + sig.getQuantity();
                        RiskDecision d = risk.evaluate(sig, account, limits);
                        if (d.isAllowed()) {
                            Order o = d.getOrder();
                            exec.submitOrder(o, sig.getStopPrice(), sig.getTargetPrice());
                            counts.get(sess)[6]++;
                            riskCol = "ALLOW qty=" + o.getQuantity() + " " + d.getReason();
                        } else {
                            counts.get(sess)[5]++;
                            riskCol = "DENY " + d.getReason();
                            riskDenials.merge(d.getReason().replaceAll("[0-9.]+", "#"), 1L, Long::sum);
                            bus.publish(new PositionClosedEvent(symbol, 0.0, false, now)); // SIM release
                        }
                        signalLog.add(TS.format(now.atZone(ET)) + " ET " + sess + " | " + signalCol + " | " + riskCol);
                        bus.awaitIdle(5_000);
                    }
                }
                for (com.topstep.trading.event.GateDecisionEvent g; (g = orderGates.poll()) != null; ) {
                    if (!symbol.equals(g.getSymbol())) continue;
                    signalLog.add(TS.format(now.atZone(ET)) + " ET " + sess + " | " + g.getGate() + " " + g.getReason());
                }
                boolean openNow = account.hasPosition(symbol);
                while (fillsSeen < fillEvents[0]) { counts.get(sess)[7]++; fillsSeen++; }
                hadOpenPosition = openNow;
                List<Trade> done = exec.getCompletedTrades();
                while (completedSeen < done.size()) {
                    Trade t = done.get(completedSeen++);
                    counts.get(sess)[8]++;
                    if (t.isWinner()) counts.get(sess)[9]++; else counts.get(sess)[10]++;
                    signalLog.add(TS.format(now.atZone(ET)) + " ET " + sess + " | CLOSED " + t.getSide()
                            + " q=" + t.getQuantity() + " in=" + t.getEntryPrice() + " out=" + fmt(t.getExitPrice())
                            + " pnl=" + fmt(t.getRealizedPnL()) + " R=" + fmt(t.getRMultiple()) + " " + t.getNotes());
                }

                // ── per-bar facts ──
                String vote = BiasVoteEngine.get(symbol).map(BiasVoteEngine::gatesToken).orElse("vote=?");
                if (firstNonNeutralBar < 0 && ctx.htfBias != com.topstep.trading.strategy.MarketBias.NEUTRAL) {
                    firstNonNeutralBar = bar;
                    firstNonNeutralAt = TS.format(now.atZone(ET));
                }
                if (firstVoteDecisiveBar < 0 && !vote.contains("NEUTRAL") && !vote.contains("?")) {
                    firstVoteDecisiveBar = bar;
                }
                Map<String, Long> stallsNow = funnel.stalls();
                String stall = diffKey(stallsBefore, stallsNow);
                stallsBefore = stallsNow;
                String death = "";
                if (after == SetupState.INVALIDATED && before != SetupState.INVALIDATED) {
                    death = normalise(ctx.lastGateFailed);
                    deathsBySession.get(sess).merge(death, 1L, Long::sum);
                    deepestBySession.get(sess).merge(SetupState.values()[deepest].name(), 1L, Long::sum);
                }
                if (after != before) {
                    stateArrivals.merge(after.name(), 1L, Long::sum);
                    if (after == SetupState.BIAS_SET) { episodes++; deepest = after.ordinal(); }
                    if (after != SetupState.INVALIDATED && after != SetupState.IDLE) deepest = Math.max(deepest, after.ordinal());
                }
                String holding = holdingGate(after, ctx, stall);
                holdingBySession.get(sess).merge(holding, 1L, Long::sum);
                if (!stall.isEmpty()) stallsBySession.get(sess).merge(stall, 1L, Long::sum);
                long[] k = counts.get(sess);
                k[0]++;
                if (ctx.killzoneOpen) k[1]++;
                if (ctx.htfBias != com.topstep.trading.strategy.MarketBias.NEUTRAL) k[2]++;

                String row = bar + "," + TS.format(now.atZone(ET)) + "," + sess + "," + before + "," + after + ","
                        + holding + "," + ctx.htfBias + "," + q(vote) + "," + ctx.killzoneOpen + ","
                        + (ctx.sweep == null ? "" : (ctx.sweep.isBullish() ? "LOW@" : "HIGH@") + ctx.sweep.getSweptLevel()) + ","
                        + ctx.raidScore + "," + ctx.displacement + ","
                        + (ctx.fvg == null ? "" : ctx.fvg.getBottom() + "-" + ctx.fvg.getTop()) + "," + ctx.mss + ","
                        + (ctx.ote == null ? ",," : ctx.ote.f62() + "," + ctx.ote.f79() + "," + ctx.ote.one00()) + ","
                        + (Double.isNaN(ctx.pdArrayInOte) ? "" : ctx.pdArrayInOte) + ","
                        + (ctx.entry == 0 ? "" : ctx.entry) + "," + (ctx.stop == 0 ? "" : ctx.stop) + ","
                        + (ctx.rr == 0 ? "" : fmt(ctx.rr)) + "," + ctx.sizeRequest + "," + q(ctx.lastGateFailed) + ","
                        + q(stall) + "," + q(death) + "," + q(signalCol) + "," + q(riskCol) + ","
                        + exec.getActiveOrdersList(symbol).size() + "," + account.getPositions().size() + ","
                        + done.size() + "," + c.getClose() + ","
                        // V5 Agent 05.2: entry model + the impulse-leg proof numbers.
                        + (ctx.oteEntryModel == null ? "" : ctx.oteEntryModel) + ","
                        + (ctx.impulseLegStart == null ? "" : TS.format(ctx.impulseLegStart.atZone(ET))
                                + ".." + TS.format(ctx.impulseLegEnd.atZone(ET))) + ","
                        + (Double.isNaN(ctx.impulseDispRangeAtr) ? "" : fmt(ctx.impulseDispRangeAtr)) + ","
                        + (Double.isNaN(ctx.impulseDispBody) ? "" : fmt(ctx.impulseDispBody)) + ","
                        + (Double.isNaN(ctx.impulseMssSwing) ? "" : ctx.impulseMssSwing) + ","
                        + (Double.isNaN(ctx.impulseMssClose) ? "" : ctx.impulseMssClose) + ","
                        + q(ctx.impulseLegVerdict);
                csv.println(row);

                if (transcript != null && !now.isBefore(transcript[0]) && now.isBefore(transcript[1])) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(TS.format(now.atZone(ET))).append(" ET  bar=").append(bar)
                      .append(" o=").append(c.getOpen()).append(" h=").append(c.getHigh())
                      .append(" l=").append(c.getLow()).append(" c=").append(c.getClose())
                      .append("\n   state ").append(before).append(" -> ").append(after)
                      .append("  HOLDING=").append(holding)
                      .append("  bias=").append(ctx.htfBias).append(" ").append(vote)
                      .append("  kzOpen=").append(ctx.killzoneOpen)
                      .append("\n   sweep=").append(ctx.sweep == null ? "none" : (ctx.sweep.isBullish() ? "LOW@" : "HIGH@") + ctx.sweep.getSweptLevel())
                      .append(" raidScore=").append(ctx.raidScore)
                      .append(" disp=").append(ctx.displacement)
                      .append(" fvg=").append(ctx.fvg == null ? "none" : ctx.fvg.getBottom() + "-" + ctx.fvg.getTop())
                      .append(" mss=").append(ctx.mss)
                      .append(" ote=").append(ctx.ote == null ? "none" : "[" + ctx.ote.f62() + "," + ctx.ote.f79() + "] inv=" + ctx.ote.one00())
                      .append(" pd=").append(Double.isNaN(ctx.pdArrayInOte) ? "none" : ctx.pdArrayInOte)
                      .append("\n   entry=").append(ctx.entry).append(" stop=").append(ctx.stop).append(" rr=").append(fmt(ctx.rr))
                      .append(" size=").append(ctx.sizeRequest)
                      .append(" lastGateFailed=").append(ctx.lastGateFailed)
                      .append(stall.isEmpty() ? "" : "  stall=" + stall)
                      .append(death.isEmpty() ? "" : "  DEATH=" + death)
                      .append(java.util.Objects.equals(ctx.impulseLegVerdict, lastImpulseVerdict[0]) ? ""
                              : "\n   entryModel=" + ctx.oteEntryModel + " impulse=" + ctx.impulseLegVerdict)
                      .append(signalCol.isEmpty() ? "" : "\n   SIGNAL " + signalCol + "\n   RISK " + riskCol);
                    // V5 Agent 05.3: fills, completed trades, order cancels, re-arms on this bar.
                    for (String n : barNotes) sb.append("\n   ").append(n);
                    for (int li = logMark; li < signalLog.size(); li++) {
                        String l = signalLog.get(li);
                        int cut = l.indexOf(" | ");
                        String tail = cut < 0 ? l : l.substring(cut + 3);
                        if (!tail.equals(signalCol + " | " + riskCol)) sb.append("\n   ").append(tail);
                    }
                    if (before == SetupState.IN_TRADE && after != SetupState.IN_TRADE && after != SetupState.INVALIDATED) {
                        sb.append("\n   RE-ARMED after the position closed (IN_TRADE -> ").append(after).append(")");
                    }
                    lastImpulseVerdict[0] = ctx.impulseLegVerdict;
                    com.topstep.trading.chartstate.ChartStateQueryAPI cs = chartStateOf(runner);
                    java.util.Optional<LiquidityRaid> bestRaid = cs == null ? java.util.Optional.empty() : cs.getBestActiveRaid();
                    bestRaid.ifPresent(r -> sb.append("\n   bestRaid=").append(r));
                    if (cs != null && now.atZone(ET).getMinute() % 15 == 0) {
                        sb.append("\n   levels=");
                        for (KnownLevel l : cs.getAllLevels()) {
                            sb.append(l.getType()).append('@').append(l.getPrice()).append(l.isRaided() ? "(raided) " : " ");
                        }
                    }
                    tr.println(sb);
                }
                prev = after;
            }
        }
        Thread.sleep(300);

        // ── Summary ──
        try (PrintWriter md = new PrintWriter(Files.newBufferedWriter(out.resolve("summary.md")))) {
            md.println("# FUNNEL AUTOPSY — " + cfgDesc);
            md.println();
            md.println("tape=" + dir.toAbsolutePath() + " symbol=" + symbol + " bars=" + primary.size()
                    + " smtBars=" + smtBars.size() + " first=" + TS.format(primary.get(0).getTimestamp().atZone(ET))
                    + " ET last=" + TS.format(primary.get(primary.size() - 1).getTimestamp().atZone(ET)) + " ET");
            md.println();
            md.println("riskLimits: DLL=" + limits.getMaxDailyLoss() + " MLL=" + limits.getMaxLossLimit()
                    + " maxContracts=" + limits.getMaxContracts() + " maxTotal=" + limits.getMaxTotalContracts()
                    + " riskPerTrade=" + limits.getRiskPerTrade() + " riskEngineRR=[" + limits.getMinRiskRewardRatio()
                    + "," + limits.getMaxRiskRewardRatio() + "] validatorRR=[" + limits.getSignalMinRr() + ","
                    + limits.getSignalMaxRr() + "]");
            md.println();
            md.println("cold start: first non-NEUTRAL bias at bar " + firstNonNeutralBar + " (" + firstNonNeutralAt
                    + " ET); first decisive 3-of-4 vote at bar " + firstVoteDecisiveBar);
            md.println("episodes (BIAS_SET arrivals)=" + episodes + " stateArrivals=" + stateArrivals);
            md.println();
            md.println("## Per-session counts");
            md.println();
            md.println("| session | bars | kzOpen | biasNonNeutral | signals | riskDenied | ordersSent | fills | closed | wins | losses |");
            md.println("|---|---|---|---|---|---|---|---|---|---|---|");
            for (String s : SESSIONS) {
                long[] k = counts.get(s);
                md.println("| " + s + " | " + k[0] + " | " + k[1] + " | " + k[2] + " | " + k[4] + " | " + k[5]
                        + " | " + k[6] + " | " + k[7] + " | " + k[8] + " | " + k[9] + " | " + k[10] + " |");
            }
            md.println();
            md.println("## Gate HOLDING the machine, bars per session (each row sums to that session's bars)");
            md.println();
            java.util.TreeSet<String> gates = new java.util.TreeSet<>();
            holdingBySession.values().forEach(m -> gates.addAll(m.keySet()));
            md.print("| session |");
            for (String g : gates) md.print(" " + g + " |");
            md.println(" total |");
            md.print("|---|");
            for (String g : gates) md.print("---|");
            md.println("---|");
            for (String s : SESSIONS) {
                Map<String, Long> m = holdingBySession.get(s);
                long tot = m.values().stream().mapToLong(Long::longValue).sum();
                md.print("| " + s + " |");
                for (String g : gates) md.print(" " + m.getOrDefault(g, 0L) + " |");
                md.println(" " + tot + " |");
            }
            md.println();
            md.println("## Top-3 holding gates per session");
            md.println();
            for (String s : SESSIONS) {
                Map<String, Long> m = holdingBySession.get(s);
                long tot = m.values().stream().mapToLong(Long::longValue).sum();
                if (tot == 0) continue;
                List<Map.Entry<String, Long>> ranked = new ArrayList<>(m.entrySet());
                ranked.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                StringBuilder sb = new StringBuilder("- " + s + " (" + tot + " bars): ");
                for (int i = 0; i < Math.min(3, ranked.size()); i++) {
                    if (i > 0) sb.append("; ");
                    sb.append(ranked.get(i).getKey()).append(' ').append(ranked.get(i).getValue())
                      .append(" (").append(String.format("%.1f%%", 100.0 * ranked.get(i).getValue() / tot)).append(')');
                }
                md.println(sb);
            }
            md.println();
            md.println("## Setup deaths (INVALIDATED transitions) by reason per session");
            md.println();
            for (String s : SESSIONS) {
                if (!deathsBySession.get(s).isEmpty()) md.println("- " + s + ": " + deathsBySession.get(s));
            }
            md.println();
            md.println("## Deepest state reached at death per session");
            md.println();
            for (String s : SESSIONS) {
                if (!deepestBySession.get(s).isEmpty()) md.println("- " + s + ": " + deepestBySession.get(s));
            }
            md.println();
            md.println("## Stall reasons (FunnelTelemetry) per session");
            md.println();
            for (String s : SESSIONS) {
                if (!stallsBySession.get(s).isEmpty()) md.println("- " + s + ": " + stallsBySession.get(s));
            }
            md.println();
            md.println("## Risk-engine denials: " + riskDenials);
            md.println();
            md.println("## Signal / order / trade log");
            md.println();
            for (String l : signalLog) md.println("- " + l);
            md.println();
            md.println("## FunnelTelemetry (current session): " + funnel.logLine());
        }
        System.out.println(Files.readString(out.resolve("summary.md")));
        runner.shutdown();
        bus.stop();
    }

    /** Which gate is holding the machine on this bar (one label per candle). */
    static String holdingGate(SetupState s, SetupContext ctx, String stall) {
        switch (s) {
            case IDLE:          return "M2-bias-NEUTRAL";
            case BIAS_SET:      return "MANIP-no-leg";
            case MANIP_DONE:    return "M4-no-sweep";
            case SWEEP_DONE:    return "M5-" + (stall.isEmpty() ? "waiting" : stall.replace("SWEEP_DONE:", ""));
            case DISPLACED:     return "M6-no-MSS";
            case MSS_CONFIRMED: return "M7-" + (stall.isEmpty() ? "waiting" : stall.replace("MSS_CONFIRMED:", ""));
            case OTE_ARMED: {
                String g = ctx.lastGateFailed;
                // V5 Agent 05.4: must never happen any more (every armed refusal writes a reason).
                if (g == null) return "OTE_ARMED-sizer-standdown-or-tier";
                int colon = g.indexOf(':');
                return "GATE-" + (colon > 0 ? g.substring(0, colon) : g).trim();
            }
            case IN_TRADE:      return "IN_TRADE";
            case INVALIDATED:   return "INVALIDATED-await-rearm" + (ctx.killzoneOpen ? "" : "-kzClosed");
            default:            return s.name();
        }
    }

    private static String normalise(String reason) {
        if (reason == null || reason.isBlank()) return "unknown";
        if (reason.startsWith("expired (")) return "expired";
        if (reason.startsWith("HTF bias flip")) return "HTF bias flip";
        return reason.replaceAll("[0-9.]+", "#");
    }

    private static String diffKey(Map<String, Long> before, Map<String, Long> now) {
        for (Map.Entry<String, Long> e : now.entrySet()) {
            if (!e.getValue().equals(before.getOrDefault(e.getKey(), 0L))) return e.getKey();
        }
        return "";
    }

    private static StrategySignalEvent awaitSignal(ConcurrentLinkedQueue<StrategySignalEvent> q) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            StrategySignalEvent s = q.poll();
            if (s != null) return s;
            Thread.sleep(10);
        }
        return null;
    }

    private static Instant[] parseWindow(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] p = raw.split(",");
        if (p.length != 2) return null;
        return new Instant[] {
                LocalDateTime.parse(p[0].trim()).atZone(ET).toInstant(),
                LocalDateTime.parse(p[1].trim()).atZone(ET).toInstant() };
    }

    /** Read the runner's private chart-state adapter (test-only reflection; no production API added). */
    private static com.topstep.trading.chartstate.ChartStateQueryAPI chartStateOf(StdvOteRunnerStrategy r) {
        try {
            java.lang.reflect.Field f = StdvOteRunnerStrategy.class.getDeclaredField("chartState");
            f.setAccessible(true);
            return (com.topstep.trading.chartstate.ChartStateQueryAPI) f.get(r);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String fmt(double d) { return String.format("%.2f", d); }

    private static String q(String s) {
        if (s == null) return "";
        return '"' + s.replace('"', '\'').replace('\n', ' ') + '"';
    }

    private static List<Candle> load(Path file, String symbol) throws Exception {
        JsonNode arr = MAPPER.readTree(Files.readString(file));
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
