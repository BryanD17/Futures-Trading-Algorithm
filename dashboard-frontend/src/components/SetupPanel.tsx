import { useCallback, useEffect, useState } from 'react';
import ConfluencePanel from './ConfluencePanel';
import './SetupPanel.css';
import { SetupApi } from '../services/setupApi';
import { ChartApi } from '../services/chartApi';
import type { BotChartResponse, ChartSymbol } from '../types/chart';
import {
  INSTRUMENT_PRECISION,
  MANDATORY_GATES,
  STATE_ORDER,
  TRADEABLE_SYMBOLS,
  type ProjectionDto,
  type SetupSnapshotDto,
  type SetupStateName,
  type TradeableSymbol,
} from '../types/setup';

/**
 * Live STDV+OTE setup view.
 *
 * Per-instrument tabs (MNQ / MES / MGC) render the state-machine stepper,
 * HTF bias / killzone / SMT pills, the M1..M9 confluence checklist with
 * the last failed gate surfaced, the STDV projection ladder (with
 * liquidity-backed and realism badges), the OTE zone, and (when armed)
 * the planned entry / stop / target / RR / tier / size.
 *
 * Data source: /api/setup/{symbol}, polled every 1s. WebSocket push is a
 * follow-up; polling is intentionally short-window so UI lag stays sub-3s.
 */
export default function SetupPanel() {
  const [symbol, setSymbol] = useState<TradeableSymbol>('MNQ');
  const [snapshot, setSnapshot] = useState<SetupSnapshotDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchSnapshot = useCallback(async (s: TradeableSymbol) => {
    try {
      const data = await SetupApi.getSetup(s);
      setSnapshot(data);
      setError(null);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'failed';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    setLoading(true);
    fetchSnapshot(symbol);
    const id = setInterval(() => fetchSnapshot(symbol), 1000);
    return () => clearInterval(id);
  }, [symbol, fetchSnapshot]);

  // Warm/cold tripwire (V2 Agent 03): the chart-warmth flag from
  // /api/chart, shown next to the HTF/Killzone/SMT pills so the one
  // screen the owner watches carries it. 15s cadence is plenty — warmth
  // only changes at engine start/stop.
  const [chartWarmth, setChartWarmth] = useState<BotChartResponse | null>(null);
  useEffect(() => {
    let cancelled = false;
    const fetchWarmth = () =>
      ChartApi.getChart(symbol as ChartSymbol, 1)
        .then((r) => { if (!cancelled) setChartWarmth(r); })
        .catch(() => { if (!cancelled) setChartWarmth(null); });
    fetchWarmth();
    const id = setInterval(fetchWarmth, 15_000);
    return () => { cancelled = true; clearInterval(id); };
  }, [symbol]);

  const precision = INSTRUMENT_PRECISION[symbol];

  return (
    <div className="setup-panel" aria-label="STDV+OTE setup state">
      <div className="setup-toolbar" role="tablist" aria-label="Instrument">
        {TRADEABLE_SYMBOLS.map((s) => (
          <button
            key={s}
            role="tab"
            aria-selected={symbol === s}
            className={symbol === s ? 'active' : ''}
            onClick={() => setSymbol(s)}
          >
            {s}
          </button>
        ))}
        <div className="setup-strategy-tag" aria-label="Active strategy">
          STDV_OTE
        </div>
      </div>

      {error && (
        <div className="setup-error" role="alert">
          Backend disconnected — retrying… ({error})
        </div>
      )}

      {loading && !snapshot && <div className="setup-empty">Waiting for engine…</div>}

      {snapshot && (
        <>
          <StateMachineStepper state={snapshot.state} lastGateFailed={snapshot.lastGateFailed} />

          <div className="setup-row">
            <BiasBadge bias={snapshot.htfBias} bullish={snapshot.legBullish} />
            <KillzonePill open={snapshot.killzoneOpen} />
            <SmtPill state={snapshot.smtState} />
            {snapshot.tier && <TierBadge tier={snapshot.tier} />}
            <WarmPill warmth={chartWarmth} />
          </div>

          {snapshot.vote?.votes && (
            <div className="setup-row vote-row" aria-label="3-of-4 bias vote">
              <span className="vote-mode">VOTE {snapshot.vote.mode}</span>
              {snapshot.vote.votes.map((v) => (
                <span
                  key={v.source}
                  className={`pill vote-pill vote-${v.direction.toLowerCase()}`}
                  title={v.detail}
                >
                  {v.source}: <strong>{v.direction}</strong>
                </span>
              ))}
              <span className="vote-final">
                → {snapshot.vote.finalBias} (agree: {String(snapshot.vote.agree)})
              </span>
            </div>
          )}

          <div className="setup-grid">
            <ConfluenceChecklist snapshot={snapshot} />
            <OteBlock snapshot={snapshot} precision={precision} />
            <StdvLadder projections={snapshot.projections} precision={precision} />
          </div>

          {snapshot.state === 'IN_TRADE' || snapshot.state === 'MANAGING' || snapshot.state === 'DONE' ? (
            <PlanBlock snapshot={snapshot} precision={precision} />
          ) : null}

          {/* V4 Agent 07 — the confluence stack. Read-only: it reports what is
              true right now, and nothing on it gates a trade. */}
          <ConfluencePanel />
        </>
      )}
    </div>
  );
}

// ─── State machine stepper ─────────────────────────────────────────────────

function StateMachineStepper({
  state,
  lastGateFailed,
}: {
  state: SetupStateName;
  lastGateFailed: string | null;
}) {
  const currentIdx = STATE_ORDER.indexOf(state);
  return (
    <ol className="setup-stepper" aria-label="State machine progress">
      {STATE_ORDER.map((s, i) => {
        const reached = currentIdx >= i && currentIdx >= 0;
        const current = i === currentIdx;
        return (
          <li
            key={s}
            className={`step ${reached ? 'reached' : ''} ${current ? 'current' : ''}`}
            aria-current={current ? 'step' : undefined}
          >
            <span className="step-dot" aria-hidden="true" />
            <span className="step-label">{s.replace(/_/g, ' ')}</span>
          </li>
        );
      })}
      {state === 'INVALIDATED' && (
        <li className="step invalidated">
          INVALIDATED
          {lastGateFailed && <span className="step-detail"> — {lastGateFailed}</span>}
        </li>
      )}
    </ol>
  );
}

// ─── Pills / badges ────────────────────────────────────────────────────────

function BiasBadge({ bias, bullish }: { bias: string; bullish: boolean }) {
  const cls = bias === 'BULLISH' ? 'bull' : bias === 'BEARISH' ? 'bear' : 'neutral';
  return (
    <div className={`pill bias ${cls}`}>
      HTF: <strong>{bias}</strong>
      {bullish && bias !== 'NEUTRAL' ? ' ↑' : ''}
      {!bullish && bias === 'BEARISH' ? ' ↓' : ''}
    </div>
  );
}

function KillzonePill({ open }: { open: boolean }) {
  return (
    <div className={`pill killzone ${open ? 'open' : 'closed'}`}>
      Killzone: <strong>{open ? 'OPEN' : 'CLOSED'}</strong>
    </div>
  );
}

function SmtPill({ state }: { state: string }) {
  return (
    <div className={`pill smt smt-${state.toLowerCase()}`}>
      SMT: <strong>{state}</strong>
    </div>
  );
}

function WarmPill({ warmth }: { warmth: BotChartResponse | null }) {
  if (!warmth) {
    return <div className="pill chartwarm unknown">Chart: <strong>?</strong></div>;
  }
  return warmth.warm ? (
    <div className="pill chartwarm warm">
      CHART WARM <strong>({warmth.barsIngested1m} bars)</strong>
    </div>
  ) : (
    <div className="pill chartwarm cold" role="alert">
      CHART <strong>COLD</strong>
    </div>
  );
}

function TierBadge({ tier }: { tier: string }) {
  return <div className={`pill tier ${tier.toLowerCase()}`}>{tier.replace('_', ' ')}</div>;
}

// ─── Confluence checklist ──────────────────────────────────────────────────

function ConfluenceChecklist({ snapshot }: { snapshot: SetupSnapshotDto }) {
  const stateIdx = STATE_ORDER.indexOf(snapshot.state);
  const passed: Record<string, boolean> = {
    M1: snapshot.symbol === 'MNQ' || snapshot.symbol === 'MES' || snapshot.symbol === 'MGC',
    M2: snapshot.htfBias !== 'NEUTRAL',
    // M2b/M7b pass unless the evaluator is in its blocking mode and its
    // last gate evaluation rejected (LOG/OFF modes never block).
    M2b: !snapshot.pd || snapshot.pd.gatePassing,
    M7b: !snapshot.ote30m || snapshot.ote30m.gatePassing,
    M3: snapshot.killzoneOpen,
    M4: !!snapshot.sweep && snapshot.raidScore > 0,
    M5: snapshot.displacement && !!snapshot.fvg,
    M6: snapshot.mss,
    M7:
      !!snapshot.ote &&
      snapshot.pdArrayInOte !== null &&
      snapshot.entry > 0 &&
      snapshot.rr >= 2.0,
    M8: snapshot.sizeRequest >= 5 && snapshot.sizeRequest <= 20,
    M9: stateIdx >= STATE_ORDER.indexOf('IN_TRADE'),
  };
  const failedGate = snapshot.lastGateFailed;
  return (
    <section className="setup-card checklist" aria-label="Mandatory gates">
      <h3>Mandatory gates (M1..M9)</h3>
      <ul>
        {MANDATORY_GATES.map((g) => {
          const ok = passed[g.id];
          const isFailing = failedGate === g.id;
          return (
            <li key={g.id} className={`gate ${ok ? 'ok' : 'pending'} ${isFailing ? 'failing' : ''}`}>
              <span className="gate-id">{g.id}</span>
              <span className="gate-icon" aria-hidden="true">
                {ok ? '✓' : isFailing ? '✕' : '·'}
              </span>
              <span className="gate-label">{g.label}</span>
            </li>
          );
        })}
      </ul>
      {failedGate && (
        <p className="failed-gate" role="status">
          Last failed gate: <strong>{failedGate}</strong>
        </p>
      )}
      {snapshot.pd && (
        <p className="pd-gate-stats" role="status">
          PD gate <strong>{snapshot.pd.mode}</strong> — would-block L/S:{' '}
          {snapshot.pd.wouldBlockLong}/{snapshot.pd.wouldBlockShort}
          {snapshot.pd.mode === 'BLOCK' && (
            <>
              {' '}· blocked L/S: {snapshot.pd.blockedLong}/{snapshot.pd.blockedShort}
            </>
          )}
        </p>
      )}
    </section>
  );
}

// ─── STDV ladder ───────────────────────────────────────────────────────────

function StdvLadder({
  projections,
  precision,
}: {
  projections: ProjectionDto[];
  precision: number;
}) {
  return (
    <section className="setup-card ladder" aria-label="STDV projection ladder">
      <h3>STDV ladder (exits)</h3>
      {projections.length === 0 ? (
        <p className="empty">No manipulation leg yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>σ</th>
              <th>Price</th>
              <th>Liquidity</th>
              <th>Realism</th>
            </tr>
          </thead>
          <tbody>
            {projections.map((p) => (
              <tr key={p.sigma} className={p.sigma === -2 ? 'primary' : ''}>
                <td>{p.sigma.toFixed(2)}</td>
                <td>{p.snappedPrice.toFixed(precision)}</td>
                <td>
                  {p.isLiquidityBacked ? (
                    <span className="liq-backed" title={p.snappedLevelType || ''}>
                      {p.snappedLevelType}
                    </span>
                  ) : (
                    <span className="liq-raw">raw</span>
                  )}
                </td>
                <td>
                  {p.realismTag === 'n/a' ? '·' : (
                    <span className={`realism realism-${p.realismTag.toLowerCase()}`}>
                      {p.realismTag}
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

// ─── OTE block ─────────────────────────────────────────────────────────────

function OteBlock({
  snapshot,
  precision,
}: {
  snapshot: SetupSnapshotDto;
  precision: number;
}) {
  if (!snapshot.ote) {
    return (
      <section className="setup-card ote" aria-label="OTE zone">
        <h3>OTE zone (entries)</h3>
        <p className="empty">No OTE zone built yet.</p>
      </section>
    );
  }
  const o = snapshot.ote;
  return (
    <section className="setup-card ote" aria-label="OTE zone">
      <h3>OTE zone (entries) {o.bullish ? '↑ bullish' : '↓ bearish'}</h3>
      <dl className="ote-levels">
        <div><dt>1.0 (invalidation)</dt><dd>{o.one00.toFixed(precision)}</dd></div>
        <div><dt>0.79 (deep)</dt><dd>{o.f79.toFixed(precision)}</dd></div>
        <div className="primary"><dt>0.705 (precise)</dt><dd>{o.f705.toFixed(precision)}</dd></div>
        <div><dt>0.62 (near)</dt><dd>{o.f62.toFixed(precision)}</dd></div>
        <div><dt>0.50 (equilibrium)</dt><dd>{o.eq50.toFixed(precision)}</dd></div>
      </dl>
      {snapshot.pdArrayInOte !== null && (
        <p className="pd-array">
          PD array ({snapshot.pdArrayKind ?? 'in-zone'}) edge:{' '}
          <strong>{snapshot.pdArrayInOte.toFixed(precision)}</strong>
        </p>
      )}
    </section>
  );
}

// ─── Plan block ────────────────────────────────────────────────────────────

function PlanBlock({
  snapshot,
  precision,
}: {
  snapshot: SetupSnapshotDto;
  precision: number;
}) {
  return (
    <section className="setup-card plan" aria-label="Planned trade">
      <h3>Plan</h3>
      <div className="plan-grid">
        <div><span className="k">Entry</span><span className="v">{snapshot.entry.toFixed(precision)}</span></div>
        <div><span className="k">Stop</span><span className="v">{snapshot.stop.toFixed(precision)}</span></div>
        <div><span className="k">RR</span><span className="v">{snapshot.rr.toFixed(2)}</span></div>
        <div><span className="k">Size</span><span className="v">{snapshot.sizeFilled} micros</span></div>
        <div><span className="k">Tier</span><span className="v">{snapshot.tier ?? '·'}</span></div>
      </div>
      {snapshot.confluenceFactors.length > 0 && (
        <ul className="factors">
          {snapshot.confluenceFactors.map((f, i) => (
            <li key={i}>{f}</li>
          ))}
        </ul>
      )}
    </section>
  );
}
