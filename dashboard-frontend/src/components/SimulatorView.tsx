import { useState } from 'react'
import { SimulationService } from '../services/api'

function SimulatorView() {
  const [params, setParams] = useState({
    winRate: 0.45,
    avgWinR: 3.0,
    avgLossR: -1.0,
    tradesPerDay: 2.0,
    baseRiskPct: 0.005,
    iterations: 10000
  })
  const [result, setResult] = useState<any>(null)
  const [loading, setLoading] = useState(false)

  const runSimulation = async () => {
    setLoading(true)
    try {
      const data = await SimulationService.runSimulation(params)
      setResult(data)
    } catch (err) {
      console.error('Simulation failed:', err)
    } finally {
      setLoading(false)
    }
  }

  const runOptimization = async () => {
    setLoading(true)
    try {
      const data = await SimulationService.runOptimization(params)
      setResult(data)
    } catch (err) {
      console.error('Optimization failed:', err)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ padding: '16px', background: '#1a1a2e', borderRadius: '8px' }}>
      <h3 style={{ margin: '0 0 16px 0', color: '#e0e0e0' }}>Monte Carlo Simulator</h3>

      {/* Parameter inputs */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '12px', marginBottom: '16px' }}>
        <ParamInput label="Win Rate" value={params.winRate} step={0.05}
          onChange={v => setParams({...params, winRate: v})} format={v => `${(v*100).toFixed(0)}%`} />
        <ParamInput label="Avg Win (R)" value={params.avgWinR} step={0.5}
          onChange={v => setParams({...params, avgWinR: v})} />
        <ParamInput label="Avg Loss (R)" value={params.avgLossR} step={0.1}
          onChange={v => setParams({...params, avgLossR: v})} />
        <ParamInput label="Trades/Day" value={params.tradesPerDay} step={0.5}
          onChange={v => setParams({...params, tradesPerDay: v})} />
        <ParamInput label="Base Risk %" value={params.baseRiskPct} step={0.0025}
          onChange={v => setParams({...params, baseRiskPct: v})} format={v => `${(v*100).toFixed(2)}%`} />
        <ParamInput label="Iterations" value={params.iterations} step={1000}
          onChange={v => setParams({...params, iterations: v})} />
      </div>

      {/* Action buttons */}
      <div style={{ display: 'flex', gap: '8px', marginBottom: '16px' }}>
        <button onClick={runSimulation} disabled={loading}
          style={{ padding: '8px 16px', background: '#2196F3', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>
          {loading ? 'Running...' : 'Run Simulation'}
        </button>
        <button onClick={runOptimization} disabled={loading}
          style={{ padding: '8px 16px', background: '#FF9800', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>
          {loading ? 'Running...' : 'Optimize Risk'}
        </button>
      </div>

      {/* Results */}
      {result && (
        <div style={{ background: '#0d0d1a', borderRadius: '4px', padding: '12px' }}>
          <h4 style={{ margin: '0 0 8px 0', color: '#e0e0e0' }}>Results</h4>
          {result.passRate !== undefined && (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '8px' }}>
              <ResultItem label="Pass Rate" value={`${(result.passRate * 100).toFixed(1)}%`}
                color={result.passRate > 0.5 ? '#4CAF50' : '#F44336'} />
              <ResultItem label="Blowup Rate" value={`${((result.blowupRate || 0) * 100).toFixed(1)}%`} color="#FF9800" />
              <ResultItem label="Expected Payout" value={`$${(result.expectedPayout || 0).toFixed(0)}`}
                color={(result.expectedPayout || 0) > 0 ? '#4CAF50' : '#F44336'} />
              {result.avgTradesToPass !== undefined && (
                <ResultItem label="Avg Trades to Pass" value={`${result.avgTradesToPass.toFixed(0)}`} color="#2196F3" />
              )}
              {result.maxDrawdownP95 !== undefined && (
                <ResultItem label="Max DD (P95)" value={`$${result.maxDrawdownP95.toFixed(0)}`} color="#FF9800" />
              )}
              {result.optimalRiskPct !== undefined && (
                <ResultItem label="Optimal Risk" value={`${(result.optimalRiskPct * 100).toFixed(2)}%`} color="#4CAF50" />
              )}
            </div>
          )}

          {/* Sweep curve for optimization */}
          {result.sweepCurve && (
            <div style={{ marginTop: '12px' }}>
              <h4 style={{ color: '#e0e0e0', marginBottom: '8px' }}>Risk Sweep Curve</h4>
              <table style={{ width: '100%', fontSize: '12px', color: '#ccc' }}>
                <thead>
                  <tr style={{ borderBottom: '1px solid #333' }}>
                    <th style={{ textAlign: 'left', padding: '4px' }}>Risk %</th>
                    <th style={{ textAlign: 'right', padding: '4px' }}>Pass Rate</th>
                    <th style={{ textAlign: 'right', padding: '4px' }}>Blowup</th>
                    <th style={{ textAlign: 'right', padding: '4px' }}>E[Payout]</th>
                  </tr>
                </thead>
                <tbody>
                  {result.sweepCurve.map((p: any, i: number) => (
                    <tr key={i} style={{
                      borderBottom: '1px solid #222',
                      background: p.riskPct === result.optimalRiskPct ? 'rgba(76,175,80,0.2)' : 'transparent'
                    }}>
                      <td style={{ padding: '4px' }}>{(p.riskPct * 100).toFixed(2)}%</td>
                      <td style={{ textAlign: 'right', padding: '4px' }}>{(p.passRate * 100).toFixed(1)}%</td>
                      <td style={{ textAlign: 'right', padding: '4px' }}>{(p.blowupRate * 100).toFixed(1)}%</td>
                      <td style={{ textAlign: 'right', padding: '4px', color: p.expectedPayout > 0 ? '#4CAF50' : '#F44336' }}>
                        ${p.expectedPayout.toFixed(0)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function ParamInput({ label, value, step, onChange, format }: {
  label: string; value: number; step: number;
  onChange: (v: number) => void; format?: (v: number) => string
}) {
  return (
    <div>
      <label style={{ fontSize: '11px', color: '#888', display: 'block', marginBottom: '4px' }}>{label}</label>
      <input
        type="number"
        value={value}
        step={step}
        onChange={e => onChange(parseFloat(e.target.value) || 0)}
        style={{ width: '100%', padding: '6px', background: '#0d0d1a', border: '1px solid #333', borderRadius: '4px', color: '#fff', fontSize: '14px' }}
      />
      {format && <div style={{ fontSize: '10px', color: '#666', marginTop: '2px' }}>{format(value)}</div>}
    </div>
  )
}

function ResultItem({ label, value, color }: { label: string; value: string; color: string }) {
  return (
    <div style={{ padding: '8px', background: '#1a1a2e', borderRadius: '4px' }}>
      <div style={{ fontSize: '10px', color: '#888' }}>{label}</div>
      <div style={{ fontSize: '18px', fontWeight: 'bold', color }}>{value}</div>
    </div>
  )
}

export default SimulatorView
