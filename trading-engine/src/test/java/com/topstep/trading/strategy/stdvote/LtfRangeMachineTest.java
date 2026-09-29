package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.strategy.ImpulseExtensionAnalyzer;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.TradeTier;
import com.topstep.trading.strategy.session.SessionWindow;
import com.topstep.trading.strategy.stdvote.PdArrayLocator.PdArray;
import com.topstep.trading.validation.MandatoryConfluenceValidator;
import com.topstep.trading.validation.ValidationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V5 Agent 05.9 - the INDEPENDENT LTF dealing-range machine (range.ltf.*):
 * the intraday-swing range and its anchors, the LTF bias independent of the
 * HTF bias (INDEPENDENT trades it, HTF_ALIGNED refuses it), the LTF
 * equilibrium / OTE / ladder numbers, the keys, and flag OFF = no machine.
 */
@DisplayName("V5 Agent 05.9 - LTF dealing-range machine: swing range, own bias / equilibrium / OTE, gating")
class LtfRangeMachineTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final double TICK = 0.25;
    /** range.ltf.minLegTicks default 120 x 0.25 = 30 MNQ points. */
    private static final double MIN_LEG = 120 * TICK;

    @AfterEach
    void clear() {
        for (String k : new String[] {"range.ltf.enabled", "range.ltf.minLegTicks", "range.ltf.minLegTicks.MGC",
                "range.ltf.gating", "range.ltf.maxPerDay", "range.ltf.riskFraction", "range.ltf.sessions"}) {
            System.clearProperty(k);
        }
        StdvOteRegistry.unregister("MNQ");
    }

    private static Instant et(String iso) {
        return LocalDateTime.parse(iso).atZone(ET).toInstant();
    }

    /** Five 1m candles that aggregate to the 5m bar (o, h, l, c) starting at {@code start}. */
    private static List<Candle> bar5(Instant start, double o, double h, double l, double c) {
        double mid = (o + c) / 2.0;
        List<Candle> out = new ArrayList<>();
        out.add(new Candle("MNQ", start, o, h, Math.min(o, mid), mid, 10));
        out.add(new Candle("MNQ", start.plusSeconds(60), mid, mid, l, mid, 10));
        out.add(new Candle("MNQ", start.plusSeconds(120), mid, mid, mid, mid, 10));
        out.add(new Candle("MNQ", start.plusSeconds(180), mid, mid, mid, mid, 10));
        out.add(new Candle("MNQ", start.plusSeconds(240), mid, Math.max(mid, c), Math.min(mid, c), c, 10));
        return out;
    }

    /** 5m bars (o,h,l,c) from 10:00 ET 2026-09-28; returns the start of the next bar. */
    private static Instant feed(DealingRangeTracker t, Instant start, double[][] bars) {
        Instant at = start;
        for (double[] b : bars) {
            for (Candle c : bar5(at, b[0], b[1], b[2], b[3])) t.onCandle(c);
            at = at.plusSeconds(300);
        }
        return at;
    }

    private static final double[][] BULL_LEG = {
            {20010, 20012, 20005, 20008},   // b0 10:00
            {20008, 20009, 20000, 20002},   // b1 10:05
            {20002, 20004, 19990, 19995},   // b2 10:10  swing LOW 19990 (leg into it 22 pt < 30: no range)
            {19995, 20010, 19993, 20008},   // b3 10:15
            {20008, 20025, 20006, 20022},   // b4 10:20
            {20022, 20040, 20020, 20035},   // b5 10:25
            {20035, 20050, 20030, 20045},   // b6 10:30  swing HIGH 20050 (leg 19990 -> 20050 = 60 pt)
            {20045, 20046, 20035, 20038},   // b7 10:35
            {20038, 20042, 20033, 20036},   // b8 10:40
    };

    // ── the LTF range from 5m swings ─────────────────────────────────────

    @Test
    @DisplayName("LTF range = the most recent confirmed 5m swing leg >= minLegTicks, anchored on the swing bars")
    void rangeFromFiveMinuteSwings() {
        DealingRangeTracker t = DealingRangeTracker.intradaySwings(MIN_LEG);
        assertThat(t.window()).isEqualTo(DealingRangeTracker.Window.INTRADAY_SWINGS);
        Instant next = feed(t, et("2026-09-28T10:00"), BULL_LEG);
        // b6 is confirmed only when b8 is final (first 1m bar of b9).
        assertThat(t.snapshot().decisive()).isFalse();
        assertThat(t.snapshot().direction()).isEqualTo(MarketBias.NEUTRAL);
        t.onCandle(new Candle("MNQ", next, 20036, 20037, 20034, 20035, 10));
        DealingRangeTracker.Snapshot s = t.snapshot();
        assertThat(s.decisive()).isTrue();
        assertThat(s.direction()).isEqualTo(MarketBias.BULLISH);
        assertThat(s.low()).isEqualTo(19990.0);
        assertThat(s.high()).isEqualTo(20050.0);
        assertThat(s.equilibrium()).isEqualTo(20020.0);
        DealingRangeTracker.SwingRange sr = t.swingRange();
        assertThat(sr.legLowAt()).isEqualTo(et("2026-09-28T10:10"));    // the swing-low bar
        assertThat(sr.legHighAt()).isEqualTo(et("2026-09-28T10:30"));   // the swing-high bar
        assertThat(sr.lastEvent()).startsWith("REBUILD BULLISH [19990.0,20050.0]");
    }

    @Test
    @DisplayName("a pullback leg < minLegTicks changes nothing; an opposite leg >= minLegTicks flips (newest leg)")
    void flipsOnlyOnAnOppositeLegOfTheMinimumSize() {
        DealingRangeTracker t = DealingRangeTracker.intradaySwings(MIN_LEG);
        Instant next = feed(t, et("2026-09-28T10:00"), BULL_LEG);
        next = feed(t, next, new double[][] {
                {20036, 20038, 20028, 20030},   // b9  10:45
                {20030, 20032, 20025, 20028},   // b10 10:50 swing LOW 20025: leg 20050 -> 20025 = 25 < 30
                {20028, 20034, 20027, 20032},   // b11 10:55
                {20032, 20036, 20029, 20034},   // b12 11:00 swing HIGH 20036 (leg 20025 -> 20036 = 11)
        });
        t.onCandle(new Candle("MNQ", next, 20034, 20035, 20033, 20034, 10));
        assertThat(t.snapshot().direction()).isEqualTo(MarketBias.BULLISH);
        assertThat(t.snapshot().low()).isEqualTo(19990.0);
        assertThat(t.snapshot().high()).isEqualTo(20050.0);
        next = feed(t, next, new double[][] {
                {20034, 20035, 20010, 20012},   // b13 11:05
                {20012, 20013, 20000, 20002},   // b14 11:10 swing LOW 20000: leg 20036 -> 20000 = 36 >= 30
                {20002, 20008, 20001, 20006},   // b15 11:15
                {20006, 20010, 20004, 20008},   // b16 11:20
        });
        assertThat(t.snapshot().direction()).isEqualTo(MarketBias.BULLISH);   // not confirmed yet
        t.onCandle(new Candle("MNQ", next, 20008, 20009, 20007, 20008, 10));
        DealingRangeTracker.Snapshot s = t.snapshot();
        assertThat(s.direction()).isEqualTo(MarketBias.BEARISH);
        assertThat(s.high()).isEqualTo(20036.0);
        assertThat(s.low()).isEqualTo(20000.0);
        assertThat(s.equilibrium()).isEqualTo(20018.0);
        assertThat(t.swingRange().legHighAt()).isEqualTo(et("2026-09-28T11:00"));
        assertThat(t.swingRange().legLowAt()).isEqualTo(et("2026-09-28T11:10"));
        assertThat(t.swingRange().lastEvent()).startsWith("FLIP BEARISH [20000.0,20036.0]");
    }

    @Test
    @DisplayName("between swings: new extreme extends, a wick beyond the origin extends it, a 1m CLOSE beyond the origin flips")
    void betweenSwingsTheHtfRules() {
        DealingRangeTracker t = DealingRangeTracker.intradaySwings(MIN_LEG);
        Instant next = feed(t, et("2026-09-28T10:00"), BULL_LEG);
        t.onCandle(new Candle("MNQ", next, 20036, 20055, 20034, 20050, 10));                 // new high
        assertThat(t.snapshot().high()).isEqualTo(20055.0);
        t.onCandle(new Candle("MNQ", next.plusSeconds(60), 20000, 20001, 19988, 19995, 10)); // wick below 19990
        assertThat(t.snapshot().direction()).isEqualTo(MarketBias.BULLISH);
        assertThat(t.snapshot().low()).isEqualTo(19988.0);
        t.onCandle(new Candle("MNQ", next.plusSeconds(120), 19995, 19996, 19980, 19984, 10)); // close below
        assertThat(t.snapshot().direction()).isEqualTo(MarketBias.BEARISH);
        assertThat(t.snapshot().low()).isEqualTo(19980.0);
        assertThat(t.snapshot().high()).isEqualTo(20055.0);
    }

    @Test
    @DisplayName("carry: the 17:00-18:00 ET halt is ignored and the LTF range carries across the 18:00 reopen")
    void carriesAcrossTheReopen() {
        DealingRangeTracker t = DealingRangeTracker.intradaySwings(MIN_LEG);
        Instant next = feed(t, et("2026-09-28T15:00"), BULL_LEG);
        t.onCandle(new Candle("MNQ", next, 20036, 20037, 20034, 20035, 10));
        DealingRangeTracker.Snapshot before = t.snapshot();
        assertThat(before.direction()).isEqualTo(MarketBias.BULLISH);
        t.onCandle(new Candle("MNQ", et("2026-09-28T17:30"), 19000, 21000, 19000, 19000, 10));  // halt print
        assertThat(t.snapshot()).isEqualTo(before);
        t.onCandle(new Candle("MNQ", et("2026-09-28T18:00"), 20030, 20032, 20028, 20031, 10));  // reopen
        assertThat(t.snapshot()).isEqualTo(before);
    }

    // ── LTF bias independent of the HTF bias ────────────────────────────

    /**
     * A BEARISH LTF range [20000, 20036] (eq 20018) INSIDE a BULLISH HTF range
     * [19800, 20100] (eq 19950): the LTF machine shorts a HIGH sweep of its own
     * premium OTE band (0.618-0.786 from the LTF low = [20022.25, 20028.25]).
     */
    private static SetupContext ltfShort(String gating) {
        OteZone z = new OteEntryCalculator().buildZone(20000.0, 20036.0, false, TICK).orElseThrow();
        SetupContext c = new SetupContext();
        c.symbol = "MNQ";
        c.machine = LtfRangeConfig.MACHINE_LTF;
        c.ltfGating = gating;
        c.ltfRangeTicks = (20036.0 - 20000.0) / TICK;
        c.ltfMinLegTicks = 120;
        c.ltfHtfBias = MarketBias.BULLISH;
        c.ltfHtfRangeLow = 19800.0;
        c.ltfHtfRangeHigh = 20100.0;
        c.ltfHtfEq = 19950.0;
        c.rangeLow = 20000.0;
        c.rangeHigh = 20036.0;
        c.rangeEq = 20018.0;
        c.state = SetupState.OTE_ARMED;
        c.htfBias = MarketBias.BEARISH;          // the LTF machine's OWN bias
        c.sessionWindow = SessionWindow.NY_AM.name();
        c.killzoneOpen = true;
        c.sweep = new LiquiditySweep(false, 20026.0, et("2026-09-28T11:40"), false);
        c.raidScore = 6;
        PdArray ob = new PdArray("OB", false, 20023.0, 20027.0, et("2026-09-28T11:39"));
        c.displacement = true;
        c.fvg = ob.asFairValueGap();
        c.m5LinkKind = "FVG";
        c.displacementAt = et("2026-09-28T11:40");
        c.mss = true;
        c.mssAt = et("2026-09-28T11:45");
        c.ote = z;
        c.oteAnchorMode = OteAnchorMode.DEALING_RANGE.name();
        c.pdArrayInOte = 20025.0;
        c.pdArrayKind = "OB";
        c.entry = 20025.0;
        c.stop = 20030.0;
        c.t1 = 20018.0;
        c.t2 = 20000.0;
        c.finalTarget = 20000.0;
        c.rrT1 = (20025.0 - 20018.0) / (20030.0 - 20025.0);
        c.rr = (20025.0 - 20000.0) / (20030.0 - 20025.0);
        c.tier = TradeTier.TIER_1;
        c.sizeRequest = 5;
        return c;
    }

    @Test
    @DisplayName("LTF own OTE band / equilibrium: [20000, 20036] bearish -> band [20022.25, 20028.25], eq 20018")
    void ownEquilibriumAndOte() {
        OteZone z = new OteEntryCalculator().buildZone(20000.0, 20036.0, false, TICK).orElseThrow();
        assertThat(z.eq50()).isEqualTo(20018.0);
        assertThat(Math.min(z.f62(), z.f79())).isEqualTo(20022.25);
        assertThat(Math.max(z.f62(), z.f79())).isEqualTo(20028.25);
        assertThat(z.f705()).isCloseTo(20025.5, within(0.25));
    }

    @Test
    @DisplayName("INDEPENDENT: a bearish LTF short inside a BULLISH HTF passes M1..M9 (M2 on the LTF machine's own bias)")
    void independentTradesTheLtfShort() {
        MandatoryConfluenceValidator v = new MandatoryConfluenceValidator(null, null, null);
        ValidationResult r = v.validateStdvOte(ltfShort("INDEPENDENT"));
        assertThat(r.passed()).as(r.getFailures().toString()).isTrue();
        assertThat(r.getConfirmations()).anyMatch(s -> s.startsWith("M2: LTF machine bias=BEARISH range [20000.0,20036.0] 144.0 >= 120.0"));
    }

    @Test
    @DisplayName("HTF_ALIGNED: the same short is refused (M2: LTF direction != HTF bias); aligned but wrong HTF side -> M2b")
    void htfAlignedRefusesIt() {
        MandatoryConfluenceValidator v = new MandatoryConfluenceValidator(null, null, null);
        ValidationResult r = v.validateStdvOte(ltfShort("HTF_ALIGNED"));
        assertThat(r.passed()).isFalse();
        assertThat(r.getFailures().get(0))
                .isEqualTo("M2: LTF direction BEARISH != HTF bias BULLISH (range.ltf.gating=HTF_ALIGNED)");
        SetupContext aligned = ltfShort("HTF_ALIGNED");
        aligned.ltfHtfBias = MarketBias.BEARISH;          // HTF bearish too, but its eq 19950 < entry is premium: OK
        assertThat(v.validateStdvOte(aligned).passed()).isTrue();
        aligned.ltfHtfEq = 20050.0;                        // entry 20025 is in the HTF DISCOUNT: a short is refused
        assertThat(v.validateStdvOte(aligned).getFailures().get(0))
                .isEqualTo("M2b: HTF_ALIGNED entry 20025.0 not in the HTF premium (HTF eq 20050.0)");
    }

    @Test
    @DisplayName("M2 re-checks the LTF range size; the HTF machine never reads the LTF fields")
    void m2RechecksTheLtfRange() {
        MandatoryConfluenceValidator v = new MandatoryConfluenceValidator(null, null, null);
        SetupContext small = ltfShort("INDEPENDENT");
        small.ltfRangeTicks = 100;
        assertThat(v.validateStdvOte(small).getFailures().get(0))
                .isEqualTo("M2: LTF range 100.0 ticks < range.ltf.minLegTicks 120.0");
        SetupContext htf = ltfShort("HTF_ALIGNED");
        htf.machine = LtfRangeConfig.MACHINE_HTF;       // same numbers on the HTF machine: no LTF branch
        htf.ltfRangeTicks = 1;
        assertThat(v.validateStdvOte(htf).passed()).isTrue();
    }

    // ── the LTF core: own ladder, own tag, never registered ─────────────

    @Test
    @DisplayName("LTF core: T1 = the LTF equilibrium, T2 = the LTF far edge, signal STDV_OTE_LTF:, not registered")
    void ltfCoreLadderAndTag() {
        EventBus bus = new EventBus();
        ConcurrentLinkedQueue<StrategySignalEvent> q = new ConcurrentLinkedQueue<>();
        bus.subscribe(StrategySignalEvent.class, q::add);
        bus.start();
        StdvOteRegistry.unregister("MNQ");
        StdvOteStrategy core = new StdvOteStrategy("MNQ",
                new StdvProjectionEngine(null, new ImpulseExtensionAnalyzer("MNQ", 30)),
                new OteEntryCalculator(), new MandatoryConfluenceValidator(null, null, null), bus, 0, false);
        core.configureLtfMachine();
        assertThat(StdvOteRegistry.get("MNQ")).isEmpty();
        core.recordHtfBias(MarketBias.BEARISH);
        SetupContext c = core.getSetupContext();
        SetupContext t = ltfShort("INDEPENDENT");
        c.ltfGating = t.ltfGating;
        c.ltfRangeTicks = t.ltfRangeTicks;
        c.ltfMinLegTicks = t.ltfMinLegTicks;
        c.ltfHtfBias = t.ltfHtfBias;
        c.ltfHtfEq = t.ltfHtfEq;
        c.rangeLow = t.rangeLow;
        c.rangeHigh = t.rangeHigh;
        c.rangeEq = t.rangeEq;
        c.sessionWindow = t.sessionWindow;
        c.killzoneOpen = true;
        c.sweep = t.sweep;
        c.raidScore = t.raidScore;
        c.displacement = true;
        c.fvg = t.fvg;
        c.m5LinkKind = t.m5LinkKind;
        c.displacementAt = t.displacementAt;
        c.mss = true;
        c.mssAt = t.mssAt;
        c.ote = t.ote;
        c.oteAnchorMode = t.oteAnchorMode;
        c.pdArrayInOte = 20025.0;
        c.pdArrayKind = "OB";
        c.pdArrayFarEdge = 20027.0;
        c.state = SetupState.OTE_ARMED;
        boolean emitted = core.tryEmit(TICK, 4, TradeTier.TIER_1, 5);
        assertThat(emitted).as(c.lastGateFailed).isTrue();
        assertThat(c.machine).isEqualTo("LTF");
        assertThat(c.t1).isEqualTo(20018.0);      // the LTF equilibrium
        assertThat(c.t2).isEqualTo(20000.0);      // the LTF far edge
        StrategySignalEvent sig = core.getLastEmittedSignal();
        assertThat(sig.getReason()).startsWith(LtfRangeConfig.REASON_PREFIX)
                .contains("range=[20000.0,20036.0] eq=20018.0");
        // stop beyond max(0.786 = 20028.25, OB far edge 20027) + 4 ticks
        assertThat(sig.getStopPrice()).isEqualTo(20029.25);
        bus.stop();
    }

    // ── keys ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("keys: defaults (OFF, 120, INTRADAY_SWINGS, INDEPENDENT, 4, 1.0, all open sessions), per-symbol leg, clamps")
    void keys() {
        LtfRangeConfig d = LtfRangeConfig.fromEngineConfig("MNQ");
        assertThat(d.enabled()).isFalse();
        assertThat(LtfRangeConfig.enabledInConfig()).isFalse();
        assertThat(d.minLegTicks()).isEqualTo(120);
        assertThat(d.window()).isEqualTo("INTRADAY_SWINGS");
        assertThat(d.gating()).isEqualTo(LtfRangeConfig.Gating.INDEPENDENT);
        assertThat(d.maxPerDay()).isEqualTo(4);
        assertThat(d.riskFraction()).isEqualTo(1.0);
        assertThat(d.sessions()).containsExactlyInAnyOrder(SessionWindow.ASIA, SessionWindow.LONDON,
                SessionWindow.PRE_NY, SessionWindow.NY_AM, SessionWindow.NY_LUNCH, SessionWindow.NY_PM,
                SessionWindow.PRE_ASIA);
        assertThat(d.sessionAllowed(SessionWindow.NO_ENTRY)).isFalse();
        assertThat(d.sessionAllowed(SessionWindow.WEEKEND)).isFalse();
        System.setProperty("range.ltf.minLegTicks", "200");
        System.setProperty("range.ltf.minLegTicks.MGC", "80");
        System.setProperty("range.ltf.gating", "HTF_ALIGNED");
        System.setProperty("range.ltf.riskFraction", "3");
        System.setProperty("range.ltf.sessions", "NY_AM,NO_ENTRY,WEEKEND");
        LtfRangeConfig c = LtfRangeConfig.fromEngineConfig("MNQ");
        assertThat(c.minLegTicks()).isEqualTo(200);
        assertThat(LtfRangeConfig.fromEngineConfig("MGC").minLegTicks()).isEqualTo(80);
        assertThat(c.gating()).isEqualTo(LtfRangeConfig.Gating.HTF_ALIGNED);
        assertThat(c.riskFraction()).isEqualTo(1.0);                     // never more than the normal budget
        assertThat(c.sessions()).containsExactly(SessionWindow.NY_AM);   // NO_ENTRY / WEEKEND dropped
    }

    @Test
    @DisplayName("maxPerDay counts per CME trading day (18:00 ET roll)")
    void dailyQuota() {
        LtfRangeConfig.DailyQuota q = new LtfRangeConfig.DailyQuota();
        q.record(et("2026-09-28T20:00"));   // trading day 09-29
        q.record(et("2026-09-29T10:00"));   // trading day 09-29
        assertThat(q.left(et("2026-09-29T11:00"), 2)).isFalse();
        assertThat(q.left(et("2026-09-29T18:30"), 2)).isTrue();   // next trading day
        assertThat(q.left(et("2026-09-29T11:00"), 3)).isTrue();
    }

    @Test
    @DisplayName("flag OFF (default): the runner builds no LTF machine; ON builds one (unregistered, tagged LTF)")
    void flagOffBuildsNothing() {
        StdvOteRunnerStrategy off = new StdvOteRunnerStrategy("MNQ", "MES", null);
        assertThat(off.getLtfContext()).isNull();
        assertThat(off.ltfForTest()).isNull();
        assertThat(off.getLtfRange()).isEqualTo(DealingRangeTracker.Snapshot.EMPTY);
        assertThat(off.machine()).isEqualTo("HTF");
        off.shutdown();
        System.setProperty("range.ltf.enabled", "true");
        StdvOteRunnerStrategy on = new StdvOteRunnerStrategy("MNQ", "MES", null);
        assertThat(on.getLtfContext()).isNotNull();
        assertThat(on.getLtfContext().machine).isEqualTo("LTF");
        assertThat(on.ltfForTest().machine()).isEqualTo("LTF");
        assertThat(on.getSetupContext().machine).isEqualTo("HTF");
        // the registry / API keep reading the HTF machine
        assertThat(StdvOteRegistry.get("MNQ").orElseThrow().getSetupContext()).isSameAs(on.getSetupContext());
        on.shutdown();
    }
}
