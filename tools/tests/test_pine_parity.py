"""Unit tests for tools/pine_parity.py on synthetic CSVs (V6 Agent 05.4)."""
import csv
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import pine_parity as pp  # noqa: E402

JAVA_HEADER = ["ts_et", "side", "entry", "stop", "t1", "t2", "size_micros", "raid_level_type", "raid_score",
               "killzone", "bias", "gate_proof"]
JAVA_ROWS = [
    # G2
    ["2026-09-25 11:33", "SELL", "30847.50", "30889.75", "30805.25", "30776.75", "2", "OTE_BAND", "7", "NY_AM", "BEARISH", "x"],
    # 12:32 row (big ENTRY_MODE delta)
    ["2026-09-28 12:32", "SELL", "30645.00", "30723.00", "30558.00", "30510.50", "1", "LONDON_HIGH", "10", "NY_LUNCH", "BEARISH", "x"],
    # G1
    ["2026-09-28 14:53", "SELL", "30636.00", "30674.00", "30558.00", "30510.50", "3", "LONDON_HIGH", "10", "NY_PM", "BEARISH", "x"],
    # a BUY nobody takes
    ["2026-09-27 20:40", "BUY", "30813.00", "30801.75", "30840.00", "30853.75", "5", "OTE_BAND", "6", "ASIA", "BULLISH", "x"],
]

# Newer TradingView layout; Date/Time in the chart timezone (America/Chicago = ET-1h).
TV_NEW = ["Trade #", "Type", "Date/Time", "Signal", "Price USD", "Position size (qty)", "Position size (value)",
          "Net P&L USD", "Net P&L %", "Run-up USD", "Run-up %", "Drawdown USD", "Drawdown %",
          "Cumulative P&L USD", "Cumulative P&L %"]
# Older layout (Signal before Date/Time, Contracts, Profit USD).
TV_OLD = ["Trade #", "Type", "Signal", "Date/Time", "Price USD", "Contracts", "Profit USD", "Profit %",
          "Cumulative profit USD", "Cumulative profit %", "Run-up USD", "Run-up %", "Drawdown USD", "Drawdown %"]


def tv_row_new(n, typ, dt_ct, sig, px, qty, pnl):
    return [n, typ, dt_ct, sig, px, qty, "", pnl, "", "", "", "", "", "", ""]


def tv_row_old(n, typ, dt_ct, sig, px, qty, pnl):
    return [n, typ, sig, dt_ct, px, qty, pnl, "", "", "", "", "", "", ""]


def write(path, header, rows):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)


def good_trades(rowfn):
    # TradingView exports exit row first, then entry row, per trade.
    return [
        rowfn("1", "Exit short", "2026-09-25 11:10", "T1", "30805.25", "2", "84.50"),
        rowfn("1", "Entry short", "2026-09-25 10:35", "S|sl=30889.75|tp=30805.25", "30846.00", "2", "84.50"),
        rowfn("2", "Exit short", "2026-09-28 11:59", "SL", "30723.00", "1", "-40.00"),
        rowfn("2", "Entry short", "2026-09-28 11:30", "S|sl=30723.00|tp=30558.00", "30655.00", "1", "-40.00"),
        rowfn("3", "Exit short", "2026-09-28 15:20", "T1", "30558.00", "3", "120.00"),
        rowfn("3", "Entry short", "2026-09-28 13:50", "S|sl=30674.00|tp=30558.00", "30638.25", "3", "120.00"),
    ]


class ParityTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.java = os.path.join(self.d, "java.csv")
        write(self.java, JAVA_HEADER, JAVA_ROWS)
        self.out = os.path.join(self.d, "PARITY_REPORT.md")

    def run_tool(self, tv_path, *extra):
        return pp.main([self.java, tv_path, "--out", self.out, *extra])

    def test_new_layout_matches_and_classifies_entry_mode(self):
        tv = os.path.join(self.d, "tv.csv")
        write(tv, TV_NEW, good_trades(tv_row_new))
        rc = self.run_tool(tv)
        rep = Path(self.out).read_text()
        self.assertEqual(rc, 0, rep)
        self.assertIn("| match rate | 3/4 = 75.0 % |", rep)
        self.assertIn("| opposite-direction disagreements | 0 |", rep)
        # 12:32 row: TV 12:30 ET 5m close 30655.00 vs Java 30645.00 -> 40 ticks (short: higher = better) -> ENTRY_MODE
        self.assertIn("| 2026-09-28 12:32 | SELL | 2 | 2026-09-28 12:30 | +0 | -40 | ENTRY_MODE | +0 | +0 | 1/1 |", rep)
        # G1: 30638.25 vs 30636.00 = 9 ticks -> ENTRY_MODE, stop/T1 exact -> PASS
        self.assertIn("**G1**: PASS (entry -9t (ENTRY_MODE))", rep)
        self.assertIn("**G2**: PASS", rep)
        self.assertIn("**G3**: PASS", rep)
        self.assertIn("2026-09-27 20:40 ET BUY", rep)  # unmatched Java
        self.assertIn("Share of gross profit from trades held ≤ 5 s: **0.0 %**", rep)

    def test_old_layout_parses_identically(self):
        tv = os.path.join(self.d, "tv_old.csv")
        write(tv, TV_OLD, good_trades(tv_row_old))
        self.assertEqual(self.run_tool(tv), 0)
        self.assertIn("| match rate | 3/4 = 75.0 % |", Path(self.out).read_text())

    def test_window_is_plus_minus_one_exec_bar(self):
        java = pp.read_java(self.java)
        mk = lambda ts: pp.TvTrade("9", "SELL", pp._parse_dt(ts, pp.ET), 30636.0, 3)
        m, *_ = pp.match(java, [mk("2026-09-28 14:55")], 0.25, 5)   # next 5m bar -> match
        self.assertEqual(len(m), 1)
        self.assertEqual(m[0].bar_offset, 1)
        m, *_ = pp.match(java, [mk("2026-09-28 15:00")], 0.25, 5)   # two bars later -> no match
        self.assertEqual(len(m), 0)

    def test_stop_t1_decisive_and_opposite_and_late_are_hard_fails(self):
        tv = os.path.join(self.d, "tv_bad.csv")
        rows = [
            tv_row_new("1", "Entry short", "2026-09-28 13:50", "S|sl=30675.00|tp=30558.00", "30638.25", "3", "1"),
            tv_row_new("1", "Exit short", "2026-09-28 13:50:03", "", "30630.00", "3", "10"),
            tv_row_new("2", "Entry long", "2026-09-25 10:35", "L", "30846.00", "2", "1"),  # opposite of G2
            tv_row_new("2", "Exit long", "2026-09-25 10:40", "", "30850.00", "2", "1"),
            tv_row_new("3", "Entry long", "2026-09-28 15:35", "L", "30600.00", "1", "1"),  # 16:35 ET
            tv_row_new("3", "Exit long", "2026-09-28 15:39", "", "30601.00", "1", "1"),
        ]
        write(tv, TV_NEW, rows)
        rc = self.run_tool(tv)
        rep = Path(self.out).read_text()
        self.assertEqual(rc, 1)
        self.assertIn("LEVELS: 2026-09-28 14:53 SELL stop +4t", rep)       # 1.00 pt = 4 ticks > 2
        self.assertIn("OPPOSITE: Java SELL 2026-09-25 11:33", rep)
        self.assertIn("LATE ENTRY: TV #3 BUY at 2026-09-28 16:35 ET", rep)
        self.assertIn("**G1**: FAIL (stop +4t", rep)
        # 3-second hold made 10/12 of gross profit -> microscalp violation reported
        self.assertIn("**83.3 %**", rep)
        self.assertIn("VIOLATION", rep)

    def test_size_above_cap_is_a_hard_fail(self):
        """Agent 06.4: ANY size above cap. G1 at 6 micros (cap 5) -> hard fail; same file with --cap 0 -> none."""
        tv = os.path.join(self.d, "tv_cap.csv")
        write(tv, TV_NEW, [tv_row_new("1", "Entry short", "2026-09-28 13:50", "S|sl=30674.00|tp=30558.00", "30638.25", "6", "1"),
                           tv_row_new("1", "Exit short", "2026-09-28 14:20", "", "30558.00", "6", "1")])
        rc = self.run_tool(tv)
        rep = Path(self.out).read_text()
        self.assertEqual(rc, 1, rep)
        self.assertIn("SIZE ABOVE CAP: TV #1 SELL at 2026-09-28 14:50 ET size 6 > cap 5 micros (I4)", rep)
        self.assertIn("| 2026-09-28 14:53 | SELL | 1 | 2026-09-28 14:50 | +0 | -9 | ENTRY_MODE | +0 | +0 | 3/6 |", rep)
        self.assertEqual(self.run_tool(tv, "--cap", "0"), 0)
        self.assertEqual(self.run_tool(tv, "--cap", "6"), 0)

    def test_size_at_cap_passes(self):
        tv = os.path.join(self.d, "tv_atcap.csv")
        write(tv, TV_NEW, [tv_row_new("1", "Entry short", "2026-09-28 13:50", "S|sl=30674.00|tp=30558.00", "30638.25", "5", "1"),
                           tv_row_new("1", "Exit short", "2026-09-28 14:20", "", "30558.00", "5", "1")])
        self.assertEqual(self.run_tool(tv), 0)
        self.assertNotIn("SIZE ABOVE CAP", Path(self.out).read_text())

    def test_require_stop_makes_a_stopless_trade_a_hard_fail(self):
        """Agent 06.4: ANY trade without a stop. Default = reported N/A (rc 0); --require-stop = hard fail."""
        tv = os.path.join(self.d, "tv_nostop.csv")
        write(tv, TV_NEW, [tv_row_new("1", "Entry short", "2026-09-28 13:50", "S|tp=30558.00", "30638.25", "3", "1"),
                           tv_row_new("1", "Exit short", "2026-09-28 14:20", "", "30558.00", "3", "1")])
        self.assertEqual(self.run_tool(tv), 0)
        rc = self.run_tool(tv, "--require-stop")
        rep = Path(self.out).read_text()
        self.assertEqual(rc, 1, rep)
        self.assertIn("NO STOP: TV #1 SELL at 2026-09-28 14:50 ET has no sl= in its Signal and no stop column (I2)", rep)
        self.assertIn("stop required: yes", rep)

    def test_missing_levels_reported_na(self):
        tv = os.path.join(self.d, "tv_nolevels.csv")
        write(tv, TV_NEW, [tv_row_new("1", "Entry short", "2026-09-28 13:50", "S", "30638.25", "3", "1"),
                           tv_row_new("1", "Exit short", "2026-09-28 14:20", "", "30558.00", "3", "1")])
        self.assertEqual(self.run_tool(tv), 0)
        self.assertIn("stop/T1 not in the TV export", Path(self.out).read_text())

    def test_g3_fails_on_a_london_entry(self):
        tv = os.path.join(self.d, "tv_g3.csv")
        write(tv, TV_NEW, [tv_row_new("1", "Entry short", "2026-09-28 04:30", "S", "30600.00", "1", "1"),
                           tv_row_new("1", "Exit short", "2026-09-28 05:00", "", "30590.00", "1", "1")])
        self.run_tool(tv)
        self.assertIn("**G3**: FAIL (1 TV entry in London", Path(self.out).read_text())

    def test_not_a_tv_export_fails_loud(self):
        bad = os.path.join(self.d, "bad.csv")
        write(bad, ["a", "b"], [["1", "2"]])
        with self.assertRaises(ValueError):
            pp.read_tv(bad, pp.ET)

    def test_real_oa2_export_headers_parse(self):
        """The owner's real 2026-11-05 List-of-Trades export (parity/tv_trades.csv): BOM + 'Trade number',
        'Date and time', 'Size (qty)', 'Net PnL USD', Signal 'sl=..|tp=..|ref=..' on entries, 'SL'/'T1' on exits."""
        real = (ROOT / "parity" / "tv_trades.csv").read_text(encoding="utf-8-sig").splitlines()
        self.assertTrue(real[0].startswith("Trade number,Type,Date and time,Signal,Price USD,Size (qty)"), real[0])
        tv_path = os.path.join(self.d, "tv_real_head.csv")
        Path(tv_path).write_text("\ufeff" + "\n".join(real[:5]) + "\n", encoding="utf-8")   # header + trades 1-2
        tv = pp.read_tv(tv_path, pp.ZoneInfo("America/Chicago"))
        self.assertEqual([t.num for t in tv], ["1", "2"])
        t1 = tv[0]
        self.assertEqual(t1.side, "SELL")
        self.assertEqual(t1.entry_ts.strftime("%Y-%m-%d %H:%M"), "2026-09-15 05:55")   # 04:55 CT -> ET
        self.assertEqual(t1.exit_ts.strftime("%Y-%m-%d %H:%M"), "2026-09-15 06:30")
        self.assertEqual((t1.entry, t1.stop, t1.t1, t1.size, t1.profit), (29339.5, 29370.5, 29317.75, 4, -252.0))
        self.assertEqual(t1.exit_price, 29370.5)
        self.assertEqual(tv[1].size, 5)
        # the full real file loads, every trade carries a stop and a T1 (06.4 "no stop" check has teeth)
        full = pp.read_tv(str(ROOT / "parity" / "tv_trades.csv"), pp.ZoneInfo("America/Chicago"))
        self.assertEqual(len(full), 22)
        self.assertEqual(pp.without_stop(full), [])
        self.assertTrue(all(t.t1 is not None and t.exit_ts is not None for t in full))

    def test_late_entry_window_ends_at_the_1800_et_roll(self):
        """I3 hard window is [16:30, 18:00) ET. An overnight entry (18:10 ET = 17:10 CT, real TV trade #5) is the
        next CME trading day and must NOT be a hard fail; 16:35 ET must."""
        mk = lambda ts_ct: pp.TvTrade("x", "SELL", pp._parse_dt(ts_ct, pp.ZoneInfo("America/Chicago")).astimezone(pp.ET), 1.0, 1)
        self.assertEqual(pp.late_entries([mk("2026-09-28 17:10"), mk("2026-09-29 18:50"), mk("2026-09-28 15:29")]), [])
        late = pp.late_entries([mk("2026-09-28 15:30"), mk("2026-09-28 16:59"), mk("2026-09-28 17:00")])
        self.assertEqual([t.entry_ts.strftime("%H:%M") for t in late], ["16:30", "17:59"])

    def test_same_setup_out_of_window_is_evidence_not_a_match(self):
        """G2 taken 4 bars late with the same stop/T1 (what the real OA-2 export shows on 2026-09-25): the tool
        does NOT match it (App. E +-1 bar) but lists it under 'Same setup, different time'."""
        tv = os.path.join(self.d, "tv_late_g2.csv")
        write(tv, TV_NEW, [tv_row_new("3", "Exit short", "2026-09-25 11:00", "SL", "30890", "2", "-134"),
                           tv_row_new("3", "Entry short", "2026-09-25 10:55", "sl=30890.00|tp=30805.25|ref=30847.75", "30857", "2", "-134")])
        self.assertEqual(self.run_tool(tv), 0)
        rep = Path(self.out).read_text()
        self.assertIn("| match rate | 0/4 = 0.0 % |", rep)
        self.assertIn("Java 2026-09-25 11:33 ET SELL ↔ TV #3 2026-09-25 11:55 ET (+5 bars; stop +1t, T1 +0t", rep)
        self.assertIn("**G2**: FAIL (no TradingView trade matched)", rep)

    def test_real_oracle_file_loads(self):
        java = pp.read_java(str(ROOT / "parity" / "java_signals.csv"))
        self.assertEqual(len(java), 8)
        self.assertEqual(java[-1].ts.strftime("%Y-%m-%d %H:%M"), "2026-09-28 14:53")


if __name__ == "__main__":
    unittest.main()
