package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V5 Agent 05.8 — the opt-in counter-trend scalp rules on the owner's LIVE
 * case (2026-09-29 05:34 ET, LONDON): MNQ dealing range BULLISH
 * 30371.75 -> 30635.00, EQ 30503.375, premium OTE band 30534.50-30578.75,
 * discount OTE band 30428.00-30472.25.
 */
@DisplayName("V5 Agent 05.8 — counter-trend scalp (premium sweep to equilibrium), rules")
class CounterTrendScalpTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final double TICK = 0.25;
    private static final double LOW = 30371.75;
    private static final double HIGH = 30635.00;
    private static final double MIN_FIB = 0.705;

    private static final CounterTrendScalp.Config ON = new CounterTrendScalp.Config(true,
            CounterTrendScalp.Config.parseSessions(CounterTrendScalp.DEFAULT_SESSIONS),
            400, 0.5, 2);

    @AfterEach
    void clear() {
        for (String k : new String[] {"entry.counterTrendScalp", "entry.counterTrend.sessions",
                "entry.counterTrend.minRangeTicks", "entry.counterTrend.maxRiskFraction",
                "entry.counterTrend.maxPerDay"}) {
            System.clearProperty(k);
        }
    }

    private static Instant et(String iso) {
        return LocalDateTime.parse(iso).atZone(ET).toInstant();
    }

    private static LiquiditySweep high(double level) {
        return new LiquiditySweep(false, level, et("2026-09-29T05:30"), false);
    }

    private static LiquiditySweep low(double level) {
        return new LiquiditySweep(true, level, et("2026-09-29T05:30"), false);
    }

    private static CounterTrendScalp.Verdict shortVerdict(double level, double ext, int score, SessionWindow w) {
        return CounterTrendScalp.qualify(ON, TICK, HIGH, LOW, MarketBias.BULLISH, true, MarketBias.BULLISH,
                high(level), score, 5, ext, MIN_FIB, w, 30550.0);
    }

    // ── short in a BULLISH range ─────────────────────────────────────────

    @Test
    @DisplayName("short vs a BULLISH range arms ONLY on a high sweep inside the premium OTE band [30534.5, 30578.75]")
    void shortArmsOnlyFromThePremiumBand() {
        CounterTrendScalp.Verdict ok = shortVerdict(30560.0, 30566.0, 6, SessionWindow.LONDON);
        assertThat(ok.armed()).as(ok.reason()).isTrue();
        OteZone z = ok.zone();
        assertThat(z.bullish()).isFalse();
        assertThat(Math.min(z.f62(), z.f79())).isEqualTo(30534.5);
        assertThat(Math.max(z.f62(), z.f79())).isEqualTo(30578.75);
        assertThat(z.one00()).isEqualTo(HIGH);

        // Premium half but below the band: a random fade, refused with the numbers.
        CounterTrendScalp.Verdict belowBand = shortVerdict(30520.0, 30560.0, 6, SessionWindow.LONDON);
        assertThat(belowBand.armed()).isFalse();
        assertThat(belowBand.reason()).isEqualTo("CT: sweep 30520.0 not in the premium OTE band [30534.5,30578.75]");
        assertThat(belowBand.a()).isEqualTo(30520.0);
        assertThat(belowBand.b()).isEqualTo(30534.5);
        // Discount half.
        CounterTrendScalp.Verdict discount = shortVerdict(30490.0, 30560.0, 6, SessionWindow.LONDON);
        assertThat(discount.reason()).startsWith("CT: sweep 30490.0 not in the premium half (eq 30503.375)");
        // Above the band (beyond the 0.786).
        assertThat(shortVerdict(30590.0, 30595.0, 6, SessionWindow.LONDON).reason())
                .isEqualTo("CT: sweep 30590.0 not in the premium OTE band [30534.5,30578.75]");
        // In the band but the retrace never reached the 0.705 (30557.34 -> 30557.25).
        assertThat(shortVerdict(30540.0, 30550.0, 6, SessionWindow.LONDON).reason())
                .isEqualTo("CT: sweep extreme 30550.0 short of 0.705 (30557.25)");
        // Extreme beyond the band.
        assertThat(shortVerdict(30560.0, 30600.0, 6, SessionWindow.LONDON).reason())
                .startsWith("CT: sweep extreme 30600.0 outside the OTE band");
        // A LOW sweep never primes the short.
        CounterTrendScalp.Verdict lowSweep = CounterTrendScalp.qualify(ON, TICK, HIGH, LOW, MarketBias.BULLISH,
                true, MarketBias.BULLISH, low(30560.0), 6, 5, 30560.0, MIN_FIB, SessionWindow.LONDON, 30550.0);
        assertThat(lowSweep.reason()).isEqualTo("CT: LOW sweep does not prime a short");
        // M4's floor is unchanged.
        assertThat(shortVerdict(30560.0, 30566.0, 4, SessionWindow.LONDON).reason())
                .startsWith("CT: raid score 4 < floor 5");
        // The bias must agree with the range it fades.
        CounterTrendScalp.Verdict neutral = CounterTrendScalp.qualify(ON, TICK, HIGH, LOW, MarketBias.BULLISH,
                true, MarketBias.NEUTRAL, high(30560.0), 6, 5, 30566.0, MIN_FIB, SessionWindow.LONDON, 30550.0);
        assertThat(neutral.reason()).isEqualTo("CT: bias NEUTRAL disagrees with range BULLISH");
        // A close beyond the range 1.0.
        CounterTrendScalp.Verdict through = CounterTrendScalp.qualify(ON, TICK, HIGH, LOW, MarketBias.BULLISH,
                true, MarketBias.BULLISH, high(30560.0), 6, 5, 30566.0, MIN_FIB, SessionWindow.LONDON, 30636.0);
        assertThat(through.reason()).startsWith("CT: close 30636.0 beyond the range 1.0");
    }

    @Test
    @DisplayName("the trigger is the IMPULSE_LEG rejection: close back below the swept level with a down-close")
    void rejectionTrigger() {
        OteZone z = shortVerdict(30560.0, 30566.0, 6, SessionWindow.LONDON).zone();
        Candle upClose = new Candle("MNQ", et("2026-09-29T05:31"), 30552.0, 30565.0, 30550.0, 30561.0, 100);
        Candle downCloseAbove = new Candle("MNQ", et("2026-09-29T05:31"), 30566.0, 30566.0, 30560.5, 30561.0, 100);
        Candle rejection = new Candle("MNQ", et("2026-09-29T05:32"), 30562.0, 30565.0, 30548.0, 30551.0, 100);
        assertThat(OteSetupDriver.impulseReaction(z, upClose, 30560.0)).isNull();
        assertThat(OteSetupDriver.impulseReaction(z, downCloseAbove, 30560.0)).isNull();
        assertThat(OteSetupDriver.impulseReaction(z, rejection, 30560.0))
                .isEqualTo("rejection: close back below swept 30560.0");
    }

    @Test
    @DisplayName("plan: T1 = the range equilibrium, FINAL = top of the discount band, stop beyond the sweep high + buffer")
    void planTargetsEquilibriumThenDiscountBandTop() {
        OteZone z = shortVerdict(30560.0, 30566.0, 6, SessionWindow.LONDON).zone();
        // ICT order block before the raid bar: up-close [30550.00, 30562.00] -> entry at its mean 30556.00.
        PdArray ob = new PdArray("OB", false, 30550.0, 30562.0, et("2026-09-29T05:29"));
        CounterTrendScalp.Plan p = CounterTrendScalp.plan(z, ob, 30566.0, TICK, 4, 5.0);
        double eq = (HIGH + LOW) / 2.0;
        assertThat(p.tradeBullish()).isFalse();
        assertThat(p.entry()).isEqualTo(30556.0);
        assertThat(p.t1()).as("T1 = equilibrium (tick-rounded)").isEqualTo(Math.round(eq / TICK) * TICK);
        assertThat(p.t1()).isEqualTo(30503.5);
        OteZone discount = new OteEntryCalculator().buildZone(LOW, HIGH, true, TICK).orElseThrow();
        assertThat(p.finalTarget()).as("final = the discount band's near edge").isEqualTo(Math.max(discount.f62(), discount.f79()));
        assertThat(p.finalTarget()).isEqualTo(30472.25);
        // Stop: max(0.786 30578.75, OB top 30562, sweep high 30566) + 4 ticks.
        assertThat(p.stop()).isEqualTo(30579.75);
        assertThat(p.rrT1()).isCloseTo((30556.0 - 30503.5) / (30579.75 - 30556.0), within(1e-9));
        assertThat(p.target()).isEqualTo(p.finalTarget());
        // Never beyond the discount band's edge: a ceiling that the final breaks keeps T1.
        CounterTrendScalp.Plan capped = CounterTrendScalp.plan(z, ob, 30566.0, TICK, 4, 2.5);
        assertThat(capped.target()).isEqualTo(capped.t1());
    }

    // ── long in a BEARISH range (mirror) ────────────────────────────────

    @Test
    @DisplayName("long vs a BEARISH range mirrors: low sweep in the discount band [30428.0, 30472.25]; T1 = eq, final = bottom of the premium band")
    void longInBearishRangeMirrors() {
        CounterTrendScalp.Verdict v = CounterTrendScalp.qualify(ON, TICK, HIGH, LOW, MarketBias.BEARISH, true,
                MarketBias.BEARISH, low(30450.0), 6, 5, 30446.0, MIN_FIB, SessionWindow.ASIA, 30455.0);
        assertThat(v.armed()).as(v.reason()).isTrue();
        OteZone z = v.zone();
        assertThat(z.bullish()).isTrue();
        assertThat(Math.min(z.f62(), z.f79())).isEqualTo(30428.0);
        assertThat(Math.max(z.f62(), z.f79())).isEqualTo(30472.25);
        assertThat(z.one00()).isEqualTo(LOW);
        // High sweep / premium: refused.
        assertThat(CounterTrendScalp.qualify(ON, TICK, HIGH, LOW, MarketBias.BEARISH, true, MarketBias.BEARISH,
                high(30450.0), 6, 5, 30446.0, MIN_FIB, SessionWindow.ASIA, 30455.0).reason())
                .isEqualTo("CT: HIGH sweep does not prime a long");
        assertThat(CounterTrendScalp.qualify(ON, TICK, HIGH, LOW, MarketBias.BEARISH, true, MarketBias.BEARISH,
                low(30560.0), 6, 5, 30556.0, MIN_FIB, SessionWindow.ASIA, 30565.0).reason())
                .startsWith("CT: sweep 30560.0 not in the discount half");
        PdArray ob = new PdArray("OB", true, 30444.0, 30456.0, et("2026-09-29T05:29"));
        CounterTrendScalp.Plan p = CounterTrendScalp.plan(z, ob, 30446.0, TICK, 4, 5.0);
        assertThat(p.tradeBullish()).isTrue();
        assertThat(p.entry()).isEqualTo(30450.0);
        assertThat(p.t1()).isEqualTo(30503.5);
        assertThat(p.finalTarget()).isEqualTo(30534.5);
        // Stop: min(0.786 30428.0, OB bottom 30444, sweep low 30446) - 4 ticks.
        assertThat(p.stop()).isEqualTo(30427.0);
    }

    // ── bounds ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("refused below entry.counterTrend.minRangeTicks (a 60-pt range = 240 ticks < 400)")
    void refusedBelowMinRangeTicks() {
        CounterTrendScalp.Verdict v = CounterTrendScalp.qualify(ON, TICK, 30635.0, 30575.0, MarketBias.BULLISH,
                true, MarketBias.BULLISH, high(30620.0), 9, 5, 30622.0, MIN_FIB, SessionWindow.LONDON, 30610.0);
        assertThat(v.armed()).isFalse();
        assertThat(v.reason()).isEqualTo("CT: range 240.00 ticks < entry.counterTrend.minRangeTicks 400");
        assertThat(v.a()).isEqualTo(240.0);
        assertThat(v.b()).isEqualTo(400.0);
        // The same geometry passes when the owner lowers the key.
        CounterTrendScalp.Config low = new CounterTrendScalp.Config(true, ON.sessions(), 200, 0.5, 2);
        CounterTrendScalp.Verdict ok = CounterTrendScalp.qualify(low, TICK, 30635.0, 30575.0, MarketBias.BULLISH,
                true, MarketBias.BULLISH, high(30620.0), 9, 5, 30622.0, MIN_FIB, SessionWindow.LONDON, 30610.0);
        assertThat(ok.armed()).as(ok.reason()).isTrue();
    }

    @Test
    @DisplayName("refused outside the allowed sessions; NO_ENTRY / WEEKEND can never be enabled; NY opt-in")
    void sessions() {
        assertThat(ON.sessions()).containsExactlyInAnyOrder(SessionWindow.ASIA, SessionWindow.LONDON, SessionWindow.PRE_NY);
        for (SessionWindow w : new SessionWindow[] {SessionWindow.NY_AM, SessionWindow.NY_LUNCH, SessionWindow.NY_PM,
                SessionWindow.PRE_ASIA, SessionWindow.NO_ENTRY, SessionWindow.WEEKEND}) {
            CounterTrendScalp.Verdict v = shortVerdict(30560.0, 30566.0, 6, w);
            assertThat(v.armed()).as(w.name()).isFalse();
            assertThat(v.reason()).startsWith("CT: session " + w + " not in entry.counterTrend.sessions");
        }
        for (SessionWindow w : new SessionWindow[] {SessionWindow.ASIA, SessionWindow.LONDON, SessionWindow.PRE_NY}) {
            assertThat(shortVerdict(30560.0, 30566.0, 6, w).armed()).as(w.name()).isTrue();
        }
        // Configuring the sacred windows is ignored.
        assertThat(CounterTrendScalp.Config.parseSessions("NO_ENTRY,WEEKEND,LONDON,bogus"))
                .containsExactly(SessionWindow.LONDON);
        CounterTrendScalp.Config forced = new CounterTrendScalp.Config(true,
                EnumSet.of(SessionWindow.NO_ENTRY, SessionWindow.WEEKEND, SessionWindow.NY_AM), 400, 0.5, 2);
        assertThat(forced.sessions()).containsExactly(SessionWindow.NY_AM);
        assertThat(forced.sessionAllowed(SessionWindow.NO_ENTRY)).isFalse();
        assertThat(forced.sessionAllowed(SessionWindow.WEEKEND)).isFalse();
        assertThat(forced.sessionAllowed(SessionWindow.NY_AM)).isTrue();
        // NY opt-in through the key.
        System.setProperty("entry.counterTrend.sessions", "ASIA,LONDON,PRE_NY,NY_AM,NY_LUNCH,NY_PM");
        assertThat(CounterTrendScalp.Config.fromEngineConfig().sessions()).hasSize(6)
                .doesNotContain(SessionWindow.NO_ENTRY, SessionWindow.WEEKEND, SessionWindow.PRE_ASIA);
    }

    @Test
    @DisplayName("maxPerDay: 2 emissions per CME trading day (18:00 ET roll), then refused until the next day")
    void maxPerDay() {
        CounterTrendScalp ct = new CounterTrendScalp("MNQ", ON, TICK);
        Instant asia = et("2026-09-28T20:00");      // trading day 09-29
        Instant london = et("2026-09-29T04:00");    // trading day 09-29
        Instant nextDay = et("2026-09-29T19:00");   // trading day 09-30
        assertThat(CounterTrendScalp.tradingDay(asia)).isEqualTo(CounterTrendScalp.tradingDay(london));
        assertThat(ct.quotaLeft(asia)).isTrue();
        ct.onEmitted(asia);
        assertThat(ct.ownsPosition()).isTrue();
        ct.onFlat();
        assertThat(ct.quotaLeft(london)).isTrue();
        ct.onEmitted(london);
        ct.onFlat();
        assertThat(ct.emitsOn(london)).isEqualTo(2);
        assertThat(ct.quotaLeft(london)).as("3rd scalp of the day").isFalse();
        assertThat(ct.quotaLeft(nextDay)).isTrue();
        CounterTrendScalp none = new CounterTrendScalp("MNQ",
                new CounterTrendScalp.Config(true, ON.sessions(), 400, 0.5, 0), TICK);
        assertThat(none.quotaLeft(asia)).isFalse();
    }

    @Test
    @DisplayName("sizing: the existing risk-derived sizer on budget x maxRiskFraction (never more than the budget)")
    void halfBudget() {
        assertThat(CounterTrendScalp.scaledBudget(250.0, 0.5)).isEqualTo(125.0);
        assertThat(CounterTrendScalp.scaledBudget(250.0, 3.0)).isEqualTo(250.0);
        assertThat(new CounterTrendScalp.Config(true, ON.sessions(), 400, 7.0, 2).maxRiskFraction()).isEqualTo(1.0);
        // Live short: entry 30556.00, stop 30579.75 = 95 ticks x $0.50 = $47.50 / micro.
        StdvOteSizer.RiskSize full = StdvOteSizer.riskDerived(250.0, 30556.0, 30579.75, TICK, 0.5, 1, 20);
        StdvOteSizer.RiskSize half = StdvOteSizer.riskDerived(CounterTrendScalp.scaledBudget(250.0, 0.5),
                30556.0, 30579.75, TICK, 0.5, 1, 20);
        assertThat(full.contracts()).isEqualTo(5);
        assertThat(half.contracts()).isEqualTo(2);
        assertThat(half.contracts() * half.perContract()).isLessThanOrEqualTo(125.0);
    }

    @Test
    @DisplayName("flag OFF by default: the key reads false and the runner builds no scalp")
    void defaultOff() {
        assertThat(CounterTrendScalp.Config.enabledInConfig()).isFalse();
        CounterTrendScalp.Config d = CounterTrendScalp.Config.fromEngineConfig();
        assertThat(d.enabled()).isFalse();
        assertThat(d.minRangeTicks()).isEqualTo(400);
        assertThat(d.maxRiskFraction()).isEqualTo(0.5);
        assertThat(d.maxPerDay()).isEqualTo(2);
        assertThat(d.sessions()).containsExactlyInAnyOrder(SessionWindow.ASIA, SessionWindow.LONDON, SessionWindow.PRE_NY);
        StdvOteRunnerStrategy off = new StdvOteRunnerStrategy("MNQ", "MES", null);
        assertThat(off.counterTrendForTest()).isNull();
        assertThat(off.getCounterTrendContext()).isNull();
        StdvOteRegistry.unregister("MNQ");
        System.setProperty("entry.counterTrendScalp", "true");
        StdvOteRunnerStrategy on = new StdvOteRunnerStrategy("MNQ", "MES", null);
        assertThat(on.counterTrendForTest()).isNotNull();
        assertThat(on.getCounterTrendContext().entryKind).isEqualTo(CounterTrendScalp.ENTRY_KIND);
        StdvOteRegistry.unregister("MNQ");
    }

    // ── the validator: M2 = the counter-trend rule, the rest in the trade's direction ──

    private static SetupContext ctContext() {
        OteZone z = new OteEntryCalculator().buildZone(LOW, HIGH, false, TICK).orElseThrow();
        SetupContext c = new SetupContext();
        c.symbol = "MNQ";
        c.entryKind = CounterTrendScalp.ENTRY_KIND;
        c.state = SetupState.OTE_ARMED;
        c.htfBias = MarketBias.BULLISH;
        c.sessionWindow = SessionWindow.LONDON.name();
        c.killzoneOpen = true;
        c.sweep = high(30560.0);
        c.raidScore = 6;
        c.ctRangeTicks = (HIGH - LOW) / TICK;
        c.ctMinRangeTicks = 400;
        c.ctSweptLevel = 30560.0;
        c.ctBandLo = 30534.5;
        c.ctBandHi = 30578.75;
        c.ctRejectionOpen = 30562.0;
        c.ctRejectionClose = 30551.0;
        PdArray ob = new PdArray("OB", false, 30550.0, 30562.0, et("2026-09-29T05:29"));
        c.displacement = true;
        c.fvg = ob.asFairValueGap();
        c.m5LinkKind = "CT_REJECTION+OB";
        c.displacementAt = et("2026-09-29T05:30");
        c.mss = true;
        c.mssAt = et("2026-09-29T05:32");
        c.ote = z;
        c.pdArrayInOte = 30556.0;
        c.pdArrayKind = "OB";
        c.entry = 30556.0;
        c.stop = 30579.75;
        c.t1 = 30503.5;
        c.finalTarget = 30472.25;
        c.rrT1 = (30556.0 - 30503.5) / (30579.75 - 30556.0);
        c.rr = (30556.0 - 30472.25) / (30579.75 - 30556.0);
        c.tier = TradeTier.TIER_1;
        c.sizeRequest = 2;
        return c;
    }

    @Test
    @DisplayName("validator: a counter-trend short passes M1..M9 (M2 = the CT rule, M2b in the SHORT's direction)")
    void validatorPassesTheScalp() {
        MandatoryConfluenceValidator v = new MandatoryConfluenceValidator(null, null, null);
        v.setPremiumDiscountEvaluator(null);
        ValidationResult r = v.validateStdvOte(ctContext());
        assertThat(r.passed()).as(r.getFailures().toString()).isTrue();
        assertThat(r.getConfirmations().get(1)).startsWith("M2: COUNTER_TREND_SCALP short vs bias=BULLISH");
    }

    @Test
    @DisplayName("validator: the same short WITHOUT the CT entry kind still fails M2 (the with-trend chain is unchanged)")
    void withTrendChainUnchanged() {
        MandatoryConfluenceValidator v = new MandatoryConfluenceValidator(null, null, null);
        SetupContext c = ctContext();
        c.entryKind = null;
        ValidationResult r = v.validateStdvOte(c);
        assertThat(r.passed()).isFalse();
        assertThat(r.getFailures().get(0)).isEqualTo("M2: trade direction mismatches HTF bias");
    }

    @Test
    @DisplayName("validator: CT M2 re-checks its numbers (range ticks, band) and M5 the rejection")
    void validatorRechecksTheNumbers() {
        MandatoryConfluenceValidator v = new MandatoryConfluenceValidator(null, null, null);
        SetupContext small = ctContext();
        small.ctRangeTicks = 240;
        assertThat(v.validateStdvOte(small).getFailures().get(0)).isEqualTo("M2: CT range 240.0 ticks < min 400.0");
        SetupContext outside = ctContext();
        outside.ctSweptLevel = 30520.0;
        assertThat(v.validateStdvOte(outside).getFailures().get(0)).startsWith("M2: CT sweep 30520.0 not in the premium OTE band");
        SetupContext noRejection = ctContext();
        noRejection.ctRejectionClose = 30563.0;
        assertThat(v.validateStdvOte(noRejection).getFailures().get(0)).startsWith("M5: CT rejection not proven");
        SetupContext lowRr = ctContext();
        lowRr.rrT1 = 0.6;
        assertThat(v.validateStdvOte(lowRr).getFailures().get(0)).startsWith("M7: RR(T1) 0.60 < floor");
        SetupContext wrongWay = ctContext();
        wrongWay.htfBias = MarketBias.BEARISH;   // a "counter-trend" short in a BEARISH range is not counter-trend
        assertThat(v.validateStdvOte(wrongWay).getFailures().get(0)).startsWith("M2: CT trade direction must oppose HTF bias");
    }

    @Test
    @DisplayName("candidates: the ICT OB before the raid bar, the rejection wick, and arrays reaching the swept level")
    void candidatesAtTheSweep() {
        Candle up = new Candle("MNQ", et("2026-09-29T05:28"), 30548.0, 30559.0, 30546.0, 30557.0, 10);
        Candle down = new Candle("MNQ", et("2026-09-29T05:29"), 30557.0, 30558.0, 30550.0, 30552.0, 10);
        Candle raid = new Candle("MNQ", et("2026-09-29T05:30"), 30553.0, 30566.0, 30552.0, 30555.0, 10);
        List<PdArray> c = CounterTrendScalp.candidates(false, 30560.0, raid, List.of(up, down), Optional.empty(),
                List.of(new PdArray("FVG", false, 30540.0, 30545.0, et("2026-09-29T05:10")),
                        new PdArray("FVG", false, 30555.0, 30565.0, et("2026-09-29T05:15"))));
        assertThat(c).extracting(PdArray::kind).containsExactly("OB", "WICK", "FVG");
        assertThat(c.get(0).bottom()).isEqualTo(30546.0);   // the newest UP-close bar before the raid
        assertThat(c.get(1).top()).isEqualTo(30566.0);
        assertThat(c.get(2).top()).isEqualTo(30565.0);      // the 30540-30545 gap never reaches 30560
    }
}
