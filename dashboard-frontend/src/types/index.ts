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
