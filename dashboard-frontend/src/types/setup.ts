// STDV+OTE setup state — mirrors api-backend SetupController DTOs.

export type TradeableSymbol = 'MNQ' | 'MES' | 'MGC';

export const TRADEABLE_SYMBOLS: readonly TradeableSymbol[] = ['MNQ', 'MES', 'MGC'] as const;

export type SetupStateName =
  | 'IDLE'
  | 'BIAS_SET'
  | 'MANIP_DONE'
  | 'SWEEP_DONE'
  | 'DISPLACED'
  | 'MSS_CONFIRMED'
  | 'OTE_ARMED'
  | 'IN_TRADE'
  | 'MANAGING'
  | 'DONE'
  | 'INVALIDATED';

export const STATE_ORDER: SetupStateName[] = [
  'IDLE',
  'BIAS_SET',
  'MANIP_DONE',
  'SWEEP_DONE',
  'DISPLACED',
  'MSS_CONFIRMED',
  'OTE_ARMED',
  'IN_TRADE',
  'MANAGING',
  'DONE',
];

export type MarketBiasName = 'BULLISH' | 'BEARISH' | 'NEUTRAL';
export type TierName = 'TIER_1' | 'TIER_2' | 'TIER_3' | 'TIER_4';

export interface InstrumentDto {
  symbol: TradeableSymbol;
  tickSize: number;
  tickValue: number;
  pointValue: number;
  minMicros: number;
  maxMicros: number;
  raidMinQuality: number;
  correlate: string;
}

export interface ProjectionDto {
  sigma: number;
  rawPrice: number;
  snappedPrice: number;
  snappedLevelType: string | null;
  isLiquidityBacked: boolean;
  realismTag: string;
}

export interface OteZoneDto {
  bullish: boolean;
  legLow: number;
  legHigh: number;
  eq50: number;
  f62: number;
  f705: number;
  f79: number;
  one00: number;
}

export interface SweepDto {
  bullish: boolean;
  sweptLevel: number;
  hasSmt: boolean;
}

export interface FvgDto {
  bullish: boolean;
  top: number;
  bottom: number;
  filled: boolean;
}

export interface SetupSnapshotDto {
  symbol: string;
  state: SetupStateName;
  htfBias: MarketBiasName;
  killzoneOpen: boolean;
  smtState: string;
  legLow: number;
  legHigh: number;
  legBullish: boolean;
  projections: ProjectionDto[];
  sweep: SweepDto | null;
  raidScore: number;
  displacement: boolean;
  fvg: FvgDto | null;
  mss: boolean;
  ote: OteZoneDto | null;
  pdArrayInOte: number | null;
  pdArrayKind: string | null;
  tier: TierName | null;
  confluenceFactors: string[];
  entry: number;
  stop: number;
  rr: number;
  sizeRequest: number;
  sizeFilled: number;
  lastGateFailed: string | null;
  pd: PdGateDto | null;
  vote: BiasVoteDto | null;
  ote30m: Ote30mGateDto | null;
}

// M7b 30m-OTE confluence gate telemetry (V3 Agent 06). Null on cold start.
export interface Ote30mGateDto {
  mode: 'OFF' | 'LOG' | 'GATE';
  acceptArmed: boolean;
  gatePassing: boolean;
  wouldBlock: number;
  blocked: number;
  abstains: number;
}

// 3-of-4 bias vote telemetry (V3 Agent 03). Null on cold start.
export interface BiasVoteEntryDto {
  source: 'V1' | 'V2' | 'V3' | 'V4';
  direction: 'BULL' | 'BEAR' | 'ABSTAIN';
  detail: string;
}

export interface BiasVoteDto {
  mode: 'LEGACY' | 'LOG' | 'VOTE';
  finalBias?: MarketBiasName;
  alignedBull?: number;
  alignedBear?: number;
  abstains?: number;
  votes?: BiasVoteEntryDto[];
  agree?: boolean;
  counters: {
    agree: number;
    disagree: number;
    voteNeutral_legacyDirectional: number;
    voteDirectional_legacyNeutral: number;
  };
}

// M2b premium/discount gate telemetry (V3 Agent 02). Session-scoped
// counters; null until the engine wires the evaluator (cold start).
export interface PdGateDto {
  mode: 'OFF' | 'LOG' | 'BLOCK';
  eqBandTicks: number;
  minRangeTicks: number;
  gatePassing: boolean;
  wouldBlockLong: number;
  wouldBlockShort: number;
  blockedLong: number;
  blockedShort: number;
  abstainsByReason: Record<string, number>;
  verdictsByRangeSource: Record<string, number>;
}

export interface ActiveSetupListDto {
  strategy: string;
  activeSymbols: string[];
}

// Tick precision per instrument — used for price formatting on the panel.
export const INSTRUMENT_PRECISION: Record<TradeableSymbol, number> = {
  MNQ: 2,
  MES: 2,
  MGC: 2,
};

// Mandatory gates checked by the M1..M9 validator. Used by the
// ConfluenceChecklist component to render the checklist rows. Each gate's
// "passed" state is derived from the snapshot — the gate names match the
// validator's failure summary so a single string compare drives the UI.
export interface GateDef {
  id: 'M1' | 'M2' | 'M2b' | 'M3' | 'M4' | 'M5' | 'M6' | 'M7' | 'M7b' | 'M8' | 'M9';
  label: string;
}

export const MANDATORY_GATES: GateDef[] = [
  { id: 'M1', label: 'Instrument is MNQ / MES / MGC' },
  { id: 'M2', label: 'HTF bias aligned (not NEUTRAL)' },
  { id: 'M2b', label: 'Premium/Discount (entry vs equilibrium)' },
  { id: 'M3', label: 'Killzone open' },
  { id: 'M4', label: 'Liquidity sweep + raid score' },
  { id: 'M5', label: 'Displacement + FVG' },
  { id: 'M6', label: 'Market structure shift / CHoCH' },
  { id: 'M7', label: 'OTE entry + PD array + RR ≥ 2.0' },
  { id: 'M7b', label: '30m OTE confluence' },
  { id: 'M8', label: 'Size in [5, 20] micros' },
  { id: 'M9', label: 'Risk guardrails clear' },
];
