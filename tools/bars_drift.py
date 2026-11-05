#!/usr/bin/env python3
"""V6 Agent 06.1 — data-drift check: TradingView 1m bars (OA-3 export) vs the Java tape bars.

    python3 tools/bars_drift.py parity/bars_mnq_1m.csv parity/tv_bars_mnq_1m.csv \
        [--tick 0.25] [--tv-tz America/Chicago] [--out drift.md] [--label MNQ]

Inputs
  tape CSV   GoldenSignalExport bars (Agent 00): ts_et, ts_utc, open, high, low, close, volume.
  TV CSV     "Export chart data..." from TradingView (OA-3). Parsed by header name; the time column
             may be unix seconds (TradingView default, UTC) or ISO ("ISO time" option: with an offset,
             or naive = chart timezone -> --tv-tz). OHLC columns: open, high, low, close (any case).

Output: a Markdown section (stdout, or --out) with bars compared, bars missing on either side,
median / p95 / max |close delta| in ticks and the count of bars where ANY of O/H/L/C differs by
more than 1 tick. Verdict per master prompt 06.1: median |close delta| <= 1 tick -> OK; above it the
parity run is judged with that tolerance and the report says so. Exit code: 0 (even when drifted;
drift is a tolerance, not a failure); 2 when the files do not overlap at all (wrong range / symbol).
"""
from __future__ import annotations

import argparse
import csv
import re
import statistics
import sys
from datetime import datetime, timezone
from typing import Optional
from zoneinfo import ZoneInfo

UTC = timezone.utc


def _norm(h: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", h.strip().lower()).strip()


def _col(headers: list[str], *cands: str) -> Optional[str]:
    normed = {_norm(h): h for h in headers}
    for c in cands:
        if c in normed:
            return normed[c]
    return None


def _num(s) -> Optional[float]:
    try:
        return float(str(s).strip().replace(",", ""))
    except (TypeError, ValueError):
        return None


def parse_tv_time(s: str, tv_tz: ZoneInfo) -> datetime:
    s = s.strip()
    if re.fullmatch(r"-?\d+(\.\d+)?", s):                   # unix seconds (TradingView default)
        return datetime.fromtimestamp(float(s), tz=UTC).replace(second=0, microsecond=0)
    d = datetime.fromisoformat(s.replace("Z", "+00:00"))     # ISO, with or without an offset
    if d.tzinfo is None:
        d = d.replace(tzinfo=tv_tz)
    return d.astimezone(UTC).replace(second=0, microsecond=0)


def read_tape(path: str) -> dict[datetime, tuple[float, float, float, float]]:
    out = {}
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            t = datetime.fromisoformat(r["ts_utc"].replace("Z", "+00:00")).astimezone(UTC)
            out[t] = (float(r["open"]), float(r["high"]), float(r["low"]), float(r["close"]))
    return out


def read_tv(path: str, tv_tz: ZoneInfo) -> dict[datetime, tuple[float, float, float, float]]:
    with open(path, newline="", encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        return {}
    h = list(rows[0].keys())
    ht = _col(h, "time", "date time", "date", "timestamp", "ts utc", "ts")
    ho, hh, hl, hc = (_col(h, "open"), _col(h, "high"), _col(h, "low"), _col(h, "close"))
    if None in (ht, ho, hh, hl, hc):
        raise ValueError(f"{path}: not a TradingView chart-data export (need time/open/high/low/close; headers {h})")
    out = {}
    for r in rows:
        vals = tuple(_num(r[k]) for k in (ho, hh, hl, hc))
        if any(v is None for v in vals):
            continue
        out[parse_tv_time(r[ht], tv_tz)] = vals  # type: ignore[assignment]
    return out


def compare(tape, tv, tick: float):
    common = sorted(set(tape) & set(tv))
    only_tape = sorted(set(tape) - set(tv))
    only_tv = sorted(set(tv) - set(tape))
    close_d = [abs(tv[t][3] - tape[t][3]) / tick for t in common]
    any_gt1 = sum(1 for t in common if any(abs(a - b) / tick > 1 for a, b in zip(tape[t], tv[t])))
    stats = {
        "n": len(common), "only_tape": len(only_tape), "only_tv": len(only_tv), "any_gt1": any_gt1,
        "median": statistics.median(close_d) if close_d else None,
        "p95": (sorted(close_d)[max(0, int(round(0.95 * len(close_d))) - 1)] if close_d else None),
        "max": max(close_d) if close_d else None,
        "worst": (max(common, key=lambda t: abs(tv[t][3] - tape[t][3])) if common else None),
    }
    return stats, only_tape, only_tv


def render(label: str, tape_path: str, tv_path: str, stats, only_tape, only_tv, tick: float) -> str:
    L = [f"## Data drift — {label} 1m (Agent 06.1)\n",
         f"- tape `{tape_path}` vs TradingView `{tv_path}` · tick {tick} · times UTC (ET = UTC−4 in Sep, CT = ET−1 h)"]
    if not stats["n"]:
        L.append("- **NO OVERLAP**: the TradingView export does not cover the tape range "
                 "(2026-09-21 00:00 → 2026-09-28 16:22 ET). Re-export per OA-3.")
        return "\n".join(L) + "\n"
    verdict = "OK (≤ 1 tick median)" if stats["median"] <= 1 else f"DRIFT: judge parity with ±{stats['median']:.0f}-tick tolerance (06.1)"
    L.append("| metric | value |\n|---|---|")
    L.append(f"| bars compared | {stats['n']} |")
    L.append(f"| tape bars missing in TV | {stats['only_tape']} |")
    L.append(f"| TV bars missing in tape | {stats['only_tv']} |")
    L.append(f"| median \\|close Δ\\| (ticks) | {stats['median']:.2f} |")
    L.append(f"| p95 \\|close Δ\\| (ticks) | {stats['p95']:.2f} |")
    L.append(f"| max \\|close Δ\\| (ticks) | {stats['max']:.2f} at {stats['worst']:%Y-%m-%d %H:%M} UTC |")
    L.append(f"| bars with any O/H/L/C Δ > 1 tick | {stats['any_gt1']} |")
    L.append(f"| **verdict** | **{verdict}** |")
    if only_tape[:5]:
        L.append("\nFirst tape bars missing in TV: " + ", ".join(f"{t:%m-%d %H:%M}" for t in only_tape[:5]) + " UTC")
    if only_tv[:5]:
        L.append("\nFirst TV bars missing in tape: " + ", ".join(f"{t:%m-%d %H:%M}" for t in only_tv[:5]) + " UTC")
    return "\n".join(L) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tape_csv")
    ap.add_argument("tv_csv")
    ap.add_argument("--tick", type=float, default=0.25)
    ap.add_argument("--tv-tz", default="America/Chicago", help="chart timezone for naive ISO times")
    ap.add_argument("--label", default="MNQ")
    ap.add_argument("--out", default=None)
    a = ap.parse_args(argv)
    tape = read_tape(a.tape_csv)
    tv = read_tv(a.tv_csv, ZoneInfo(a.tv_tz))
    stats, only_tape, only_tv = compare(tape, tv, a.tick)
    text = render(a.label, a.tape_csv, a.tv_csv, stats, only_tape, only_tv, a.tick)
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text)
    print(text, end="")
    return 0 if stats["n"] else 2


if __name__ == "__main__":
    sys.exit(main())
