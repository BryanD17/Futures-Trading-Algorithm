#!/usr/bin/env python3
"""V6 Agent 05.5 (R5 / I9 / AP-9): fail on secrets before they reach git.

Flags
  * key-shaped strings: AWS / GitHub / Slack / OpenAI-Anthropic-style keys,
    private-key blocks, JWTs;
  * webhook URLs with a real token in them (TradingView relays — CrossTrade,
    TradersPost, PickMyTrade —, Discord, Slack, generic ".../webhook/<token>");
  * ``key=`` / ``secret:`` / ``password=`` / ``token=`` / ``apiKey`` /
    ``account_id`` style assignments whose value is NOT a placeholder.

Placeholders that pass: ``<RELAY_SECRET>``, ``<ACCOUNT_1>``, ``${ENV}``,
``$ENV``, ``{{alert_secret}}``, ``YOUR_...``, ``changeme``, ``xxx...``,
``***``, ``REDACTED``, ``placeholder``, ``example``, ``dummy``, ``test``,
``none``/``null``/empty, and values shorter than 8 characters.

A line can be allow-listed with the marker ``secret-scan: allow`` (use for
documentation that must show a pattern, never for a real value).

Usage
  secret_scan.py --staged        # pre-commit: added lines of the staged diff
  secret_scan.py --all           # CI: every tracked file
  secret_scan.py FILE [FILE...]  # explicit files (tests)
Exit 1 when anything is found (prints file:line: rule: redacted match).
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ALLOW_MARKER = "secret-scan: allow"

# (rule name, compiled regex) — key-shaped strings, always a hit.
KEY_SHAPES = [
    ("aws-access-key", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("github-token", re.compile(r"\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36,}\b")),
    ("github-pat", re.compile(r"\bgithub_pat_[A-Za-z0-9_]{40,}\b")),
    ("slack-token", re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b")),
    ("sk-api-key", re.compile(r"\bsk-(?:ant-|proj-|live_|test_)?[A-Za-z0-9_\-]{24,}\b")),
    ("stripe-key", re.compile(r"\b(?:rk|pk|sk)_(?:live|test)_[A-Za-z0-9]{16,}\b")),
    ("google-api-key", re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b")),
    ("private-key-block", re.compile(r"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----")),
    ("jwt", re.compile(r"\beyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}")),
]

# A token: >= 20 contiguous [A-Za-z0-9_] with at least one digit and one letter, or a UUID.
TOKEN = (r"(?:(?=[A-Za-z0-9_]*\d)(?=[A-Za-z0-9_]*[A-Za-z])[A-Za-z0-9_]{20,}"
         r"|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")

# Webhook URLs: the URL itself is the secret when it carries a token.
WEBHOOK_URLS = [
    ("discord-webhook", re.compile(r"https?://(?:ptb\.|canary\.)?discord(?:app)?\.com/api/webhooks/\d+/[A-Za-z0-9_\-]{20,}")),
    ("slack-webhook", re.compile(r"https?://hooks\.slack\.com/services/[A-Za-z0-9/]{20,}")),
    ("relay-webhook", re.compile(
        r"https?://[A-Za-z0-9.\-]*(?:crosstrade|traderspost|pickmytrade|tradersconnect|signalstack)"
        r"[A-Za-z0-9.\-]*\.[a-z]{2,}/[^\s\"'<>]*?(?<![A-Za-z0-9_])" + TOKEN + r"(?![A-Za-z0-9_])")),
    ("generic-webhook", re.compile(
        r"https?://[^\s\"'<>]*/(?:webhooks?|hooks?)/[^\s\"'<>]*?(?<![A-Za-z0-9_])" + TOKEN + r"(?![A-Za-z0-9_])")),
]

# key=value / key: value / "key": "value" assignments. The key must be a whole
# identifier ENDING in a secret-ish name (so lastToken / keyFn / key={...} pass).
ASSIGNMENT = re.compile(
    r"(?i)(?<![A-Za-z0-9_])(?P<key>(?:[A-Za-z0-9]+[_\-.])*(?:api[_\-]?key|apikey|secret(?:[_\-]?key)?|client[_\-]?secret|"
    r"passw(?:or)?d|passwd|pwd|(?:access|auth|bearer|api|refresh|relay|webhook|session)[_\-]?token|token|"
    r"webhook[_\-]?url|account[_\-]?id|private[_\-]?key|key))[\"']?\s*(?P<sep>=|:)\s*"
    r"(?P<q>[\"']?)(?P<val>[^\s\"',;)}\]]+)")

PLACEHOLDER = re.compile(
    r"(?i)^(?:[<{`$%\[(].*|.*<[^>]*>.*|your[_\-].*|"
    r"changeme|change[_\-]?me|x{3,}|\*{3,}|redacted|placeholder|example.*|dummy.*|test.*|fake.*|sample.*|"
    r"none|null|nil|true|false|undefined|todo|tbd|n/?a|\.\.\..*|replace[_\-]?me.*)$")

# Values that are code, not literals (identifiers, calls, property paths).
CODE_LIKE = re.compile(r"^(?:[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*(?:\(.*)?|[A-Za-z_]+\[.*)$")


def _secret_like(key: str, val: str, quoted: bool, sep: str = "=") -> bool:
    if PLACEHOLDER.match(val):
        return False
    k = key.lower().replace("-", "_")
    if k == "key" and sep != "=":
        return False  # bare "key:" is a map/JSX/property key; R5 targets "key=<value>" (relay payloads)
    if k.endswith("account_id") or k.endswith("accountid"):
        # account IDs (R5): a run of >= 6 digits, or an alphanumeric id with digits
        return bool(re.fullmatch(r"\d{6,}", val) or re.fullmatch(r"(?=.*\d)[A-Za-z0-9_\-]{8,}", val))
    if len(val) < 8:
        return False
    tokenish = len(val) >= 20 and re.fullmatch(r"[A-Za-z0-9_\-]+", val) is not None \
        and any(c.isdigit() for c in val) and any(c.isupper() for c in val) and any(c.islower() for c in val)
    if not quoted and CODE_LIKE.match(val) and not tokenish:
        return False  # an identifier / call / property path is code, e.g. apiKey = creds.apiKey
    has_digit = any(c.isdigit() for c in val)
    has_alpha = any(c.isalpha() for c in val)
    mixed_case = any(c.islower() for c in val) and any(c.isupper() for c in val)
    if re.fullmatch(r"[A-Za-z]+(?:[_\-.][A-Za-z]+)*\.?", val) and not (mixed_case and len(val) >= 16):
        return False  # words / constants (WOULD-BLOCK, enabled, ...)
    if re.fullmatch(r"\d{1,11}(?:\.\d+)?", val):
        return False  # ports, sizes, timeouts
    return (has_digit and has_alpha) or len(val) >= 16

SKIP_SUFFIXES = (".png", ".jpg", ".jpeg", ".gif", ".ico", ".pdf", ".jar", ".zip", ".gz", ".woff", ".woff2",
                 ".ttf", ".mp4", ".mov", ".lock", ".class")
SKIP_FILES = {"package-lock.json"}


def _redact(s: str) -> str:
    return s[:6] + "…" + f"({len(s)} chars)" if len(s) > 10 else "…"


# Where the allow marker is honoured: tests, docs, the hooks themselves and Markdown. In shipped code
# (pine/, trading-engine/, api-backend/, dashboard-frontend/, config/, tools/*.py) the marker is IGNORED,
# so it cannot be appended to a real secret to smuggle it past the hook (Fable A-04v hardening).
ALLOW_SCOPES = ("tools/tests/", "docs/", ".githooks/")


def _marker_allowed(path: str | None) -> bool:
    if path is None:
        return True  # bare line scan (unit tests)
    p = path.replace("\\", "/").lstrip("./")
    return p.endswith(".md") or any(p.startswith(s) or ("/" + s) in p for s in ALLOW_SCOPES)


def scan_line(line: str, path: str | None = None) -> list[tuple[str, str]]:
    """Return [(rule, redacted match)] for one line."""
    if ALLOW_MARKER in line and _marker_allowed(path):
        return []
    hits: list[tuple[str, str]] = []
    for name, rx in KEY_SHAPES + WEBHOOK_URLS:
        m = rx.search(line)
        if m:
            hits.append((name, _redact(m.group(0))))
    if hits:
        return hits
    for m in ASSIGNMENT.finditer(line):
        if _secret_like(m.group("key"), m.group("val"), bool(m.group("q")), m.group("sep")):
            hits.append(("assignment:" + m.group("key"), _redact(m.group("val"))))
    return hits


def scan_text(path: str, text: str, line_numbers: list[int] | None = None) -> list[str]:
    out = []
    lines = text.splitlines()
    for i, line in enumerate(lines):
        n = line_numbers[i] if line_numbers else i + 1
        for rule, red in scan_line(line, path):
            out.append(f"{path}:{n}: {rule}: {red}")
    return out


def _skip(path: str) -> bool:
    p = Path(path)
    return p.name in SKIP_FILES or p.suffix.lower() in SKIP_SUFFIXES


def staged_findings() -> list[str]:
    diff = subprocess.run(["git", "diff", "--cached", "-U0", "--no-color", "--diff-filter=ACMR"],
                          capture_output=True, text=True, check=True).stdout
    findings: list[str] = []
    path = None
    lineno = 0
    for raw in diff.splitlines():
        if raw.startswith("+++ "):
            path = raw[6:] if raw.startswith("+++ b/") else None
            continue
        if raw.startswith("@@"):
            m = re.search(r"\+(\d+)", raw)
            lineno = int(m.group(1)) if m else 0
            continue
        if path is None or _skip(path):
            continue
        if raw.startswith("+") and not raw.startswith("+++"):
            findings += scan_text(path, raw[1:], [lineno])
            lineno += 1
    return findings


def all_findings() -> list[str]:
    files = subprocess.run(["git", "ls-files", "-z"], capture_output=True, text=True, check=True).stdout.split("\0")
    findings: list[str] = []
    for f in files:
        if not f or _skip(f) or not Path(f).is_file():
            continue
        try:
            text = Path(f).read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        findings += scan_text(f, text)
    return findings


def main(argv: list[str]) -> int:
    if not argv or argv == ["--staged"]:
        findings = staged_findings()
    elif argv == ["--all"]:
        findings = all_findings()
    else:
        findings = []
        for f in argv:
            findings += scan_text(f, Path(f).read_text(encoding="utf-8", errors="replace"))
    if findings:
        print("secret-scan: possible secrets found (R5). Replace with placeholders such as <RELAY_SECRET> / "
              "<ACCOUNT_1>, or mark a documentation-only line with 'secret-scan: allow'.", file=sys.stderr)
        for f in findings:
            print("  " + f, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
