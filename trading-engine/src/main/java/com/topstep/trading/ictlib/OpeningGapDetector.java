package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.TradingSessionCalendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * §S5 — WEEKLY and DAILY OPENING GAPS: the untraded band between one session's
 * final price and the next session's open. These behave as persistent magnets,
 * which is why they have no fill-terminal state — they are evicted by retention
 * count, not consumed.
 *
 * <h2>One calendar, one truth</h2>
 * Sessions come from {@link TradingSessionCalendar} — the V3 D1 ladder's
 * calendar — and nothing else. 18:00 ET opens the next session; 17:00–18:00 ET
 * is the maintenance break and belongs to no session at all. Risk G-R7 is
 * exactly the bug of having a second calendar drift one session out of step, so
 * there is deliberately no local notion of "day" in this class.
 *
 * <h2>Weeks key on SESSIONS, not calendar days</h2>
 * A new trading week is detected when the new session's week (its Monday, via
 * {@code previousOrSame(MONDAY)} on the SESSION date) differs from the previous
 * session's. That is what makes a holiday-shifted open behave: if Monday never
 * trades, the first session that produces candles that week is the one the
 * weekly gap keys on, and the gap spans from the previous week's actual last
 * close. Nothing here looks at whether a date "is a Monday".
 *
 * <p>SPEC DECISION (§S5, zero-width gaps): when the previous close and the new
 * open are the SAME price there is no untraded band, so no detection is
 * created. A zero-width zone would render as a line the chart could never
 * "enter" and would occupy a retention slot that a real magnet needs.
 *
 * <p>Lifecycle: ACTIVE → TOUCHED (price enters the zone). No terminal state.
 */
public final class OpeningGapDetector implements FamilyDetector {

    private LocalDate sessionDate;
    private LocalDate weekKey;
    private double previousSessionClose = Double.NaN;

    @Override
    public DetectionType family() {
        return DetectionType.OPENING_GAP_DAILY;
    }

    @Override
    public void onBar(TimeframeSeries series, DetectionRegistry registry) {
        Candle c = series.at(0);
        if (c == null) return;

        advanceLifecycles(series, registry, DetectionType.OPENING_GAP_DAILY);
        advanceLifecycles(series, registry, DetectionType.OPENING_GAP_WEEKLY);

        // Maintenance-break bars belong to no session: they neither open one
        // nor supply a session's closing price.
        if (TradingSessionCalendar.inMaintenanceBreak(c.getTimestamp())) return;

        LocalDate sd = TradingSessionCalendar.sessionDate(c.getTimestamp());
        LocalDate wk = sd.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        if (sessionDate != null && !sd.equals(sessionDate)
                && !Double.isNaN(previousSessionClose)) {
            createGap(series, registry, DetectionType.OPENING_GAP_DAILY, sd);
            if (weekKey != null && !wk.equals(weekKey)) {
                createGap(series, registry, DetectionType.OPENING_GAP_WEEKLY, sd);
            }
        }

        sessionDate = sd;
        weekKey = wk;
        previousSessionClose = c.getClose();
    }

    private void createGap(TimeframeSeries series, DetectionRegistry registry,
                           DetectionType type, LocalDate newSession) {
        Candle c = series.at(0);
        double open = c.getOpen();
        double prev = previousSessionClose;
        if (open == prev) return;                       // no untraded band

        double low = Math.min(prev, open);
        double high = Math.max(prev, open);
        MutableDetection d = registry.create(type, series.timeframe(),
                DetectionDirection.of(open > prev), low, high,
                series.barIndex(), c.getTimestamp(), DetectionState.ACTIVE);
        d.putMeta("midline", (prev + open) / 2.0);
        d.putMeta("previousSessionClose", prev);
        d.putMeta("sessionOpen", open);
        d.putMeta("sessionDate", newSession.toString());
    }

    private void advanceLifecycles(TimeframeSeries series, DetectionRegistry registry,
                                   DetectionType type) {
        Candle c = series.at(0);
        long bar = series.barIndex();
        for (MutableDetection d : registry.mutableView(type, series.timeframe())) {
            if (d.terminal() || d.createdAtBar() >= bar) continue;
            if (c.getLow() <= d.priceTop() && c.getHigh() >= d.priceBottom()) {
                d.advanceTo(DetectionState.TOUCHED, c.getTimestamp(), bar);
            }
        }
    }
}
