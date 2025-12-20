import { useState, useEffect } from 'react'
import { StatusService, ControlService } from '../services/api'
import './Controls.css'

export default function Controls() {
  const [status, setStatus] = useState<any>(null)
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState('')
  const [showLiveConfirm, setShowLiveConfirm] = useState(false)

  useEffect(() => {
    const fetchStatus = async () => {
      try {
        const data = await StatusService.getStatus()
        setStatus(data)
      } catch (err) {
        console.error('Failed to fetch status:', err)
      }
    }
    fetchStatus()
    const interval = setInterval(fetchStatus, 2000)
    return () => clearInterval(interval)
  }, [])

  const handleStart = async (mode: string) => {
    if (mode === 'LIVE') {
      setShowLiveConfirm(true)
      return
    }
    await startEngine(mode)
  }

  const startEngine = async (mode: string) => {
    setLoading(true)
    setShowLiveConfirm(false)
    try {
      const result = await ControlService.start(mode)
      setMessage(result.message)
    } catch (err: any) {
      setMessage('Failed to start: ' + (err.response?.data?.message || err.message))
    }
    setLoading(false)
  }

  const handlePause = async () => {
    setLoading(true)
    try {
      const result = await ControlService.pause()
      setMessage(result.message)
    } catch (err: any) {
      setMessage('Failed to pause: ' + (err.response?.data?.message || err.message))
    }
    setLoading(false)
  }

  const handleResume = async () => {
    setLoading(true)
    try {
      const result = await ControlService.resume()
      setMessage(result.message)
    } catch (err: any) {
      setMessage('Failed to resume: ' + (err.response?.data?.message || err.message))
    }
    setLoading(false)
  }

  const handleStop = async () => {
    setLoading(true)
    try {
      const result = await ControlService.stop()
      setMessage(result.message)
    } catch (err: any) {
      setMessage('Failed to stop: ' + (err.response?.data?.message || err.message))
    }
    setLoading(false)
  }

  const handleKillSwitch = async () => {
    if (!confirm('⚠️ ACTIVATE KILL SWITCH?\n\nThis will:\n- Stop all trading immediately\n- Flatten all open positions\n- Block new orders until reset\n\nAre you sure?')) {
      return
    }
    setLoading(true)
    try {
      const result = await ControlService.killSwitch('Manual dashboard activation')
      setMessage(result.message)
    } catch (err: any) {
      setMessage('Failed to activate kill switch: ' + (err.response?.data?.message || err.message))
    }
    setLoading(false)
  }

  const handleFlatten = async () => {
    if (!confirm('Flatten all positions?\n\nThis will close all open positions at market price.')) {
      return
    }
    setLoading(true)
    try {
      const result = await ControlService.flatten('Manual flatten from dashboard')
      setMessage(result.message)
    } catch (err: any) {
      setMessage('Failed to flatten: ' + (err.response?.data?.message || err.message))
    }
    setLoading(false)
  }

  const getStatusColor = () => {
    if (!status) return '#6b7280'
    if (status.status === 'RUNNING') return '#10b981'
    if (status.status === 'PAUSED') return '#f59e0b'
    return '#ef4444'
  }

  const isRunning = status?.status === 'RUNNING' || status?.status === 'PAUSED'
  const isStopped = status?.status === 'STOPPED' || !status?.status
  const isLive = status?.mode === 'LIVE'

  return (
    <div className="controls">
      <h2>Engine Controls</h2>

      <div className="status-indicator">
        <div
          className="status-dot"
          style={{ backgroundColor: getStatusColor() }}
        />
        <span className="status-text">
          {status?.status || 'STOPPED'}
          {status?.mode && status.mode !== 'STOPPED' && ` (${status.mode})`}
        </span>
        {isLive && <span className="live-badge">⚠️ LIVE</span>}
      </div>

      {/* Start buttons - only show when stopped */}
      {isStopped && (
        <div className="start-buttons">
          <button
            onClick={() => handleStart('SIM')}
            disabled={loading}
            className="btn-start-sim"
          >
            ▶️ Start SIM
          </button>
          <button
            onClick={() => handleStart('LIVE')}
            disabled={loading}
            className="btn-start-live"
          >
            🔴 Start LIVE
          </button>
        </div>
      )}

      {/* Control buttons - only show when running */}
      {isRunning && (
        <div className="control-buttons">
          <button
            onClick={handlePause}
            disabled={loading || status?.status !== 'RUNNING'}
            className="btn-pause"
          >
            ⏸ Pause
          </button>
          <button
            onClick={handleResume}
            disabled={loading || status?.status !== 'PAUSED'}
            className="btn-resume"
          >
            ▶ Resume
          </button>
          <button
            onClick={handleStop}
            disabled={loading}
            className="btn-stop"
          >
            ⏹ Stop
          </button>
        </div>
      )}

      {/* Emergency controls - only show when LIVE */}
      {isLive && isRunning && (
        <div className="emergency-controls">
          <h3>🚨 Emergency Controls</h3>
          <button
            onClick={handleFlatten}
            disabled={loading}
            className="btn-flatten"
          >
            📤 Flatten All
          </button>
          <button
            onClick={handleKillSwitch}
            disabled={loading}
            className="btn-killswitch"
          >
            🛑 KILL SWITCH
          </button>
        </div>
      )}

      {/* LIVE confirmation modal */}
      {showLiveConfirm && (
        <div className="modal-overlay">
          <div className="modal-content">
            <h3>⚠️ Start LIVE Trading?</h3>
            <p>You are about to start <strong>LIVE</strong> trading mode.</p>
            <p className="warning-text">
              • Real money is at risk<br/>
              • Ensure you have tested in SIM mode<br/>
              • Make sure Topstep credentials are set<br/>
              • Monitor closely during the session
            </p>
            <div className="modal-buttons">
              <button onClick={() => setShowLiveConfirm(false)} className="btn-cancel">
                Cancel
              </button>
              <button onClick={() => startEngine('LIVE')} className="btn-confirm-live">
                Yes, Start LIVE
              </button>
            </div>
          </div>
        </div>
      )}

      {message && (
        <div className={`control-message ${message.includes('ERROR') || message.includes('Failed') ? 'error' : 'success'}`}>
          {message}
        </div>
      )}
    </div>
  )
}
