"""Unit tests for tools/pine_lint.py (V6 Agent 02).  Run: python3 -m unittest discover -s tools/tests -v"""
import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
REPO = os.path.dirname(TOOLS)
sys.path.insert(0, TOOLS)

import pine_lint  # noqa: E402

HEAD = "//@version=6\n// BUILD: x\n// ==== file: 00_header.pine ====\nstrategy(\"t\", overlay = true, pyramiding = 0)\n"
MES = ("// ==== file: 70_bias_smt.pine ====\n"
       "sym = input.symbol(\"CME_MINI:MES1!\", \"smt\")\n"
       "[mesT, mesC] = request.security_lower_tf(sym, \"1\", [time, close])\n"
       "pdh = request.security(syminfo.tickerid, \"D\", high[1], lookahead = barmerge.lookahead_off)\n")
GOOD_80 = ("// ==== file: 80_strategy.pine ====\n"
           "if barstate.isconfirmed\n"
           "    if go and strategy.position_size == 0\n"
           "        strategy.entry(\"L\", strategy.long, qty = 1, alert_message = msg)\n"
           "        strategy.exit(\"L-x\", from_entry = \"L\", stop = sp, limit = tp, alert_message = msgx)\n")


def rules(text):
    ok, res = pine_lint.lint_text(text)
    return ok, {r.split(" ")[0]: (good, d) for r, good, d in res}


class LintTest(unittest.TestCase):
    def test_good_strategy_passes(self):
        ok, r = rules(HEAD + MES + GOOD_80)
        self.assertTrue(ok, r)
        self.assertIn("1 entry id(s) paired", r["L7"][1])

    def test_stub_without_entries_passes_vacuously(self):
        ok, r = rules(HEAD + MES + "// ==== file: 80_strategy.pine ====\n// stub\n")
        self.assertTrue(ok, r)
        self.assertIn("vacuous", r["L6"][1])

    def test_two_strategies_fail(self):
        ok, r = rules(HEAD + "strategy(\"b\")\n")
        self.assertFalse(r["L1"][0])

    def test_strategy_inside_string_or_comment_ignored(self):
        ok, r = rules(HEAD + "// strategy(\"c\")\ns = \"strategy(\"\n")
        self.assertTrue(r["L1"][0], r["L1"])

    def test_version_required(self):
        ok, r = rules("//@version=5\nstrategy(\"t\")\n")
        self.assertFalse(r["L2"][0])

    def test_lookahead_missing_fails(self):
        ok, r = rules(HEAD + "x = request.security(syminfo.tickerid, \"D\", high[1])\n")
        self.assertFalse(r["L3"][0])

    def test_lookahead_on_without_offset_fails_with_offset_passes(self):
        ok, r = rules(HEAD + "x = request.security(syminfo.tickerid, \"D\", high, lookahead = barmerge.lookahead_on)\n")
        self.assertFalse(r["L3"][0])
        ok, r = rules(HEAD + "x = request.security(syminfo.tickerid, \"D\", high[1], lookahead = barmerge.lookahead_on)\n")
        self.assertTrue(r["L3"][0], r["L3"])

    def test_multiline_security_call(self):
        ok, r = rules(HEAD + "x = request.security(syminfo.tickerid, \"D\",\n     high[1],\n     lookahead = barmerge.lookahead_off)\n")
        self.assertTrue(r["L3"][0], r["L3"])

    def test_entry_outside_80_fails(self):
        txt = HEAD + "// ==== file: 70_bias_smt.pine ====\nif barstate.isconfirmed\n    strategy.entry(\"L\", strategy.long)\n    strategy.exit(\"x\", from_entry = \"L\", stop = 1.0)\n"
        ok, r = rules(txt)
        self.assertFalse(r["L4"][0])

    def test_order_on_foreign_data_fails(self):
        bad = GOOD_80.replace("qty = 1", "qty = mesC.size()")
        ok, r = rules(HEAD + MES + bad)
        self.assertFalse(r["L5"][0])
        self.assertIn("mesC", r["L5"][1])

    def test_order_calling_request_fails(self):
        bad = GOOD_80.replace("stop = sp", "stop = request.security(\"CME_MINI:MES1!\", \"1\", low, lookahead = barmerge.lookahead_off)")
        ok, r = rules(HEAD + bad)
        self.assertFalse(r["L5"][0])

    def test_unguarded_entry_fails(self):
        bad = ("// ==== file: 80_strategy.pine ====\n"
               "if go\n"
               "    strategy.entry(\"L\", strategy.long)\n"
               "    strategy.exit(\"x\", from_entry = \"L\", stop = sp)\n")
        ok, r = rules(HEAD + bad)
        self.assertFalse(r["L6"][0])
        neg = bad.replace("if go", "if not barstate.isconfirmed")
        ok, r = rules(HEAD + neg)
        self.assertFalse(r["L6"][0])
        combined = bad.replace("if go", "if go and barstate.isconfirmed")
        ok, r = rules(HEAD + combined)
        self.assertTrue(r["L6"][0], r["L6"])
        # `or` dilutes the guard: the entry can fire on an unconfirmed bar (Fable verifier, 2026-11-05)
        diluted = bad.replace("if go", "if go or barstate.isconfirmed")
        ok, r = rules(HEAD + diluted)
        self.assertFalse(r["L6"][0], r["L6"])
        # a nested guard still counts; an unrelated `or` on an inner line does not break it
        nested = ("// ==== file: 80_strategy.pine ====\n"
                  "if barstate.isconfirmed\n"
                  "    if go or go2\n"
                  "        strategy.entry(\"L\", strategy.long)\n"
                  "        strategy.exit(\"x\", from_entry = \"L\", stop = sp)\n")
        ok, r = rules(HEAD + nested)
        self.assertTrue(r["L6"][0], r["L6"])

    def test_entry_without_stop_fails(self):
        bad = GOOD_80.replace("stop = sp, ", "")
        ok, r = rules(HEAD + bad)
        self.assertFalse(r["L7"][0])
        other = GOOD_80.replace("from_entry = \"L\"", "from_entry = \"S\"")
        ok, r = rules(HEAD + other)
        self.assertFalse(r["L7"][0])
        na_stop = GOOD_80.replace("stop = sp", "stop = na")
        ok, r = rules(HEAD + na_stop)
        self.assertFalse(r["L7"][0])

    def test_exit_without_from_entry_covers_all(self):
        txt = GOOD_80.replace("from_entry = \"L\", ", "")
        ok, r = rules(HEAD + txt)
        self.assertTrue(r["L7"][0], r["L7"])

    def test_secrets(self):
        for bad in ("u = \"https://hooks.example.com/abc\"\n",
                    "// key=abcdef123456\n",
                    "s = input.string(\"realSecretValue\", \"relay secret\")\n",
                    "t = \"AbCdEfGhIjKlMnOpQrStUvWxYz0123456789\"\n"):
            ok, r = rules(HEAD + bad)
            self.assertFalse(r["L8"][0], bad)
        for good in ("s = input.string(\"\", \"relay secret\")\n",
                     "s = input.string(\"<RELAY_SECRET>\", \"relay secret\")\n",
                     "a = input.string(\"<ACCOUNT_1>\", \"accounts\")\n"):
            ok, r = rules(HEAD + good)
            self.assertTrue(r["L8"][0], (good, r["L8"]))

    def test_tick_entries_and_pyramiding(self):
        ok, r = rules(HEAD.replace("pyramiding = 0", "pyramiding = 0, calc_on_every_tick = true"))
        self.assertFalse(r["L9"][0])
        ok, r = rules(HEAD.replace("pyramiding = 0", "pyramiding = 2"))
        self.assertFalse(r["L9"][0])

    def test_real_dist_passes(self):
        dist = os.path.join(REPO, "pine", "dist", "LucidICT_v6.pine")
        r = subprocess.run([sys.executable, os.path.join(TOOLS, "pine_lint.py"), dist],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(r.returncode, 0, r.stdout.decode())


if __name__ == "__main__":
    unittest.main()
