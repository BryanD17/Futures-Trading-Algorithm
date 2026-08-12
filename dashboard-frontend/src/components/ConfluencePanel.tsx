import { useEffect, useState } from 'react';
import axios from 'axios';
import './ConfluencePanel.css';

/**
 * THE CONFLUENCE STACK (V4 Agent 07) — every confluence fact for both
 * directions, side by side.
 *
 * Read-only. The engine's ConfluenceService aggregates facts that other
 * components already computed and gates nothing, so nothing on this panel can
 * influence a trade. Its job is to make "what is actually true right now"
 * answerable at a glance instead of by grepping [GATES].
 *
 * — is not a small no. It means the source could not answer (cold, not
 * running), and such fields are excluded from BOTH the score and the maximum
 * so a cold stack reads as cold rather than as a bad one.
 */

const POLL_MS = 15_000;
const SYMBOLS = ['MNQ', 'MES', 'MGC'] as const;

interface ConfluenceFieldDto {
  key: string;
  value: 'TRUE' | 'FALSE' | 'UNKNOWN';
  glyph: string;
  weight: number;
  owner: string;
  detail: string | null;
}

interface ConfluenceSideDto {
  direction: 'LONG' | 'SHORT';
  score: number;
  maxScore: number;
  ratio: number;
  trueCount: number;
  knownCount: number;
  fieldCount: number;
  top: string[];
  fields: ConfluenceFieldDto[];
}

interface ConfluenceDto {
  symbol: string;
  long: ConfluenceSideDto;
  short: ConfluenceSideDto;
  line: string;
}

export default function ConfluencePanel() {
  const [symbol, setSymbol] = useState<(typeof SYMBOLS)[number]>('MNQ');
  const [data, setData] = useState<ConfluenceDto | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const r = await axios.get<ConfluenceDto>(`/api/confluence/${symbol}`, {
          timeout: 5000,
        });
        if (!cancelled) {
          setData(r.data);
          setError(null);
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    };
    load();
    const id = setInterval(load, POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [symbol]);

  return (
    <section className="setup-card confluence" aria-label="Confluence stack">
      <div className="confluence-head">
        <h3>Confluence stack</h3>
        <div className="confluence-symbols" role="tablist" aria-label="Symbol">
          {SYMBOLS.map((s) => (
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
        </div>
      </div>

      {error && !data && (
        <div className="confluence-error" role="alert">
          Confluence unavailable: {error}
        </div>
      )}

      {!data && !error && <div className="confluence-empty">Loading…</div>}

      {data && (
        <>
          <div className="confluence-scores">
            <Score side={data.long} label="LONG" />
            <Score side={data.short} label="SHORT" />
          </div>

          <table className="confluence-table">
            <thead>
              <tr>
                <th scope="col">Confluence</th>
                <th scope="col" className="dir">Long</th>
                <th scope="col" className="dir">Short</th>
                <th scope="col" className="w">w</th>
              </tr>
            </thead>
            <tbody>
              {data.long.fields.map((f, i) => {
                const s = data.short.fields[i];
                return (
                  <tr key={f.key}>
                    <th scope="row" title={`source: ${f.owner}`}>
                      {f.key}
                      {f.detail && <span className="detail">{f.detail}</span>}
                    </th>
                    <td className={`dir v-${f.value.toLowerCase()}`}>{f.glyph}</td>
                    <td className={`dir v-${s.value.toLowerCase()}`}>{s.glyph}</td>
                    <td className="w">{f.weight}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>

          <p className="confluence-note">
            <strong>—</strong> means the source could not answer (cold or not
            running). Those fields count for neither side, which is why a cold
            stack shows a small score out of a small maximum. Read-only: nothing
            here gates a trade.
          </p>
          <code className="confluence-line">{data.line}</code>
        </>
      )}
    </section>
  );
}

function Score({ side, label }: { side: ConfluenceSideDto; label: string }) {
  const pct = Math.round(side.ratio * 100);
  return (
    <div className={`confluence-score ${label.toLowerCase()}`}>
      <span className="label">{label}</span>
      <span className="value">
        {side.trueCount}/{side.knownCount}
      </span>
      <span className="weighted">
        w={side.ratio.toFixed(2)} ({side.score}/{side.maxScore})
      </span>
      <span className="bar">
        <i style={{ width: `${pct}%` }} />
      </span>
      {side.top.length > 0 && <span className="top">top: {side.top.join(', ')}</span>}
    </div>
  );
}
