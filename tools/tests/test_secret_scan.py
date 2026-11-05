"""Unit tests for .githooks/secret_scan.py (V6 Agent 05.5, R5).

Fake secrets are assembled at runtime so this file never contains a key-shaped literal.
"""
import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("secret_scan", ROOT / ".githooks" / "secret_scan.py")
ss = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ss)

TOK = "a1B2c3D4e5F6g7H8i9J0kLmN"   # 24 mixed chars, assembled into URLs/keys below


def hit(line):
    return ss.scan_line(line)


class SecretScanTest(unittest.TestCase):
    def test_key_shapes(self):
        self.assertTrue(hit("aws = " + "AKIA" + "ABCDEFGHIJKLMNOP"))
        self.assertTrue(hit("token " + "ghp_" + "x" * 36))
        self.assertTrue(hit("-----BEGIN " + "RSA PRIVATE KEY-----"))
        self.assertTrue(hit("k = " + "sk-ant-" + TOK))

    def test_webhook_urls(self):
        self.assertTrue(hit("url: https://app." + "crosstrade.io/v1/send/" + TOK))
        self.assertTrue(hit("https://webhooks." + "traderspost.io/trading/webhook/" + TOK + "/x"))
        self.assertTrue(hit("https://discord.com/api/webhooks/123456/" + TOK))
        self.assertTrue(hit("https://example.org/webhook/" + TOK))
        # vendor docs URLs carry no token -> clean
        self.assertFalse(hit("Source: https://crosstrade.io/docs/webhooks/advanced-options/strategy-synchronization"))
        self.assertFalse(hit("https://support.lucidtrading.com/en/articles/12945790-lucidflex-evaluation-account"))

    def test_assignments(self):
        self.assertTrue(hit("key=" + TOK + ";command=PLACE"))
        self.assertTrue(hit('apiKey: "' + TOK + '"'))
        self.assertTrue(hit("TOPSTEP_API_KEY=" + TOK))
        self.assertTrue(hit("account_" + "id = " + "2804" + "2793"))
        self.assertTrue(hit('"pass' + 'word": "' + 'Hunter2' * 2 + '"'))

    def test_placeholders_and_code_pass(self):
        for line in ["key=<RELAY_SECRET>;", "account_id=<ACCOUNT_1>", "apiKey: ${RELAY_KEY}",
                     "secret = {{alert_secret}}", "password=changeme", "apiKey = creds.apiKey;",
                     "String key = \"confluence.weight.\" + x;", "{ key: 'fib_0.62', label: 'OTE' }",
                     "lastToken = \"WOULD-BLOCK\";", "token: null", "key=<RELAY_SECRET input>; destination=tradovate"]:
            self.assertEqual(hit(line), [], line)

    def test_allow_marker(self):
        self.assertEqual(hit("key=" + TOK + "  # secret-scan: allow"), [])

    def test_redaction_never_prints_the_secret(self):
        found = ss.scan_text("f.txt", "key=" + TOK)
        self.assertEqual(len(found), 1)
        self.assertNotIn(TOK, found[0])


if __name__ == "__main__":
    unittest.main()
