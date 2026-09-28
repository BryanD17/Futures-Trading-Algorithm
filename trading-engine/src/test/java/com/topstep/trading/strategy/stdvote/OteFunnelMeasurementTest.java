package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chart.ChartEngine;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.Event;
import com.topstep.trading.event.OteAlarmEvent;
import com.topstep.trading.event.OteArmedEvent;
import com.topstep.trading.event.OteInvalidatedEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.strategy.DefaultStrategyContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * V5 Agent 04 — MEASUREMENT (always passes): replays the 7-day tape through
 * the REAL runner with the production ChartEngine attached (as LiveEngineRunner
 * wires it — the autopsy harness does not attach one, so M7b ABSTAINs there),
 * and reports the OTE event counts, anchor modes, emissions and the M7b kill
 * share. Enabled with {@code AUTOPSY_DIR} (same as FunnelAutopsyHarness);
 * {@code AUTOPSY_PROPS="k=v;k=v"} applies extra properties.
 */
class OteFunnelMeasurementTest {

    @Test
    void measure() throws Exception {
        if (System.getenv("AUTOPSY_DIR") == null) {
            System.out.println("[OTE-MEAS] skipped — set AUTOPSY_DIR");
            return;
        }
        String extra = System.getenv("AUTOPSY_PROPS");
        if (extra != null && !extra.isBlank()) {
            for (String kv : extra.split(";")) {
                int i = kv.indexOf('=');
                if (i > 0) System.setProperty(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
            }
        }
        List<Candle> mnq = OteGoldenReplay.loadTape("MNQ");
        List<Candle> mes = OteGoldenReplay.loadTape("MES");
        OteGoldenReplay.CapturingBus bus = new OteGoldenReplay.CapturingBus();
        StdvOteRunnerStrategy runner = new StdvOteRunnerStrategy("MNQ", "MES", bus);
        runner.initialize();
        ChartEngine chart = new ChartEngine();
        chart.registerInstrument("MNQ", 0.25);
        runner.setChartEngine(chart);
        DefaultStrategyContext ctx = new DefaultStrategyContext(new AccountState(50_000.0));
        int si = 0;
        for (Candle c : mnq) {
            while (si < mes.size() && !mes.get(si).getTimestamp().isAfter(c.getTimestamp())) {
                runner.onCandle(mes.get(si++), ctx);
            }
            chart.onCandle(c);
            ctx.setCurrentTime(c.getTimestamp());
            runner.onCandle(c, ctx);
        }
        int armed = 0, alarms = 0, invalid = 0, signals = 0;
        Map<String, Integer> anchors = new TreeMap<>();
        Map<String, Integer> invalidReasons = new TreeMap<>();
        for (Event e : bus.events) {
            if (e instanceof OteArmedEvent a) {
                armed++;
                anchors.merge(a.getAnchorMode(), 1, Integer::sum);
                System.out.println("[OTE-MEAS] ARMED " + OteGoldenReplay.fmt(a.getCandleTime()) + " "
                        + a.toMap());
            } else if (e instanceof OteAlarmEvent) {
                alarms++;
            } else if (e instanceof OteInvalidatedEvent iv) {
                invalid++;
                invalidReasons.merge(String.valueOf(iv.getReason()).replaceAll("[0-9.]+", "#"), 1, Integer::sum);
            } else if (e instanceof StrategySignalEvent s) {
                signals++;
                System.out.println("[OTE-MEAS] SIGNAL " + s.getSignalType() + " e=" + s.getEntryPrice()
                        + " s=" + s.getStopPrice() + " t=" + s.getTargetPrice() + " " + s.getReason());
            }
        }
        Ote30mConfluenceGate g = Ote30mConfluenceGate.get("MNQ").orElseThrow();
        System.out.println("[OTE-MEAS] props=" + extra + " OTE_ARMED=" + armed + " OTE_ALARM=" + alarms
                + " OTE_INVALIDATED=" + invalid + " signals=" + signals + " anchors=" + anchors
                + " invalidReasons=" + invalidReasons);
        System.out.println("[OTE-MEAS] M7b mode=" + g.mode() + " " + g.toApiMap()
                + " evaluations=" + g.evaluationCount()
                + " killShare(wouldBlock/decided)=" + g.killShare());
    }
}
