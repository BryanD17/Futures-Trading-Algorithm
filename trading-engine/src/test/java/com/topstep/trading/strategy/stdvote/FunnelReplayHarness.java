package com.topstep.trading.strategy.stdvote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * OFFLINE FUNNEL REPLAY over REAL exchange tape (quant diagnosis harness).
 *
 * <p>Feeds real 1m bars (fetched from the TopstepX history API into JSON
 * files) through a REAL {@link StdvOteRunnerStrategy} — every detector,
 * gate, and window exactly as live — and prints the signal funnel: how far
 * each setup episode got, what killed it, and how many orders would have
 * been emitted. Run with:
 *
 * <pre>
 * ./gradlew :trading-engine:test --tests "*FunnelReplayHarness" \
 *     -Dfunnel.data.dir=/path/with/real_MNQ_1m.json \
 *     [-DscalpMode.enabled=true -Dscalp.minRaidScore=5 …]
 * </pre>
 *
 * <p>Skipped entirely when {@code funnel.data.dir} is not set (CI has no
 * tape). This is a MEASUREMENT tool, not an assertion suite — it always
 * passes; the numbers are the product.
 */
class FunnelReplayHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @EnabledIfSystemProperty(named = "funnel.data.dir", matches = ".+")
    void funnelReplay() throws Exception {
        Path dir = Path.of(System.getProperty("funnel.data.dir"));
        String symbol = System.getProperty("funnel.symbol", "MNQ");
        String smt = "MNQ".equals(symbol) ? "MES" : null;
        List<Candle> mnq = load(dir.resolve("real_" + symbol + "_1m.json"), symbol);
        List<Candle> mes = (smt != null)
                ? load(dir.resolve("real_" + smt + "_1m.json"), smt)
                : List.of();
        System.out.println("[REPLAY] " + symbol + " bars=" + mnq.size()
                + " smt(" + smt + ") bars=" + mes.size());

        EventBus bus = new EventBus();
        List<StrategySignalEvent> emissions = new CopyOnWriteArrayList<>();
        bus.subscribe(StrategySignalEvent.class, emissions::add);

        StdvOteRunnerStrategy runner = new StdvOteRunnerStrategy(symbol, smt, bus);
        SetupContext ctx = runner.getSetupContext();

        // Funnel accounting: per setup episode, the deepest state reached
        // and (on invalidation) the reason.
        Map<String, Integer> deepestByEpisode = new LinkedHashMap<>();
        Map<String, Integer> reasons = new LinkedHashMap<>();
        int episodes = 0;
        SetupState prev = SetupState.IDLE;
        int deepest = 0;

        // Simulated trade outcomes: limit fill at entry, then first-touch
        // stop vs target (same-candle both-touch counted as a LOSS —
        // pessimistic). Target reconstructed from the scalp geometry:
        // entry ± rr * risk in the trade direction.
        class SimTrade {
            final boolean isLong; final double entry; final double stop; final double target;
            final Instant at; boolean filled; Integer resultR; // +1 win, -1 loss
            SimTrade(boolean isLong, double entry, double stop, double rr, Instant at) {
                this.isLong = isLong; this.entry = entry; this.stop = stop; this.at = at;
                double risk = Math.abs(entry - stop);
                this.target = isLong ? entry + rr * risk : entry - rr * risk;
            }
        }
        List<SimTrade> trades = new ArrayList<>();

        // Merge-replay by timestamp, MES (SMT) before MNQ per minute.
        int mi = 0;
        for (Candle c : mnq) {
            while (mi < mes.size()
                    && !mes.get(mi).getTimestamp().isAfter(c.getTimestamp())) {
                runner.onCandle(mes.get(mi++), null);
            }
            runner.onCandle(c, null);

            // Advance open simulated trades on this candle.
            for (SimTrade t : trades) {
                if (t.resultR != null) continue;
                if (!t.filled) {
                    t.filled = t.isLong ? c.getLow() <= t.entry : c.getHigh() >= t.entry;
                    if (!t.filled) continue;
                }
                boolean stopHit = t.isLong ? c.getLow() <= t.stop : c.getHigh() >= t.stop;
                boolean targetHit = t.isLong ? c.getHigh() >= t.target : c.getLow() <= t.target;
                if (stopHit) t.resultR = -1;            // pessimistic on both-touch
                else if (targetHit) t.resultR = 1;
            }

            SetupState s = ctx.state;
            if (s != prev) {
                if (s == SetupState.BIAS_SET && (prev == SetupState.IDLE
                        || prev == SetupState.INVALIDATED
                        || prev == SetupState.IN_TRADE)) {
                    episodes++;
                    deepest = s.ordinal();
                }
                if (s != SetupState.INVALIDATED) {
                    deepest = Math.max(deepest, s.ordinal());
                }
                if (s == SetupState.INVALIDATED) {
                    String reason = String.valueOf(ctx.lastGateFailed);
                    // Normalize numeric noise out of reasons.
                    reason = reason.replaceAll("[0-9.]+", "#");
                    reasons.merge(reason, 1, Integer::sum);
                    String depth = stateName(deepest);
                    // Sub-cause at the OTE-arm wall: what was the machine
                    // actually waiting for when it died in MSS_CONFIRMED?
                    if (deepest == SetupState.MSS_CONFIRMED.ordinal()) {
                        if (ctx.ote == null) {
                            depth += "/no-valid-leg";
                        } else if (Double.isNaN(ctx.pdArrayInOte)) {
                            depth += "/fvg-edge-not-in-zone";
                        } else {
                            depth += "/waiting-on-reaction";
                        }
                    }
                    deepestByEpisode.merge(depth, 1, Integer::sum);
                } else if (s == SetupState.IN_TRADE) {
                    deepestByEpisode.merge("IN_TRADE(EMITTED)", 1, Integer::sum);
                    // Authoritative emission record (the bus is async): the
                    // machine only enters IN_TRADE after a published signal.
                    System.out.println("[REPLAY] EMITTED " + c.getTimestamp()
                            + " dir=" + (ctx.legBullish ? "LONG" : "SHORT")
                            + " entry=" + ctx.entry + " stop=" + ctx.stop
                            + " rr=" + String.format("%.2f", ctx.rr)
                            + " size=" + ctx.sizeFilled + " tier=" + ctx.tier);
                    trades.add(new SimTrade(ctx.legBullish, ctx.entry, ctx.stop,
                            ctx.rr, c.getTimestamp()));
                    // Simulate the position lifecycle the live stack provides:
                    // a close event lets the scalp re-arm engine hunt the
                    // next setup instead of parking in IN_TRADE forever.
                    bus.publish(new com.topstep.trading.event.PositionClosedEvent(
                            symbol, 0.0, true, c.getTimestamp()));
                }
                prev = s;
            }
        }

        // EventBus dispatch is async — give in-flight publications a beat
        // before counting (measurement harness only, never production).
        Thread.sleep(1000);

        // ── Standalone detector diagnostics on the same tape ─────────────
        // How often do the M5/M6 primitives even FIRE on real 5m bars,
        // independent of funnel sequencing? This separates "thresholds
        // unrealistic for the timeframe" from "funnel sequencing too tight".
        com.topstep.trading.strategy.BarAggregationManager agg =
                new com.topstep.trading.strategy.BarAggregationManager("MNQ", 5000);
        com.topstep.trading.strategy.DisplacementDetector disp =
                new com.topstep.trading.strategy.DisplacementDetector(20, 1.5, 0.65, "MNQ");
        com.topstep.trading.strategy.MarketStructureShiftDetector mss =
                new com.topstep.trading.strategy.MarketStructureShiftDetector(50, 2);
        int dispCount = 0;
        int mssCount = 0;
        Instant lastDispTs = null;
        for (Candle c : mnq) {
            var completed = agg.processCandle(c);
            Candle m5 = completed.get(
                    com.topstep.trading.strategy.BarAggregationManager.Timeframe.M5);
            if (m5 == null) continue;
            disp.update(m5);
            var d = disp.getLastDisplacement();
            if (d != null && d.getTimestamp() != null
                    && !d.getTimestamp().equals(lastDispTs)) {
                lastDispTs = d.getTimestamp();
                dispCount++;
            }
            if (mss.update(m5) != null) mssCount++;
        }
        System.out.println("[REPLAY] standalone 5m diagnostics over the full tape: "
                + "displacements=" + dispCount + " mssEvents=" + mssCount
                + " (5m bars=" + agg.getCandleCount(
                        com.topstep.trading.strategy.BarAggregationManager.Timeframe.M5) + ")");

        System.out.println("\n========== FUNNEL REPLAY REPORT ==========");
        System.out.println("[REPLAY] scalpMode=" + System.getProperty("scalpMode.enabled")
                + " minRaidScore=" + System.getProperty("scalp.minRaidScore")
                + " hysteresis=" + System.getProperty("bias.hysteresis.enabled")
                + " detectorTf=" + System.getProperty("stdvote.detectorTimeframe", "5(default)"));
        System.out.println("[REPLAY] setup episodes started: " + episodes);
        System.out.println("[REPLAY] deepest-state-at-death histogram: " + deepestByEpisode);
        System.out.println("[REPLAY] invalidation reasons: " + reasons);
        int wins = 0;
        int losses = 0;
        int unfilled = 0;
        for (SimTrade t : trades) {
            if (t.resultR == null) { unfilled++; continue; }
            if (t.resultR > 0) wins++; else losses++;
        }
        System.out.println("[REPLAY] TRADES=" + trades.size()
                + " wins=" + wins + " losses=" + losses
                + " unfilled/open=" + unfilled
                + " (1R scalp geometry, pessimistic both-touch)");
        System.out.println("[REPLAY] EMISSIONS: " + emissions.size());
        for (StrategySignalEvent e : emissions) {
            System.out.println("[REPLAY]   -> " + e.getSignalType() + " " + e.getSymbol()
                    + " entry=" + e.getEntryPrice() + " stop=" + e.getStopPrice()
                    + " target=" + e.getTargetPrice() + " size=" + e.getQuantity()
                    + " RR=" + String.format("%.2f", e.getActualRR()));
        }
        System.out.println("==========================================\n");
        runner.shutdown();
    }

    private static String stateName(int ordinal) {
        return SetupState.values()[ordinal].name();
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
