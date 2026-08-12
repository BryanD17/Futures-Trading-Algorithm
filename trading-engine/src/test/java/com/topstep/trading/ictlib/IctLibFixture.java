package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared test scaffolding for the ICT library.
 *
 * <p>Every sequence here is hand-built and hand-checked against Appendix S /
 * Appendix W of {@code ICT_STACK_MASTER_PROMPT_V4.txt}. Prices are MNQ-scaled
 * (tick 0.25) exactly as the worked examples are.
 */
final class IctLibFixture {

    static final String SYM = "MNQ";
    /** 2026-08-11 10:00 ET — inside a regular session, away from any rollover. */
    static final Instant T0 = Instant.parse("2026-08-11T14:00:00Z");

    private IctLibFixture() {}

    static Candle c(int minute, double o, double h, double l, double close) {
        return new Candle(SYM, T0.plusSeconds(60L * minute), o, h, l, close, 100L);
    }

    /**
     * Five leading bars with bodies 12, 11, 13, 10, 12 (mean 11.6). Chosen so
     * that the W2 candle i-2 (body 10) is NOT itself a displacement — otherwise
     * an extra gap would form one bar early and the assertions would be testing
     * the wrong detection.
     */
    static List<Candle> warmupBars() {
        List<Candle> out = new ArrayList<>();
        out.add(c(0, 20900, 20913, 20898, 20912)); // body 12
        out.add(c(1, 20912, 20924, 20905, 20923)); // body 11
        out.add(c(2, 20923, 20937, 20916, 20936)); // body 13
        out.add(c(3, 20936, 20947, 20929, 20946)); // body 10
        out.add(c(4, 20946, 20959, 20939, 20958)); // body 12
        return out;
    }

    /** Drives one timeframe's detectors exactly as {@code IctLibEngine} does. */
    static final class Harness {
        final TimeframeSeries series;
        final DetectionRegistry registry;
        final List<FamilyDetector> detectors;
        /** Pools published through the §S6 listener, in confirmation order. */
        final List<Detection> pools;

        Harness(IctLibConfig config) {
            this.series = new TimeframeSeries(IctLibEngine.TF_1M);
            this.registry = new DetectionRegistry(SYM, config.retentions());
            this.pools = new ArrayList<>();
            this.detectors = List.of(
                    new DisplacementScanner(config),
                    new FairValueGapDetector(config),
                    new BprDetector(),
                    new VolumeImbalanceDetector(config),
                    new OpeningGapDetector(),
                    new LiquidityPoolDetector(config,
                            (sym, pool) -> pools.add(pool)));
        }

        void push(Candle candle) {
            series.push(candle);
            for (FamilyDetector d : detectors) {
                d.onBar(series, registry);
            }
        }

        void pushAll(List<Candle> candles) {
            for (Candle candle : candles) push(candle);
        }

        List<Detection> fvgs(DetectionDirection direction) {
            List<Detection> out = new ArrayList<>();
            for (Detection d : registry.byType(DetectionType.FVG)) {
                if (d.direction() == direction) out.add(d);
            }
            return out;
        }
    }

    static Harness harness() {
        return new Harness(IctLibConfig.defaults());
    }

    static Harness harness(IctLibConfig config) {
        return new Harness(config);
    }
}
