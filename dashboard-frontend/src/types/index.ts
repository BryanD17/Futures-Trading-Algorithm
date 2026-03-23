export interface Status {
  status: string
  mode: string
  timestamp: string
  version: string
}

export interface Position {
  symbol: string
  side: 'LONG' | 'SHORT'
  quantity: number
  avgEntryPrice: number
  currentPrice: number
  unrealizedPnL: number
  stopPrice?: number
  targetPrice?: number
}

export interface Trade {
  tradeId: string
  symbol: string
  side: 'BUY' | 'SELL'
  quantity: number
  entryPrice: number
  exitPrice: number
  entryTime: string
  exitTime: string
  realizedPnL: number
  rMultiple: number
  notes?: string
}

export interface RiskMetrics {
  maxDailyLoss: number
  currentDailyLoss: number
  remainingRiskBudget: number
  maxContracts: number
  currentContracts: number
  riskPerTrade: number
}

export interface DailyMetrics {
  startingBalance: number
  currentBalance: number
  realizedPnL: number
  unrealizedPnL: number
  totalPnL: number
  tradesCount: number
  winRate: number
  maxDrawdown: number
}

export interface LifecycleState {
  phase: string
  phaseDisplayName: string
  currentEquity: number
  startingBalance: number
  profitTarget: number
  maxLossLimit: number
  dailyLossLimit: number
  distanceToTarget: number
  distanceToBlowup: number
  targetCompletionPct: number
  drawdownUsagePct: number
  riskBudgetRemaining: number
  riskZone: string
  riskZoneDescription: string
  riskZoneColor: string
  riskZoneMultiplier: number
  tradingDaysElapsed: number
  totalTradesTaken: number
  consecutiveLosses: number
  tradingPausedForDay: boolean
  highWaterMark: number
}

export interface PassProbability {
  passProbability: number
  blowupProbability: number
  timeoutProbability: number
  expectedPayout: number
  iterations: number
}

export interface EquityFanBand {
  day: number
  p5: number
  p25: number
  p50: number
  p75: number
  p95: number
}

export interface SimulationResult {
  passRate: number
  blowupRate: number
  timeoutRate: number
  avgTradesToPass: number
  avgDaysToPass: number
  expectedPayout: number
  maxDrawdownP50: number
  maxDrawdownP95: number
  maxDrawdownP99: number
}

export interface OptimizationPoint {
  riskPct: number
  passRate: number
  blowupRate: number
  expectedPayout: number
  avgTradesToPass: number
  maxDrawdownP95: number
}

export interface ChallengeEconomics {
  challengesPassed: number
  challengesTotal: number
  passRate: number
  totalFeesPaid: number
  totalPayoutsReceived: number
  totalNetProfit: number
  roi: number
}
