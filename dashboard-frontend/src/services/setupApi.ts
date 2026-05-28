import axios from 'axios';
import type {
  ActiveSetupListDto,
  InstrumentDto,
  ProjectionDto,
  SetupSnapshotDto,
  TradeableSymbol,
} from '../types/setup';

const api = axios.create({
  baseURL: '/api/setup',
  timeout: 5000,
  headers: { 'Content-Type': 'application/json' },
});

export const SetupApi = {
  /** Active strategy + symbols currently registered in the engine. */
  async listActive(): Promise<ActiveSetupListDto> {
    const r = await api.get<ActiveSetupListDto>('');
    return r.data;
  },

  /** Per-instrument specs (tick / point / micros / raid floor). */
  async listInstruments(): Promise<InstrumentDto[]> {
    const r = await api.get<InstrumentDto[]>('/instruments');
    return r.data;
  },

  /** Full setup snapshot for an instrument. 404 only on non-tradeable symbols. */
  async getSetup(symbol: TradeableSymbol): Promise<SetupSnapshotDto> {
    const r = await api.get<SetupSnapshotDto>(`/${symbol}`);
    return r.data;
  },

  /** Just the STDV projection ladder for an instrument. */
  async getProjections(symbol: TradeableSymbol): Promise<ProjectionDto[]> {
    const r = await api.get<ProjectionDto[]>(`/${symbol}/projections`);
    return r.data;
  },
};
