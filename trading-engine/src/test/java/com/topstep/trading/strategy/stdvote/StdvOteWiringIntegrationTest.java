package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.event.StrategySignalEvent.SignalType;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * SA5 end-to-end wiring test: a deterministic synthetic 1m candle sequence
 * drives {@link StdvOteRunnerStrategy#onCandle} through the full state
 * machine — HTF bias (completed 15m bars), Judas manipulation leg (killzone
 * open anchor), liquidity sweep, displacement + its own FVG, MSS, OTE
 * retrace + rejection reaction — and a {@link StrategySignalEvent} is
 * published on the EventBus with sane geometry.
 *
 * <p>Determinism: every clock in the decision path (KillzoneClock,
 * SilverBulletClock, BarAggregationManager boundaries, RaidDetector's
 * internal clocks) is a pure function of the candle timestamp — no wall
 * clock is read, so fixed timestamps make the run fully reproducible.
 *
 * <h2>Fixture geometry (MNQ, tick 0.25, 2026-06-15, times UTC = ET+4)</h2>
 *
 * <ul>
 *   <li>Warmup 12:00–13:44 (seven 15m bars): rising structure; 15m swing
 *       highs 21020 → 21040 and a bullish 15m BOS. The second swing high
 *       confirms on the 14:00Z bar close → HTF bias BULLISH exactly then
 *       (never from 1m noise, never before the killzone action).</li>
 *   <li>Killzone opens 13:45Z (9:45 ET); open price 21032. Judas dip to
 *       21014 (13:52), recovery, secondary stab to 21012 (14:00, the
 *       sweep), reclaim close above 21032 at 14:03.</li>
 *   <li>Manipulation leg = [21012, 21035] → −2σ projection = 21058.</li>
 *   <li>Displacement burst 14:02–14:03 leaves its own FVG [21020, 21023]
 *       and breaks the 21035 swing → MSS. Impulse leg [21012, 21052].</li>
 *   <li>OTE retrace 14:06–14:07; rejection wick at 21024 inside the
 *       [21020.5, 21027.25] band → entry 21023 (FVG top), stop
 *       21012 − 4 ticks = 21011, target ≈ 21058, RR ≈ 2.92 ≥ 2.0 (passes
 *       the M7 floor with no gate weakened).</li>
 * </ul>
 */
@DisplayName("StdvOteWiringIntegrationTest (SA5 detector wiring end-to-end)")
class StdvOteWiringIntegrationTest {

    private static final String SYMBOL = "MNQ";
    private static final Instant WARMUP_START = Instant.parse("2026-06-15T12:00:00Z");
    private static final Instant KILLZONE_OPEN = Instant.parse("2026-06-15T13:45:00Z");

    @org.junit.jupiter.api.BeforeEach
    void pinDetectorTimeframe() {
        // The SA5 fixture encodes 1m entry anatomy — pin the detector
        // timeframe (LIVE default is 5m, field fix 2026-07-09).
        System.setProperty("stdvote.detectorTimeframe", "1");
    }

    @AfterEach
    void cleanupRegistry() {
        StdvOteRegistry.unregister(SYMBOL);
        System.clearProperty(StdvOteRunnerStrategy.STOP_BUFFER_TICKS_PROPERTY);
        System.clearProperty("stdvote.detectorTimeframe");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Fixture construction
    // ──────────────────────────────────────────────────────────────────────

    private static Candle at(Instant ts, double o, double h, double l, double c) {
        return new Candle(SYMBOL, ts, o, h, l, c, 100);
    }

    private static Candle kz(int minutesAfterOpen, double o, double h, double l, double c) {
        return at(KILLZONE_OPEN.plus(minutesAfterOpen, ChronoUnit.MINUTES), o, h, l, c);
    }

    /**
     * Build one 15m window as 15 1m candles: linear O→C drift with 0.25
     * default wicks; the window high prints on bar 7, the low on bar 3.
     */
    private static List<Candle> window15m(Instant start, double o, double h, double l, double c) {
        List<Candle> out = new ArrayList<>(15);
        double prevClose = o;
        for (int i = 0; i < 15; i++) {
            double barClose = o + (c - o) * (i + 1) / 15.0;
            double barOpen = prevClose;
            double hi = Math.max(barOpen, barClose) + 0.25;
            double lo = Math.min(barOpen, barClose) - 0.25;
            if (i == 7) hi = h;
            if (i == 3) lo = l;
            out.add(at(start.plus(i, ChronoUnit.MINUTES), barOpen, hi, lo, barClose));
            prevClose = barClose;
        }
        return out;
    }

    /** Warmup 12:00–13:44 — seven 15m bars establishing the bullish HTF. */
    private static List<Candle> warmupCandles() {
        List<Candle> all = new ArrayList<>();
        Instant t = WARMUP_START;
        // W1..W7 targets: see class javadoc.
        double[][] w = {
                // O, H, L, C
                { 21000, 21010, 20999, 21008 },   // W1
                { 21008, 21020, 21006, 21014 },   // W2  (15m swing high #1 = 21020)
                { 21014, 21016, 21000, 21006 },   // W3  (15m swing low 21000)
                { 21006, 21024, 21005, 21023 },   // W4  (BOS: close > 21020 → 15m BULLISH)
                { 21023, 21026, 21015, 21025 },   // W5
                { 21025, 21030, 21020, 21029 },   // W6
                { 21029, 21040, 21026, 21032 },   // W7  (swing high #2 = 21040, confirmed vs W8)
        };
        for (double[] win : w) {
            all.addAll(window15m(t, win[0], win[1], win[2], win[3]));
            t = t.plus(15, ChronoUnit.MINUTES);
        }
        return all;
    }

    /** Killzone bars 13:45–14:07 — Judas, sweep, displacement, MSS, OTE. */
    private static List<Candle> killzoneCandles() {
        List<Candle> c = new ArrayList<>();
        c.add(kz(0,  21032, 21034,    21030, 21031));    // KZ open (open price 21032)
        c.add(kz(1,  21031, 21032,    21026, 21027));    // Judas push down
        c.add(kz(2,  21027, 21028,    21025, 21026));
        c.add(kz(3,  21026, 21027,    21024, 21025));    // L1 swing low 21024
        c.add(kz(4,  21025, 21028,    21025, 21027));
        c.add(kz(5,  21027, 21029,    21026, 21028));
        c.add(kz(6,  21028, 21028.5,  21019, 21020));
        c.add(kz(7,  21020, 21021,    21014, 21017));    // Judas low 21014; L2 swing low
        c.add(kz(8,  21017, 21020,    21015.5, 21019));
        c.add(kz(9,  21019, 21024,    21017, 21023));
        c.add(kz(10, 21023, 21028,    21022, 21027));
        c.add(kz(11, 21027, 21032,    21026, 21031));
        c.add(kz(12, 21031, 21035,    21030, 21034));    // swing high 21035 (MSS break level)
        c.add(kz(13, 21034, 21034.75, 21031, 21033));
        c.add(kz(14, 21033, 21034,    21032, 21033));    // W8 closes 21033 (bias sets next bar)
        c.add(kz(15, 21033, 21033.5,  21012, 21016));    // 14:00 STAB: sweep @21012; BIAS_SET
        c.add(kz(16, 21016, 21020,    21014, 21018));    // base bar (FVG c1: high 21020)
        c.add(kz(17, 21018, 21028,    21017, 21027));    // recovery
        c.add(kz(18, 21027, 21039.5,  21023, 21039.5));  // 14:03 displacement + FVG[21020,21023]
                                                         //   + reclaim > 21032 + MSS > 21035
        c.add(kz(19, 21039.5, 21046,  21036, 21045));    // impulse extends
        c.add(kz(20, 21045, 21052,    21042, 21050));    // impulse high 21052
        c.add(kz(21, 21050, 21050.5,  21036, 21037));    // pullback begins
        c.add(kz(22, 21037, 21038,    21024, 21038));    // 14:07 OTE rejection wick → EMIT
        return c;
    }

    private static List<Candle> fullFixture() {
        List<Candle> all = new ArrayList<>(warmupCandles());
        all.addAll(killzoneCandles());
        return all;
    }

    private DefaultStrategyContext strategyCtx() {
        return new DefaultStrategyContext(new AccountState(50_000.0));
    }

    private void feed(StdvOteRunnerStrategy s, List<Candle> candles) {
        DefaultStrategyContext ctx = strategyCtx();
        for (Candle c : candles) {
            s.onCandle(c, ctx);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Main end-to-end assertion
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("full fixture drives IDLE → IN_TRADE and publishes one sane signal")
    void fullSequenceEmitsSaneSignal() {
        CapturingEventBus bus = new CapturingEventBus();
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(SYMBOL, "MES", bus);
        s.initialize();

        feed(s, fullFixture());

        SetupContext ctx = s.getSetupContext();
        assertThat(ctx.state).isEqualTo(SetupState.IN_TRADE);
        assertThat(bus.signals).hasSize(1);

        StrategySignalEvent evt = bus.signals.get(0);
        assertThat(evt.getSignalType()).isEqualTo(SignalType.LONG_ENTRY);
        assertThat(evt.getSymbol()).isEqualTo(SYMBOL);
        assertThat(evt.getSide()).isEqualTo(OrderSide.BUY);
        assertThat(evt.getTier()).isEqualTo(TradeTier.TIER_1);
        assertThat(evt.getQuantity()).isEqualTo(6);
        assertThat(ctx.sizeFilled).isEqualTo(6);

        // Manipulation leg came from the Judas detector (killzone-open
        // anchored), NOT from the legacy swing-pair fallback.
        assertThat(ctx.legLow).isEqualTo(21012.0);
        assertThat(ctx.legHigh).isEqualTo(21035.0);

        // Entry: the displacement-created FVG top, inside the OTE band.
        assertThat(evt.getEntryPrice()).isEqualTo(21023.0);
        assertThat(ctx.ote).isNotNull();
        assertThat(ctx.ote.contains(evt.getEntryPrice()))
                .as("entry must sit inside the OTE band [f79=%s, f62=%s]",
                        ctx.ote.f79(), ctx.ote.f62())
                .isTrue();

        // Stop: beyond the sweep extreme (21012) by the default 4-tick buffer.
        assertThat(ctx.sweep).isNotNull();
        assertThat(ctx.sweep.getSweptLevel()).isEqualTo(21012.0);
        assertThat(evt.getStopPrice()).isEqualTo(21011.0);

        // Target: the -2σ STDV projection off the manipulation leg
        // (21012 + 2 * 23 = 21058; level-snapping may move it by <= 3 ticks).
        StdvProjection minus2 = ctx.projections.stream()
                .filter(p -> Double.compare(p.sigma(), -2.0) == 0)
                .findFirst().orElseThrow();
        assertThat(evt.getTargetPrice()).isEqualTo(minus2.effectivePrice());
        assertThat(evt.getTargetPrice()).isCloseTo(21058.0, offset(0.76));

        // RR clears the M7 floor (>= 2.0) with no gate weakened.
        assertThat(evt.getActualRR()).isGreaterThanOrEqualTo(2.0);
        assertThat(ctx.rr).isGreaterThanOrEqualTo(2.0);

        // Raid score fed the M4 gate at or above the MNQ floor.
        assertThat(ctx.raidScore).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("stdvOte.stopBufferTicks property changes the stop buffer (default preserved at 4)")
    void stopBufferTicksIsConfigurable() {
        System.setProperty(StdvOteRunnerStrategy.STOP_BUFFER_TICKS_PROPERTY, "2");
        try {
            CapturingEventBus bus = new CapturingEventBus();
            StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(SYMBOL, "MES", bus);
            feed(s, fullFixture());
            assertThat(bus.signals).hasSize(1);
            // 21012 - 2 * 0.25 instead of the default 21011.0.
            assertThat(bus.signals.get(0).getStopPrice()).isEqualTo(21011.5);
        } finally {
            System.clearProperty(StdvOteRunnerStrategy.STOP_BUFFER_TICKS_PROPERTY);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Idempotency / edge cases
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("duplicate and out-of-order candles are dropped (state unchanged)")
    void duplicateAndStaleCandlesAreDropped() {
        CapturingEventBus bus = new CapturingEventBus();
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(SYMBOL, "MES", bus);

        List<Candle> fixture = fullFixture();
        // Feed through the 14:03 cascade bar (MSS_CONFIRMED).
        List<Candle> prefix = fixture.subList(0, warmupCandles().size() + 19);
        feed(s, prefix);
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.MSS_CONFIRMED);

        Candle last = prefix.get(prefix.size() - 1);
        DefaultStrategyContext ctx = strategyCtx();
        // Exact duplicate of the last candle: dropped.
        s.onCandle(last, ctx);
        // Out-of-order stale candle from an hour earlier: dropped.
        s.onCandle(at(WARMUP_START.plus(30, ChronoUnit.MINUTES), 21000, 21100, 20900, 21050), ctx);
        // A stale candle with a wildly different price must not perturb
        // detectors, tracker extremes, or the state machine.
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.MSS_CONFIRMED);
        assertThat(bus.signals).isEmpty();

        // The remainder of the fixture still completes the trade.
        feed(s, fixture.subList(warmupCandles().size() + 19, fixture.size()));
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.IN_TRADE);
        assertThat(bus.signals).hasSize(1);
    }

    @Test
    @DisplayName("session end mid-setup invalidates the in-flight setup")
    void sessionEndMidSetupInvalidates() {
        CapturingEventBus bus = new CapturingEventBus();
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(SYMBOL, "MES", bus);

        List<Candle> fixture = fullFixture();
        feed(s, fixture.subList(0, warmupCandles().size() + 19)); // through 14:03
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.MSS_CONFIRMED);

        s.onSessionEnd();
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.INVALIDATED);
        assertThat(bus.signals).isEmpty();
        // Idempotent + shutdown-safe.
        s.onSessionEnd();
        s.shutdown();
    }

    @Test
    @DisplayName("no HTF bias (warmup only) keeps the machine IDLE with no NPEs")
    void neutralHtfBiasStaysIdle() {
        CapturingEventBus bus = new CapturingEventBus();
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(SYMBOL, "MES", bus);

        feed(s, warmupCandles()); // HTF never leaves RANGING before 14:00Z
        SetupContext ctx = s.getSetupContext();
        assertThat(ctx.state).isEqualTo(SetupState.IDLE);
        assertThat(ctx.htfBias).isEqualTo(MarketBias.NEUTRAL);
        assertThat(bus.signals).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Synchronous capturing bus (deterministic — no async dispatch).
    // ──────────────────────────────────────────────────────────────────────

    private static final class CapturingEventBus extends EventBus {
        final List<StrategySignalEvent> signals = new ArrayList<>();

        @Override
        public void publish(com.topstep.trading.event.Event event) {
            if (event instanceof StrategySignalEvent sig) {
                signals.add(sig);
            }
        }
    }
}
