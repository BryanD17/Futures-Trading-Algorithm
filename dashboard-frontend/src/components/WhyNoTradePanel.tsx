import { useCallback, useEffect, useMemo, useState } from 'react';
import axios from 'axios';
import { SetupApi } from '../services/setupApi';
import {
  INSTRUMENT_PRECISION,
  TRADEABLE_SYMBOLS,
  type GateDecisionDto,
  type SetupSnapshotDto,
  type TradeableSymbol,
} from '../types/setup';

/**
 * V5 Agent 08 — "why no trade" panel.
 *
 * Answers "which gate killed it at 15:05 ET today?" from three sources:
 *  1. GET /api/setup          → gateDecisions (last 200 GateDecisionEvents,
 *                               oldest first) + gateCounts
 *  2. GET /api/setup/{symbol} → current SetupContext snapshot (strip)
 *  3. GET /api/status         → telemetry.gateCounts, only as a fallback when
 *                               /api/setup does not carry gateCounts
 *
 * Refresh: polling every 5 s. The dashboard WebSocket (/ws/stream) only
 * carries `account_update` frames, so there is no push channel for gates.
 * Read-only: nothing here can place, change or cancel an order.
 */

const POLL_MS = 5000;
const DEFAULT_ROWS = 50;
const MAX_ROWS = 200;

const ET_PARTS = new Intl.DateTimeFormat('en-US', {
  timeZone: 'America/New_York',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

interface EtStamp {
  date: string; // YYYY-MM-DD in ET
  time: string; // HH:mm in ET
}

function toEt(iso: string | null): EtStamp | null {
  if (!iso) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  const p: Record<string, string> = {};
  for (const part of ET_PARTS.formatToParts(d)) p[part.type] = part.value;
  const hour = p.hour === '24' ? '00' : p.hour;
  return { date: `${p.year}-${p.month}-${p.day}`, time: `${hour}:${p.minute}` };
}

/** Gates that are not setup-quality rejections but risk / sizing / plumbing vetoes. */
const HOT_GATE = /^(RISK|SIZE|WARMUP|ORDER)/i;

function fmtNum(n: number | null | undefined, digits = 2): string {
  if (n === null || n === undefined || !Number.isFinite(n)) return '·';
  if (n === 0) return '0';
  return Number.isInteger(n) ? String(n) : n.toFixed(digits);
}

interface StatusTelemetry {
  telemetry?: { gateCounts?: Record<string, number> };
}

export default function WhyNoTradePanel({ symbol }: { symbol: TradeableSymbol }) {
  const [decisions, setDecisions] = useState<GateDecisionDto[]>([]);
  const [gateCounts, setGateCounts] = useState<Record<string, number>>({});
  const [snapshot, setSnapshot] = useState<SetupSnapshotDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null);

  const [symbolFilter, setSymbolFilter] = useState<string>('ALL');
  const [sessionFilter, setSessionFilter] = useState<string>('ALL');
  const [expanded, setExpanded] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const list = await SetupApi.listActive();
      setDecisions(list.gateDecisions ?? []);
      if (list.gateCounts) {
        setGateCounts(list.gateCounts);
      } else {
        const st = await axios.get<StatusTelemetry>('/api/status', { timeout: 5000 });
        setGateCounts(st.data.telemetry?.gateCounts ?? {});
      }
      setError(null);
      setLastRefresh(new Date());
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'failed');
    }
  }, []);

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, POLL_MS);
    return () => clearInterval(id);
  }, [refresh]);

  useEffect(() => {
    let cancelled = false;
    const load = () =>
      SetupApi.getSetup(symbol)
        .then((s) => { if (!cancelled) setSnapshot(s); })
        .catch(() => { if (!cancelled) setSnapshot(null); });
    load();
    const id = setInterval(load, POLL_MS);
    return () => { cancelled = true; clearInterval(id); };
  }, [symbol]);

  const sessions = useMemo(() => {
    const set = new Set<string>();
    for (const d of decisions) if (d.session) set.add(d.session);
    return Array.from(set).sort();
  }, [decisions]);

  const filtered = useMemo(() => {
    // Backend returns oldest first; the table is newest first.
    const out: GateDecisionDto[] = [];
    for (let i = decisions.length - 1; i >= 0; i--) {
      const d = decisions[i];
      if (symbolFilter !== 'ALL' && d.symbol !== symbolFilter) continue;
      if (sessionFilter !== 'ALL' && d.session !== sessionFilter) continue;
      out.push(d);
    }
    return out;
  }, [decisions, symbolFilter, sessionFilter]);

  const limit = expanded ? MAX_ROWS : DEFAULT_ROWS;
  const rows = filtered.slice(0, limit);
  const todayEt = toEt(new Date().toISOString())?.date ?? '';

  const counts = Object.entries(gateCounts).sort((a, b) => b[1] - a[1]);

  return (
    <section className="setup-card why-no-trade" aria-label="Why no trade">
      <h3>
        Why no trade
        <span className="wnt-refresh">
          polling 5s{lastRefresh ? ` · ${lastRefresh.toLocaleTimeString()}` : ''}
        </span>
      </h3>

      {error && (
        <div className="setup-error" role="alert">
          Gate feed unavailable — retrying… ({error})
        </div>
      )}

      <CurrentSetupStrip symbol={symbol} snapshot={snapshot} />

      <div className="wnt-chips" aria-label="Gate counts">
        {counts.length === 0 ? (
          <span className="empty">No gate decisions counted yet.</span>
        ) : (
          counts.map(([gate, n]) => (
            <span key={gate} className={`pill wnt-chip ${HOT_GATE.test(gate) ? 'hot' : ''}`}>
              {gate}: <strong>{n}</strong>
            </span>
          ))
        )}
      </div>

      <div className="wnt-filters">
        <label>
          Symbol{' '}
          <select value={symbolFilter} onChange={(e) => setSymbolFilter(e.target.value)}>
            <option value="ALL">All</option>
            {TRADEABLE_SYMBOLS.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </label>
        <label>
          Session{' '}
          <select value={sessionFilter} onChange={(e) => setSessionFilter(e.target.value)}>
            <option value="ALL">All</option>
            {sessions.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </label>
        <span className="wnt-count">
          {rows.length} of {filtered.length} shown ({decisions.length} in buffer)
        </span>
        {filtered.length > DEFAULT_ROWS && (
          <button type="button" className="wnt-expand" onClick={() => setExpanded((x) => !x)}>
            {expanded ? `Show ${DEFAULT_ROWS}` : `Show up to ${MAX_ROWS}`}
          </button>
        )}
      </div>

      {rows.length === 0 ? (
        <p className="empty">
          No gate decisions yet — the engine publishes one per candle with a setup and per denial.
        </p>
      ) : (
        <div className="wnt-table-wrap">
          <table className="wnt-table">
            <thead>
              <tr>
                <th>Time (ET)</th>
                <th>Sym</th>
                <th>Session</th>
                <th>State</th>
                <th>Gate</th>
                <th>Reason</th>
                <th className="num">A</th>
                <th className="num">B</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((d, i) => {
                const et = toEt(d.candleTime);
                const prevDate = i === 0 ? todayEt : toEt(rows[i - 1].candleTime)?.date;
                const showDate = et !== null && et.date !== prevDate;
                return (
                  <tr
                    key={`${d.publishedAt}-${d.symbol}-${d.gate}-${i}`}
                    className={HOT_GATE.test(d.gate) ? 'hot' : ''}
                  >
                    <td className="mono">
                      {et ? (
                        <>
                          {showDate && <span className="wnt-date">{et.date.slice(5)} </span>}
                          {et.time}
                        </>
                      ) : '·'}
                    </td>
                    <td>{d.symbol}</td>
                    <td>{d.session ?? '·'}</td>
                    <td>{d.state ?? '·'}</td>
                    <td className="gate-cell">{d.gate}</td>
                    <td className="reason-cell">{d.reason ?? ''}</td>
                    <td className="num mono">{fmtNum(d.numberA)}</td>
                    <td className="num mono">{fmtNum(d.numberB)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

// ─── Current setup strip ───────────────────────────────────────────────────

function CurrentSetupStrip({
  symbol,
  snapshot,
}: {
  symbol: TradeableSymbol;
  snapshot: SetupSnapshotDto | null;
}) {
  if (!snapshot) {
    return <div className="wnt-strip empty">{symbol}: waiting for setup snapshot…</div>;
  }
  const p = INSTRUMENT_PRECISION[symbol];
  const px = (n: number | null | undefined) =>
    n === null || n === undefined || !Number.isFinite(n) || n === 0 ? '·' : n.toFixed(p);
  const o = snapshot.ote;
  const hasPlan = snapshot.entry > 0;
  const biasCls =
    snapshot.htfBias === 'BULLISH' ? 'bull' : snapshot.htfBias === 'BEARISH' ? 'bear' : '';
  return (
    <dl className="wnt-strip" aria-label="Current setup">
      <div>
        <dt>{symbol} session</dt>
        <dd>
          {snapshot.sessionWindow ?? '·'}
          {snapshot.primeKillzone && <span className="wnt-prime">PRIME</span>}
        </dd>
      </div>
      <div><dt>State</dt><dd>{snapshot.state}</dd></div>
      <div className={snapshot.lastGateFailed ? 'bad' : ''}>
        <dt>Last gate failed</dt><dd>{snapshot.lastGateFailed ?? '·'}</dd>
      </div>
      <div><dt>Bias</dt><dd className={biasCls}>{snapshot.htfBias}</dd></div>
      <div>
        <dt>Range H / EQ / L</dt>
        <dd className="mono">
          {px(snapshot.rangeHigh)} / {px(snapshot.rangeEq)} / {px(snapshot.rangeLow)}
        </dd>
      </div>
      <div>
        <dt>OTE [0.62, 0.79] · 1.0</dt>
        <dd className="mono">
          {o ? `[${px(o.f62)}, ${px(o.f79)}] · ${px(o.one00)}` : 'not built'}
        </dd>
      </div>
      <div>
        <dt>Entry / Stop / RR</dt>
        <dd className="mono">
          {hasPlan ? `${px(snapshot.entry)} / ${px(snapshot.stop)} / ${snapshot.rr.toFixed(2)}` : '·'}
        </dd>
      </div>
      <div><dt>Raid score</dt><dd className="mono">{snapshot.raidScore}</dd></div>
    </dl>
  );
}
