/**
 * Types for GET /api/chart/{symbol} — the bot's internal 30m chart
 * (ChartEngine memory) exposed by ChartStateController. This is the exact
 * V1 contract; the BotChart tab renders this payload VERBATIM (no
 * client-side aggregation) so what you see is what the engine holds.
 */

/** One 30m candle from the bot's in-memory series. */
export interface BotCandle {
  /** ISO-8601 timestamp of the 30m bucket open (UTC). */
  t: string;
  o: number;
  h: number;
  l: number;
  c: number;
  v: number;
}

export type OteStateName =
  | 'FORMING'
  | 'ARMED'
  | 'REACTED'
  | 'INVALIDATED'
  | 'EXPIRED';

/** The OTE fib overlay drawn on the current significant 30m leg. */
export interface OteOverlay {
  symbol: string;
  direction: 'BULLISH' | 'BEARISH';
  state: OteStateName;
  /** V4 §S9 — which anchoring strategy produced this zone. */
  anchorMode?: AnchorModeName;
  /** V4 §S9 — the band this zone is actually armed on. */
  bandStart?: number;
  bandSweet?: number;
  bandEnd?: number;
  bandRatios?: string;
  legOrigin: number;
  legExtreme: number;
  'fib_0.5': number;
  'fib_0.62': number;
  'fib_0.705': number;
  'fib_0.786': number;
  'fib_1.0': number;
  originTime: string | null;
  extremeTime: string | null;
  taggedAt: string | null;
}

/** Which leg-selection strategy anchored the OTE zone (V4 §S9). */
export type AnchorModeName = 'FRACTAL_LEG' | 'TREND_SHIFT';

/**
 * ICT library detection families (V4 Appendix S). These keys are the stable
 * `jsonKey` of the engine's DetectionType enum — do not rename them here
 * without renaming them there.
 */
export type DetectionFamily =
  | 'displacement'
  | 'fvg'
  | 'bpr'
  | 'volumeImbalance'
  | 'openingGapWeekly'
  | 'openingGapDaily'
  | 'liquidityPool'
  | 'orderBlock'
  | 'mss'
  | 'bos';

/** Lifecycle states, mirroring the engine's DetectionState. */
export type DetectionStateName =
  | 'ACTIVE'
  | 'TOUCHED'
  | 'FILLED'
  | 'BROKEN'
  | 'TESTED'
  | 'BREAKER'
  | 'REMOVED'
  | 'PARTIAL'
  | 'SWEPT'
  | 'POINT';

/** One ICT detection as served by the engine. */
export interface Detection {
  id: string;
  type: DetectionFamily;
  timeframe: string;
  direction: 'BULLISH' | 'BEARISH' | 'NEUTRAL';
  top: number;
  bottom: number;
  state: DetectionStateName;
  createdAt: string;
  stateChangedAt: string | null;
  /** Family-specific extras: midline, poolPrice, clusterSize, level, … */
  meta?: Record<string, string | number | boolean | null>;
}

/** The bounded detections object. `truncated` is never silently true. */
export interface DetectionsPayload {
  timeframe: string;
  cap: number;
  returned: number;
  truncated: boolean;
  counts: Partial<Record<DetectionFamily, number>>;
  families: Partial<Record<DetectionFamily, Detection[]>>;
}

/** The full /api/chart/{symbol} response. */
export interface BotChartResponse {
  symbol: string;
  timeframe: string;
  candles30m: BotCandle[];
  ote: OteOverlay | null;
  barsIngested1m: number;
  lastCandleTime: string | null;
  /** >= ~1500 one-minute bars ingested — the honest-gating precondition. */
  warm: boolean;
  /** V4 §S9 — the resolved anchoring switch. Always present. */
  anchorMode: AnchorModeName;
  /** V4 §S9 — "start,end" of the retracement band in use. */
  oteBand: string;
  /** V4 §S9 — true when both anchoring modes are being compared. */
  anchorCompare: boolean;
  /** V4 §S9 — the other mode's zone, only when anchorCompare is on. */
  shadowOte: OteOverlay | null;
  /** V4 Appendix S — the ICT detection overlay, bounded server-side. */
  detections: DetectionsPayload;
}

/**
 * Which ictlib timeframe the overlay asks for. 15m is the default because on
 * a 30m chart the 1m instances are noise; 1m and all are there for the
 * side-by-side verification pass against TopstepX.
 */
export type DetectionTimeframe = '15m' | '1m' | 'all';
export const DETECTION_TIMEFRAMES: DetectionTimeframe[] = ['15m', '1m', 'all'];

export type ChartSymbol = 'MNQ' | 'MES' | 'MGC';
export const CHART_SYMBOLS: ChartSymbol[] = ['MNQ', 'MES', 'MGC'];
export const LOOKBACK_OPTIONS = [50, 100, 200] as const;
export type LookbackOption = (typeof LOOKBACK_OPTIONS)[number];
