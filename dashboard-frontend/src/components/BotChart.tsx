import { useCallback, useEffect, useRef, useState } from 'react';
import {
  createChart,
  CandlestickSeries,
  LineStyle,
  type IChartApi,
  type ISeriesApi,
  type IPriceLine,
  type UTCTimestamp,
} from 'lightweight-charts';
import { ChartApi } from '../services/chartApi';
import {
  CHART_SYMBOLS,
  LOOKBACK_OPTIONS,
  type BotChartResponse,
  type ChartSymbol,
  type LookbackOption,
  type OteStateName,
} from '../types/chart';
import './BotChart.css';

/**
 * BOT CHART — the human window into the engine's ChartEngine memory.
 *
 * The ALGORITHM trades off the in-memory 30m chart (it does not read
 * pixels); this tab renders that memory VERBATIM (no client-side
 * aggregation) so the owner can hold the dashboard next to TopstepX and
 * verify the bot sees the same candles and the same OTE band.
 *
 * Required UI states (never a blank box): loading skeleton, friendly
 * empty state, an unmissable COLD banner when warm=false, and an error
 * state with retry. Polling: every 15s, paused while the tab is hidden.
 */

const POLL_MS = 15_000;

type FibKey = 'fib_0.62' | 'fib_0.705' | 'fib_0.786' | 'fib_1.0';

const FIB_LINES: Array<{ key: FibKey; label: string }> = [
  { key: 'fib_0.62', label: 'OTE 0.62' },
  { key: 'fib_0.705', label: 'OTE 0.705' },
  { key: 'fib_0.786', label: 'OTE 0.786' },
  { key: 'fib_1.0', label: 'Leg origin 1.0' },
];

export default function BotChart() {
  const [symbol, setSymbol] = useState<ChartSymbol>('MNQ');
  const [lookback, setLookback] = useState<LookbackOption>(100);
  const [data, setData] = useState<BotChartResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const containerRef = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const priceLinesRef = useRef<IPriceLine[]>([]);

  const fetchChart = useCallback(async () => {
    try {
      const r = await ChartApi.getChart(symbol, lookback);
      setData(r);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [symbol, lookback]);

  // Poll every 15s; PAUSE while the tab is hidden and refresh immediately
  // when it becomes visible again.
  useEffect(() => {
    setLoading(true);
    fetchChart();
    const interval = setInterval(() => {
      if (document.visibilityState === 'hidden') return; // paused
      fetchChart();
    }, POLL_MS);
    const onVisible = () => {
      if (document.visibilityState === 'visible') fetchChart();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      clearInterval(interval);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [fetchChart]);

  // Create the chart once per mount.
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const chart = createChart(el, {
      autoSize: true,
      layout: {
        background: { color: 'transparent' },
        textColor: '#8b95a8',
      },
      grid: {
        vertLines: { color: '#2a3140' },
        horzLines: { color: '#2a3140' },
      },
      timeScale: { timeVisible: true, secondsVisible: false },
      rightPriceScale: { borderColor: '#353c4a' },
    });
    const series = chart.addSeries(CandlestickSeries, {
      upColor: '#20c997',
      downColor: '#fa5252',
      borderUpColor: '#20c997',
      borderDownColor: '#fa5252',
      wickUpColor: '#20c997',
      wickDownColor: '#fa5252',
    });
    chartRef.current = chart;
    seriesRef.current = series;
    return () => {
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
      priceLinesRef.current = [];
    };
  }, []);

  // Push the API payload into the chart verbatim.
  useEffect(() => {
    const series = seriesRef.current;
    if (!series || !data) return;

    series.setData(
      data.candles30m.map((c) => ({
        time: Math.floor(Date.parse(c.t) / 1000) as UTCTimestamp,
        open: c.o,
        high: c.h,
        low: c.l,
        close: c.c,
      })),
    );
    chartRef.current?.timeScale().fitContent();

    // Replace the OTE price lines wholesale on every update.
    for (const line of priceLinesRef.current) {
      series.removePriceLine(line);
    }
    priceLinesRef.current = [];
    if (data.ote) {
      const ote = data.ote;
      for (const { key, label } of FIB_LINES) {
        const price = ote[key];
        if (typeof price !== 'number') continue;
        priceLinesRef.current.push(
          series.createPriceLine({
            price,
            color: key === 'fib_1.0' ? '#ffd43b' : '#4dabf7',
            lineWidth: 1,
            lineStyle: key === 'fib_0.705' ? LineStyle.Solid : LineStyle.Dashed,
            axisLabelVisible: true,
            title: label,
          }),
        );
      }
    }
  }, [data]);

  const hasCandles = !!data && data.candles30m.length > 0;

  return (
    <div className="botchart" role="region" aria-label="Bot Chart">
      <div className="botchart-toolbar">
        <div className="botchart-symbols" role="tablist" aria-label="Symbol">
          {CHART_SYMBOLS.map((s) => (
            <button
              key={s}
              role="tab"
              aria-selected={symbol === s}
              className={symbol === s ? 'active' : ''}
              onClick={() => setSymbol(s)}
            >
              {s}
            </button>
          ))}
        </div>
        <div className="botchart-lookbacks" role="tablist" aria-label="Lookback">
          {LOOKBACK_OPTIONS.map((n) => (
            <button
              key={n}
              role="tab"
              aria-selected={lookback === n}
              className={lookback === n ? 'active' : ''}
              onClick={() => setLookback(n)}
            >
              {n}
            </button>
          ))}
        </div>
        <div className="botchart-meta">
          <span className="botchart-tf">30m · engine memory</span>
          {data && (
            <span className={`pill warm ${data.warm ? 'warm-on' : 'warm-off'}`}>
              {data.warm
                ? `CHART WARM (${data.barsIngested1m} bars)`
                : 'CHART COLD'}
            </span>
          )}
          {data?.ote && <OteBadge state={data.ote.state} direction={data.ote.direction} />}
        </div>
      </div>

      {/* COLD banner — unmissable, above everything else. */}
      {data && !data.warm && (
        <div className="botchart-cold" role="alert">
          ⚠ Bot chart is COLD — backfill has not run; the engine cannot see
          enough history to trade. ({data.barsIngested1m} of ~1500 bars)
        </div>
      )}

      {/* Error state with retry. */}
      {error && !hasCandles && (
        <div className="botchart-error" role="alert">
          Failed to load the bot chart: {error}
          <button onClick={() => { setLoading(true); fetchChart(); }}>Retry</button>
        </div>
      )}

      {/* Loading skeleton (first load only). */}
      {loading && !data && !error && (
        <div className="botchart-skeleton" aria-label="Loading chart">
          <div className="skeleton-bar" />
          <div className="skeleton-bar" />
          <div className="skeleton-bar" />
        </div>
      )}

      {/* Friendly empty state — engine up but no candles yet. */}
      {data && !hasCandles && !error && (
        <div className="botchart-empty">
          No 30m candles in engine memory yet for {data.symbol}. Start the
          engine (SIM warm-boots in seconds; LIVE backfills ~3 days) and
          this chart fills itself.
        </div>
      )}

      {/* The physical chart. Kept mounted; hidden until it has data so the
          library can size itself correctly on first paint. */}
      <div
        ref={containerRef}
        className="botchart-canvas"
        style={{ display: hasCandles ? 'block' : 'none' }}
      />

      {data?.ote && (
        <div className="botchart-otelegend">
          OTE {data.ote.direction === 'BULLISH' ? 'bullish' : 'bearish'} leg:
          origin {fmt(data.ote.legOrigin)} → extreme {fmt(data.ote.legExtreme)}
          {' · '}0.62 {fmt(data.ote['fib_0.62'])} · 0.705 {fmt(data.ote['fib_0.705'])}
          {' · '}0.786 {fmt(data.ote['fib_0.786'])}
          {data.ote.taggedAt ? ` · tagged ${new Date(data.ote.taggedAt).toLocaleTimeString()}` : ''}
        </div>
      )}
    </div>
  );
}

function OteBadge({ state, direction }: { state: OteStateName; direction: string }) {
  return (
    <span className={`pill ote-state ote-${state.toLowerCase()}`}>
      OTE {state}
      {direction === 'BULLISH' ? ' ↑' : ' ↓'}
    </span>
  );
}

function fmt(n: number): string {
  return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
}
