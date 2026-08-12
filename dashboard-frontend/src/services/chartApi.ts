import axios from 'axios';
import type {
  BotChartResponse,
  ChartSymbol,
  DetectionTimeframe,
} from '../types/chart';

const api = axios.create({
  baseURL: '/api/chart',
  timeout: 5000,
  headers: { 'Content-Type': 'application/json' },
});

export const ChartApi = {
  /**
   * The bot's internal 30m chart + OTE overlay + warm flag for a symbol.
   * Unknown/cold symbols return an honest empty shape (warm=false), never
   * a 500 — callers must branch on `warm` and `candles30m.length`.
   */
  async getChart(
    symbol: ChartSymbol,
    lookback: number,
    detections: DetectionTimeframe = '15m',
  ): Promise<BotChartResponse> {
    const r = await api.get<BotChartResponse>(`/${symbol}`, {
      params: { lookback, detections },
    });
    return r.data;
  },
};
