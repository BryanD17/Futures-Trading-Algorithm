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
}

export type ChartSymbol = 'MNQ' | 'MES' | 'MGC';
export const CHART_SYMBOLS: ChartSymbol[] = ['MNQ', 'MES', 'MGC'];
export const LOOKBACK_OPTIONS = [50, 100, 200] as const;
export type LookbackOption = (typeof LOOKBACK_OPTIONS)[number];
