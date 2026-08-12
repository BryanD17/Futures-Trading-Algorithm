package com.topstep.trading.chart;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of a 30m OTE (Optimal Trade Entry) fib zone — the
 * in-memory equivalent of the retracement tool drawn on the TopstepX chart.
 *
 * <p>Fib convention matches the screenshot: 0.0 sits at the LEG EXTREME
 * (the high of an up-leg), 1.0 at the LEG ORIGIN (the low of an up-leg).
 * So fib(0.705) for a bullish leg is 70.5% of the way back DOWN from the
 * high toward the low — the OTE sweet spot.
 *
 * <p>V4 Agent 05 added two components, {@code band} and {@code anchorMode}, so
 * a zone carries the band it was armed on and the strategy that anchored it.
 * The pre-V4 8-argument constructor still exists and defaults both, which is
 * why every existing consumer and test compiles and behaves unchanged.
 */
public record OteZoneSnapshot(
        String symbol,
        boolean bullish,
        double legOrigin,      // the 1.0 level (low of up-leg / high of down-leg)
        double legExtreme,     // the 0.0 level (high of up-leg / low of down-leg)
        Instant originTime,
        Instant extremeTime,
        OteState state,
        Instant taggedAt,      // when price first entered the band; null until ARMED
        OteBand band,          // the retracement band this zone is armed on
        AnchorMode anchorMode  // which leg-selection strategy produced it
) {

    /** Pre-V4 shape: engine-default band, fractal-leg anchoring. */
    public OteZoneSnapshot(String symbol, boolean bullish, double legOrigin, double legExtreme,
                           Instant originTime, Instant extremeTime, OteState state,
                           Instant taggedAt) {
        this(symbol, bullish, legOrigin, legExtreme, originTime, extremeTime, state, taggedAt,
                OteBand.engineDefault(), AnchorMode.FRACTAL_LEG);
    }

    public static OteZoneSnapshot forLeg(String symbol, boolean bullish,
                                         double origin, double extreme,
                                         Instant originTime, Instant extremeTime) {
        return forLeg(symbol, bullish, origin, extreme, originTime, extremeTime,
                OteBand.engineDefault(), AnchorMode.FRACTAL_LEG);
    }

    /** V4: build a zone on a specific band, tagged with the mode that anchored it. */
    public static OteZoneSnapshot forLeg(String symbol, boolean bullish,
                                         double origin, double extreme,
                                         Instant originTime, Instant extremeTime,
                                         OteBand band, AnchorMode anchorMode) {
        return new OteZoneSnapshot(symbol, bullish, origin, extreme,
                originTime, extremeTime, OteState.FORMING, null, band, anchorMode);
    }

    /** Price at a given fib ratio (0.0 = extreme, 1.0 = origin). */
    public double fib(double ratio) {
        return legExtreme + (legOrigin - legExtreme) * ratio;
    }

    public double oteStart() { return fib(band.start()); }
    public double oteSweet() { return fib(band.sweet()); }
    public double oteEnd()   { return fib(band.end()); }

    /** Suggested protective stop: just beyond the leg origin (1.0). */
    public double protectiveStop(double tickSize, int bufferTicks) {
        double buf = tickSize * Math.max(0, bufferTicks);
        return bullish ? legOrigin - buf : legOrigin + buf;
    }

    /** First natural target: return to the leg extreme (0.0). */
    public double primaryTarget() { return legExtreme; }

    public OteZoneSnapshot withState(OteState newState) {
        return new OteZoneSnapshot(symbol, bullish, legOrigin, legExtreme,
                originTime, extremeTime, newState, taggedAt, band, anchorMode);
    }

    public OteZoneSnapshot withTagTime(Instant t) {
        return new OteZoneSnapshot(symbol, bullish, legOrigin, legExtreme,
                originTime, extremeTime, OteState.ARMED,
                taggedAt == null ? t : taggedAt, band, anchorMode);
    }

    /** JSON-friendly map for the dashboard chart overlay. */
    public Map<String, Object> toApiMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", symbol);
        m.put("direction", bullish ? "BULLISH" : "BEARISH");
        m.put("state", state.name());
        m.put("legOrigin", legOrigin);
        m.put("legExtreme", legExtreme);
        m.put("fib_0.5", fib(0.5));
        m.put("fib_0.62", fib(ChartEngine.OTE_START));
        m.put("fib_0.705", fib(ChartEngine.OTE_SWEET));
        m.put("fib_0.786", fib(0.786));
        m.put("fib_1.0", legOrigin);
        m.put("originTime", originTime == null ? null : originTime.toString());
        m.put("extremeTime", extremeTime == null ? null : extremeTime.toString());
        m.put("taggedAt", taggedAt == null ? null : taggedAt.toString());
        // V4 Agent 05: which strategy anchored this zone, and the band it is
        // actually armed on. The fixed fib_* keys above stay for backwards
        // compatibility; bandStart/bandSweet/bandEnd are the live numbers when
        // the owner has overridden chart.oteBand.
        m.put("anchorMode", anchorMode.name());
        m.put("bandStart", oteStart());
        m.put("bandSweet", oteSweet());
        m.put("bandEnd", oteEnd());
        m.put("bandRatios", band.start() + "," + band.end());
        return m;
    }
}
