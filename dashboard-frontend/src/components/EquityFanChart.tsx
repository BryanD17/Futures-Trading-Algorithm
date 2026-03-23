import { useState, useEffect } from 'react'
import { LifecycleService } from '../services/api'
import { AreaChart, Area, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from 'recharts'

function EquityFanChart() {
  const [fanData, setFanData] = useState<any>(null)

  useEffect(() => {
    LifecycleService.getEquityFan()
      .then(data => setFanData(data))
      .catch(err => console.error('Failed to fetch equity fan:', err))
  }, [])

  if (!fanData || !fanData.bands || fanData.bands.length === 0) {
    return (
      <div style={{ padding: '16px', background: '#1a1a2e', borderRadius: '8px', marginBottom: '16px' }}>
        <h3 style={{ margin: '0 0 8px 0', color: '#e0e0e0' }}>Equity Path vs MC Fan Chart</h3>
        <p style={{ color: '#666' }}>No simulation data available. Run a Monte Carlo simulation first.</p>
      </div>
    )
  }

  // Merge actual path with band data
  const chartData = fanData.bands.map((band: any, i: number) => ({
    day: band.day,
    p5: band.p5,
    p25: band.p25,
    p50: band.p50,
    p75: band.p75,
    p95: band.p95,
    actual: fanData.actualPath && i < fanData.actualPath.length ? fanData.actualPath[i] : null
  }))

  return (
    <div style={{ padding: '16px', background: '#1a1a2e', borderRadius: '8px', marginBottom: '16px' }}>
      <h3 style={{ margin: '0 0 12px 0', color: '#e0e0e0' }}>Equity Path vs MC Fan Chart</h3>

      <ResponsiveContainer width="100%" height={300}>
        <AreaChart data={chartData} margin={{ top: 5, right: 20, bottom: 5, left: 10 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#333" />
          <XAxis dataKey="day" stroke="#888" label={{ value: 'Trading Day', position: 'insideBottom', offset: -5 }} />
          <YAxis stroke="#888" tickFormatter={(v) => `$${(v/1000).toFixed(0)}K`} />
          <Tooltip
            contentStyle={{ background: '#1a1a2e', border: '1px solid #333' }}
            labelFormatter={(l) => `Day ${l}`}
            formatter={(value: number, name: string) => [`$${value.toFixed(0)}`, name]}
          />

          {/* P5-P95 band */}
          <Area type="monotone" dataKey="p95" stackId="band" stroke="none" fill="#4CAF50" fillOpacity={0.1} />
          <Area type="monotone" dataKey="p75" stackId="band2" stroke="none" fill="#4CAF50" fillOpacity={0.15} />
          <Area type="monotone" dataKey="p25" stackId="band3" stroke="none" fill="#FF9800" fillOpacity={0.1} />
          <Area type="monotone" dataKey="p5" stackId="band4" stroke="none" fill="#F44336" fillOpacity={0.1} />

          {/* Median line */}
          <Line type="monotone" dataKey="p50" stroke="#888" strokeDasharray="5 5" dot={false} />

          {/* Actual equity path */}
          <Line type="monotone" dataKey="actual" stroke="#2196F3" strokeWidth={2} dot={false} />

          {/* Reference lines */}
          {fanData.profitTargetLine && (
            <ReferenceLine y={fanData.profitTargetLine} stroke="#4CAF50" strokeDasharray="3 3" label="Target" />
          )}
          {fanData.blowupLine && (
            <ReferenceLine y={fanData.blowupLine} stroke="#F44336" strokeDasharray="3 3" label="MLL" />
          )}
        </AreaChart>
      </ResponsiveContainer>

      <div style={{ display: 'flex', gap: '16px', justifyContent: 'center', marginTop: '8px', fontSize: '11px', color: '#888' }}>
        <span><span style={{ color: '#2196F3' }}>---</span> Actual Path</span>
        <span><span style={{ color: '#888' }}>- -</span> Median (P50)</span>
        <span><span style={{ color: '#4CAF50', opacity: 0.3 }}>|||</span> P25-P75</span>
        <span><span style={{ color: '#FF9800', opacity: 0.3 }}>|||</span> P5-P95</span>
      </div>
    </div>
  )
}

export default EquityFanChart
