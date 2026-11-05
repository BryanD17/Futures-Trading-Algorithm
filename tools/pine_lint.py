#!/usr/bin/env python3
"""Static safety checks for the built Pine strategy (V6 Agent 02, task 02.4). Fails CI on violation.

    python3 tools/pine_lint.py pine/dist/LucidICT_v6.pine

Regex / AST-light: comments and string literals are understood, call arguments are matched by
balanced parentheses across lines, and block structure is read from Pine's indentation.

Rules (each prints PASS/FAIL with evidence):
  L1 single-strategy   exactly one strategy( declaration; no indicator(/library(            (I1)
  L2 version           first line is //@version=6
  L3 lookahead         every request.security( has lookahead = barmerge.lookahead_off, OR the
                       documented non-repainting pair lookahead_on + a [1] offset in the expression
                       (PINE_CONSTRAINTS C-03 / C-17); lookahead_on without [1] always fails  (I8/AP-2)
  L4 entries-in-80     no strategy.entry / strategy.order outside 80_strategy.pine
  L5 no-foreign-orders no order call (entry/order/exit/close/close_all/cancel) references a
                       foreign request.* symbol, a variable assigned from such a request, or any
                       request.* call                                                           (I1)
  L6 confirmed-entries every strategy.entry sits inside an `if ... barstate.isconfirmed ...` block whose
                       condition is not negated and not diluted by `or`                          (I8)
  L7 entry-has-stop    every strategy.entry id has a strategy.exit(from_entry = <id>, stop = ...)
                       (an exit without from_entry covers all entries)                         (I2)
  L8 no-secrets        no URLs, webhook endpoints, key/secret/password literals or long tokens  (R5/AP-9)
  L9 no-tick-entries   strategy() does not set calc_on_every_tick = true; pyramiding is 0      (AP-3/I5/AP-10)

Rules L4-L7 apply to every entry PRESENT: a dist with zero entries (Agent 03 not merged yet) passes
them vacuously and says so. Python 3.9+ standard library only.
"""
import re
import sys

FILE_MARKER_RE = re.compile(r"^// ==== file: (\S+) ====$")
ORDER_FUNCS = ("strategy.entry", "strategy.order", "strategy.exit", "strategy.close_all",
               "strategy.close", "strategy.cancel_all", "strategy.cancel")
OWN_SYMBOLS = {"syminfo.tickerid", '""', "''", "syminfo.ticker"}


# ----------------------------------------------------------------------------- lexing helpers
def strip_comments(text):
    """Remove // comments (outside string literals), keep line structure and string contents."""
    out = []
    i, n = 0, len(text)
    quote = None
    while i < n:
        ch = text[i]
        if quote:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if ch == quote or ch == "\n":
                quote = None
            i += 1
            continue
        if ch in ("'", '"'):
            quote = ch
            out.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def mask_strings(text):
    """Replace string-literal contents with spaces (same length) so regexes never match inside them."""
    out = []
    quote = None
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if quote:
            if ch == "\\" and i + 1 < n:
                out.append("  ")
                i += 2
                continue
            if ch == quote or ch == "\n":
                quote = None
                out.append(ch)
            else:
                out.append(" ")
            i += 1
            continue
        if ch in ("'", '"'):
            quote = ch
        out.append(ch)
        i += 1
    return "".join(out)


def line_of(text, pos):
    return text.count("\n", 0, pos) + 1


def find_calls(code, masked, name):
    """All calls `name(` -> list of (line_no, args_text). `masked` has strings blanked (same offsets)."""
    pat = re.compile(r"(?<![\w.])" + re.escape(name) + r"\s*\(")
    calls = []
    for m in pat.finditer(masked):
        start = m.end()
        depth = 1
        j = start
        while j < len(masked) and depth > 0:
            c = masked[j]
            if c in "([":
                depth += 1
            elif c in ")]":
                depth -= 1
            j += 1
        calls.append((line_of(masked, m.start()), code[start:j - 1], m.start()))
    return calls


def split_args(args):
    """Split a call's argument text on top-level commas."""
    parts, depth, cur = [], 0, []
    quote = None
    for ch in args:
        if quote:
            cur.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch in ("'", '"'):
            quote = ch
        elif ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
            continue
        cur.append(ch)
    if "".join(cur).strip():
        parts.append("".join(cur).strip())
    return parts


def named_arg(parts, key):
    for p in parts:
        m = re.match(r"^" + re.escape(key) + r"\s*=(?!=)\s*(.+)$", p, re.S)
        if m:
            return m.group(1).strip()
    return None


def positional(parts, idx):
    pos = [p for p in parts if not re.match(r"^[A-Za-z_]\w*\s*=(?!=)", p)]
    return pos[idx].strip() if idx < len(pos) else None


# ----------------------------------------------------------------------------- the linter
class Lint:
    def __init__(self, text):
        self.text = text.replace("\r\n", "\n")
        self.lines = self.text.split("\n")
        self.code = strip_comments(self.text)
        self.masked = mask_strings(self.code)
        self.code_lines = self.code.split("\n")
        self.file_of_line = {}
        cur = None
        for i, ln in enumerate(self.lines, start=1):
            m = FILE_MARKER_RE.match(ln.strip())
            if m:
                cur = m.group(1)
            self.file_of_line[i] = cur
        self.results = []

    def add(self, rule, ok, detail):
        self.results.append((rule, ok, detail))

    def calls(self, name):
        return find_calls(self.code, self.masked, name)

    # L1
    def single_strategy(self):
        decl = self.calls("strategy")
        other = self.calls("indicator") + self.calls("library")
        ok = len(decl) == 1 and not other
        self.add("L1 single-strategy", ok,
                 "{} strategy() declaration(s), {} indicator()/library()".format(len(decl), len(other)))

    # L2
    def version(self):
        first = next((ln.strip() for ln in self.lines if ln.strip()), "")
        self.add("L2 version", first == "//@version=6", "first line: " + first[:40])

    # L3
    def lookahead(self):
        bad = []
        calls = self.calls("request.security")
        for line, args, _ in calls:
            parts = split_args(args)
            la = named_arg(parts, "lookahead")
            expr = positional(parts, 2) or named_arg(parts, "expression") or ""
            if la is not None and re.fullmatch(r"barmerge\.lookahead_off", la):
                continue
            if la is not None and re.fullmatch(r"barmerge\.lookahead_on", la) and "[1]" in expr:
                continue
            bad.append("line {}: lookahead={}".format(line, la))
        # lookahead_on anywhere else (e.g. other request.* functions) without [1]
        for line, args, _ in self.calls("request.security_lower_tf"):
            if "lookahead" in args:
                bad.append("line {}: request.security_lower_tf has no lookahead parameter".format(line))
        n = len(calls)
        self.add("L3 lookahead", not bad,
                 ("; ".join(bad)) if bad else "{} request.security call(s), all lookahead_off (or on+[1])".format(n))

    def entries(self):
        return self.calls("strategy.entry")

    # L4
    def entries_in_80(self):
        bad = []
        n = 0
        for fn in ("strategy.entry", "strategy.order"):
            for line, _, _ in self.calls(fn):
                n += 1
                src = self.file_of_line.get(line)
                if src != "80_strategy.pine":
                    bad.append("{} at line {} (source {})".format(fn, line, src))
        detail = "; ".join(bad) if bad else (
            "{} entry/order call(s), all in 80_strategy.pine".format(n) if n else
            "0 strategy.entry/order calls (Agent 03 not merged yet) — vacuous PASS")
        self.add("L4 entries-in-80", not bad, detail)

    # L5
    def no_foreign_orders(self):
        foreign_names = set()
        foreign_syms = []
        for line, args, pos in self.calls_any_request():
            parts = split_args(args)
            sym = positional(parts, 0) or named_arg(parts, "symbol") or ""
            if sym.strip() in OWN_SYMBOLS:
                continue
            foreign_syms.append((line, sym.strip()))
            for tok in re.findall(r"[A-Za-z_][\w.]*", sym):
                if tok not in ("syminfo.tickerid", "ticker.new", "ticker.modify"):
                    foreign_names.add(tok)
            # LHS of the assignment on the call's line(s)
            line_start = self.masked.rfind("\n", 0, pos) + 1
            lhs = self.masked[line_start:pos]
            m = re.match(r"^\s*(?:var\s+)?(?:[\w<>\[\]]+\s+)?(\[[^\]]*\]|[A-Za-z_]\w*)\s*:?=\s*$", lhs)
            if m:
                for tok in re.findall(r"[A-Za-z_]\w*", m.group(1)):
                    foreign_names.add(tok)
        bad = []
        norders = 0
        for fn in ORDER_FUNCS:
            for line, args, _ in self.calls(fn):
                norders += 1
                masked_args = mask_strings(args)
                if "request." in masked_args:
                    bad.append("{} at line {} calls request.*".format(fn, line))
                for name in foreign_names:
                    if re.search(r"(?<![\w.])" + re.escape(name) + r"(?![\w])", masked_args):
                        bad.append("{} at line {} references foreign-symbol data '{}'".format(fn, line, name))
        detail = "; ".join(bad) if bad else "{} order call(s); foreign request symbols {} -> data names {} never used in orders".format(
            norders, [s for _, s in foreign_syms] or "none", sorted(foreign_names) or "none")
        self.add("L5 no-foreign-orders", not bad, detail)

    def calls_any_request(self):
        out = []
        pat = re.compile(r"(?<![\w.])(request\.\w+)\s*\(")
        for m in pat.finditer(self.masked):
            out.extend(c for c in find_calls(self.code, self.masked, m.group(1)) if c[2] == m.start())
        return out

    # block ancestry from indentation
    def ancestors(self, line_no):
        res = []
        cur_indent = None
        for i in range(line_no, 0, -1):
            raw = self.code_lines[i - 1]
            if not raw.strip():
                continue
            ind = len(raw) - len(raw.lstrip(" "))
            if cur_indent is None:
                cur_indent = ind
                continue
            if ind < cur_indent:
                res.append(raw.strip())
                cur_indent = ind
                if ind == 0:
                    break
        return res

    # L6
    def confirmed_entries(self):
        bad = []
        ents = self.entries()
        for line, _, _ in ents:
            anc = self.ancestors(line)
            # the guard must be an `if` whose condition contains barstate.isconfirmed, not negated and not
            # diluted by `or` (if a or barstate.isconfirmed can fire intrabar)
            ok = any(re.match(r"^(else\s+)?if\b", a) and re.search(r"(?<!not )barstate\.isconfirmed", a)
                     and not re.search(r"not\s+barstate\.isconfirmed", a)
                     and not re.search(r"(?<![\w.])or(?![\w.])", a) for a in anc)
            if not ok:
                bad.append("strategy.entry at line {} is not inside an `if barstate.isconfirmed` block (and-only guard)".format(line))
        detail = "; ".join(bad) if bad else ("{} strategy.entry call(s), all guarded".format(len(ents)) if ents
                                              else "0 strategy.entry calls — vacuous PASS")
        self.add("L6 confirmed-entries", not bad, detail)

    # L7
    def entry_has_stop(self):
        exits = []
        for line, args, _ in self.calls("strategy.exit"):
            parts = split_args(args)
            stop = named_arg(parts, "stop")
            has_stop = stop is not None and stop.strip() != "na"
            exits.append((named_arg(parts, "from_entry"), has_stop, line))
        bad = []
        ents = self.entries()
        for line, args, _ in ents:
            parts = split_args(args)
            eid = named_arg(parts, "id") or positional(parts, 0)
            ok = any(has and (fe is None or fe.strip() == (eid or "").strip()) for fe, has, _ in exits)
            if not ok:
                bad.append("strategy.entry({}) at line {} has no strategy.exit(from_entry = {}, stop = ...)".format(eid, line, eid))
        detail = "; ".join(bad) if bad else ("{} entry id(s) paired with a stop exit".format(len(ents)) if ents
                                              else "0 strategy.entry calls — vacuous PASS")
        self.add("L7 entry-has-stop", not bad, detail)

    # L8
    def no_secrets(self):
        bad = []
        pats = [
            (re.compile(r"https?://", re.I), "URL"),
            (re.compile(r"webhook\.[a-z]|hooks\.[a-z]", re.I), "webhook host"),
            (re.compile(r"""(?i)\b(key|secret|password|passwd|token|api_?key)\s*[=:]\s*["']?(?!<)[A-Za-z0-9_\-+/]{6,}"""), "credential assignment"),
            (re.compile(r"""["'][A-Za-z0-9_\-+/=]{32,}["']"""), "long token literal"),
        ]
        for i, ln in enumerate(self.lines, start=1):
            for rx, what in pats:
                if rx.search(ln):
                    bad.append("line {}: {}".format(i, what))
        # secret-like inputs must default to empty or a <PLACEHOLDER>
        for line, args, _ in self.calls("input.string"):
            parts = split_args(args)
            title = (positional(parts, 1) or named_arg(parts, "title") or "").lower()
            defval = (positional(parts, 0) or named_arg(parts, "defval") or "").strip()
            if re.search(r"secret|key|password|token|account", title):
                if defval not in ('""', "''") and not re.fullmatch(r"""["']<[A-Z0-9_]+>["']""", defval):
                    bad.append("line {}: secret-like input {} defaults to {}".format(line, title, defval))
        self.add("L8 no-secrets", not bad, "; ".join(bad) if bad else "no URLs / keys / tokens / secret defaults")

    # L9
    def no_tick_entries(self):
        decl = self.calls("strategy")
        bad = []
        if decl:
            parts = split_args(decl[0][1])
            cet = named_arg(parts, "calc_on_every_tick")
            if cet is not None and cet.strip() == "true":
                bad.append("calc_on_every_tick = true")
            pyr = named_arg(parts, "pyramiding")
            if pyr is not None and pyr.strip() != "0":
                bad.append("pyramiding = " + pyr.strip())
        self.add("L9 no-tick-entries", not bad, "; ".join(bad) if bad else "calc_on_every_tick off, pyramiding 0")

    def run(self):
        self.single_strategy()
        self.version()
        self.lookahead()
        self.entries_in_80()
        self.no_foreign_orders()
        self.confirmed_entries()
        self.entry_has_stop()
        self.no_secrets()
        self.no_tick_entries()
        return all(ok for _, ok, _ in self.results)


def lint_text(text):
    lt = Lint(text)
    ok = lt.run()
    return ok, lt.results


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if not argv:
        print("usage: python3 tools/pine_lint.py <file.pine> [...]", file=sys.stderr)
        return 2
    all_ok = True
    for path in argv:
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
        ok, results = lint_text(text)
        print("pine_lint: " + path)
        for rule, rok, detail in results:
            print("  [{}] {}: {}".format("PASS" if rok else "FAIL", rule, detail))
        print("pine_lint: {} ({}/{} rules pass)".format("OK" if ok else "FAILED",
                                                       sum(1 for _, r, _ in results if r), len(results)))
        all_ok = all_ok and ok
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
