"""Tests for V6 Agent 03: AccountProfile generation (tools/pine_build.py) and the 80_strategy order
envelope contract. Run: python3 -m unittest discover -s tools/tests -v

Pine cannot run here, so the sizing formula is mirrored in Python (`size_contracts`, a line-for-line
copy of sizeContracts in pine/src/80_strategy.pine) and checked against EVERY oracle row of
parity/java_signals.csv; the Pine self-test (input selfTest, task 03.8) asserts the same cases on TV.
"""
import csv
import math
import os
import re
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
REPO = os.path.dirname(TOOLS)
sys.path.insert(0, TOOLS)

import pine_build  # noqa: E402
import pine_lint  # noqa: E402
import pine_parity  # noqa: E402

YAML = os.path.join(REPO, "config", "lucid_profiles.yaml")
SRC80 = os.path.join(REPO, "pine", "src", "80_strategy.pine")
SRC10 = os.path.join(REPO, "pine", "src", "10_inputs.pine")
DIST = os.path.join(REPO, "pine", "dist", "LucidICT_v6.pine")
ORACLE = os.path.join(REPO, "parity", "java_signals.csv")


def slurp(p):
    with open(p, encoding="utf-8") as fh:
        return fh.read()


def size_contracts(budget, ref, fill, stop, tick, tick_value, cap_micros, mpc):
    """Mirror of sizeContracts() in 80_strategy.pine."""
    if budget is None or budget <= 0 or tick <= 0 or tick_value <= 0 or mpc <= 0:
        return 0
    dist = abs(fill - stop) if ref is None else max(abs(ref - stop), abs(fill - stop))
    stop_ticks = int(round(dist / tick))
    if stop_ticks <= 0:
        return 0
    risk_qty = math.floor(budget / (stop_ticks * tick_value) + 1e-9)
    cap_c = math.floor(cap_micros / mpc + 1e-9)
    return max(0, min(risk_qty, cap_c))


class YamlReaderTest(unittest.TestCase):
    def test_matches_pyyaml_on_real_file(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML not installed")
        text = slurp(YAML)
        self.assertEqual(pine_build.parse_yaml_subset(text), yaml.safe_load(text))

    def test_subset_features_and_errors(self):
        d = pine_build.parse_yaml_subset(
            "a:\n  b: 1   # c\n  s: \"x # y\"\n  n: null\n  l:\n    - { p: 0, q: 2.5 }\n    - k: v\n      w: true\n")
        self.assertEqual(d, {"a": {"b": 1, "s": "x # y", "n": None, "l": [{"p": 0, "q": 2.5}, {"k": "v", "w": True}]}})
        with self.assertRaises(pine_build.BuildError):
            pine_build.parse_yaml_subset("a: 1\n  b: 2\n")


class ProfileGenerationTest(unittest.TestCase):
    def setUp(self):
        self.common, self.profs = pine_build.load_profiles(YAML)
        self.block = "\n".join(pine_build.generate_profiles_block(YAML))

    def test_every_yaml_profile_is_an_option_plus_custom(self):
        import ast
        m = re.search(r'i_profile = input\.string\("(\w+)", "Plan profile", options = (\[[^\]]*\])', self.block)
        self.assertIsNotNone(m)
        opts = ast.literal_eval(m.group(2))
        self.assertEqual(opts, [n for n, _ in self.profs] + ["CUSTOM"])
        self.assertEqual(m.group(1), self.profs[0][0])

    def test_values_come_from_yaml(self):
        y = pine_build.parse_yaml_subset(slurp(YAML))["profiles"]
        for name, spec in self.profs:
            self.assertEqual(spec["max_micros"], y[name]["max_contracts_micros"])
            self.assertEqual(spec["scaling_cap_micros"], y[name]["scaling_cap_micros"])
            self.assertEqual(spec["soft_daily_loss"], y[name]["v6_soft_daily_loss_usd"])
            self.assertEqual(spec["mll"], y[name]["max_loss_limit_usd"])
            self.assertIn('p == "{}" ? {} :'.format(name, spec["max_micros"]), self.block)
            self.assertIn('p == "{}" ? {} :'.format(name, pine_build._flt(spec["soft_daily_loss"])), self.block)
            # Fable A-03v: drawdownBuffer$ comes from the YAML key, not the derived fallback
            self.assertEqual(spec["drawdown_buffer"], float(y[name]["v6_drawdown_buffer_usd"]))
            self.assertAlmostEqual(spec["drawdown_buffer"], 0.10 * spec["mll"], places=6, msg=name)
        # OA-7 default: LucidFlex 50K soft daily loss $600 (CONSERVATIVE-UNVERIFIED)
        self.assertIn('p == "LUCID_FLEX_50K_EVAL" ? 600.0 :', self.block)

    def test_accessors_are_simple_qualified(self):
        """strategy.risk.max_intraday_loss needs a simple value. A parameter declared with a bare type
        keyword defaults to the series qualifier (Pine docs, user-defined functions -> qualifier keywords),
        so every accessor must declare `simple string p` and be a pure ?: chain (no switch / mutable local)."""
        fns = re.findall(r"^(ap\w+)\((simple string p)[^)]*\) =>\n    (.+)$", self.block, re.M)
        names = {f for f, _, _ in fns}
        for want in ("apMaxMicros", "apScalingCapBase", "apSoftDailyLoss", "apDrawdownBuffer",
                     "apStartBalance", "apMll", "apLockedFloor"):
            self.assertIn(want, names)
        self.assertNotIn("switch", self.block)
        body = dict((f, b) for f, _, b in fns)["apSoftDailyLoss"]
        self.assertTrue(body.endswith(": i_cSoftDL"), body)
        self.assertEqual(body.count("?"), len(self.profs))
        self.assertIn("apScalingCap(simple string p, float pr) =>", self.block)

    def test_time_limits_from_yaml(self):
        self.assertIn("const int V6_HARD_NO_ENTRY_HHMM = 1630", self.block)
        self.assertIn("const int V6_HARD_FLATTEN_HHMM  = 1640", self.block)

    def test_scaling_ternary_matches_bands(self):
        y = pine_build.parse_yaml_subset(slurp(YAML))["profiles"]
        for name, spec in self.profs:
            if not spec["bands"]:
                continue
            m = re.search(r'if p == "{}"\n        v := ([^\n]+)'.format(name), self.block)
            self.assertIsNotNone(m, name)
            expr = m.group(1)
            py = re.sub(r"(pr >= [\d.]+) \? (\d+) :", r"(\2 if \1 else", expr)
            py += ")" * expr.count("?")
            for band in y[name]["scaling_plan"]:
                for pr in (band["profit_from_usd"], band["profit_from_usd"] + 0.5):
                    self.assertEqual(eval(py, {}, {"pr": pr}), band["micros"], (name, pr, py))
            self.assertEqual(eval(py, {}, {"pr": -500.0}), y[name]["scaling_plan"][0]["micros"])

    def test_bad_yaml_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "p.yaml")
            with open(p, "w") as fh:
                fh.write("common:\n  v6_no_entry_after_et: \"16:30\"\n  v6_flatten_at_et: \"16:40\"\n"
                         "profiles:\n  X:\n    max_contracts_micros: 4\n    scaling_cap_micros: 9\n"
                         "    v6_soft_daily_loss_usd: 1\n    starting_balance_usd: 1\n    max_loss_limit_usd: 1\n"
                         "    locked_mll_balance_usd: 1\n")
            with self.assertRaises(pine_build.BuildError):
                pine_build.load_profiles(p)

    def test_no_plan_numbers_typed_in_pine_src(self):
        """AP-5: no YAML plan number literal appears in the hand-written Pine (10 / 80)."""
        plan_numbers = set()
        for _, s in self.profs:
            for k in ("start_balance", "mll", "locked_floor", "soft_daily_loss", "drawdown_buffer"):
                plan_numbers.add(s[k])
        # Agent 03's code: the [Account]/[Risk] inputs of 10 + 80 without the self-test fixture cases
        # (abstract test numbers and oracle prices, not plan numbers).
        inputs = "\n".join(ln for ln in slurp(SRC10).split("\n") if "G_ACCOUNT" in ln or "G_RISK" in ln)
        s80 = slurp(SRC80)
        s80 = s80[:s80.index("selfTestCases() =>")]
        code = pine_lint.strip_comments(inputs + "\n" + s80)
        lits = {float(x) for x in re.findall(r"(?<![\w.])(\d+(?:\.\d+)?)(?![\w.])", code)}
        lits.discard(1000.0)  # MS_SEC unit constant (ms per second)
        hits = sorted(n for n in plan_numbers if n in lits and n >= 100)
        self.assertEqual(hits, [], "plan numbers typed into pine/src: {}".format(hits))


class SizingOracleTest(unittest.TestCase):
    """03.3 / I4: floor($250 / (stopTicks x $0.50)) capped at the Java effective 5 micros reproduces
    every oracle size (PORT_MAP S5); with the Lucid profile cap alone (40) it would not."""

    def test_all_oracle_sizes(self):
        with open(ORACLE, newline="") as fh:
            rows = list(csv.DictReader(fh))
        self.assertEqual(len(rows), 8)
        for r in rows:
            e, s = float(r["entry"]), float(r["stop"])
            cap = min(40, 40, 5)
            self.assertEqual(size_contracts(250.0, e, e, s, 0.25, 0.5, cap, 1), int(r["size_micros"]), r["ts_et"])

    def test_oracle_per_micro_risk_arithmetic(self):
        """Fable A-03v: re-derive each row's size from the oracle's own '$X/micro' text and the formula
        ticks = |entry - stop| / 0.25, $/micro = ticks x $0.50, size = min(5, floor(250 / $/micro))."""
        with open(ORACLE, newline="") as fh:
            rows = list(csv.DictReader(fh))
        for r in rows:
            e, s = float(r["entry"]), float(r["stop"])
            ticks = round(abs(e - s) / 0.25)
            per_micro = ticks * 0.5
            m = re.search(r"\(\$([\d.]+)/micro\)", r["risk_decision"])
            self.assertAlmostEqual(float(m.group(1)), per_micro, places=2, msg=r["ts_et"])
            self.assertEqual(min(5, math.floor(250.0 / per_micro + 1e-9)), int(r["size_micros"]), r["ts_et"])
            # the Pine mirror with the 5m fill drifting up to 4 ticks AWAY from the stop never exceeds the oracle
            drift = 4 * 0.25 * (1 if e > s else -1)
            self.assertLessEqual(size_contracts(250.0, e, e + drift, s, 0.25, 0.5, 5, 1), int(r["size_micros"]))
            # ... and a fill CLOSER to the stop reproduces the oracle size exactly (distance = Java ref)
            self.assertEqual(size_contracts(250.0, e, e - drift, s, 0.25, 0.5, 5, 1), int(r["size_micros"]))

    def test_entry_cap_mismatch_is_exactly_the_09_23_fourth_signal(self):
        """I5 maxEntriesPerSession = 3 (sacred default) vs the oracle: simulate the per-trading-day counter
        over java_signals.csv. Exactly one row (2026-09-23 06:37) is refused -> documented in GOLDEN_SIGNALS
        as the known ENTRY-CAP mismatch for Agent 06; the default is NOT changed (AP-8)."""
        import datetime
        with open(ORACLE, newline="") as fh:
            rows = list(csv.DictReader(fh))
        per_day, refused = {}, []
        for r in rows:
            t = datetime.datetime.strptime(r["ts_et"], "%Y-%m-%d %H:%M")
            day = (t + datetime.timedelta(hours=6)).date()  # CME trading day rolls 18:00 ET
            per_day[day] = per_day.get(day, 0) + 1
            if per_day[day] > 3:
                refused.append(r["ts_et"])
        self.assertEqual(refused, ["2026-09-23 06:37"])
        doc = slurp(os.path.join(REPO, "docs", "v6", "GOLDEN_SIGNALS.md"))
        self.assertIn("ENTRY-CAP", doc)
        self.assertIn("2026-09-23 06:37", doc)

    def test_g1_g2_worked_examples(self):
        # G1: ref 30636.00, stop 30674.00 -> 152 ticks -> $76/micro -> 3; 5m close 30638.25 is closer -> still 3
        self.assertEqual(size_contracts(250.0, 30636.00, 30638.25, 30674.00, 0.25, 0.5, 5, 1), 3)
        # G2: ref 30847.50, stop 30889.75 -> 169 ticks -> $84.50 -> 2
        self.assertEqual(size_contracts(250.0, 30847.50, 30847.50, 30889.75, 0.25, 0.5, 5, 1), 2)
        # a fill further from the stop shrinks the size (never risks more than the budget)
        self.assertEqual(size_contracts(250.0, 30636.00, 30600.00, 30674.00, 0.25, 0.5, 5, 1), 1)
        # micro-equivalent cap: 30 micros on a mini chart = 3 contracts
        self.assertEqual(size_contracts(1e6, 100, 100, 99, 0.25, 0.5, 30, 10), 3)
        self.assertEqual(size_contracts(None, 100, 100, 99, 0.25, 0.5, 5, 1), 0)

    def test_pine_self_test_uses_the_same_cases(self):
        src = slurp(SRC80)
        for case in ("sizeContracts(250.0, 30636.00, 30638.25, 30674.00, 0.25, 0.5, 5, 1) != 3",
                     "sizeContracts(250.0, 30847.50, 30847.50, 30889.75, 0.25, 0.5, 5, 1) != 2"):
            self.assertIn(case, src)


class StrategyContractTest(unittest.TestCase):
    def setUp(self):
        self.dist = slurp(DIST)
        self.lint = pine_lint.Lint(self.dist)

    def order_calls(self):
        out = []
        for fn in pine_lint.ORDER_FUNCS:
            for line, args, _ in self.lint.calls(fn):
                out.append((fn, line, args))
        return out

    def test_every_order_call_has_alert_message_hook(self):
        calls = self.order_calls()
        self.assertGreaterEqual(len(calls), 6)
        for fn, line, args in calls:
            am = pine_lint.named_arg(pine_lint.split_args(args), "alert_message")
            self.assertIsNotNone(am, "{} at line {} has no alert_message".format(fn, line))
            self.assertTrue(am.startswith("orderAlert("), "{} at line {}: {}".format(fn, line, am))

    def test_entries_and_exits_shape(self):
        ents = self.lint.calls("strategy.entry")
        exits = self.lint.calls("strategy.exit")
        self.assertEqual(len(ents), 2)
        for _, args, _ in ents:
            parts = pine_lint.split_args(args)
            self.assertIn(pine_lint.positional(parts, 0), ('"LONG"', '"SHORT"'))
            self.assertEqual(pine_lint.named_arg(parts, "qty"), "entryQty")
            self.assertEqual(pine_lint.named_arg(parts, "comment"), "cmt")
        for _, args, _ in exits:
            parts = pine_lint.split_args(args)
            self.assertEqual(pine_lint.named_arg(parts, "stop"), "sigStop")
            self.assertEqual(pine_lint.named_arg(parts, "limit"), "sigT1")
        self.assertEqual(len(self.lint.calls("strategy.order")), 0)
        # I1 / AP-10: never a reversal order
        self.assertNotRegex(pine_lint.strip_comments(self.dist), r"strategy\.(reverse|order)\s*\(")

    def test_signal_comment_is_parsed_by_parity_tool(self):
        sig = "sl=30674.00|tp=30558.00|ref=30636.00"
        self.assertEqual(float(pine_parity._SL.search(sig).group(1)), 30674.00)
        self.assertEqual(float(pine_parity._TP.search(sig).group(1)), 30558.00)
        self.assertIn('string cmt = "sl=" + str.tostring(sigStop, format.mintick) + "|tp=" + str.tostring(sigT1, format.mintick)',
                      slurp(SRC80))

    def test_hook_never_returns_empty(self):
        """The hook moved to 75_payload.pine (Agent 04): same signature, last line falls back to a tag."""
        src75 = slurp(os.path.join(REPO, "pine", "src", "75_payload.pine"))
        self.assertRegex(src75, r"(?m)^orderAlert\(string kind, int dir, int qty, float stopPx, float tpPx, string why\) =>$")
        body = src75[src75.index("\norderAlert("):]
        last = [ln for ln in body.split("\n") if ln.startswith("    ")][-1].strip()
        self.assertEqual(last, 'str.length(p) > 0 ? p : PAYLOAD_TAG + " " + BUILD_ID + " " + kind')
        self.assertNotRegex(pine_lint.strip_comments(slurp(SRC80)), r"(?m)^orderAlert\(")

    def test_invariant_guards_present(self):
        src = slurp(SRC80)
        for needle in ("strategy.risk.max_intraday_loss(apSoftDailyLoss(i_profile), strategy.cash)",
                       "strategy.risk.max_intraday_filled_orders(i_maxEntries * 2)",
                       "entryTimeBlocked(time_close)", "spacingOk(lastOrderMs, time_close, i_minSecBetween)",
                       "dayEntries >= i_maxEntries", "flatOk(strategy.position_size, strategy.opentrades",
                       "holdOk(strategy.opentrades.entry_time(0), time_close, i_minHoldSec)",
                       "bracketReason(dir, fillPx, sigStop, sigT1, TICK, i_minStopTicks, i_minT1Ticks)"):
            self.assertIn(needle, src)
        header = slurp(os.path.join(REPO, "pine", "src", "00_header.pine"))
        self.assertIn("pyramiding = 0", header)
        self.assertIn("process_orders_on_close = true", header)

    def test_profile_not_read_by_detectors(self):
        """I7: no file before 80 (except the generated inputs) reads the plan profile."""
        for name in sorted(os.listdir(os.path.join(REPO, "pine", "src"))):
            if name[:2] in ("10", "80"):
                continue
            code = pine_lint.strip_comments(slurp(os.path.join(REPO, "pine", "src", name)))
            self.assertNotRegex(code, r"\bi_profile\b|\bap[A-Z]\w*\(", name)


if __name__ == "__main__":
    unittest.main()
