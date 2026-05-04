import { useState, useEffect } from 'react'
import { LifecycleService } from '../services/api'

function PassProbabilityGauge() {
  const [data, setData] = useState<any>(null)

  useEffect(() => {
    const fetchData = () => {
      LifecycleService.getPassProbability()
        .then(d => setData(d))
        .catch(err => console.error('Failed to fetch pass probability:', err))
    }
    fetchData()
    const interval = setInterval(fetchData, 15000) // Update every 15s
    return () => clearInterval(interval)
  }, [])

  const passPct = (data?.passProbability ?? 0) * 100
  const blowupPct = (data?.blowupProbability ?? 0) * 100
  const expectedPayout = data?.expectedPayout ?? 0
  const targetCompletionPct = (data?.targetCompletionPct ?? 0) * 100
  const drawdownUsagePct = (data?.drawdownUsagePct ?? 0) * 100

  const getColor = (pct: number) => {
    if (pct >= 60) return '#4CAF50'
    if (pct >= 40) return '#8BC34A'
    if (pct >= 25) return '#FF9800'
    return '#F44336'
  }

  return (
    <div style={{ padding: '16px', background: '#1a1a2e', borderRadius: '8px', marginBottom: '16px' }}>
      <h3 style={{ margin: '0 0 12px 0', color: '#e0e0e0' }}>Pass Probability</h3>

      <div style={{ display: 'flex', alignItems: 'center', gap: '24px' }}>
        {/* Main gauge */}
        <div style={{ textAlign: 'center', flex: '0 0 auto' }}>
          <div style={{
            fontSize: '48px',
            fontWeight: 'bold',
            color: getColor(passPct),
            lineHeight: '1'
          }}>
            {passPct.toFixed(1)}%
          </div>
          <div style={{ fontSize: '12px', color: '#888', marginTop: '4px' }}>
            pass probability
          </div>
        </div>

        {/* Details */}
        <div style={{ flex: 1, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px' }}>
          <DetailItem label="Blowup Risk" value={`${blowupPct.toFixed(1)}%`}
            color={blowupPct > 40 ? '#F44336' : '#FF9800'} />
          <DetailItem label="Expected Payout" value={`$${expectedPayout.toFixed(0)}`}
            color={expectedPayout > 0 ? '#4CAF50' : '#F44336'} />
          <DetailItem label="Target Progress"
            value={`${targetCompletionPct.toFixed(1)}%`}
            color="#2196F3" />
          <DetailItem label="Drawdown Used"
            value={`${drawdownUsagePct.toFixed(1)}%`}
            color="#FF9800" />
        </div>
      </div>
    </div>
  )
}

function DetailItem({ label, value, color }: { label: string; value: string; color: string }) {
  return (
    <div>
      <div style={{ fontSize: '11px', color: '#888' }}>{label}</div>
      <div style={{ fontSize: '16px', fontWeight: 'bold', color }}>{value}</div>
    </div>
  )
}

export default PassProbabilityGauge
