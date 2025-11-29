import { useState, useEffect } from 'react'
import { TradesService } from '../services/api'
import type { Trade } from '../types'
import './Trades.css'

export default function Trades() {
  const [trades, setTrades] = useState<Trade[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const fetchTrades = async () => {
      try {
        const data = await TradesService.getTrades(0, 50)
        setTrades(data)
      } catch (err) {
        console.error('Failed to fetch trades:', err)
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

  return (
    <div className="trades">
      <h2>Recent Trades</h2>

      {trades.length === 0 ? (
        <div className="empty-state">No trades yet</div>
      ) : (
        <div className="trades-table">
          <table>
            <thead>
              <tr>
                <th>Time</th>
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
              {trades.map((trade) => (
                <tr key={trade.tradeId}>
                  <td>{new Date(trade.exitTime).toLocaleString()}</td>
                  <td className="symbol">{trade.symbol}</td>
                  <td>
                    <span className={`side-badge ${trade.side.toLowerCase()}`}>
                      {trade.side}
                    </span>
                  </td>
                  <td>{trade.quantity}</td>
                  <td>${trade.entryPrice.toFixed(2)}</td>
                  <td>${trade.exitPrice.toFixed(2)}</td>
                  <td style={{ color: trade.realizedPnL >= 0 ? '#10b981' : '#ef4444', fontWeight: 700 }}>
                    ${trade.realizedPnL.toFixed(2)}
                  </td>
                  <td style={{ color: trade.rMultiple >= 0 ? '#10b981' : '#ef4444' }}>
                    {trade.rMultiple.toFixed(2)}R
                  </td>
                  <td className="notes">{trade.notes || '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
