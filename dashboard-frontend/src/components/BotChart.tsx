import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
  DETECTION_TIMEFRAMES,
  LOOKBACK_OPTIONS,
  type BotChartResponse,
  type ChartSymbol,
  type Detection,
  type DetectionFamily,
  type DetectionTimeframe,
  type LookbackOption,
  type OteStateName,
} from '../types/chart';
import {
  LAYERS,
  SESSIONS,
  inWindow,
  rgba,
  styleFor,
  type LayerSpec,
} from './detectionLayers';
import './BotChart.css';

/**
 * BOT CHART — the human window into the engine's ChartEngine memory.
 *
 * The ALGORITHM trades off the in-memory 30m chart (it does not read
 * pixels); this tab renders that memory VERBATIM (no client-side
 * aggregation) so the owner can hold the dashboard next to TopstepX and
 * verify the bot sees the same candles, the same OTE band, and — since V4 —
 * the same ICT structures: FVGs, order blocks with breaker styling, liquidity
 * pools with sweep state, volume imbalances, opening-gap magnets and MSS/BOS
 * markers.
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

/** A resolved rectangle in pixel space, ready to render. */
interface Box {
  id: string;
  x: number;
  y: number;
  w: number;
  h: number;
  fill: string;
  stroke: string;
  dash: string;
  label: string;
  midlineY: number | null;
}

/** A resolved point marker in pixel space. */
interface Marker {
  id: string;
  x: number;
  y: number;
  colour: string;
  label: string;
  up: boolean;
}

/** A session shading band in pixel space. */
interface Band {
  id: string;
  x: number;
  w: number;
  colour: string;
  trading: boolean;
}

export default function BotChart() {
  const [symbol, setSymbol] = useState<ChartSymbol>('MNQ');
  const [lookback, setLookback] = useState<LookbackOption>(100);
  const [data, setData] = useState<BotChartResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showSessions, setShowSessions] = useState(true);
  const [detectionTf, setDetectionTf] = useState<DetectionTimeframe>('15m');
  const [layersOn, setLayersOn] = useState<Record<string, boolean>>(() =>
    Object.fromEntries(LAYERS.map((l) => [l.key, l.defaultOn])),
  );

  // Bumped whenever the chart pans/zooms/resizes so the overlay recomputes.
  const [viewTick, setViewTick] = useState(0);
  const [boxes, setBoxes] = useState<Box[]>([]);
  const [markers, setMarkers] = useState<Marker[]>([]);
  const [bands, setBands] = useState<Band[]>([]);

  const containerRef = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const priceLinesRef = useRef<IPriceLine[]>([]);

  const fetchChart = useCallback(async () => {
    try {
      const r = await ChartApi.getChart(symbol, lookback, detectionTf);
      setData(r);
      setError(null);
      if (import.meta.env.DEV) {
        // B15: measure the payload once per fetch rather than guessing at it.
        const bytes = JSON.stringify(r).length;
        console.debug(
          `[BotChart] payload ${bytes} bytes · detections ${r.detections?.returned ?? 0}` +
            `/${r.detections?.cap ?? 0}${r.detections?.truncated ? ' (TRUNCATED)' : ''}`,
        );
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [symbol, lookback, detectionTf]);

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

    const bump = () => setViewTick((n) => n + 1);
    chart.timeScale().subscribeVisibleTimeRangeChange(bump);
    const observer = new ResizeObserver(bump);
    observer.observe(el);

    return () => {
      observer.disconnect();
      chart.timeScale().unsubscribeVisibleTimeRangeChange(bump);
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
    setViewTick((n) => n + 1);
  }, [data]);

  // ── THE OVERLAY ────────────────────────────────────────────────────────
  // Detections are FACTS with price bounds and a lifetime; the chart's job is
  // to place them, not to reinterpret them. Everything below is pure
  // projection from (price, time) into pixels.
  useEffect(() => {
    const chart = chartRef.current;
    const series = seriesRef.current;
    const el = containerRef.current;
    if (!chart || !series || !el || !data || data.candles30m.length === 0) {
      setBoxes([]);
      setMarkers([]);
      setBands([]);
      return;
    }

    const width = el.clientWidth;
    const height = el.clientHeight;
    const ts = chart.timeScale();
    const sec = (iso: string) => Math.floor(Date.parse(iso) / 1000) as UTCTimestamp;
    const firstBar = sec(data.candles30m[0].t);

    /** x for a timestamp, clamped to the chart's left edge for older facts. */
    const xFor = (iso: string | null, fallback: number): number => {
      if (!iso) return fallback;
      const t = sec(iso);
      const x = ts.timeToCoordinate(t);
      if (x !== null) return x;
      return t < firstBar ? 0 : width;
    };

    const nextBoxes: Box[] = [];
    const nextMarkers: Marker[] = [];

    for (const layer of LAYERS) {
      if (!layersOn[layer.key]) continue;
      const items: Detection[] = data.detections?.families?.[layer.key] ?? [];
      for (const d of items) {
        const style = styleFor(d.state);
        const x1 = xFor(d.createdAt, 0);
        const x2 = style.frozen ? xFor(d.stateChangedAt, width) : width;

        if (layer.render === 'point') {
          const y = series.priceToCoordinate(d.top);
          if (y === null) continue;
          nextMarkers.push({
            id: d.id,
            x: x1,
            y,
            colour: layer.colour,
            label: `${layer.label} ${d.direction === 'BULLISH' ? '↑' : '↓'}`,
            up: d.direction === 'BULLISH',
          });
          continue;
        }

        const yTop = series.priceToCoordinate(d.top);
        const yBottom = series.priceToCoordinate(d.bottom);
        if (yTop === null || yBottom === null) continue;

        const midline =
          layer.midline && typeof d.meta?.midline === 'number'
            ? series.priceToCoordinate(d.meta.midline as number)
            : null;

        nextBoxes.push({
          id: d.id,
          x: Math.min(x1, x2),
          y: Math.min(yTop, yBottom),
          w: Math.max(2, Math.abs(x2 - x1)),
          h: Math.max(1, Math.abs(yBottom - yTop)),
          fill: rgba(layer.colour, style.muted ? 0.05 : style.fill),
          stroke: rgba(layer.colour, style.muted ? 0.35 : 0.9),
          dash: style.dash,
          label: `${layer.label} · ${d.state}`,
          midlineY: midline,
        });
      }
    }

    // Session shading, computed per visible 30m candle so the bands land on
    // the same x-grid as the bars themselves.
    const nextBands: Band[] = [];
    if (showSessions) {
      const times = data.candles30m.map((c) => Date.parse(c.t));
      for (const w of SESSIONS) {
        for (let i = 0; i < times.length; i++) {
          if (!inWindow(times[i], w)) continue;
          const x = ts.timeToCoordinate(sec(data.candles30m[i].t));
          if (x === null) continue;
          const nextX =
            i + 1 < times.length
              ? ts.timeToCoordinate(sec(data.candles30m[i + 1].t))
              : null;
          const bw = nextX !== null ? Math.max(1, nextX - x) : 6;
          nextBands.push({
            id: `${w.label}-${i}`,
            x,
            w: bw,
            colour: w.colour,
            trading: w.kind === 'trading',
          });
        }
      }
    }

    setBoxes(nextBoxes);
    setMarkers(nextMarkers);
    setBands(nextBands);
    // height participates so a resize repaints; it is read, not stored.
    void height;
  }, [data, layersOn, showSessions, viewTick]);

  const hasCandles = !!data && data.candles30m.length > 0;

  const activeCounts = useMemo(() => data?.detections?.counts ?? {}, [data]);

  const toggle = (key: DetectionFamily) =>
    setLayersOn((prev) => ({ ...prev, [key]: !prev[key] }));

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
        <div className="botchart-detectiontf" role="tablist" aria-label="Detection timeframe">
          {DETECTION_TIMEFRAMES.map((t) => (
            <button
              key={t}
              role="tab"
              aria-selected={detectionTf === t}
              className={detectionTf === t ? 'active' : ''}
              onClick={() => setDetectionTf(t)}
              title={`Show ICT detections found on the ${t} series`}
            >
              {t}
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
          {data?.anchorMode && (
            <span className="pill anchor" title={`OTE band ${data.oteBand}`}>
              {data.anchorMode}
              {data.anchorCompare ? ' · compare' : ''}
            </span>
          )}
          {data?.ote && <OteBadge state={data.ote.state} direction={data.ote.direction} />}
        </div>
      </div>

      {/* Layer toggles — one per ICT family, with its live count. */}
      {hasCandles && (
        <div className="botchart-layers" role="group" aria-label="Detection layers">
          {LAYERS.map((l: LayerSpec) => (
            <button
              key={l.key}
              className={`layer-chip ${layersOn[l.key] ? 'on' : 'off'}`}
              aria-pressed={layersOn[l.key]}
              onClick={() => toggle(l.key)}
              title={`${l.label} (${activeCounts[l.key] ?? 0} shown)`}
            >
              <span className="swatch" style={{ background: l.colour }} />
              {l.label}
              <span className="count">{activeCounts[l.key] ?? 0}</span>
            </button>
          ))}
          <button
            className={`layer-chip ${showSessions ? 'on' : 'off'}`}
            aria-pressed={showSessions}
            onClick={() => setShowSessions((v) => !v)}
            title="Session shading (display sessions + TRADING killzones)"
          >
            <span className="swatch sessions" />
            Sessions
          </button>
          {data?.detections?.truncated && (
            <span className="layer-truncated" role="status">
              payload capped at {data.detections.cap} — older detections omitted
            </span>
          )}
        </div>
      )}

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

      {/* The physical chart plus its detection overlay. Kept mounted; hidden
          until it has data so the library can size itself on first paint. */}
      <div
        className="botchart-stage"
        style={{ display: hasCandles ? 'block' : 'none' }}
      >
        <div ref={containerRef} className="botchart-canvas" />
        <svg className="botchart-overlay" aria-hidden="true">
          {bands.map((b) => (
            <rect
              key={b.id}
              x={b.x}
              y={0}
              width={b.w}
              height="100%"
              fill={rgba(b.colour, b.trading ? 0.1 : 0.045)}
            />
          ))}
          {boxes.map((b) => (
            <g key={b.id}>
              <rect
                x={b.x}
                y={b.y}
                width={b.w}
                height={b.h}
                fill={b.fill}
                stroke={b.stroke}
                strokeWidth={1}
                strokeDasharray={b.dash || undefined}
              />
              {b.midlineY !== null && (
                <line
                  x1={b.x}
                  x2={b.x + b.w}
                  y1={b.midlineY}
                  y2={b.midlineY}
                  stroke={b.stroke}
                  strokeWidth={1}
                  strokeDasharray="3 3"
                />
              )}
            </g>
          ))}
          {markers.map((m) => (
            <g key={m.id}>
              <circle cx={m.x} cy={m.y} r={3.5} fill={m.colour} />
              <text x={m.x + 6} y={m.y + 3} fill={m.colour} className="overlay-label">
                {m.label}
              </text>
            </g>
          ))}
        </svg>
      </div>

      {hasCandles && (
        <div className="botchart-sessionlegend">
          <span className="legend-title">Shading:</span>
          {SESSIONS.filter((s) => s.kind === 'display').map((s) => (
            <span key={s.label} className="legend-item">
              <i style={{ background: rgba(s.colour, 0.35) }} />
              {s.label}
            </span>
          ))}
          <span className="legend-item legend-trading">
            <i style={{ background: rgba('#20c997', 0.55) }} />
            TRADING killzones (the only windows any gate reads)
          </span>
        </div>
      )}

      {data?.ote && (
        <div className="botchart-otelegend">
          OTE {data.ote.direction === 'BULLISH' ? 'bullish' : 'bearish'} leg:
          origin {fmt(data.ote.legOrigin)} → extreme {fmt(data.ote.legExtreme)}
          {' · '}0.62 {fmt(data.ote['fib_0.62'])} · 0.705 {fmt(data.ote['fib_0.705'])}
          {' · '}0.786 {fmt(data.ote['fib_0.786'])}
          {data.ote.anchorMode ? ` · anchored ${data.ote.anchorMode}` : ''}
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
