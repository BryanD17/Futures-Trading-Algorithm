import { useState, useEffect } from 'react'
import { LifecycleService } from '../services/api'

function ChallengeEconomicsPanel() {
  const [economics, setEconomics] = useState<any>(null)

  useEffect(() => {
    LifecycleService.getEconomics()
      .then(data => setEconomics(data))
      .catch(err => console.error('Failed to fetch economics:', err))
  }, [])

  if (!economics) {
    return (
      <div style={{ padding: '16px', background: '#1a1a2e', borderRadius: '8px' }}>
        <h3 style={{ margin: '0 0 8px 0', color: '#e0e0e0' }}>Challenge Economics</h3>
        <p style={{ color: '#666' }}>Loading economics data...</p>
      </div>
    )
  }

  const roi = economics.roi || 0
  const shouldBuyAnother = economics.passRate > 0.2 && roi > -0.3

  return (
    <div style={{ padding: '16px', background: '#1a1a2e', borderRadius: '8px' }}>
      <h3 style={{ margin: '0 0 16px 0', color: '#e0e0e0' }}>Challenge Economics</h3>

      {/* Summary metrics */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: '12px', marginBottom: '16px' }}>
        <SummaryCard label="Total Fees" value={`$${(economics.totalFeesPaid || 0).toFixed(0)}`} color="#FF9800" />
        <SummaryCard label="Total Payouts" value={`$${(economics.totalPayoutsReceived || 0).toFixed(0)}`} color="#4CAF50" />
        <SummaryCard label="Net Profit" value={`$${(economics.totalNetProfit || 0).toFixed(0)}`}
          color={(economics.totalNetProfit || 0) >= 0 ? '#4CAF50' : '#F44336'} />
        <SummaryCard label="ROI" value={`${(roi * 100).toFixed(1)}%`}
          color={roi >= 0 ? '#4CAF50' : '#F44336'} />
      </div>

      {/* Pass rate */}
      <div style={{ display: 'flex', gap: '12px', marginBottom: '16px' }}>
        <div style={{ flex: 1, padding: '12px', background: '#0d0d1a', borderRadius: '4px' }}>
          <div style={{ fontSize: '11px', color: '#888' }}>Actual Pass Rate</div>
          <div style={{ fontSize: '24px', fontWeight: 'bold', color: '#2196F3' }}>
            {economics.challengesTotal > 0 ? `${(economics.passRate * 100).toFixed(1)}%` : 'N/A'}
          </div>
          <div style={{ fontSize: '11px', color: '#888' }}>
            {economics.challengesPassed} / {economics.challengesTotal} challenges
          </div>
        </div>

        <div style={{ flex: 1, padding: '12px', background: '#0d0d1a', borderRadius: '4px' }}>
          <div style={{ fontSize: '11px', color: '#888' }}>Should Buy Another?</div>
          <div style={{
            fontSize: '18px', fontWeight: 'bold',
            color: economics.challengesTotal === 0 ? '#888' : shouldBuyAnother ? '#4CAF50' : '#F44336'
          }}>
            {economics.challengesTotal === 0 ? 'Need Data' : shouldBuyAnother ? 'YES' : 'NO'}
          </div>
          <div style={{ fontSize: '11px', color: '#888' }}>
            {economics.challengesTotal === 0
              ? 'Complete at least 1 challenge'
              : shouldBuyAnother
                ? 'Positive expected value'
                : 'Expected value is negative'}
          </div>
        </div>
      </div>

      {/* Challenge history table */}
      {economics.challenges && economics.challenges.length > 0 && (
        <div>
          <h4 style={{ color: '#e0e0e0', marginBottom: '8px' }}>Challenge History</h4>
          <table style={{ width: '100%', fontSize: '12px', color: '#ccc', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid #333' }}>
                <th style={{ textAlign: 'left', padding: '6px' }}>Account</th>
                <th style={{ textAlign: 'right', padding: '6px' }}>Fee</th>
                <th style={{ textAlign: 'right', padding: '6px' }}>Payout</th>
                <th style={{ textAlign: 'right', padding: '6px' }}>Net</th>
                <th style={{ textAlign: 'center', padding: '6px' }}>Outcome</th>
                <th style={{ textAlign: 'right', padding: '6px' }}>Days</th>
              </tr>
            </thead>
            <tbody>
              {economics.challenges.map((c: any, i: number) => (
                <tr key={i} style={{ borderBottom: '1px solid #222' }}>
                  <td style={{ padding: '6px' }}>{c.accountId}</td>
                  <td style={{ textAlign: 'right', padding: '6px' }}>${c.feePaid.toFixed(0)}</td>
                  <td style={{ textAlign: 'right', padding: '6px' }}>${c.payoutReceived.toFixed(0)}</td>
                  <td style={{ textAlign: 'right', padding: '6px', color: c.netProfit >= 0 ? '#4CAF50' : '#F44336' }}>
                    ${c.netProfit.toFixed(0)}
                  </td>
                  <td style={{ textAlign: 'center', padding: '6px' }}>
                    <span style={{
                      padding: '2px 8px', borderRadius: '4px', fontSize: '11px',
                      background: c.outcome === 'PASSED' ? 'rgba(76,175,80,0.2)' : 'rgba(244,67,54,0.2)',
                      color: c.outcome === 'PASSED' ? '#4CAF50' : '#F44336'
                    }}>
                      {c.outcome}
                    </span>
                  </td>
                  <td style={{ textAlign: 'right', padding: '6px' }}>{c.daysToResolve}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function SummaryCard({ label, value, color }: { label: string; value: string; color: string }) {
  return (
    <div style={{ textAlign: 'center', padding: '8px', background: '#0d0d1a', borderRadius: '4px' }}>
      <div style={{ fontSize: '10px', color: '#888' }}>{label}</div>
      <div style={{ fontSize: '18px', fontWeight: 'bold', color }}>{value}</div>
    </div>
  )
}

export default ChallengeEconomicsPanel
