package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.BarAggregationManager;
import com.topstep.trading.strategy.BarAggregationManager.Timeframe;
import com.topstep.trading.strategy.DisplacementDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.topstep.trading.strategy.stdvote.OteGoldenReplay.et;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 Agent 04 (RC-09) — displacement calibration on the REAL 7-day MNQ tape,
 * aggregated to 5m exactly as the runner does (BarAggregationManager).
 *
 * <p>"Real impulse" = a 5m bar i where the move open[i] → close[i+2] covers at
 * least 1.5 x ATR14 (mean true range of the 14 bars before i) in one
 * direction. The detector "passes" the impulse when it fires, in that
 * direction, on any of bars i..i+2. The acceptance bar: &ge; 30 % of real
 * impulses pass AND the G1 15:00 bar (range 44.0 = 1.66 x ATR, body 53 %)
 * qualifies.
 */
@DisplayName("V5 Agent 04 displacement calibration (real tape)")
class DisplacementCalibrationTest {

    record Result(int bars, int fired, int impulses, int impulsesPassed, boolean g1) {}

    static Result measure(double atrMult, double bodyPct, boolean priorAtr) throws Exception {
        List<Candle> tape = OteGoldenReplay.loadTape("MNQ");
        BarAggregationManager agg = new BarAggregationManager("MNQ", 5000);
        DisplacementDetector d = new DisplacementDetector(20, atrMult, bodyPct, "CAL");
        if (priorAtr) d.usePriorTrueRangeAtr(OteConfig.DISPLACEMENT_ATR_LEN);
        List<Candle> bars = new ArrayList<>();
        List<Integer> firedDir = new ArrayList<>(); // +1 bull, -1 bear, 0 none
        for (Candle c : tape) {
            Map<Timeframe, Candle> done = agg.processCandle(c);
            Candle b = done.get(Timeframe.M5);
            if (b == null) continue;
            d.update(b);
            bars.add(b);
            DisplacementDetector.Displacement last = d.getLastDisplacement();
            firedDir.add(last != null && b.getTimestamp().equals(last.getTimestamp())
                    ? (last.isBullish() ? 1 : -1) : 0);
        }
        int fired = 0;
        for (int f : firedDir) if (f != 0) fired++;
        int impulses = 0;
        int passed = 0;
        for (int i = 15; i + 2 < bars.size(); i++) {
            double atr = 0;
            for (int j = i - 14; j < i; j++) {
                Candle c = bars.get(j);
                double pc = bars.get(j - 1).getClose();
                atr += Math.max(c.getHigh() - c.getLow(),
                        Math.max(Math.abs(c.getHigh() - pc), Math.abs(c.getLow() - pc)));
            }
            atr /= 14.0;
            double move = bars.get(i + 2).getClose() - bars.get(i).getOpen();
            if (Math.abs(move) < 1.5 * atr) continue;
            impulses++;
            int dir = move > 0 ? 1 : -1;
            if (firedDir.get(i) == dir || firedDir.get(i + 1) == dir || firedDir.get(i + 2) == dir) passed++;
        }
        boolean g1 = false;
        for (int i = 0; i < bars.size(); i++) {
            if (bars.get(i).getTimestamp().equals(et("2026-09-28T15:00"))) g1 = firedDir.get(i) == -1;
        }
        return new Result(bars.size(), fired, impulses, passed, g1);
    }

    @Test
    @DisplayName("calibrated default passes >= 30 % of real impulses and the G1 15:00 bar")
    void calibratedDefault() throws Exception {
        Result before = measure(1.5, 0.65, false);
        Result owner = measure(1.2, 0.55, true);
        Result after = measure(OteConfig.displacementAtrMult(), OteConfig.displacementBodyPct(), true);
        String row = "%s | %d/%d bars = %.1f %% | impulses %d/%d = %.1f %% | G1 15:00 qualifies=%s%n";
        System.out.printf("MEAS displacement pass-rate (5m bars, 7-day MNQ tape)%n");
        System.out.printf(row, "BEFORE 1.5 x avgRange14(incl.) / body 0.65", before.fired(), before.bars(),
                100.0 * before.fired() / before.bars(), before.impulsesPassed(), before.impulses(),
                100.0 * before.impulsesPassed() / before.impulses(), before.g1());
        System.out.printf(row, "OWNER  1.2 x ATR14(prior) / body 0.55", owner.fired(), owner.bars(),
                100.0 * owner.fired() / owner.bars(), owner.impulsesPassed(), owner.impulses(),
                100.0 * owner.impulsesPassed() / owner.impulses(), owner.g1());
        System.out.printf(row, "AFTER  " + OteConfig.displacementAtrMult() + " x ATR14(prior) / body "
                        + OteConfig.displacementBodyPct(), after.fired(), after.bars(),
                100.0 * after.fired() / after.bars(), after.impulsesPassed(), after.impulses(),
                100.0 * after.impulsesPassed() / after.impulses(), after.g1());

        assertThat(after.g1()).as("G1 15:00 bar must qualify").isTrue();
        assertThat((double) after.impulsesPassed() / after.impulses()).isGreaterThanOrEqualTo(0.30);
        assertThat(after.fired()).isGreaterThan(before.fired());
    }

    @Test
    @DisplayName("G1 15:00 bar numbers: range/ATR 1.66, body 0.53")
    void g1Numbers() throws Exception {
        List<Candle> tape = OteGoldenReplay.loadTape("MNQ");
        BarAggregationManager agg = new BarAggregationManager("MNQ", 5000);
        DisplacementDetector d = new DisplacementDetector(20, OteConfig.displacementAtrMult(),
                OteConfig.displacementBodyPct(), "CAL").usePriorTrueRangeAtr(OteConfig.DISPLACEMENT_ATR_LEN);
        for (Candle c : tape) {
            Candle b = agg.processCandle(c).get(Timeframe.M5);
            if (b == null) continue;
            d.update(b);
            if (b.getTimestamp().equals(et("2026-09-28T15:00"))) {
                System.out.printf("MEAS G1 15:00 5m bar o=%.2f h=%.2f l=%.2f c=%.2f ATR14=%.2f range/ATR=%.3f body=%.3f%n",
                        b.getOpen(), b.getHigh(), b.getLow(), b.getClose(), d.getLastAtr(),
                        d.getLastRangeOverAtr(), d.getLastBodyRatio());
                assertThat(d.getLastRangeOverAtr()).isBetween(1.65, 1.67);
                assertThat(d.getLastBodyRatio()).isBetween(0.53, 0.54);
                assertThat(d.getLastDisplacement().getTimestamp()).isEqualTo(b.getTimestamp());
                return;
            }
        }
        throw new AssertionError("G1 bar not found");
    }
}
