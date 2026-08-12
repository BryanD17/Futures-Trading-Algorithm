package com.topstep.trading.chart;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * §S9 TREND-SHIFT OTE ANCHORING.
 *
 * <p>The engine only ever sees 1m candles, so every 30m bar here is fed as
 * thirty of them — the same path backfill and live use. That matters: the
 * lifecycle advances on 1m bars while anchors only move on confirmed 30m
 * pivots, and testing the two together is the only way the post-ARM rule is
 * actually exercised.
 *
 * <p>Price path (30m bars, index → shape):
 * <pre>
 *   0..9    plateau                       h 21105 / l 21095
 *   10      pivot HIGH #1                 h 21150
 *   11..20  plateau
 *   21      pivot LOW                     l 21000        ← the trend's origin
 *   22..31  plateau
 *   32      pivot HIGH #2  (&gt; #1)         h 21200        ← the BULL SHIFT
 *   33..42  plateau                                      ← shift confirms here
 * </pre>
 * A pivot needs ten bars on EACH side, so each one is confirmed exactly ten
 * 30m bars after it printed — which is the point: an anchor never repaints.
 */
class ChartEngineTrendShiftTest {

    private static final String SYM = "MNQ";

    private final ChartEngine engine = new ChartEngine();
    private Instant cursor = Instant.parse("2026-08-10T13:00:00Z");   // :00 aligned
    private double lastClose = 21100;

    private ChartEngineTrendShiftTest useTrendShift() {
        engine.registerInstrument(SYM, 0.25);
        engine.configureAnchoring(SYM, AnchorMode.TREND_SHIFT, null);
        return this;
    }

    /** Feed one 30m bar as thirty 1m candles with the given OHLC. */
    private void bar30(double o, double h, double l, double c) {
        for (int i = 0; i < 30; i++) {
            Candle candle = switch (i) {
                case 0 -> new Candle(SYM, cursor, o, o, o, o, 10L);
                case 1 -> new Candle(SYM, cursor, o, h, o, h, 10L);
                case 2 -> new Candle(SYM, cursor, h, h, l, c, 10L);
                default -> new Candle(SYM, cursor, c, c, c, c, 10L);
            };
            engine.onCandle(candle);
            cursor = cursor.plusSeconds(60);
        }
        lastClose = c;
    }

    /**
     * One extra 1m candle to OPEN the next window, which is what finalises the
     * previous 30m bar. Without it the last bar stays in progress and the
     * confirmed-pivot index sits one bar short — the aggregator emits a bar
     * when the next window starts, not when the last minute of a window closes.
     */
    private void flush() {
        engine.onCandle(new Candle(SYM, cursor, lastClose, lastClose, lastClose,
                lastClose, 10L));
        cursor = cursor.plusSeconds(60);
    }

    private void plateau(int bars) {
        for (int i = 0; i < bars; i++) bar30(21100, 21105, 21095, 21100);
    }

    /** Everything up to and including the confirmed BULL shift. */
    private void bullShiftSequence() {
        plateau(10);                             // 0..9
        bar30(21100, 21150, 21095, 21120);       // 10  pivot high #1
        plateau(10);                             // 11..20
        bar30(21100, 21105, 21000, 21050);       // 21  pivot low  (origin)
        plateau(10);                             // 22..31
        bar30(21100, 21200, 21095, 21180);       // 32  pivot high #2 → shift
        plateau(10);                             // 33..42 → confirms it
        flush();
    }

    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("BULL shift anchors origin at the swing LOW that started the leg, extreme at the new high")
    void shiftAnchoring() {
        useTrendShift();
        bullShiftSequence();

        Optional<OteZoneSnapshot> z = engine.getActiveOteZone(SYM);
        assertThat(z).isPresent();
        assertThat(z.get().bullish()).isTrue();
        assertThat(z.get().legOrigin()).isEqualTo(21000.0);
        assertThat(z.get().legExtreme()).isEqualTo(21200.0);
        assertThat(z.get().anchorMode()).isEqualTo(AnchorMode.TREND_SHIFT);
        assertThat(z.get().state()).isEqualTo(OteState.FORMING);
        // 0.0 at the extreme, 1.0 at the origin — the engine's existing convention.
        assertThat(z.get().oteStart()).isEqualTo(21076.0, within(1e-9));
        assertThat(z.get().oteSweet()).isEqualTo(21059.0, within(1e-9));
    }

    @Test
    @DisplayName("Pre-ARM extension RE-STRETCHES the fibs and invalidates nothing")
    void extensionBeforeArmRestretches() {
        useTrendShift();
        bullShiftSequence();
        assertThat(engine.getActiveOteZone(SYM).orElseThrow().state())
                .isEqualTo(OteState.FORMING);

        bar30(21100, 21260, 21095, 21240);       // 43  a higher confirmed high
        plateau(10);                             // 44..53 → confirms it
        flush();

        OteZoneSnapshot z = engine.getActiveOteZone(SYM).orElseThrow();
        assertThat(z.legExtreme()).isEqualTo(21260.0);
        assertThat(z.legOrigin()).isEqualTo(21000.0);       // origin is unchanged
        assertThat(z.state()).isEqualTo(OteState.FORMING);
        // Nothing was rewritten, so nothing was invalidated.
        assertThat(engine.getLastInvalidatedZone(SYM)).isEmpty();
    }

    @Test
    @DisplayName("POST-ARM extension INVALIDATES the tagged zone and forms a fresh one (Appendix E7)")
    void extensionAfterArmInvalidatesAndReforms() {
        useTrendShift();
        bullShiftSequence();

        // Dip into the 0.62–0.79 band (0.62 sits at 21076) and close back above
        // it: the zone is now a historical fact about prices actually traded.
        for (int i = 0; i < 10; i++) bar30(21090, 21095, 21070, 21085);   // 43..52
        assertThat(engine.getActiveOteZone(SYM).orElseThrow().state())
                .isIn(OteState.ARMED, OteState.REACTED);

        bar30(21085, 21260, 21080, 21240);       // 53  higher confirmed high
        plateau(10);                             // 54..63 → confirms it
        flush();

        OteZoneSnapshot dead = engine.getLastInvalidatedZone(SYM).orElseThrow();
        assertThat(dead.state()).isEqualTo(OteState.INVALIDATED);
        assertThat(dead.legExtreme()).isEqualTo(21200.0);   // the OLD anchors, preserved

        OteZoneSnapshot fresh = engine.getActiveOteZone(SYM).orElseThrow();
        assertThat(fresh.state()).isEqualTo(OteState.FORMING);
        assertThat(fresh.legExtreme()).isEqualTo(21260.0);
        assertThat(fresh.taggedAt()).isNull();              // a genuinely new zone
    }

    @Test
    @DisplayName("An OPPOSITE trend shift invalidates the live zone and anchors the other way")
    void oppositeShiftInvalidates() {
        useTrendShift();
        bullShiftSequence();

        bar30(21100, 21105, 20950, 21010);       // 43  pivot low BELOW 21000
        plateau(10);                             // 44..53 → confirms it
        flush();

        OteZoneSnapshot dead = engine.getLastInvalidatedZone(SYM).orElseThrow();
        assertThat(dead.bullish()).isTrue();
        assertThat(dead.state()).isEqualTo(OteState.INVALIDATED);

        OteZoneSnapshot bear = engine.getActiveOteZone(SYM).orElseThrow();
        assertThat(bear.bullish()).isFalse();
        assertThat(bear.legOrigin()).isEqualTo(21200.0);    // the last swing HIGH
        assertThat(bear.legExtreme()).isEqualTo(20950.0);
        assertThat(bear.anchorMode()).isEqualTo(AnchorMode.TREND_SHIFT);
    }

    @Test
    @DisplayName("Close through the ORIGIN still invalidates — the existing rule is untouched")
    void closeThroughOriginStillInvalidates() {
        useTrendShift();
        bullShiftSequence();

        bar30(20990, 21000, 20985, 20990);       // closes below the 21000 origin
        assertThat(engine.getActiveOteZone(SYM)).isEmpty();
    }

    @Test
    @DisplayName("DEFAULT is FRACTAL_LEG and it is the pre-V4 behaviour, tagged as such")
    void fractalLegRemainsTheDefault() {
        ChartEngine defaultEngine = new ChartEngine();
        assertThat(defaultEngine.anchorModeFor(SYM)).isEqualTo(AnchorMode.FRACTAL_LEG);
        assertThat(defaultEngine.bandFor(SYM).isEngineDefault()).isTrue();
        assertThat(defaultEngine.isAnchorCompareEnabled()).isFalse();

        engine.registerInstrument(SYM, 0.25);    // no configureAnchoring call
        bullShiftSequence();
        OteZoneSnapshot z = engine.getActiveOteZone(SYM).orElseThrow();
        assertThat(z.anchorMode()).isEqualTo(AnchorMode.FRACTAL_LEG);
        assertThat(z.toApiMap()).containsEntry("anchorMode", "FRACTAL_LEG");
    }

    @Test
    @DisplayName("The band is configurable and travels WITH the zone")
    void bandIsConfigurableAndCarried() {
        engine.registerInstrument(SYM, 0.25);
        engine.configureAnchoring(SYM, AnchorMode.TREND_SHIFT, OteBand.parse("0.618,0.786"));
        bullShiftSequence();

        OteZoneSnapshot z = engine.getActiveOteZone(SYM).orElseThrow();
        assertThat(z.band().start()).isEqualTo(0.618);
        assertThat(z.band().end()).isEqualTo(0.786);
        assertThat(z.band().sweet()).isEqualTo(0.702, within(1e-9));
        assertThat(z.oteStart()).isEqualTo(21200 - 200 * 0.618, within(1e-9));
        assertThat(z.toApiMap()).containsEntry("bandRatios", "0.618,0.786");

        // Malformed or absent input falls back rather than throwing.
        assertThat(OteBand.parse("nonsense").isEngineDefault()).isTrue();
        assertThat(OteBand.parse(null).isEngineDefault()).isTrue();
        assertThat(OteBand.parse("0.9,0.5").isEngineDefault()).isTrue();
    }

    @Test
    @DisplayName("chart.anchorCompare runs BOTH modes; the shadow never becomes the answer")
    void anchorCompareRunsBothModes() {
        useTrendShift();
        engine.setAnchorCompare(true);
        bullShiftSequence();

        OteZoneSnapshot primary = engine.getActiveOteZone(SYM).orElseThrow();
        OteZoneSnapshot shadow = engine.getShadowOteZone(SYM).orElseThrow();
        assertThat(primary.anchorMode()).isEqualTo(AnchorMode.TREND_SHIFT);
        assertThat(shadow.anchorMode()).isEqualTo(AnchorMode.FRACTAL_LEG);

        // On this deliberately clean tape the two modes AGREE on the anchors.
        // That is a legitimate outcome and exactly the kind of evidence the
        // compare flag exists to gather — the flag answers a question, it does
        // not assume an answer.
        assertThat(shadow.legOrigin()).isEqualTo(primary.legOrigin());
        assertThat(shadow.legExtreme()).isEqualTo(primary.legExtreme());

        // Now the case that motivated §S9: a shallow dip creates a NEW fractal
        // leg (2-bar swings) while no 10-bar pivot has confirmed, so the
        // fractal mode re-anchors onto a micro leg and flips direction while
        // trend-shift holds the real trend leg.
        bar30(21100, 21105, 21050, 21060);
        plateau(4);
        flush();

        OteZoneSnapshot heldTrend = engine.getActiveOteZone(SYM).orElseThrow();
        OteZoneSnapshot microLeg = engine.getShadowOteZone(SYM).orElseThrow();
        assertThat(heldTrend.bullish()).isTrue();
        assertThat(heldTrend.legOrigin()).isEqualTo(21000.0);
        assertThat(microLeg.bullish()).isFalse();
        assertThat(microLeg.legExtreme()).isEqualTo(21050.0);

        // And getActiveOteZone — what every consumer reads — is still primary.
        assertThat(engine.getActiveOteZone(SYM).orElseThrow().anchorMode())
                .isEqualTo(AnchorMode.TREND_SHIFT);
    }

    @Test
    @DisplayName("Determinism: the same feed produces the same anchors twice")
    void determinism() {
        useTrendShift();
        bullShiftSequence();
        OteZoneSnapshot first = engine.getActiveOteZone(SYM).orElseThrow();

        ChartEngineTrendShiftTest second = new ChartEngineTrendShiftTest();
        second.useTrendShift();
        second.bullShiftSequence();
        OteZoneSnapshot again = second.engine.getActiveOteZone(SYM).orElseThrow();

        assertThat(again).isEqualTo(first);
    }

    @Test
    @DisplayName("ABSTAIN while cold: a shift with no opposite swing yet draws nothing")
    void abstainsWithoutAnOrigin() {
        useTrendShift();
        // Two rising pivot highs and never a pivot low: the regime flips, but
        // there is no origin to anchor to, so no zone is invented.
        plateau(10);
        bar30(21100, 21150, 21095, 21120);
        plateau(10);
        bar30(21100, 21200, 21095, 21180);
        plateau(10);
        flush();

        assertThat(engine.getActiveOteZone(SYM)).isEmpty();
    }
}
