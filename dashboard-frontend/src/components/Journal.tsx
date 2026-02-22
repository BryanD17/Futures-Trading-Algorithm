import { useState, useEffect, useCallback } from 'react'
import { JournalService } from '../services/api'
import './Journal.css'

interface JournalEntry {
  tradeId: string
  sessionDate: string
  symbol: string
  side: string
  quantity: number
  entryPrice: number
  exitPrice: number
  entryTime: string
  exitTime: string
  entryTimeCT: string
  exitTimeCT: string
  realizedPnL: number
  rMultiple: number
  tier: string | null
  confluenceFactors: string[]
  exitReason: string
  result: string
}

interface JournalStats {
  totalTrades: number
  wins: number
  losses: number
  winRate: number
  totalPnL: number
  avgRMultiple: number
}

function Journal() {
  const [entries, setEntries] = useState<JournalEntry[]>([])
  const [stats, setStats] = useState<JournalStats | null>(null)
  const [loading, setLoading] = useState(true)
  const [symbolFilter, setSymbolFilter] = useState('')
  const [resultFilter, setResultFilter] = useState('')
  const [tierFilter, setTierFilter] = useState('')
  const [notes, setNotes] = useState<Record<string, string>>({})
  const [editingNote, setEditingNote] = useState<string | null>(null)

  // Load saved notes from localStorage
  useEffect(() => {
    const saved = localStorage.getItem('journal-notes')
    if (saved) {
      try {
        setNotes(JSON.parse(saved))
      } catch { /* ignore parse errors */ }
    }
  }, [])

  const fetchData = useCallback(async () => {
    try {
      const filters: Record<string, string> = {}
      if (symbolFilter) filters.symbol = symbolFilter
      if (resultFilter) filters.result = resultFilter
      if (tierFilter) filters.tier = tierFilter

      const [entriesData, statsData] = await Promise.all([
        JournalService.getJournal(filters),
        JournalService.getStats()
      ])
      setEntries(entriesData)
      setStats(statsData)
    } catch (err) {
      console.error('Failed to fetch journal data:', err)
    } finally {
      setLoading(false)
    }
  }, [symbolFilter, resultFilter, tierFilter])

  useEffect(() => {
    fetchData()
    const interval = setInterval(fetchData, 30000) // Refresh every 30s
    return () => clearInterval(interval)
  }, [fetchData])

  const saveNote = (tradeId: string, text: string) => {
    const updated = { ...notes, [tradeId]: text }
    setNotes(updated)
    localStorage.setItem('journal-notes', JSON.stringify(updated))
    setEditingNote(null)
  }

  const getTierClass = (tier: string | null): string => {
    if (!tier) return 'tier-1'
    return tier.toLowerCase().replace('_', '-')
  }

  const formatPnL = (pnl: number): string => {
    return `${pnl >= 0 ? '+' : ''}$${pnl.toFixed(2)}`
  }

  if (loading) {
    return <div className="journal-loading">Loading journal...</div>
  }

  return (
    <div className="journal">
      {/* Stats Bar */}
      {stats && (
        <div className="journal-stats">
          <div className="stat-card">
            <div className="label">Total Trades</div>
            <div className="value">{stats.totalTrades}</div>
          </div>
          <div className="stat-card">
            <div className="label">Wins</div>
            <div className="value positive">{stats.wins}</div>
          </div>
          <div className="stat-card">
            <div className="label">Losses</div>
            <div className="value negative">{stats.losses}</div>
          </div>
          <div className="stat-card">
            <div className="label">Win Rate</div>
            <div className={`value ${stats.winRate >= 50 ? 'positive' : 'negative'}`}>
              {stats.winRate.toFixed(1)}%
            </div>
          </div>
          <div className="stat-card">
            <div className="label">Total P&L</div>
            <div className={`value ${stats.totalPnL >= 0 ? 'positive' : 'negative'}`}>
              {formatPnL(stats.totalPnL)}
            </div>
          </div>
          <div className="stat-card">
            <div className="label">Avg R</div>
            <div className={`value ${stats.avgRMultiple >= 0 ? 'positive' : 'negative'}`}>
              {stats.avgRMultiple >= 0 ? '+' : ''}{stats.avgRMultiple.toFixed(2)}R
            </div>
          </div>
        </div>
      )}

      {/* Filter Row */}
      <div className="journal-filters">
        <div>
          <span className="filter-label">Symbol </span>
          <select value={symbolFilter} onChange={e => setSymbolFilter(e.target.value)}>
            <option value="">All</option>
            <option value="NQ">NQ</option>
            <option value="ES">ES</option>
            <option value="6J">6J</option>
            <option value="6E">6E</option>
            <option value="GC">GC</option>
            <option value="MGC">MGC</option>
            <option value="CL">CL</option>
          </select>
        </div>
        <div>
          <span className="filter-label">Result </span>
          <select value={resultFilter} onChange={e => setResultFilter(e.target.value)}>
            <option value="">All</option>
            <option value="WIN">WIN</option>
            <option value="LOSS">LOSS</option>
          </select>
        </div>
        <div>
          <span className="filter-label">Tier </span>
          <select value={tierFilter} onChange={e => setTierFilter(e.target.value)}>
            <option value="">All</option>
            <option value="TIER_4">TIER 4</option>
            <option value="TIER_3">TIER 3</option>
            <option value="TIER_2">TIER 2</option>
            <option value="TIER_1">TIER 1</option>
          </select>
        </div>
      </div>

      {/* Trade Cards */}
      {entries.length === 0 ? (
        <div className="journal-empty">
          No journal entries found. Trades will appear here after your first session.
        </div>
      ) : (
        <div className="journal-trades">
          {entries.map((entry, i) => (
            <div key={entry.tradeId} className="trade-card">
              <div className="trade-card-header">
                <div className="trade-title">
                  #{i + 1}
                  <span className={`result-badge ${entry.result.toLowerCase()}`}>
                    {entry.result}
                  </span>
                  {entry.symbol} {entry.side} x{entry.quantity}
                </div>
                {entry.tier && (
                  <span className={`tier-badge ${getTierClass(entry.tier)}`}>
                    {entry.tier.replace('_', ' ')}
                  </span>
                )}
              </div>

              <div className="trade-card-row">
                <span className="detail-label">Time:</span>
                <span className="detail-value">
                  {entry.entryTimeCT || entry.entryTime} &rarr; {entry.exitTimeCT || entry.exitTime}
                </span>
              </div>

              <div className="trade-card-row">
                <span className="detail-label">Price:</span>
                <span className="detail-value">
                  {entry.entryPrice.toFixed(6)} &rarr; {entry.exitPrice.toFixed(6)}
                </span>
                <span className="detail-label">P&L:</span>
                <span className={`pnl-value ${entry.realizedPnL >= 0 ? 'positive' : 'negative'}`}>
                  {formatPnL(entry.realizedPnL)}
                </span>
                <span className="detail-label">R:</span>
                <span className={`pnl-value ${entry.rMultiple >= 0 ? 'positive' : 'negative'}`}>
                  {entry.rMultiple >= 0 ? '+' : ''}{entry.rMultiple.toFixed(2)}R
                </span>
              </div>

              {entry.confluenceFactors && entry.confluenceFactors.length > 0 && (
                <div className="trade-card-row">
                  <span className="detail-label">Confluence:</span>
                  <div className="confluence-pills">
                    {entry.confluenceFactors.map((factor, j) => (
                      <span key={j} className="confluence-pill">{factor}</span>
                    ))}
                  </div>
                </div>
              )}

              {entry.exitReason && (
                <div className="trade-card-row">
                  <span className="detail-label">Exit:</span>
                  <span className="detail-value">{entry.exitReason}</span>
                </div>
              )}

              {/* Personal Notes */}
              <div className="trade-notes">
                {editingNote === entry.tradeId ? (
                  <textarea
                    className="note-input"
                    defaultValue={notes[entry.tradeId] || ''}
                    placeholder="Add your observations..."
                    onBlur={(e) => saveNote(entry.tradeId, e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault()
                        saveNote(entry.tradeId, (e.target as HTMLTextAreaElement).value)
                      }
                    }}
                    autoFocus
                  />
                ) : notes[entry.tradeId] ? (
                  <div
                    className="detail-value"
                    onClick={() => setEditingNote(entry.tradeId)}
                    style={{ cursor: 'pointer', fontStyle: 'italic', color: '#94a3b8' }}
                  >
                    {notes[entry.tradeId]}
                  </div>
                ) : (
                  <button
                    className="add-note-btn"
                    onClick={() => setEditingNote(entry.tradeId)}
                  >
                    + Add Note
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export default Journal
