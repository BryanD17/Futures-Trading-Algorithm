import { useState, useEffect } from 'react'
import { PositionsService } from '../services/api'
import type { Position } from '../types'
import './Positions.css'

/**
 * Helper to safely format a number with fallback
 */
function formatPrice(value: number | undefined | null, decimals: number = 2): string {
  if (value === undefined || value === null || isNaN(value)) {
    return '-'
  }
  return value.toFixed(decimals)
}

export default function Positions() {
  const [positions, setPositions] = useState<Position[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const fetchPositions = async () => {
      try {
        const data = await PositionsService.getPositions()
        // Validate data before setting state
        if (Array.isArray(data)) {
          setPositions(data)
          setError(null)
        } else {
          console.error('Invalid positions data:', data)
          setPositions([])
        }
      } catch (err) {
        console.error('Failed to fetch positions:', err)
        setError('Failed to load positions')
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

  if (error) {
    return <div className="error-state">{error}</div>
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
              {positions.map((pos, idx) => {
                // Safely handle potentially missing fields
                const side = pos.side || 'UNKNOWN'
                const pnl = pos.unrealizedPnL ?? 0

                return (
                  <tr key={pos.symbol || idx}>
                    <td className="symbol">{pos.symbol || '-'}</td>
                    <td>
                      <span className={`side-badge ${side.toLowerCase()}`}>
                        {side}
                      </span>
                    </td>
                    <td>{pos.quantity ?? '-'}</td>
                    <td>${formatPrice(pos.avgEntryPrice)}</td>
                    <td>${formatPrice(pos.currentPrice)}</td>
                    <td style={{ color: pnl >= 0 ? '#10b981' : '#ef4444' }}>
                      ${formatPrice(pnl)}
                    </td>
                    <td>{pos.stopPrice ? `$${formatPrice(pos.stopPrice)}` : '-'}</td>
                    <td>{pos.targetPrice ? `$${formatPrice(pos.targetPrice)}` : '-'}</td>
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
