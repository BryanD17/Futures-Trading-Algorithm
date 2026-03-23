import axios from 'axios'
import type { Status, Position, Trade, RiskMetrics, DailyMetrics } from '../types'

const API_BASE = '/api'

const api = axios.create({
  baseURL: API_BASE,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
})

export const StatusService = {
  async getStatus(): Promise<Status> {
    const response = await api.get('/status')
    return response.data
  },

  async getHealth(): Promise<{ status: string }> {
    const response = await api.get('/status/health')
    return response.data
  }
}

export const PositionsService = {
  async getPositions(): Promise<Position[]> {
    const response = await api.get('/positions')
    return response.data
  }
}

export const TradesService = {
  async getTrades(page = 0, size = 50): Promise<Trade[]> {
    const response = await api.get(`/trades?page=${page}&size=${size}`)
    return response.data
  }
}

export const RiskService = {
  async getRiskMetrics(): Promise<RiskMetrics> {
    const response = await api.get('/risk')
    return response.data
  }
}

export const MetricsService = {
  async getDailyMetrics(): Promise<DailyMetrics> {
    const response = await api.get('/metrics/daily')
    return response.data
  }
}

export const JournalService = {
  async getJournal(filters?: Record<string, string>): Promise<any[]> {
    const params = filters ? new URLSearchParams(filters).toString() : ''
    const response = await api.get(`/journal${params ? '?' + params : ''}`)
    return response.data
  },

  async getStats(): Promise<any> {
    const response = await api.get('/journal/stats')
    return response.data
  }
}

export const LifecycleService = {
  async getLifecycle(): Promise<any> {
    const response = await api.get('/lifecycle')
    return response.data
  },

  async getPassProbability(): Promise<any> {
    const response = await api.get('/lifecycle/pass-probability')
    return response.data
  },

  async getEquityFan(): Promise<any> {
    const response = await api.get('/lifecycle/equity-fan')
    return response.data
  },

  async getEconomics(): Promise<any> {
    const response = await api.get('/lifecycle/economics')
    return response.data
  }
}

export const SimulationService = {
  async runSimulation(params: any): Promise<any> {
    const response = await api.post('/simulate', params)
    return response.data
  },

  async runOptimization(params: any): Promise<any> {
    const response = await api.post('/optimize', params)
    return response.data
  },

  async getResult(id: number): Promise<any> {
    const response = await api.get(`/simulate/results/${id}`)
    return response.data
  },

  async getRecentResults(): Promise<any[]> {
    const response = await api.get('/simulate/results')
    return response.data
  }
}

export const ControlService = {
  async start(mode: string = 'SIM'): Promise<{ status: string; message: string; warning?: string }> {
    const response = await api.post(`/control/start?mode=${mode}`)
    return response.data
  },

  async pause(): Promise<{ status: string; message: string }> {
    const response = await api.post('/control/pause')
    return response.data
  },

  async resume(): Promise<{ status: string; message: string }> {
    const response = await api.post('/control/resume')
    return response.data
  },

  async stop(): Promise<{ status: string; message: string }> {
    const response = await api.post('/control/stop')
    return response.data
  },

  async killSwitch(reason: string): Promise<{ status: string; message: string }> {
    const response = await api.post(`/control/killswitch?reason=${encodeURIComponent(reason)}`)
    return response.data
  },

  async flatten(reason: string): Promise<{ status: string; message: string }> {
    const response = await api.post(`/control/flatten?reason=${encodeURIComponent(reason)}`)
    return response.data
  }
}
