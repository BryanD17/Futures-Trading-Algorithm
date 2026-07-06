import { useState, useEffect } from 'react'
import { RiskService } from '../services/api'
import type { RiskMetrics } from '../types'
import './Risk.css'

export default function Risk() {
  const [risk, setRisk] = useState<RiskMetrics | null>(null)
  const [loading, setLoading] = useState(true)

  // Settings form state (strings so inputs can be cleared while typing)
  const [profitTargetInput, setProfitTargetInput] = useState('')
  const [maxDailyLossInput, setMaxDailyLossInput] = useState('')
  const [riskPerTradeInput, setRiskPerTradeInput] = useState('')
  const [saving, setSaving] = useState(false)
  const [saveMessage, setSaveMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)

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

  useEffect(() => {
    fetchRisk()
    const interval = setInterval(fetchRisk, 5000)
    return () => clearInterval(interval)
  }, [])

  const handleSaveSettings = async () => {
    const settings: { profitTarget?: number; maxDailyLoss?: number; riskPerTrade?: number } = {}
    const parse = (s: string) => {
      const n = parseFloat(s)
      return isNaN(n) ? undefined : n
    }
    const pt = parse(profitTargetInput)
    const dl = parse(maxDailyLossInput)
    const rpt = parse(riskPerTradeInput)
    if (pt !== undefined) settings.profitTarget = pt
    if (dl !== undefined) settings.maxDailyLoss = dl
    if (rpt !== undefined) settings.riskPerTrade = rpt

    if (Object.keys(settings).length === 0) {
      setSaveMessage({ kind: 'error', text: 'Enter at least one value to update' })
      return
    }
    if ((pt !== undefined && pt <= 0) || (dl !== undefined && dl <= 0) || (rpt !== undefined && rpt <= 0)) {
      setSaveMessage({ kind: 'error', text: 'Values must be positive' })
      return
    }

    setSaving(true)
    setSaveMessage(null)
    try {
      const applied = await RiskService.updateSettings(settings)
      setSaveMessage({
        kind: 'ok',
        text: `Applied — target $${applied.profitTarget.toFixed(0)}, daily loss cap $${applied.maxDailyLoss.toFixed(0)}, risk/trade $${applied.riskPerTrade.toFixed(0)}`
      })
      setProfitTargetInput('')
      setMaxDailyLossInput('')
      setRiskPerTradeInput('')
      fetchRisk()
    } catch (err: any) {
      const msg = err?.response?.data?.error || err?.message || 'Failed to update settings'
      setSaveMessage({ kind: 'error', text: msg })
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <div className="loading">Loading risk metrics...</div>
  }

  if (!risk) {
    return <div className="error">Failed to load risk metrics</div>
  }

  const lossPercentage = (Math.abs(risk.currentDailyLoss) / risk.maxDailyLoss) * 100
  const contractsPercentage = (risk.currentContracts / risk.maxContracts) * 100
  const drawdownPct =
    risk.maxLossLimit && risk.maxLossLimit > 0
      ? ((risk.currentDrawdown ?? 0) / risk.maxLossLimit) * 100
      : 0
  const targetPct = Math.min(100, Math.max(0, (risk.profitTargetProgress ?? 0) * 100))

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
                  width: `${Math.min(100, lossPercentage)}%`,
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

        {risk.maxLossLimit !== undefined && (
          <div className="risk-card">
            <div className="risk-label">Max Loss Limit (Trailing)</div>
            <div className="risk-bar-container">
              <div className="risk-bar">
                <div
                  className="risk-bar-fill"
                  style={{
                    width: `${Math.min(100, drawdownPct)}%`,
                    background: drawdownPct > 80 ? '#ef4444' : drawdownPct > 50 ? '#fbbf24' : '#10b981'
                  }}
                ></div>
              </div>
              <div className="risk-values">
                <span>${(risk.currentDrawdown ?? 0).toFixed(2)}</span>
                <span className="limit">/ ${risk.maxLossLimit.toFixed(2)}</span>
              </div>
            </div>
            <div className="risk-remaining">
              Remaining: ${(risk.remainingDrawdown ?? risk.maxLossLimit).toFixed(2)}
            </div>
          </div>
        )}

        {risk.profitTarget !== undefined && (
          <div className="risk-card">
            <div className="risk-label">Profit Target</div>
            <div className="risk-bar-container">
              <div className="risk-bar">
                <div
                  className="risk-bar-fill"
                  style={{ width: `${targetPct}%`, background: '#3b82f6' }}
                ></div>
              </div>
              <div className="risk-values">
                <span>{targetPct.toFixed(1)}%</span>
                <span className="limit">of ${risk.profitTarget.toFixed(2)}</span>
              </div>
            </div>
          </div>
        )}

        <div className="risk-card">
          <div className="risk-label">Contract Usage</div>
          <div className="risk-bar-container">
            <div className="risk-bar">
              <div
                className="risk-bar-fill"
                style={{
                  width: `${Math.min(100, contractsPercentage)}%`,
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

      <div className="risk-settings">
        <h3>Risk Settings</h3>
        <p className="risk-settings-note">
          Daily loss cap and risk per trade can only be tightened — the backend clamps any
          value above the engine-start baseline. Leave a field blank to keep its current value.
        </p>
        <div className="risk-settings-form">
          <label>
            Profit Target ($)
            <input
              type="number"
              min="1"
              step="100"
              placeholder={risk.profitTarget !== undefined ? risk.profitTarget.toFixed(0) : ''}
              value={profitTargetInput}
              onChange={(e) => setProfitTargetInput(e.target.value)}
            />
          </label>
          <label>
            Daily Loss Cap ($)
            <input
              type="number"
              min="1"
              step="50"
              placeholder={risk.maxDailyLoss.toFixed(0)}
              value={maxDailyLossInput}
              onChange={(e) => setMaxDailyLossInput(e.target.value)}
            />
          </label>
          <label>
            Risk Per Trade / Stop ($)
            <input
              type="number"
              min="1"
              step="25"
              placeholder={risk.riskPerTrade.toFixed(0)}
              value={riskPerTradeInput}
              onChange={(e) => setRiskPerTradeInput(e.target.value)}
            />
          </label>
          <button onClick={handleSaveSettings} disabled={saving}>
            {saving ? 'Applying…' : 'Apply Settings'}
          </button>
        </div>
        {saveMessage && (
          <div className={`risk-settings-message ${saveMessage.kind}`}>{saveMessage.text}</div>
        )}
      </div>
    </div>
  )
}
