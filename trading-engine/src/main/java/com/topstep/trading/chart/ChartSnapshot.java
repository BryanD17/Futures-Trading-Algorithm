package com.topstep.trading.chart;

import com.topstep.trading.domain.Candle;

import java.time.Instant;
import java.util.List;

/**
 * Read-only view of everything the bot "sees" for one instrument:
 * the 30m candle series plus the live OTE zone. Served by the API so the
 * dashboard can render the bot's internal chart next to the TopstepX chart
 * and verify they match candle-for-candle.
 *
 * @param oneMinuteBarsIngested total 1m bars ever fed in (backfill + live) —
 *                              a small number here means the backfill did not
 *                              run and the bot is trading blind.
 * @param partial30m the IN-PROGRESS 30m candle (running OHLCV of the
 *                   current window), or null when no window is open. Added
 *                   2026-07-09 so the Bot Chart's right edge matches the
 *                   broker chart's forming bar instead of lagging up to 30
 *                   minutes — the owner's "chart is not accurate" report.
 *                   Never used for swing/fractal analysis (confirmed bars
 *                   only there).
 */
public record ChartSnapshot(
        String symbol,
        List<Candle> candles30m,
        OteZoneSnapshot activeOte,
        long oneMinuteBarsIngested,
        Instant lastCandleTime,
        Candle partial30m
) {}
