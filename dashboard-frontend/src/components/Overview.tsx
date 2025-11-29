import { useState, useEffect } from 'react'
import { MetricsService } from '../services/api'
import type { DailyMetrics } from '../types'
import './Overview.css'

export default function Overview() {
  const [metrics, setMetrics] = useState<DailyMetrics | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
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
    const interval = setInterval(fetchMetrics, 5000)
    return () => clearInterval(interval)
  }, [])

  if (loading) {
    return <div className="loading">Loading...</div>
  }

  if (!metrics) {
    return <div className="error">Failed to load metrics</div>
  }

  const pnlColor = metrics.totalPnL >= 0 ? '#10b981' : '#ef4444'

  return (
    <div className="overview">
      <h2>Account Overview</h2>

      <div className="metrics-grid">
        <div className="metric-card">
          <div className="metric-label">Starting Balance</div>
          <div className="metric-value">${metrics.startingBalance.toLocaleString()}</div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Current Balance</div>
          <div className="metric-value">${metrics.currentBalance.toLocaleString()}</div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Realized P&L</div>
          <div className="metric-value" style={{ color: metrics.realizedPnL >= 0 ? '#10b981' : '#ef4444' }}>
            ${metrics.realizedPnL.toLocaleString()}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Unrealized P&L</div>
          <div className="metric-value" style={{ color: metrics.unrealizedPnL >= 0 ? '#10b981' : '#ef4444' }}>
            ${metrics.unrealizedPnL.toLocaleString()}
          </div>
        </div>

        <div className="metric-card highlight">
          <div className="metric-label">Total P&L</div>
          <div className="metric-value large" style={{ color: pnlColor }}>
            ${metrics.totalPnL.toLocaleString()}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Trades Today</div>
          <div className="metric-value">{metrics.tradesCount}</div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Win Rate</div>
          <div className="metric-value">{(metrics.winRate * 100).toFixed(1)}%</div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Max Drawdown</div>
          <div className="metric-value" style={{ color: '#ef4444' }}>
            ${metrics.maxDrawdown.toLocaleString()}
          </div>
        </div>
      </div>
    </div>
  )
}
