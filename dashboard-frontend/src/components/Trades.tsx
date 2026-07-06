import { useState, useEffect } from 'react'
import { TradesService } from '../services/api'
import type { Trade } from '../types'
import './Trades.css'

/**
 * Helper to safely format a number with fallback
 */
function formatPrice(value: number | undefined | null, decimals: number = 2): string {
  if (value === undefined || value === null || isNaN(value)) {
    return '-'
  }
  return value.toFixed(decimals)
}

/**
 * Helper to safely format a date
 */
function formatDate(value: string | undefined | null): string {
  if (!value) {
    return '-'
  }
  try {
    return new Date(value).toLocaleString()
  } catch {
    return '-'
  }
}

export default function Trades() {
  const [trades, setTrades] = useState<Trade[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const fetchTrades = async () => {
      try {
        const data = await TradesService.getTrades(0, 50)
        if (Array.isArray(data)) {
          setTrades(data)
          setError(null)
        } else {
          console.error('Invalid trades data:', data)
          setTrades([])
        }
      } catch (err) {
        console.error('Failed to fetch trades:', err)
        setError('Failed to load trades')
      } finally {
        setLoading(false)
      }
    }

    fetchTrades()
    const interval = setInterval(fetchTrades, 10000)
    return () => clearInterval(interval)
  }, [])

  if (loading) {
    return <div className="loading">Loading trades...</div>
  }

  if (error) {
    return <div className="error-state">{error}</div>
  }

  const totalPnl = trades.reduce((sum, t) => sum + (t.realizedPnL ?? 0), 0)
  const wins = trades.filter((t) => (t.realizedPnL ?? 0) > 0).length
  const losses = trades.filter((t) => (t.realizedPnL ?? 0) < 0).length
  const winRate = trades.length > 0 ? (wins / trades.length) * 100 : 0

  return (
    <div className="trades">
      <h2>Recent Trades</h2>

      {trades.length > 0 && (
        <div className="trades-summary">
          <div className="trades-summary-item">
            <span className="label">Trades</span>
            <span className="value">{trades.length}</span>
          </div>
          <div className="trades-summary-item">
            <span className="label">Wins / Losses</span>
            <span className="value">
              {wins} / {losses}
            </span>
          </div>
          <div className="trades-summary-item">
            <span className="label">Win Rate</span>
            <span className="value">{winRate.toFixed(1)}%</span>
          </div>
          <div className="trades-summary-item">
            <span className="label">Net P&L</span>
            <span className="value" style={{ color: totalPnl >= 0 ? '#10b981' : '#ef4444' }}>
              ${totalPnl.toFixed(2)}
            </span>
          </div>
        </div>
      )}

      {trades.length === 0 ? (
        <div className="empty-state">No trades yet</div>
      ) : (
        <div className="trades-table">
          <table>
            <thead>
              <tr>
                <th>Entry Time</th>
                <th>Exit Time</th>
                <th>Symbol</th>
                <th>Side</th>
                <th>Qty</th>
                <th>Entry</th>
                <th>Exit</th>
                <th>P&L</th>
                <th>R Multiple</th>
                <th>Notes</th>
              </tr>
            </thead>
            <tbody>
              {trades.map((trade, idx) => {
                const side = trade.side || 'UNKNOWN'
                const pnl = trade.realizedPnL ?? 0
                const rMult = trade.rMultiple ?? 0

                return (
                  <tr key={trade.tradeId || idx}>
                    <td>{formatDate(trade.entryTime)}</td>
                    <td>{formatDate(trade.exitTime)}</td>
                    <td className="symbol">{trade.symbol || '-'}</td>
                    <td>
                      <span className={`side-badge ${side.toLowerCase()}`}>
                        {side}
                      </span>
                    </td>
                    <td>{trade.quantity ?? '-'}</td>
                    <td>${formatPrice(trade.entryPrice)}</td>
                    <td>${formatPrice(trade.exitPrice)}</td>
                    <td style={{ color: pnl >= 0 ? '#10b981' : '#ef4444', fontWeight: 700 }}>
                      ${formatPrice(pnl)}
                    </td>
                    <td style={{ color: rMult >= 0 ? '#10b981' : '#ef4444' }}>
                      {formatPrice(rMult)}R
                    </td>
                    <td className="notes">{trade.notes || '-'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
