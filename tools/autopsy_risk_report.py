"""Agent 08 risk-envelope proof + quality report from a FunnelAutopsyHarness run.

Usage: python tools/autopsy_risk_report.py <autopsy-out-dir> [dll=1000 mll=2000 maxContracts=5 maxTotal=10]

Reads candles.csv + summary.md and prints:
  - per-session trade matrix (signals, fills, closed, W/L, sum R, avg R, max DD in $ from the trade log)
  - risk envelope: worst daily loss vs DLL, peak-to-trough drawdown vs MLL, max order quantity vs
    maxContracts, positions open at/after 15:45 ET, entries inside NO_ENTRY / WEEKEND, risk denials
  - S1..S6 starvation checks
"""
import csv, re, sys, os, io, collections, datetime as dt
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

out = sys.argv[1]
kw = dict(a.split("=") for a in sys.argv[2:] if "=" in a)
DLL = float(kw.get("dll", 1000)); MLL = float(kw.get("mll", 2000))
MAXC = int(kw.get("maxContracts", 5)); MAXT = int(kw.get("maxTotal", 10))

rows = list(csv.DictReader(open(os.path.join(out, "candles.csv"), encoding="utf-8")))
summary = open(os.path.join(out, "summary.md"), encoding="utf-8").read()
log = [l[2:] for l in summary.splitlines() if l.startswith("- 20")]

sig = re.compile(r"^(\S+ \S+) ET (\S+) \| (LONG|SHORT)_ENTRY e=([\d.]+) s=([\d.]+) t=([\d.]+) rr=([\d.]+) q=(\d+) \| (ALLOW|DENY)(?: qty=(\d+))?")
clo = re.compile(r"^(\S+ \S+) ET (\S+) \| CLOSED (\w+) q=(\d+) in=([\d.]+) out=([\d.]+) pnl=(-?[\d.]+) R=(-?[\d.]+)")
signals, closes, denials, cancels = [], [], [], []
for l in log:
    m = sig.match(l)
    if m:
        signals.append(dict(ts=m[1], sess=m[2], side=m[3], e=float(m[4]), s=float(m[5]), t=float(m[6]), rr=float(m[7]),
                            q=int(m[8]), decision=m[9], qty=int(m[10]) if m[10] else 0, line=l))
        if m[9] == "DENY": denials.append(l)
        continue
    m = clo.match(l)
    if m:
        closes.append(dict(ts=m[1], sess=m[2], q=int(m[4]), pnl=float(m[7]), R=float(m[8]), line=l)); continue
    if "ORDER: cancelled" in l: cancels.append(l)

SESS = ["ASIA", "LONDON", "PRE_NY", "NY_AM", "NY_LUNCH", "NY_PM", "PRE_ASIA", "NO_ENTRY", "WEEKEND"]
per = {s: dict(bars=0, sweep=0, mss=0, ote=0, signals=0, fills=0, closed=0, W=0, L=0, R=0.0, pnl=0.0) for s in SESS}
days = collections.defaultdict(set)
for r in rows:
    s = r["session"]; per[s]["bars"] += 1; days[s].add(r["ts_ET"][:10])
    a, b = r["state_after"], r["state_before"]
    if a == "SWEEP_DONE" and b != a: per[s]["sweep"] += 1
    if a == "MSS_CONFIRMED" and b != a: per[s]["mss"] += 1
    if a == "OTE_ARMED" and b != a: per[s]["ote"] += 1
# fills from the per-session counts table in summary.md (harness counts fill at the moment of fill)
for l in summary.splitlines():
    m = re.match(r"^\| (\w+) \| (\d+) \| (\d+) \| (\d+) \| (\d+) \| (\d+) \| (\d+) \| (\d+) \| (\d+) \| (\d+) \| (\d+) \|$", l)
    if m and m[1] in per:
        per[m[1]]["signals"] = int(m[5]); per[m[1]]["fills"] = int(m[8])
for c in closes:
    p = per[c["sess"]]; p["closed"] += 1; p["R"] += c["R"]; p["pnl"] += c["pnl"]
    if c["pnl"] > 0: p["W"] += 1
    else: p["L"] += 1

print("## ALL-SESSIONS TRADE MATRIX")
print("| session | bars | days | geSWEEP | geMSS | geOTE | signals | fills | closed | W/L | sumR | avgR | pnl$ |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for s in SESS:
    p = per[s]; n = p["closed"]
    print(f"| {s} | {p['bars']} | {len(days[s])} | {p['sweep']} | {p['mss']} | {p['ote']} | {p['signals']} | {p['fills']} | {n} | {p['W']}/{p['L']} | {p['R']:.2f} | {(p['R']/n if n else 0):.2f} | {p['pnl']:.2f} |")

# risk envelope
ET_close_block = lambda ts: (ts[11:16] >= "15:45" or ts[11:16] < "18:00") and False  # placeholder
daily = collections.defaultdict(float)
for c in closes:
    d = c["ts"][:10]; daily[d] += c["pnl"]
equity, peak, maxdd = 0.0, 0.0, 0.0
for c in sorted(closes, key=lambda x: x["ts"]):
    equity += c["pnl"]; peak = max(peak, equity); maxdd = max(maxdd, peak - equity)
maxq = max([s["qty"] for s in signals if s["decision"] == "ALLOW"] or [0])
maxpos = max(int(r["positions"]) for r in rows)
open_after = [r["ts_ET"] for r in rows if int(r["positions"]) > 0 and r["session"] in ("NO_ENTRY", "WEEKEND")]
entries_blocked = [s["line"] for s in signals if s["sess"] in ("NO_ENTRY", "WEEKEND")]
worst_day = min(daily.values()) if daily else 0.0
print("\n## RISK ENVELOPE")
print(f"- worst daily P&L: {worst_day:.2f} vs DLL -{DLL:.0f} -> {'OK' if worst_day > -DLL else 'BREACH'}")
print(f"- peak-to-trough drawdown: {maxdd:.2f} vs MLL {MLL:.0f} -> {'OK' if maxdd < MLL else 'BREACH'}")
print(f"- max order quantity: {maxq} vs maxContracts {MAXC} -> {'OK' if maxq <= MAXC else 'BREACH'}; max simultaneous positions {maxpos} (total cap {MAXT})")
print(f"- bars with an open position inside NO_ENTRY/WEEKEND: {len(open_after)} {open_after[:3]}")
print(f"- entries inside NO_ENTRY/WEEKEND: {len(entries_blocked)} -> {'OK' if not entries_blocked else 'BREACH'}")
print(f"- risk-engine denials: {len(denials)}; orphan cancels: {len(cancels)}")
print(f"- net P&L {sum(c['pnl'] for c in closes):.2f}, sum R {sum(c['R'] for c in closes):.2f}, closed {len(closes)}, "
      f"W {sum(1 for c in closes if c['pnl']>0)} / L {sum(1 for c in closes if c['pnl']<=0)}, daily P&L {dict(sorted(daily.items()))}")

# starvation checks
deaths = collections.Counter()
for r in rows:
    if r["state_after"] == "INVALIDATED" and r["state_before"] in ("SWEEP_DONE", "DISPLACED", "MSS_CONFIRMED", "OTE_ARMED", "IN_TRADE"):
        deaths[r["state_before"] + ":" + r["death"][:28]] += 1
tot = sum(deaths.values()); top = deaths.most_common(1)[0] if deaths else ("", 0)
s1 = [s for s in SESS[:7] if len(days[s]) >= 3 and per[s]["fills"] == 0]
allowed = [s for s in signals if s["decision"] == "ALLOW"]
tdays = len(set(r["ts_ET"][:10] for r in rows if r["session"] not in ("WEEKEND",)))
print("\n## STARVATION CHECKS")
print(f"- S1 sessions (>=3 days, 0 fills): {s1 or 'none'} -> {'TRUE' if s1 else 'false'}")
print(f"- S2 single gate > 50% of post-sweep deaths: top = {top[0]} {top[1]}/{tot} ({(100*top[1]/tot if tot else 0):.0f}%) -> {'TRUE' if tot and top[1] > tot/2 else 'false'}")
print(f"- S3 signals allowed {len(allowed)} vs fills {sum(p['fills'] for p in per.values())} vs cancels {len(cancels)} (gap must be explained) -> {'false' if len(allowed) <= sum(p['fills'] for p in per.values()) + len(cancels) else 'CHECK'}")
print(f"- S6 trades/day: {len(closes)} closed over {tdays} days = {len(closes)/max(1,tdays):.2f} -> {'false' if len(closes)/max(1,tdays) >= 1.0 else 'TRUE'}")
print("- S4 / S5: see the trade audit and golden-case sections")
