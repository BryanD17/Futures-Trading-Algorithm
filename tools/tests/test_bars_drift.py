"""Unit tests for tools/bars_drift.py (V6 Agent 06.1 data-drift check) on synthetic CSVs."""
import csv
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import bars_drift as bd  # noqa: E402

TAPE_HDR = ["ts_et", "ts_utc", "open", "high", "low", "close", "volume"]
TAPE = [
    ["2026-09-28 14:50", "2026-09-28T18:50:00Z", "30640.00", "30650.00", "30636.00", "30638.25", "100"],
    ["2026-09-28 14:51", "2026-09-28T18:51:00Z", "30638.25", "30641.00", "30630.00", "30633.75", "100"],
    ["2026-09-28 14:52", "2026-09-28T18:52:00Z", "30633.75", "30636.00", "30628.00", "30630.00", "100"],
    ["2026-09-28 14:53", "2026-09-28T18:53:00Z", "30630.00", "30650.00", "30629.00", "30633.75", "100"],
]


def write(path, header, rows):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)


class BarsDriftTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.tape = os.path.join(self.d, "tape.csv")
        write(self.tape, TAPE_HDR, TAPE)

    def test_unix_seconds_identical_bars_ok(self):
        tv = os.path.join(self.d, "tv.csv")
        # TradingView default export: unix seconds, UTC. 2026-09-28T18:50:00Z = 1790621400
        base = 1790621400
        write(tv, ["time", "open", "high", "low", "close", "Volume"],
              [[base + 60 * i, r[2], r[3], r[4], r[5], r[6]] for i, r in enumerate(TAPE)])
        stats, ot, otv = bd.compare(bd.read_tape(self.tape), bd.read_tv(tv, bd.ZoneInfo("America/Chicago")), 0.25)
        self.assertEqual(stats["n"], 4)
        self.assertEqual(stats["median"], 0.0)
        self.assertEqual(stats["any_gt1"], 0)
        self.assertIn("OK (≤ 1 tick median)", bd.render("MNQ", self.tape, tv, stats, ot, otv, 0.25))

    def test_naive_iso_in_chart_tz_and_drift_verdict(self):
        tv = os.path.join(self.d, "tv_iso.csv")
        # "ISO time" export, naive, chart tz America/Chicago (= UTC-5 in Sep): 18:50Z -> 13:50 CT.
        # Closes shifted by 2 ticks (0.50) on every bar -> median 2 -> DRIFT verdict; one bar missing.
        rows = [["2026-09-28T13:50:00", "30640.00", "30650.00", "30636.00", "30638.75"],
                ["2026-09-28T13:51:00", "30638.25", "30641.00", "30630.00", "30634.25"],
                ["2026-09-28T13:52:00", "30633.75", "30636.00", "30628.00", "30630.50"]]
        write(tv, ["time", "open", "high", "low", "close"], rows)
        stats, ot, otv = bd.compare(bd.read_tape(self.tape), bd.read_tv(tv, bd.ZoneInfo("America/Chicago")), 0.25)
        self.assertEqual(stats["n"], 3)
        self.assertEqual(stats["only_tape"], 1)
        self.assertEqual(stats["median"], 2.0)
        self.assertEqual(stats["any_gt1"], 3)
        text = bd.render("MNQ", self.tape, tv, stats, ot, otv, 0.25)
        self.assertIn("DRIFT: judge parity with ±2-tick tolerance", text)
        self.assertIn("First tape bars missing in TV: 09-28 18:53 UTC", text)

    def test_iso_with_offset_is_converted(self):
        tv = os.path.join(self.d, "tv_off.csv")
        write(tv, ["time", "open", "high", "low", "close"],
              [["2026-09-28T13:53:00-05:00", "30630.00", "30650.00", "30629.00", "30633.75"]])
        stats, *_ = bd.compare(bd.read_tape(self.tape), bd.read_tv(tv, bd.ZoneInfo("UTC")), 0.25)
        self.assertEqual(stats["n"], 1)
        self.assertEqual(stats["median"], 0.0)

    def test_no_overlap_exits_2(self):
        tv = os.path.join(self.d, "tv_none.csv")
        write(tv, ["time", "open", "high", "low", "close"], [["1700000000", "1", "2", "0", "1"]])
        out = os.path.join(self.d, "drift.md")
        rc = bd.main([self.tape, tv, "--out", out])
        self.assertEqual(rc, 2)
        self.assertIn("NO OVERLAP", Path(out).read_text())

    def test_not_an_export_fails_loud(self):
        bad = os.path.join(self.d, "bad.csv")
        write(bad, ["a", "b"], [["1", "2"]])
        with self.assertRaises(ValueError):
            bd.read_tv(bad, bd.ZoneInfo("UTC"))

    def test_real_tape_loads(self):
        tape = bd.read_tape(str(ROOT / "parity" / "bars_mnq_1m.csv"))
        self.assertEqual(len(tape), 7830)


if __name__ == "__main__":
    unittest.main()
