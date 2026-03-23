import { useState, useEffect } from 'react'
import { LifecycleService } from '../services/api'
import type { LifecycleState } from '../types'

const phases = ['EVALUATION', 'FUNDED_PROBATION', 'FUNDED_SCALING']
const phaseLabels: Record<string, string> = {
  EVALUATION: 'Evaluation',
  FUNDED_PROBATION: 'Funded',
  FUNDED_SCALING: 'Scaling'
}

function PhaseTracker() {
  const [lifecycle, setLifecycle] = useState<LifecycleState | null>(null)

  useEffect(() => {
    const fetchData = () => {
      LifecycleService.getLifecycle()
        .then(data => setLifecycle(data))
        .catch(err => console.error('Failed to fetch lifecycle:', err))
    }
    fetchData()
    const interval = setInterval(fetchData, 5000)
    return () => clearInterval(interval)
  }, [])

  if (!lifecycle || lifecycle.phase === 'NOT_INITIALIZED') {
    return (
      <div className="phase-tracker" style={{ padding: '16px', background: '#1a1a2e', borderRadius: '8px', marginBottom: '16px' }}>
        <h3 style={{ margin: '0 0 8px 0', color: '#888' }}>Phase Tracker</h3>
        <p style={{ color: '#666' }}>Lifecycle not active. Start the trading engine.</p>
      </div>
    )
  }

  const currentIndex = phases.indexOf(lifecycle.phase)
  const isBlown = lifecycle.phase === 'BLOWN'

  return (
    <div className="phase-tracker" style={{ padding: '16px', background: '#1a1a2e', borderRadius: '8px', marginBottom: '16px' }}>
      <h3 style={{ margin: '0 0 12px 0', color: '#e0e0e0' }}>Phase Tracker</h3>

      {/* Progress bar */}
      <div style={{ display: 'flex', gap: '4px', marginBottom: '16px' }}>
        {phases.map((phase, i) => (
          <div key={phase} style={{
            flex: 1,
            height: '8px',
            borderRadius: '4px',
            background: isBlown ? '#F44336'
              : i < currentIndex ? '#4CAF50'
              : i === currentIndex ? lifecycle.riskZoneColor || '#2196F3'
              : '#333'
          }} />
        ))}
      </div>

      {/* Phase labels */}
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '16px' }}>
        {phases.map((phase, i) => (
          <span key={phase} style={{
            fontSize: '12px',
            color: i === currentIndex ? '#fff' : '#666',
            fontWeight: i === currentIndex ? 'bold' : 'normal'
          }}>
            {phaseLabels[phase]}
          </span>
        ))}
      </div>

      {isBlown && (
        <div style={{ padding: '8px', background: '#F44336', borderRadius: '4px', color: '#fff', textAlign: 'center', marginBottom: '12px' }}>
          ACCOUNT BLOWN
        </div>
      )}

      {/* Metrics grid */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '12px' }}>
        <MetricCard
          label="Distance to Target"
          value={`$${lifecycle.distanceToTarget.toFixed(2)}`}
          color="#4CAF50"
        />
        <MetricCard
          label="Drawdown Usage"
          value={`${(lifecycle.drawdownUsagePct * 100).toFixed(1)}%`}
          color={lifecycle.drawdownUsagePct > 0.6 ? '#F44336' : lifecycle.drawdownUsagePct > 0.4 ? '#FF9800' : '#4CAF50'}
        />
        <MetricCard
          label="Risk Zone"
          value={lifecycle.riskZone}
          color={lifecycle.riskZoneColor}
        />
      </div>
    </div>
  )
}

function MetricCard({ label, value, color }: { label: string; value: string; color: string }) {
  return (
    <div style={{ textAlign: 'center' }}>
      <div style={{ fontSize: '11px', color: '#888', marginBottom: '4px' }}>{label}</div>
      <div style={{ fontSize: '18px', fontWeight: 'bold', color }}>{value}</div>
    </div>
  )
}

export default PhaseTracker
