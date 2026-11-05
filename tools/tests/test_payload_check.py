"""Tests for V6 Agent 04: tools/payload_check.py, the Pine payload builders (pine/src/75_payload.pine),
the [Relay] inputs and lint L10.  Run: python3 -m unittest discover -s tools/tests -v"""
import io
import os
import re
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
REPO = os.path.dirname(TOOLS)
sys.path.insert(0, TOOLS)

import payload_check as pc  # noqa: E402
import pine_lint  # noqa: E402

SRC75 = os.path.join(REPO, "pine", "src", "75_payload.pine")
SRC10 = os.path.join(REPO, "pine", "src", "10_inputs.pine")
DIST = os.path.join(REPO, "pine", "dist", "LucidICT_v6.pine")
BID = "f1a1f130f92a25ee"


def slurp(p):
    with open(p, encoding="utf-8") as fh:
        return fh.read()


def entry(**over):
    f = dict(pc.ct_fields("ENTRY", -1, 3, 30674.00, 30558.00, build_id=BID))
    f.update(over)
    return [(k, v) for k, v in f.items() if v is not None]


def one(fields, **kw):
    return pc.check_crosstrade(fields, cap=kw.pop("cap", 5), **kw)


class CrossTradeTest(unittest.TestCase):
    def test_reference_samples_pass(self):
        for title, txt in pc.samples(BID):
            res = pc.check_text(txt, cap=5, build_id=BID)
            self.assertEqual(len(res), 1, title)
            self.assertTrue(res[0].ok, (title, res[0].errors))

    def test_entry_without_stop_fails_I2(self):
        r = one(entry(stop_loss=None))
        self.assertFalse(r.ok)
        self.assertTrue(any("stop_loss missing" in e for e in r.errors))

    def test_relative_or_wrong_side_bracket_fails(self):
        self.assertFalse(one(entry(stop_loss="20 ticks")).ok)
        self.assertFalse(one(entry(stop_loss="30500.00")).ok)  # short stop below target

    def test_qty_cap_I4(self):
        r = one(entry(qty="6", target_quantity="6"))
        self.assertTrue(any("exceeds cap 5" in e for e in r.errors))
        self.assertFalse(one(entry(qty="0", target_quantity="0")).ok)

    def test_sync_fields_required(self):
        for k, bad in (("out_of_sync", "wait"), ("strategy_exit_block", None), ("sync_strategy", None),
                       ("prev_market_position", "long"), ("market_position", "long"), ("target_quantity", "2"),
                       ("destination", None), ("account", ""), ("instrument", "MES1!")):
            self.assertFalse(one(entry(**{k: bad})).ok, k)

    def test_rejected_and_unsafe_fields(self):
        self.assertFalse(one(entry() + [("oco_id", "x")]).ok)
        self.assertFalse(one(entry() + [("cycle_accounts", "true")]).ok)
        self.assertFalse(one(entry(account="<ACCOUNT_1>,<ACCOUNT_1>")).ok)
        self.assertFalse(one(entry(command="REVERSE")).ok)

    def test_bracket_fill_must_be_rejected_by_exit_block(self):
        good = pc.ct_fields("BRACKET", 1, 2, build_id=BID)
        self.assertTrue(one(good).ok)
        d = dict(good)
        d["prev_market_position"] = "flat"
        self.assertFalse(one(list(d.items())).ok)
        self.assertFalse(one(good + [("stop_loss", "1.00")]).ok)
        # Fable A-04v: the message must be non-actionable on its own — no order foundation at all
        for f, v in (("action", "SELL"), ("qty", "2"), ("order_type", "MARKET"), ("tif", "DAY")):
            self.assertFalse(one(good + [(f, v)]).ok, f)
        self.assertFalse(any(k in ("action", "qty", "order_type", "tif") for k, _ in good))

    def test_pine_bracket_builder_has_no_order_foundation(self):
        src = slurp(SRC75)
        body = src[src.index('e.kind == "BRACKET"'):src.index("    else\n", src.index('e.kind == "BRACKET"'))]
        for f in ("action", "qty", "order_type", "tif", "take_profit", "stop_loss", "flatten_first"):
            self.assertNotIn('ctLine("{}"'.format(f), body, f)
        self.assertIn('ctLine("strategy_exit_block", "true")', body)

    def test_flatten_not_gated(self):
        f = pc.ct_fields("FLATTEN", 1, 2, build_id=BID)
        self.assertTrue(one(f).ok)
        self.assertFalse(one(f + [("sync_strategy", "true")]).ok)
        d = dict(f)
        d["command"] = "CLOSEPOSITION"
        self.assertFalse(one(list(d.items())).ok)

    def test_build_id_and_live_mode(self):
        self.assertFalse(one(entry(), build_id="0000000000000000").ok)
        self.assertFalse(one(entry(), live=True).ok)  # placeholders are errors when --live
        live = entry(key="Ab12Cd34Ef56", account="LFE0001,LFE0002")  # fake, test-only  secret-scan: allow
        r = one(live, live=True, build_id=BID)
        self.assertTrue(r.ok, r.errors)
        self.assertFalse(one(entry(key="Ab12 Cd34")).ok)  # whitespace in key (PF:142-143)

    def test_parses_alert_log_and_pine_logs_forms(self):
        txt = pc.ct_text(pc.ct_fields("ENTRY", 1, 2, 100.00, 110.00, build_id=BID))
        collapsed = "2026-11-05 10:00 LucidICT_v6 alert: " + txt.replace("\n", " ")
        logs = "[LucidICT_v6-{}-ENTRY why=OTE]\n".format(BID) + txt
        both = collapsed + "\n" + logs + "\n" + pc.ct_text(pc.ct_fields("FLATTEN", 1, 2, build_id=BID))
        res = pc.check_text(both, cap=5, build_id=BID)
        self.assertEqual([r.label for r in res], ["crosstrade ENTRY", "crosstrade ENTRY", "crosstrade FLATTEN"])
        self.assertTrue(all(r.ok for r in res), [r.errors for r in res])

    def test_cli_never_prints_the_secret(self):
        sec = "Zx9Qw8Er7Ty6Ui5Op4"  # fake, test-only  secret-scan: allow
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as fh:
            fh.write(pc.ct_text(pc.ct_fields("ENTRY", 1, 2, 100.00, 110.00, sec=sec, accounts="ACC1", build_id=BID)))
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = pc.main([fh.name, "--live", "--build-id", BID])
        os.unlink(fh.name)
        self.assertEqual(rc, 0, buf.getvalue())
        self.assertNotIn(sec, buf.getvalue())


class FallbackJsonTest(unittest.TestCase):
    def test_pickmytrade(self):
        ok = '{"symbol":"MNQ","date":"x","data":"sell","quantity":3,"order_type":"MKT","tp":30558.00,"sl":30674.00,"pyramid":false,"token":"<RELAY_SECRET>","account_id":"<ACCOUNT_1>"}'
        nostop = ok.replace('"sl":30674.00,', "")
        brk = '{"symbol":"MNQ","date":"x","note":"LucidICT_v6-x-BRACKET_FILL_IGNORE"}'
        res = pc.check_text(ok + nostop + brk, "PICKMYTRADE", 5)
        self.assertEqual([r.ok for r in res], [True, False, True])

    def test_traderspost(self):
        ok = '{"ticker":"MNQZ2026","action":"buy","sentiment":"long","quantity":2,"orderType":"market","timeInForce":"day","takeProfit":{"limitPrice":110.00},"stopLoss":{"type":"stop","stopPrice":100.00}}'
        cont = ok.replace("MNQZ2026", "MNQ1!")
        res = pc.check_text(ok + cont + '{"note":"x"}' + '{"ticker":"MNQZ2026","action":"exit","sentiment":"flat","cancel":true}', "TRADERSPOST", 5)
        self.assertEqual([r.ok for r in res], [True, False, True, True])


class PineSourceTest(unittest.TestCase):
    def test_crosstrade_field_set_matches_pine(self):
        """The Python mirror and the Pine builder emit the same CrossTrade field names."""
        src = slurp(SRC75)
        body = src[src.index("ctPayload("):src.index("// ---- PickMyTrade")]
        pine_keys = set(re.findall(r'ctLine\("([a-z_]+)"', body))
        py_keys = set()
        for kind in pc.KINDS:
            py_keys |= {k for k, _ in pc.ct_fields(kind, 1, 1, 1.0, 2.0)}
        self.assertEqual(pine_keys, py_keys)
        self.assertIn('ctLine("strategy_exit_block", "true")', body)
        self.assertIn('ctLine("command", "FLATTEN")', body)

    def test_payload_file_precedes_strategy_in_dist(self):
        dist = slurp(DIST)
        self.assertLess(dist.index("// ==== file: 75_payload.pine ===="), dist.index("// ==== file: 80_strategy.pine ===="))
        self.assertEqual(len(re.findall(r"(?m)^orderAlert\(", pine_lint.strip_comments(dist))), 1)

    def test_secret_inputs_hidden_with_placeholder_defaults(self):
        src = slurp(SRC10)
        for var, ph in (("i_relaySecret", "<RELAY_SECRET>"), ("i_relayAccounts", "<ACCOUNT_1>")):
            line = next(ln for ln in src.split("\n") if ln.startswith(var))
            self.assertIn('input.string("{}"'.format(ph), line)
            self.assertIn("display = display.none", line)
        self.assertRegex(src, r'i_relay\s+= input\.string\("CROSSTRADE".*"PICKMYTRADE", "TRADERSPOST"')
        self.assertRegex(src, r"i_tradingEnabled = input\.bool\(false")

    def test_secret_never_plotted_or_logged(self):
        code = pine_lint.strip_comments(slurp(DIST))
        for m in re.finditer(r"\b(plot\w*|label\.new|table\.cell|log\.\w+|alert)\s*\(", code):
            j = m.end()
            depth = 1
            while depth:
                depth += {"(": 1, ")": -1}.get(code[j], 0)
                j += 1
            self.assertNotRegex(code[m.end():j], r"i_relaySecret|i_relayAccounts|\bsec\b", code[m.start():j][:80])

    def test_secret_scan_clean_on_agent04_files(self):
        files = [SRC75, SRC10, os.path.join(REPO, "pine", "src", "90_alerts.pine"),
                 os.path.join(TOOLS, "payload_check.py"), os.path.join(REPO, "docs", "v6", "RELAY_SETUP.md")]
        r = subprocess.run([sys.executable, os.path.join(REPO, ".githooks", "secret_scan.py")] + files,
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)


class LintL10Test(unittest.TestCase):
    HEAD = "//@version=6\n// ==== file: 00_header.pine ====\nstrategy(\"t\", pyramiding = 0)\n// ==== file: 80_strategy.pine ====\nif barstate.isconfirmed\n"

    def l10(self, body):
        _, res = pine_lint.lint_text(self.HEAD + body)
        return next((ok, d) for r, ok, d in res if r.startswith("L10"))

    def test_missing_and_empty_fail(self):
        ok, d = self.l10('    strategy.entry("L", strategy.long, alert_message = m)\n    strategy.exit("x", from_entry = "L", stop = s)\n')
        self.assertFalse(ok)
        self.assertIn("strategy.exit", d)
        ok, _ = self.l10('    strategy.close_all(alert_message = "")\n')
        self.assertFalse(ok)

    def test_real_dist_passes(self):
        ok, res = pine_lint.lint_text(slurp(DIST))
        l10 = [x for x in res if x[0].startswith("L10")][0]
        self.assertTrue(l10[1], l10[2])
        self.assertIn("6 order call(s)", l10[2])


if __name__ == "__main__":
    unittest.main()
