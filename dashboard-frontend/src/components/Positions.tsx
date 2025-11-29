import { useState, useEffect } from 'react'
import { PositionsService } from '../services/api'
import type { Position } from '../types'
import './Positions.css'

export default function Positions() {
  const [positions, setPositions] = useState<Position[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const fetchPositions = async () => {
      try {
        const data = await PositionsService.getPositions()
        setPositions(data)
      } catch (err) {
        console.error('Failed to fetch positions:', err)
      } finally {
        setLoading(false)
      }
    }

    fetchPositions()
    const interval = setInterval(fetchPositions, 3000)
    return () => clearInterval(interval)
  }, [])

  if (loading) {
    return <div className="loading">Loading positions...</div>
  }

  return (
    <div className="positions">
      <h2>Open Positions</h2>

      {positions.length === 0 ? (
        <div className="empty-state">No open positions</div>
      ) : (
        <div className="positions-table">
          <table>
            <thead>
              <tr>
                <th>Symbol</th>
                <th>Side</th>
                <th>Quantity</th>
                <th>Avg Entry</th>
                <th>Current Price</th>
                <th>Unrealized P&L</th>
                <th>Stop</th>
                <th>Target</th>
              </tr>
            </thead>
            <tbody>
              {positions.map((pos, idx) => (
                <tr key={idx}>
                  <td className="symbol">{pos.symbol}</td>
                  <td>
                    <span className={`side-badge ${pos.side.toLowerCase()}`}>
                      {pos.side}
                    </span>
                  </td>
                  <td>{pos.quantity}</td>
                  <td>${pos.avgEntryPrice.toFixed(2)}</td>
                  <td>${pos.currentPrice.toFixed(2)}</td>
                  <td style={{ color: pos.unrealizedPnL >= 0 ? '#10b981' : '#ef4444' }}>
                    ${pos.unrealizedPnL.toFixed(2)}
                  </td>
                  <td>{pos.stopPrice ? `$${pos.stopPrice.toFixed(2)}` : '-'}</td>
                  <td>{pos.targetPrice ? `$${pos.targetPrice.toFixed(2)}` : '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
