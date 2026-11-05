#!/usr/bin/env python3
"""V6 Agent 05.4 — Java oracle vs TradingView List-of-Trades parity diff.

    python3 tools/pine_parity.py parity/java_signals.csv parity/tv_trades.csv \
        [--out PARITY_REPORT.md] [--tv-tz America/Chicago] [--tick 0.25] [--bar-min 5]

Inputs
  java_signals.csv  GoldenSignalExport (Agent 00). Key = first 12 columns; ts_et is
                    the 1m emit candle in America/New_York.
  tv_trades.csv     Strategy Tester -> List of trades -> Export (OA-2). Parsed BY HEADER
                    NAME, so both TradingView layouts work:
                      older: Trade #, Type, Signal, Date/Time, Price USD, Contracts, Profit USD, ...
                      newer: Trade #, Type, Date/Time (or "Date and time"), Signal, Price USD,
                             Position size (qty), Net P&L USD, ...
                    One trade = two rows ("Entry long|short" + "Exit long|short") sharing Trade #.
                    Date/Time is in the CHART timezone (owner: America/Chicago) -> --tv-tz.
                    Stop / T1 are not TradingView columns: they are read from optional
                    "stop"/"t1" columns, or from the entry Signal text "sl=<px>" / "tp=<px>"
                    (Agent 03 encodes them in the entry id/comment). Missing -> reported N/A.

Tolerances (master prompt Appendix E; GOLDEN_SIGNALS.md §1)
  match     same side, entry bar within +-1 execution bar (5m) of the Java emit bar
  entry     +-4 ticks; larger deltas are classified ENTRY_MODE (MARKET_ON_CONFIRM vs Java
            LIMIT at the PD array) — reported, NOT a failure
  stop/T1   +-2 ticks — DECISIVE
  size      exact after caps (difference reported)
Hard fails (exit 1): opposite-direction trade on a Java setup with no same-side match;
  any TV entry at/after 16:30 ET; a decisive stop/T1 delta when both sides are known;
  any TV trade whose size exceeds --cap (Agent 06.4 "ANY size above cap", default 5 micros =
  the strategy cap `max micros per trade`; --cap 0 disables); with --require-stop, any TV
  trade whose stop is unknown (no sl= in the Signal column and no stop column) — Agent 06.4
  "ANY trade without a stop" (tools/run_parity.sh passes it; the default stays off so a
  hand-made export without Signal text is still reported, as N/A).
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional
from zoneinfo import ZoneInfo

ET = ZoneInfo("America/New_York")
ENTRY_TOL_TICKS = 4
LEVEL_TOL_TICKS = 2
G1_T1_TOL_TICKS = 1
NO_ENTRY_AFTER_ET = (16, 30)
DEFAULT_CAP_MICROS = 5   # Pine input "max micros per trade (strategy cap)" default (10_inputs.pine, I4)
HOLD_BUCKETS = [(5, "<=5s"), (30, "<=30s"), (60, "<=1m"), (300, "<=5m"), (900, "<=15m"),
                (3600, "<=60m"), (None, ">60m")]
GOLDEN = {
    "G1": ("2026-09-28 14:53", "SELL"),
    "G2": ("2026-09-25 11:33", "SELL"),
}
G3_WINDOW_ET = ("2026-09-28 02:00", "2026-09-28 08:00")


# ----------------------------------------------------------------------------- model
@dataclass
class JavaSignal:
    ts: datetime          # ET-aware, 1m emit candle
    side: str             # BUY / SELL
    entry: float
    stop: float
    t1: float
    size: int
    killzone: str = ""
    raw: dict = field(default_factory=dict)


@dataclass
class TvTrade:
    num: str
    side: str             # BUY / SELL
    entry_ts: datetime    # ET-aware
    entry: float
    size: Optional[float]
    exit_ts: Optional[datetime] = None
    exit_price: Optional[float] = None
    profit: Optional[float] = None
    stop: Optional[float] = None
    t1: Optional[float] = None
    signal: str = ""


@dataclass
class Match:
    java: JavaSignal
    tv: TvTrade
    bar_offset: int
    d_entry: float
    d_stop: Optional[float]
    d_t1: Optional[float]
    d_size: Optional[float]

    @property
    def entry_class(self) -> str:
        return "OK" if abs(self.d_entry) <= ENTRY_TOL_TICKS else "ENTRY_MODE"

    @property
    def levels_ok(self) -> Optional[bool]:
        ds = [d for d in (self.d_stop, self.d_t1) if d is not None]
        if not ds:
            return None
        return all(abs(d) <= LEVEL_TOL_TICKS for d in ds)


# ----------------------------------------------------------------------------- parsing
def _norm(h: str) -> str:
    return re.sub(r"[^a-z0-9#&]+", " ", h.strip().lower()).strip()


def _find(headers: list[str], *cands: str, prefix: bool = False) -> Optional[str]:
    normed = {_norm(h): h for h in headers}
    for c in cands:
        if c in normed:
            return normed[c]
    if prefix:
        for c in cands:
            for n, h in normed.items():
                if n.startswith(c):
                    return h
    return None


def _num(s) -> Optional[float]:
    if s is None:
        return None
    s = str(s).strip().replace(",", "").replace("−", "-").replace("$", "")
    if s in ("", "-", "NaN", "nan"):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def _parse_dt(s: str, tz: ZoneInfo) -> datetime:
    s = s.strip()
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M",
                "%m/%d/%Y %H:%M:%S", "%m/%d/%Y %H:%M", "%b %d, %Y %H:%M", "%b %d, %Y, %H:%M"):
        try:
            return datetime.strptime(s, fmt).replace(tzinfo=tz)
        except ValueError:
            pass
    try:  # ISO with offset (e.g. 2026-09-28T19:55:00Z)
        d = datetime.fromisoformat(s.replace("Z", "+00:00"))
        return d if d.tzinfo else d.replace(tzinfo=tz)
    except ValueError:
        raise ValueError(f"unparseable Date/Time: {s!r}")


def read_java(path: str) -> list[JavaSignal]:
    out = []
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            out.append(JavaSignal(
                ts=_parse_dt(r["ts_et"], ET), side=r["side"].strip().upper(),
                entry=float(r["entry"]), stop=float(r["stop"]), t1=float(r["t1"]),
                size=int(float(r["size_micros"])), killzone=r.get("killzone", ""), raw=r))
    return out


_SL = re.compile(r"(?i)\b(?:sl|stop)\s*[=:]\s*(-?\d+(?:\.\d+)?)")
_TP = re.compile(r"(?i)\b(?:tp|t1)\s*[=:]\s*(-?\d+(?:\.\d+)?)")


def read_tv(path: str, tv_tz: ZoneInfo) -> list[TvTrade]:
    with open(path, newline="", encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
        headers = list(rows[0].keys()) if rows else []
    if not rows:
        return []
    h_num = _find(headers, "trade #", "trade", "trade no", "#")
    h_type = _find(headers, "type")
    h_dt = _find(headers, "date/time", "date time", "date and time", "time", prefix=True)
    h_sig = _find(headers, "signal", "comment")
    h_px = _find(headers, "price usd", "price", prefix=True)
    h_qty = _find(headers, "contracts", "position size qty", "quantity", "qty", "position size", prefix=True)
    h_pnl = _find(headers, "net p&l usd", "profit usd", "net p&l", "profit", prefix=True)
    h_stop = _find(headers, "stop", "stop usd", "sl")
    h_t1 = _find(headers, "t1", "take profit", "tp")
    missing = [n for n, h in (("Trade #", h_num), ("Type", h_type), ("Date/Time", h_dt), ("Price", h_px)) if h is None]
    if missing:
        raise ValueError(f"{path}: not a TradingView List-of-Trades export (missing {missing}; headers {headers})")
    trades: dict[str, TvTrade] = {}
    exits: dict[str, dict] = {}
    for r in rows:
        typ = (r.get(h_type) or "").strip().lower()
        num = (r.get(h_num) or "").strip()
        if typ.startswith("entry"):
            side = "BUY" if "long" in typ else "SELL"
            sig = (r.get(h_sig) or "") if h_sig else ""
            stop = _num(r.get(h_stop)) if h_stop else None
            t1 = _num(r.get(h_t1)) if h_t1 else None
            if stop is None and (m := _SL.search(sig)):
                stop = float(m.group(1))
            if t1 is None and (m := _TP.search(sig)):
                t1 = float(m.group(1))
            trades[num] = TvTrade(num=num, side=side, entry_ts=_parse_dt(r[h_dt], tv_tz).astimezone(ET),
                                  entry=_num(r[h_px]), size=_num(r.get(h_qty)) if h_qty else None,
                                  stop=stop, t1=t1, signal=sig)
        elif typ.startswith("exit"):
            exits[num] = r
    for num, r in exits.items():
        t = trades.get(num)
        if t is None:
            continue
        t.exit_ts = _parse_dt(r[h_dt], tv_tz).astimezone(ET)
        t.exit_price = _num(r[h_px])
        t.profit = _num(r.get(h_pnl)) if h_pnl else None
    return sorted(trades.values(), key=lambda t: t.entry_ts)


# ----------------------------------------------------------------------------- matching
def _bar_start(ts: datetime, bar_min: int) -> datetime:
    return ts.replace(minute=ts.minute - ts.minute % bar_min, second=0, microsecond=0)


def _ticks(a: Optional[float], b: Optional[float], tick: float) -> Optional[float]:
    if a is None or b is None:
        return None
    return round((a - b) / tick, 2)


def match(java: list[JavaSignal], tv: list[TvTrade], tick: float, bar_min: int):
    """Greedy nearest-bar match, same side, |bar offset| <= 1 execution bar."""
    used: set[int] = set()
    matches: list[Match] = []
    unmatched_java: list[JavaSignal] = []
    opposite: list[tuple[JavaSignal, TvTrade]] = []
    win = timedelta(minutes=bar_min)
    for j in sorted(java, key=lambda s: s.ts):
        jb = _bar_start(j.ts, bar_min)
        cands = []
        for i, t in enumerate(tv):
            if i in used:
                continue
            off = _bar_start(t.entry_ts, bar_min) - jb
            if abs(off) <= win:
                cands.append((abs(off), i, t, off))
        same = [c for c in cands if c[2].side == j.side]
        if same:
            _, i, t, off = min(same, key=lambda c: (c[0], c[1]))
            used.add(i)
            sign = 1 if j.side == "BUY" else -1  # positive = worse for the trade
            matches.append(Match(
                java=j, tv=t, bar_offset=int(off / win),
                d_entry=sign * _ticks(t.entry, j.entry, tick),
                d_stop=_ticks(t.stop, j.stop, tick), d_t1=_ticks(t.t1, j.t1, tick),
                d_size=(t.size - j.size) if t.size is not None else None))
        else:
            unmatched_java.append(j)
            opp = [c for c in cands if c[2].side != j.side]
            if opp:
                opposite.append((j, min(opp, key=lambda c: c[0])[2]))
    unmatched_tv = [t for i, t in enumerate(tv) if i not in used]
    return matches, unmatched_java, unmatched_tv, opposite


def hold_times(tv: list[TvTrade]):
    counts = {label: 0 for _, label in HOLD_BUCKETS}
    total_profit = 0.0
    fast_profit = 0.0
    n = 0
    for t in tv:
        if t.exit_ts is None:
            continue
        n += 1
        secs = (t.exit_ts - t.entry_ts).total_seconds()
        for lim, label in HOLD_BUCKETS:
            if lim is None or secs <= lim:
                counts[label] += 1
                break
        if t.profit is not None and t.profit > 0:
            total_profit += t.profit
            if secs <= 5:
                fast_profit += t.profit
    share = fast_profit / total_profit if total_profit > 0 else 0.0
    return n, counts, share


def late_entries(tv: list[TvTrade]) -> list[TvTrade]:
    h, m = NO_ENTRY_AFTER_ET
    return [t for t in tv if (t.entry_ts.hour, t.entry_ts.minute) >= (h, m)]


def above_cap(tv: list[TvTrade], cap: Optional[int]) -> list[TvTrade]:
    """Agent 06.4: ANY size above cap is a hard fail. cap None/0 = check disabled."""
    if not cap:
        return []
    return [t for t in tv if t.size is not None and abs(t.size) > cap]


def without_stop(tv: list[TvTrade]) -> list[TvTrade]:
    """Agent 06.4: ANY trade without a stop. The stop is known from the Signal text (sl=) or a stop column."""
    return [t for t in tv if t.stop is None]


def golden(matches: list[Match], java: list[JavaSignal], tv: list[TvTrade], tick: float) -> dict[str, str]:
    out = {}
    by_key = {(m.java.ts.strftime("%Y-%m-%d %H:%M"), m.java.side): m for m in matches}
    jkeys = {(j.ts.strftime("%Y-%m-%d %H:%M"), j.side) for j in java}
    for g, key in GOLDEN.items():
        if key not in jkeys:
            out[g] = "N/A (not in the Java tape)"
            continue
        m = by_key.get(key)
        if m is None:
            out[g] = "FAIL (no TradingView trade matched)"
            continue
        t1_tol = G1_T1_TOL_TICKS if g == "G1" else LEVEL_TOL_TICKS
        problems = []
        if m.d_stop is not None and abs(m.d_stop) > LEVEL_TOL_TICKS:
            problems.append(f"stop {m.d_stop:+g}t")
        if m.d_t1 is not None and abs(m.d_t1) > t1_tol:
            problems.append(f"T1 {m.d_t1:+g}t")
        note = f"entry {m.d_entry:+g}t ({m.entry_class})"
        if m.d_stop is None or m.d_t1 is None:
            note += ", stop/T1 not in the TV export"
        out[g] = ("FAIL (" + ", ".join(problems) + "; " + note + ")") if problems else f"PASS ({note})"
    lo = _parse_dt(G3_WINDOW_ET[0], ET)
    hi = _parse_dt(G3_WINDOW_ET[1], ET)
    tv_in = [t for t in tv if lo <= t.entry_ts < hi]
    java_in = [j for j in java if lo <= j.ts < hi]
    if not tv:
        out["G3"] = "N/A (no TradingView trades)"
    elif tv_in and not java_in:
        out["G3"] = f"FAIL ({len(tv_in)} TV entr{'y' if len(tv_in) == 1 else 'ies'} in London 02:00-08:00 ET; Java took none)"
    else:
        out["G3"] = "PASS (no entry in London 2026-09-28 02:00-08:00 ET, as Java)"
    return out


# ----------------------------------------------------------------------------- report
def _fmt(d: Optional[float]) -> str:
    return "N/A" if d is None else f"{d:+g}"


def build_report(java, tv, matches, unmatched_java, unmatched_tv, opposite, tick, bar_min,
                 java_path, tv_path, cap: Optional[int] = DEFAULT_CAP_MICROS,
                 require_stop: bool = False) -> tuple[str, int]:
    hard: list[str] = []
    for j, t in opposite:
        hard.append(f"OPPOSITE: Java {j.side} {j.ts:%Y-%m-%d %H:%M} ET vs TV #{t.num} {t.side} {t.entry_ts:%Y-%m-%d %H:%M} ET")
    for t in late_entries(tv):
        hard.append(f"LATE ENTRY: TV #{t.num} {t.side} at {t.entry_ts:%Y-%m-%d %H:%M} ET (>= 16:30 ET)")
    for t in above_cap(tv, cap):
        hard.append(f"SIZE ABOVE CAP: TV #{t.num} {t.side} at {t.entry_ts:%Y-%m-%d %H:%M} ET size {t.size:g} > cap {cap} micros (I4)")
    if require_stop:
        for t in without_stop(tv):
            hard.append(f"NO STOP: TV #{t.num} {t.side} at {t.entry_ts:%Y-%m-%d %H:%M} ET has no sl= in its Signal and no stop column (I2)")
    for m in matches:
        if m.levels_ok is False:
            hard.append(f"LEVELS: {m.java.ts:%Y-%m-%d %H:%M} {m.java.side} stop {_fmt(m.d_stop)}t / T1 {_fmt(m.d_t1)}t (tol +-{LEVEL_TOL_TICKS})")
    n_hold, buckets, fast_share = hold_times(tv)
    g = golden(matches, java, tv, tick)
    rate = 100.0 * len(matches) / len(java) if java else 0.0
    entry_mode = [m for m in matches if m.entry_class == "ENTRY_MODE"]
    L = []
    L.append("# PARITY_REPORT — Java oracle vs TradingView (generated by tools/pine_parity.py)\n")
    L.append(f"- Java: `{java_path}` ({len(java)} signals) · TradingView: `{tv_path}` ({len(tv)} trades)")
    L.append(f"- Tick {tick} · match window ±1 × {bar_min}m bar, same side · entry ±{ENTRY_TOL_TICKS}t (else ENTRY_MODE) · stop/T1 ±{LEVEL_TOL_TICKS}t (decisive)")
    L.append(f"- Size cap {cap if cap else 'disabled'} micros (hard fail above it) · stop required: {'yes' if require_stop else 'no (N/A reported)'}")
    L.append(f"- Times are America/New_York (ET); CT = ET − 1 h.\n")
    L.append("## Summary\n")
    L.append("| metric | value |\n|---|---|")
    L.append(f"| match rate | {len(matches)}/{len(java)} = {rate:.1f} % |")
    L.append(f"| unmatched Java | {len(unmatched_java)} |")
    L.append(f"| unmatched TradingView | {len(unmatched_tv)} |")
    L.append(f"| opposite-direction disagreements | {len(opposite)} |")
    L.append(f"| entry deltas > ±{ENTRY_TOL_TICKS}t (ENTRY_MODE, not a failure) | {len(entry_mode)} |")
    L.append(f"| hard fails | {len(hard)} |")
    L.append(f"| G1 / G2 / G3 | {g['G1']} / {g['G2']} / {g['G3']} |\n")
    L.append("## Matched trades (deltas in ticks; entry sign: + = worse fill for the trade)\n")
    L.append("| Java ts (ET) | side | TV # | TV entry (ET) | bar off | entry Δ | class | stop Δ | T1 Δ | size Java/TV |")
    L.append("|---|---|---|---|---|---|---|---|---|---|")
    for m in matches:
        L.append(f"| {m.java.ts:%Y-%m-%d %H:%M} | {m.java.side} | {m.tv.num} | {m.tv.entry_ts:%Y-%m-%d %H:%M} | "
                 f"{m.bar_offset:+d} | {m.d_entry:+g} | {m.entry_class} | {_fmt(m.d_stop)} | {_fmt(m.d_t1)} | "
                 f"{m.java.size}/{'N/A' if m.tv.size is None else f'{m.tv.size:g}'} |")
    L.append("\n## Unmatched — Java signals with no TradingView trade\n")
    L.extend([f"- {j.ts:%Y-%m-%d %H:%M} ET {j.side} entry {j.entry:.2f} stop {j.stop:.2f} T1 {j.t1:.2f} ({j.killzone})"
              for j in unmatched_java] or ["- none"])
    L.append("\n## Unmatched — TradingView trades with no Java signal\n")
    L.extend([f"- #{t.num} {t.entry_ts:%Y-%m-%d %H:%M} ET {t.side} @ {t.entry:.2f}" for t in unmatched_tv] or ["- none"])
    L.append("\n## Hold-time distribution (I6)\n")
    L.append("| bucket | trades |\n|---|---|")
    for _, label in HOLD_BUCKETS:
        L.append(f"| {label} | {buckets[label]} |")
    L.append(f"\nClosed trades: {n_hold}. Share of gross profit from trades held ≤ 5 s: **{100 * fast_share:.1f} %** "
             f"(Lucid microscalp limit 50 %; {'OK' if fast_share <= 0.5 else 'VIOLATION'}).\n")
    L.append("## Golden cases\n")
    for k in ("G1", "G2", "G3"):
        L.append(f"- **{k}**: {g[k]}")
    L.append("\n## Hard fails\n")
    L.extend([f"- {h}" for h in hard] or ["- none"])
    L.append("\nEvery mismatch needs a root cause (Agent 06): DATA / ENTRY_MODE / PORT_APPROX / BUG.")
    return "\n".join(L) + "\n", (1 if hard else 0)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("java_signals")
    ap.add_argument("tv_trades")
    ap.add_argument("--out", default="PARITY_REPORT.md")
    ap.add_argument("--tv-tz", default="America/Chicago", help="timezone of the TV export's Date/Time (chart tz)")
    ap.add_argument("--tick", type=float, default=0.25, help="tick size (MNQ 0.25)")
    ap.add_argument("--bar-min", type=int, default=5, help="execution bar minutes (5)")
    ap.add_argument("--cap", type=int, default=DEFAULT_CAP_MICROS,
                    help="max micros per trade; any TV trade above it is a hard fail (0 = off)")
    ap.add_argument("--require-stop", action="store_true",
                    help="hard fail on any TV trade whose stop is unknown (no sl= in Signal, no stop column)")
    a = ap.parse_args(argv)
    java = read_java(a.java_signals)
    tv = read_tv(a.tv_trades, ZoneInfo(a.tv_tz))
    matches, uj, ut, opp = match(java, tv, a.tick, a.bar_min)
    text, rc = build_report(java, tv, matches, uj, ut, opp, a.tick, a.bar_min, a.java_signals, a.tv_trades,
                            cap=a.cap, require_stop=a.require_stop)
    Path(a.out).write_text(text, encoding="utf-8")
    print(f"pine_parity: {len(matches)}/{len(java)} matched, {len(uj)} Java-only, {len(ut)} TV-only, "
          f"{len(opp)} opposite; report -> {a.out}" + ("  [HARD FAIL]" if rc else ""))
    return rc


if __name__ == "__main__":
    sys.exit(main())
