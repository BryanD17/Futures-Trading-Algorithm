import { useState, useEffect } from 'react'
import { StatusService, ControlService } from '../services/api'
import './Controls.css'

export default function Controls() {
  const [status, setStatus] = useState<any>(null)
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState('')

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

  const handlePause = async () => {
    setLoading(true)
    try {
      const result = await ControlService.pause()
      setMessage(result.message)
    } catch (err: any) {
      setMessage('Failed to pause: ' + err.message)
    }
    setLoading(false)
  }

  const handleResume = async () => {
    setLoading(true)
    try {
      const result = await ControlService.resume()
      setMessage(result.message)
    } catch (err: any) {
      setMessage('Failed to resume: ' + err.message)
    }
    setLoading(false)
  }

  const getStatusColor = () => {
    if (!status) return '#6b7280'
    if (status.status === 'RUNNING') return '#10b981'
    if (status.status === 'PAUSED') return '#f59e0b'
    return '#ef4444'
  }

  return (
    <div className="controls">
      <h2>Engine Controls</h2>

      <div className="status-indicator">
        <div
          className="status-dot"
          style={{ backgroundColor: getStatusColor() }}
        />
        <span className="status-text">
          {status?.status || 'Unknown'} ({status?.mode || 'N/A'})
        </span>
      </div>

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
      </div>

      {message && (
        <div className="control-message">{message}</div>
      )}
    </div>
  )
}
