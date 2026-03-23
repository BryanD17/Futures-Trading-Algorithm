import { useState, useEffect } from 'react'
import './App.css'
import Overview from './components/Overview'
import Positions from './components/Positions'
import Trades from './components/Trades'
import Risk from './components/Risk'
import Journal from './components/Journal'
import Controls from './components/Controls'
import PhaseTracker from './components/PhaseTracker'
import PassProbabilityGauge from './components/PassProbabilityGauge'
import EquityFanChart from './components/EquityFanChart'
import SimulatorView from './components/SimulatorView'
import ChallengeEconomicsPanel from './components/ChallengeEconomicsPanel'
import { StatusService } from './services/api'

function App() {
  const [activeTab, setActiveTab] = useState('overview')
  const [status, setStatus] = useState<any>(null)

  useEffect(() => {
    // Fetch initial status
    StatusService.getStatus()
      .then(data => setStatus(data))
      .catch(err => console.error('Failed to fetch status:', err))

    // Poll status every 5 seconds
    const interval = setInterval(() => {
      StatusService.getStatus()
        .then(data => setStatus(data))
        .catch(err => console.error('Failed to fetch status:', err))
    }, 5000)

    return () => clearInterval(interval)
  }, [])

  return (
    <div className="app">
      <header className="header">
        <h1>Topstep Trading Dashboard</h1>
        {status && status.mode && (
          <div className="status-badge">
            <span className={`status-indicator ${status.mode.toLowerCase()}`}></span>
            {status.mode}
          </div>
        )}
      </header>

      <nav className="tabs">
        <button
          className={activeTab === 'overview' ? 'active' : ''}
          onClick={() => setActiveTab('overview')}
        >
          Overview
        </button>
        <button
          className={activeTab === 'positions' ? 'active' : ''}
          onClick={() => setActiveTab('positions')}
        >
          Positions
        </button>
        <button
          className={activeTab === 'trades' ? 'active' : ''}
          onClick={() => setActiveTab('trades')}
        >
          Trades
        </button>
        <button
          className={activeTab === 'risk' ? 'active' : ''}
          onClick={() => setActiveTab('risk')}
        >
          Risk
        </button>
        <button
          className={activeTab === 'journal' ? 'active' : ''}
          onClick={() => setActiveTab('journal')}
        >
          Journal
        </button>
        <button
          className={activeTab === 'simulator' ? 'active' : ''}
          onClick={() => setActiveTab('simulator')}
        >
          Simulator
        </button>
        <button
          className={activeTab === 'economics' ? 'active' : ''}
          onClick={() => setActiveTab('economics')}
        >
          Economics
        </button>
      </nav>

      <main className="content">
        <Controls />
        <PhaseTracker />
        <PassProbabilityGauge />
        {activeTab === 'overview' && (
          <>
            <EquityFanChart />
            <Overview />
          </>
        )}
        {activeTab === 'positions' && <Positions />}
        {activeTab === 'trades' && <Trades />}
        {activeTab === 'risk' && <Risk />}
        {activeTab === 'journal' && <Journal />}
        {activeTab === 'simulator' && <SimulatorView />}
        {activeTab === 'economics' && <ChallengeEconomicsPanel />}
      </main>
    </div>
  )
}

export default App
