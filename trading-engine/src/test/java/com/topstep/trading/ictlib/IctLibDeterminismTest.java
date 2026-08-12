package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V4 critical rule 7: same feed → identical detections, always.
 *
 * <p>This is the test that would fail the moment someone reaches for
 * {@code Instant.now()}, a UUID, or a HashMap iteration order inside a
 * detector — the three ways determinism usually dies.
 */
class IctLibDeterminismTest {

    /**
     * A deterministic pseudo-random walk (fixed seed, integer arithmetic) with
     * enough expansion bars to produce displacements, gaps and at least one
     * region.
     */
    private static List<Candle> syntheticFeed(int bars) {
        List<Candle> out = new ArrayList<>(bars);
        long state = 42L;
        double price = 21000.0;
        for (int i = 0; i < bars; i++) {
            state = (state * 6364136223846793005L + 1442695040888963407L);
            int step = (int) ((state >>> 33) % 41) - 20;      // -20..20 ticks
            double open = price;
            double close = open + step * 0.25;
            double high = Math.max(open, close) + ((i % 7 == 0) ? 0.25 : 2.0);
            double low = Math.min(open, close) - ((i % 5 == 0) ? 0.25 : 2.0);
            out.add(new Candle(IctLibFixture.SYM,
                    IctLibFixture.T0.plusSeconds(60L * i), open, high, low, close, 100L));
            price = close;
        }
        return out;
    }

    private static List<String> fingerprint(DetectionRegistry r) {
        List<String> out = new ArrayList<>();
        for (Detection d : r.snapshot()) {
            out.add(String.join("|", d.id(), d.type().name(), d.timeframe(),
                    d.direction().name(), d.state().name(),
                    String.valueOf(d.priceBottom()), String.valueOf(d.priceTop()),
                    String.valueOf(d.createdAtBar()), String.valueOf(d.stateChangedAtBar())));
        }
        return out;
    }

    @Test
    @DisplayName("Two engines fed the same 240 bars produce byte-identical registries")
    void sameFeedSameDetections() {
        List<Candle> feed = syntheticFeed(240);

        IctLibEngine a = new IctLibEngine(IctLibConfig.defaults());
        IctLibEngine b = new IctLibEngine(IctLibConfig.defaults());
        for (Candle c : feed) a.onCandle(c);
        for (Candle c : feed) b.onCandle(c);

        List<String> fa = fingerprint(a.registry(IctLibFixture.SYM));
        List<String> fb = fingerprint(b.registry(IctLibFixture.SYM));

        assertThat(fa).isNotEmpty();
        assertThat(fa).isEqualTo(fb);
    }

    @Test
    @DisplayName("The engine registers detections on BOTH the 1m feed and the 15m aggregate")
    void bothTimeframesAreRegistered() {
        IctLibEngine engine = new IctLibEngine(IctLibConfig.defaults());
        for (Candle c : syntheticFeed(240)) engine.onCandle(c);

        List<Detection> all = engine.registry(IctLibFixture.SYM).snapshot();
        assertThat(all).extracting(Detection::timeframe)
                .contains(IctLibEngine.TF_1M, IctLibEngine.TF_15M);
    }

    @Test
    @DisplayName("Partial (still-forming) candles are ignored — detections close on closed bars")
    void partialCandlesAreIgnored() {
        IctLibEngine engine = new IctLibEngine(IctLibConfig.defaults());
        for (Candle c : IctLibFixture.warmupBars()) engine.onCandle(c);
        int before = engine.registry(IctLibFixture.SYM).size();

        engine.onCandle(new Candle(IctLibFixture.SYM,
                IctLibFixture.T0.plusSeconds(300), 21000, 21016, 20999, 21015, 100L,
                com.topstep.trading.domain.TradingSession.REGULAR, true));

        assertThat(engine.registry(IctLibFixture.SYM).size()).isEqualTo(before);
    }

    @Test
    @DisplayName("Every family stays inside its retention cap over a long feed")
    void retentionCapsHoldOverALongFeed() {
        IctLibEngine engine = new IctLibEngine(IctLibConfig.defaults());
        for (Candle c : syntheticFeed(2000)) engine.onCandle(c);

        DetectionRegistry r = engine.registry(IctLibFixture.SYM);
        // 2 timeframes × (50 displacement) + 2 × 2 sides × 10 fvg + 2 × 2 × 5 bpr
        assertThat(r.count(DetectionType.DISPLACEMENT)).isLessThanOrEqualTo(100);
        assertThat(r.count(DetectionType.FVG)).isLessThanOrEqualTo(40);
        assertThat(r.count(DetectionType.BPR)).isLessThanOrEqualTo(20);
    }
}
