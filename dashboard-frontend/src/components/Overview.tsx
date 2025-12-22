import { useState, useEffect } from 'react'
import { MetricsService } from '../services/api'
import { tradingWebSocket, AccountUpdate } from '../services/websocket'
import type { DailyMetrics } from '../types'
import './Overview.css'

export default function Overview() {
  const [metrics, setMetrics] = useState<DailyMetrics | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    // Initial fetch
    const fetchMetrics = async () => {
      try {
        const data = await MetricsService.getDailyMetrics()
        setMetrics(data)
      } catch (err) {
        console.error('Failed to fetch metrics:', err)
      } finally {
        setLoading(false)
      }
    }
    fetchMetrics()

    // Subscribe to WebSocket updates
    const unsubscribe = tradingWebSocket.subscribe((update: AccountUpdate) => {
      setMetrics(prev => prev ? {
        ...prev,
        currentBalance: update.balance,
        realizedPnL: update.realizedPnL,
        unrealizedPnL: update.unrealizedPnL,
        totalPnL: update.realizedPnL + update.unrealizedPnL,
      } : prev)
    })

    // Fallback polling (every 10 seconds)
    const interval = setInterval(fetchMetrics, 10000)

    return () => {
      unsubscribe()
      clearInterval(interval)
    }
  }, [])

  if (loading) {
    return <div className="loading">Loading...</div>
  }

  if (!metrics) {
    return <div className="error">Failed to load metrics</div>
  }

  // Safe access with defaults
  const startingBalance = metrics.startingBalance ?? 50000
  const currentBalance = metrics.currentBalance ?? 50000
  const realizedPnL = metrics.realizedPnL ?? 0
  const unrealizedPnL = metrics.unrealizedPnL ?? 0
  const totalPnL = metrics.totalPnL ?? 0
  const tradesCount = metrics.tradesCount ?? 0
  const winRate = metrics.winRate ?? 0
  const maxDrawdown = metrics.maxDrawdown ?? 0

  const pnlColor = totalPnL >= 0 ? '#10b981' : '#ef4444'

  return (
    <div className="overview">
      <h2>Account Overview</h2>

      <div className="metrics-grid">
        <div className="metric-card">
          <div className="metric-label">Starting Balance</div>
          <div className="metric-value">${startingBalance.toLocaleString()}</div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Current Balance</div>
          <div className="metric-value">${currentBalance.toLocaleString()}</div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Realized P&L</div>
          <div className="metric-value" style={{ color: realizedPnL >= 0 ? '#10b981' : '#ef4444' }}>
            ${realizedPnL.toLocaleString()}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Unrealized P&L</div>
          <div className="metric-value" style={{ color: unrealizedPnL >= 0 ? '#10b981' : '#ef4444' }}>
            ${unrealizedPnL.toLocaleString()}
          </div>
        </div>

        <div className="metric-card highlight">
          <div className="metric-label">Total P&L</div>
          <div className="metric-value large" style={{ color: pnlColor }}>
            ${totalPnL.toLocaleString()}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Trades Today</div>
          <div className="metric-value">{tradesCount}</div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Win Rate</div>
          <div className="metric-value">{(winRate * 100).toFixed(1)}%</div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Max Drawdown</div>
          <div className="metric-value" style={{ color: '#ef4444' }}>
            ${maxDrawdown.toLocaleString()}
          </div>
        </div>
      </div>
    </div>
  )
}
