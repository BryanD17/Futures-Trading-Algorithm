import { useState, useEffect } from 'react'
import { RiskService } from '../services/api'
import type { RiskMetrics } from '../types'
import './Risk.css'

export default function Risk() {
  const [risk, setRisk] = useState<RiskMetrics | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const fetchRisk = async () => {
      try {
        const data = await RiskService.getRiskMetrics()
        setRisk(data)
      } catch (err) {
        console.error('Failed to fetch risk metrics:', err)
      } finally {
        setLoading(false)
      }
    }

    fetchRisk()
    const interval = setInterval(fetchRisk, 5000)
    return () => clearInterval(interval)
  }, [])

  if (loading) {
    return <div className="loading">Loading risk metrics...</div>
  }

  if (!risk) {
    return <div className="error">Failed to load risk metrics</div>
  }

  const lossPercentage = (Math.abs(risk.currentDailyLoss) / risk.maxDailyLoss) * 100
  const contractsPercentage = (risk.currentContracts / risk.maxContracts) * 100

  return (
    <div className="risk">
      <h2>Risk Management</h2>

      <div className="risk-grid">
        <div className="risk-card">
          <div className="risk-label">Daily Loss Limit</div>
          <div className="risk-bar-container">
            <div className="risk-bar">
              <div
                className="risk-bar-fill"
                style={{
                  width: `${lossPercentage}%`,
                  background: lossPercentage > 80 ? '#ef4444' : lossPercentage > 50 ? '#fbbf24' : '#10b981'
                }}
              ></div>
            </div>
            <div className="risk-values">
              <span>${Math.abs(risk.currentDailyLoss).toFixed(2)}</span>
              <span className="limit">/ ${risk.maxDailyLoss.toFixed(2)}</span>
            </div>
          </div>
          <div className="risk-remaining">
            Remaining: ${risk.remainingRiskBudget.toFixed(2)}
          </div>
        </div>

        <div className="risk-card">
          <div className="risk-label">Contract Usage</div>
          <div className="risk-bar-container">
            <div className="risk-bar">
              <div
                className="risk-bar-fill"
                style={{
                  width: `${contractsPercentage}%`,
                  background: contractsPercentage > 80 ? '#ef4444' : contractsPercentage > 50 ? '#fbbf24' : '#3b82f6'
                }}
              ></div>
            </div>
            <div className="risk-values">
              <span>{risk.currentContracts}</span>
              <span className="limit">/ {risk.maxContracts}</span>
            </div>
          </div>
        </div>

        <div className="risk-card">
          <div className="risk-label">Risk Per Trade</div>
          <div className="risk-value-large">
            ${risk.riskPerTrade.toFixed(2)}
          </div>
          <div className="risk-description">
            Maximum loss per position (1R)
          </div>
        </div>
      </div>
    </div>
  )
}
