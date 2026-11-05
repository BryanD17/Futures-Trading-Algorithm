#!/usr/bin/env python3
"""Validate relay payloads emitted by LucidICT_v6 (V6 Agent 04, task 04.5).

    python3 tools/payload_check.py samples.txt [--relay CROSSTRADE] [--cap 5] [--build-id <hex16>] [--live]
    python3 tools/payload_check.py --sample            # print the reference payloads (placeholders only)

Input = text the owner copied from the TradingView Alert log (Message column) or from Pine Logs
(input "log masked payloads"). Any number of payloads; surrounding text is ignored:
  * CrossTrade: `key=...;` fields (newlines optional) — a payload starts at each `key=` field;
  * PickMyTrade / TradersPost: one JSON object each.

Checks field-by-field against docs/PLATFORM_FACTS.md (line numbers cited as PF:<n>):
  CrossTrade  required foundation key/command/account/instrument/action/qty/order_type (PF:139-140),
              destination=tradovate (PF:140-141), supported command (PF:149-152), no field rejected on
              Tradovate (PF:153-154), no cycle_accounts (PF:252-253), instrument on the MNQ root (I1),
              BUILD_ID in strategy_tag (D8, PF:154).
    ENTRY     stop_loss AND take_profit present, absolute prices (PF:192-198), on the correct sides,
              qty 1..cap (I4), sync_strategy=true, out_of_sync=flatten, strategy_exit_block=true,
              prev_market_position=flat, market_position matches action, target_quantity=qty (PF:215-233).
    BRACKET   (TV-side exit fill) MUST be rejected by the relay twice over: strategy_exit_block=true with
              prev_market_position != flat (PF:217-218) AND no action/qty/order_type/tif (a PLACE without its
              required foundation PF:139-140 cannot build an order), no take_profit/stop_loss/flatten_first.
    FLATTEN / SOFT_STOP  command=FLATTEN.
  PickMyTrade / TradersPost: entry carries sl+tp / stopLoss+takeProfit (I2) and quantity <= cap; the
  bracket-fill message is non-actionable (no token+data / no ticker).
The secret is never printed (redacted). Exit 0 = all PASS, 1 = any FAIL, 2 = usage / nothing parsed.
Python 3.9+ standard library only.
"""
import argparse
import json
import re
import sys

KINDS = ("ENTRY", "BRACKET", "FLATTEN", "SOFT_STOP")
CT_REQUIRED_PLACE = ("key", "command", "account", "instrument", "action", "qty", "order_type")  # PF:139-140
CT_COMMANDS = {"place", "flatplace", "closeposition", "flatten", "flatteneverything", "reverse", "reverseposition",
               "cancel", "cancelorders", "cancelallorders", "change", "cancelreplace", "cancelandbracket"}  # PF:149-150
CT_REJECTED = ("strategy_id", "append_atm", "oco_id", "stop_loss_stop", "stop_loss_limit", "custom_tag")  # PF:153-154
PLACEHOLDER_KEYS = ("<RELAY_SECRET>", "<DRY_RUN>")
TAG_RE = re.compile(r"^LucidICT_v6-([0-9a-f]{16}|[A-Za-z0-9]+)-(ENTRY|BRACKET|FLATTEN|SOFT_STOP)$")
PRICE_RE = re.compile(r"^\d+(?:\.\d+)?$")


# ----------------------------------------------------------------------------- reference builders
# Python mirror of ctPayload / pmtPayload / tpPayload in pine/src/75_payload.pine (field ORDER and
# names; tools/tests/test_payload_check.py pins the CrossTrade field set to the Pine source).
def ct_fields(kind, direction, qty, stop=None, tp=None, sec="<RELAY_SECRET>", accounts="<ACCOUNT_1>",
              instrument="MNQ1!", build_id="<BUILD_ID>"):
    side = "BUY" if direction > 0 else "SELL"
    pos = "long" if direction > 0 else "short"
    head = [("key", sec)]
    acct = [("account", accounts), ("instrument", instrument)]
    tail = [("strategy_tag", "LucidICT_v6-{}-{}".format(build_id, kind)), ("destination", "tradovate")]
    if kind == "ENTRY":
        return head + [("command", "PLACE")] + acct + [
            ("action", side), ("qty", str(qty)), ("order_type", "MARKET"), ("tif", "DAY"),
            ("take_profit", "{:.2f}".format(tp)), ("stop_loss", "{:.2f}".format(stop)), ("flatten_first", "true"),
            ("sync_strategy", "true"), ("market_position", pos), ("prev_market_position", "flat"),
            ("target_quantity", str(qty)), ("out_of_sync", "flatten"), ("strategy_exit_block", "true")] + tail
    if kind == "BRACKET":
        # no action/qty/order_type/tif on purpose: see 75_payload.pine BRACKET comment (Fable A-04v)
        return head + [("command", "PLACE")] + acct + [
            ("sync_strategy", "true"), ("market_position", "flat"), ("prev_market_position", pos),
            ("target_quantity", "0"), ("out_of_sync", "flatten"), ("strategy_exit_block", "true")] + tail
    return head + [("command", "FLATTEN")] + acct + tail


def ct_text(fields):
    return "".join("{}={};\n".format(k, v) for k, v in fields)


# ----------------------------------------------------------------------------- parsing
def parse_crosstrade(text):
    """-> list of ordered field lists. A payload starts at each `key=` field (header text before it is dropped)."""
    payloads, cur = [], None
    for tok in text.split(";"):
        tok = tok.strip()
        if not tok:
            continue
        m = re.search(r"(?:^|\s)(key\s*=.*)$", tok, re.S)
        if m:  # a new payload; text before `key=` (log header, previous junk) is dropped
            if cur:
                payloads.append(cur)
            cur = []
            tok = m.group(1).strip()
        if cur is None:
            continue
        if "=" not in tok:
            cur.append(("__junk__", tok))
            continue
        k, v = tok.split("=", 1)
        cur.append((k.strip(), v.strip()))
    if cur:
        payloads.append(cur)
    return payloads


def parse_json_objects(text):
    out, depth, start, quote, esc = [], 0, None, False, False
    for i, ch in enumerate(text):
        if quote:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                quote = False
            continue
        if ch == '"':
            quote = True
        elif ch == "{":
            if depth == 0:
                start = i
            depth += 1
        elif ch == "}" and depth:
            depth -= 1
            if depth == 0:
                try:
                    out.append(json.loads(text[start:i + 1]))
                except ValueError:
                    out.append({"__invalid__": text[start:i + 1][:60]})
    return out


def redact(v):
    if v in PLACEHOLDER_KEYS or not v:
        return v or "<empty>"
    return v[:2] + "…({} chars)".format(len(v))


# ----------------------------------------------------------------------------- validation
class Result:
    def __init__(self, label):
        self.label, self.errors, self.notes = label, [], []

    @property
    def ok(self):
        return not self.errors


def _qty(v, cap, res, name="qty"):
    try:
        q = int(str(v))
    except ValueError:
        res.errors.append("{}={!r} is not an integer".format(name, v))
        return None
    if q < 1:
        res.errors.append("{}={} < 1".format(name, q))
    if cap is not None and q > cap:
        res.errors.append("{}={} exceeds cap {} (I4)".format(name, q, cap))
    return q


def check_crosstrade(fields, cap=None, build_id=None, live=False):
    d = {}
    res = Result("crosstrade")
    for k, v in fields:
        if k == "__junk__":
            res.errors.append("field without '=': {!r}".format(v[:30]))
            continue
        if k != k.strip() or " " in k:
            res.errors.append("whitespace in field name {!r}".format(k))
        if k in d:
            res.errors.append("duplicate field " + k)
        d[k] = v
    tag = d.get("strategy_tag", "")
    m = TAG_RE.match(tag)
    kind = m.group(2) if m else ("FLATTEN" if d.get("command", "").lower() == "flatten" else "?")
    res.label = "crosstrade " + kind
    if not m:
        res.errors.append("strategy_tag {!r} is not LucidICT_v6-<BUILD_ID>-<KIND> (D8)".format(tag))
    elif build_id and m.group(1) != build_id:
        res.errors.append("BUILD_ID {} != expected {} (stale alert: delete + re-create, PF:63-73)".format(m.group(1), build_id))
    sec = d.get("key", "")
    if not sec:
        res.errors.append("key missing (PF:142)")
    elif re.search(r"\s", sec):
        res.errors.append("whitespace in key (PF:142-143)")
    elif sec in PLACEHOLDER_KEYS:
        (res.errors if live else res.notes).append("DRY RUN key {} — relay rejects it (PF:142-143)".format(sec))
    if d.get("destination", "").lower() != "tradovate":
        res.errors.append("destination must be tradovate (PF:140-141), got {!r}".format(d.get("destination")))
    cmd = d.get("command", "").lower()
    if cmd not in CT_COMMANDS:
        res.errors.append("command {!r} not supported on Tradovate (PF:149-152)".format(d.get("command")))
    for bad in CT_REJECTED:
        if bad in d:
            res.errors.append("field {} is rejected on Tradovate (PF:153-154)".format(bad))
    if "cycle_accounts" in d:
        res.errors.append("cycle_accounts breaks same-side-same-time fan-out (PF:252-253, I1)")
    accs = [a for a in d.get("account", "").split(",")]
    if not d.get("account") or any(not a.strip() for a in accs):
        res.errors.append("account list empty or has an empty name (PF:144)")
    elif len(set(accs)) != len(accs):
        res.errors.append("duplicate account in fan-out list (PF:241)")
    if any(a.startswith("<ACCOUNT_") for a in accs):
        (res.errors if live else res.notes).append("placeholder account names")
    instr = d.get("instrument", "")
    if not re.match(r"^MNQ(1!|[FGHJKMNQUVXZ]\d{1,4}| \d\d-\d\d)$", instr):
        res.errors.append("instrument {!r} is not on the MNQ root (I1: one traded root; forms PF:145-147)".format(instr))
    if kind == "ENTRY":
        for f in CT_REQUIRED_PLACE:
            if f not in d:
                res.errors.append("missing required field " + f + " (PF:139-140)")
        if cmd != "place":
            res.errors.append("ENTRY command must be PLACE")
        q = _qty(d.get("qty", ""), cap, res)
        side = d.get("action", "").upper()
        if side not in ("BUY", "SELL"):
            res.errors.append("action {!r} not BUY/SELL".format(d.get("action")))
        sl, tp = d.get("stop_loss"), d.get("take_profit")
        if not sl:
            res.errors.append("stop_loss missing — entry without a broker stop (I2)")
        if not tp:
            res.errors.append("take_profit missing — OSO needs both legs (PF:185-187)")
        if sl and tp:
            if not (PRICE_RE.match(sl) and PRICE_RE.match(tp)):
                res.errors.append("stop_loss/take_profit must be ABSOLUTE prices (PF:192-198), got {}/{}".format(sl, tp))
            else:
                s, t = float(sl), float(tp)
                if side == "BUY" and not s < t:
                    res.errors.append("long bracket: stop {} must be below target {}".format(s, t))
                if side == "SELL" and not s > t:
                    res.errors.append("short bracket: stop {} must be above target {}".format(s, t))
        if any(k.startswith("atm_") for k in d):
            res.errors.append("atm_* cannot be combined with take_profit/stop_loss (PF:206)")
        want = {"sync_strategy": "true", "out_of_sync": "flatten", "strategy_exit_block": "true",
                "prev_market_position": "flat", "order_type": "MARKET"}
        for k, v in want.items():
            if d.get(k, "").lower() != v.lower():
                res.errors.append("{}={!r}, expected {} (PF:215-233)".format(k, d.get(k), v))
        mp = d.get("market_position", "")
        if (side == "BUY" and mp != "long") or (side == "SELL" and mp != "short"):
            res.errors.append("market_position {!r} does not match action {}".format(mp, side))
        if q is not None and d.get("target_quantity") != str(q):
            res.errors.append("target_quantity {!r} != qty {}".format(d.get("target_quantity"), q))
    elif kind == "BRACKET":
        if d.get("strategy_exit_block", "").lower() != "true" or d.get("prev_market_position", "flat") == "flat":
            res.errors.append("bracket-fill payload must be rejected by strategy_exit_block (prev_market_position != flat, PF:217-218)")
        if d.get("sync_strategy", "").lower() != "true":
            res.errors.append("bracket-fill payload must carry sync_strategy=true (PF:215)")
        for f in ("take_profit", "stop_loss", "flatten_first"):
            if f in d:
                res.errors.append("bracket-fill payload must not carry " + f)
        for f in ("action", "qty", "order_type", "tif"):
            if f in d:
                res.errors.append("bracket-fill payload must not carry {} (must stay non-actionable even if the "
                                  "exit block were ignored: a market close would orphan the GTC OSO legs)".format(f))
        res.notes.append("expected relay outcome: REJECTED (strategy_exit_block + missing PLACE foundation); broker OSO owns the exit")
    elif kind in ("FLATTEN", "SOFT_STOP"):
        if cmd != "flatten":
            res.errors.append("{} must use command=FLATTEN (PF:149-152)".format(kind))
        for f in ("sync_strategy", "strategy_exit_block"):
            if f in d:
                res.errors.append("flatten must not be gated by " + f)
    res.notes.append("key=" + redact(sec))
    return res


def check_json(obj, relay, cap=None):
    res = Result(relay.lower())
    if "__invalid__" in obj:
        res.errors.append("invalid JSON: " + obj["__invalid__"])
        return res
    if relay == "PICKMYTRADE":
        act = obj.get("data")
        if act in ("buy", "sell"):
            res.label = "pickmytrade ENTRY"
            _qty(obj.get("quantity", ""), cap, res, "quantity")
            if obj.get("sl") in (None, "") or obj.get("tp") in (None, ""):
                res.errors.append("entry needs sl AND tp (I2, PF:335)")
            if "price" in obj:
                res.errors.append("price present = limit order (PF:332); V6 entries are market")
            if not obj.get("token"):
                res.errors.append("token missing (PF:333)")
        elif act == "close":
            res.label = "pickmytrade FLATTEN"
        elif act is None:
            res.label = "pickmytrade BRACKET"
            if obj.get("token"):
                res.errors.append("bracket-fill message must be non-actionable (no token)")
        else:
            res.errors.append("data {!r} not buy/sell/close (PF:332)".format(act))
        for a in obj.get("multiple_accounts", []):
            if a.get("quantity_multiplier", 1) != 1:
                res.notes.append("quantity_multiplier != 1 — check I4 per account")
        res.notes.append("token=" + redact(obj.get("token", "")))
    else:
        if "ticker" not in obj:
            res.label = "traderspost BRACKET"
            res.notes.append("non-actionable (ticker REQUIRED, PF:350) — expected for a bracket fill")
            if obj.get("action"):
                res.errors.append("bracket-fill message must carry no action")
            return res
        if "!" in str(obj["ticker"]):
            res.errors.append("TradersPost needs a dated contract, not {} (PF:356)".format(obj["ticker"]))
        act = obj.get("action")
        if act in ("buy", "sell"):
            res.label = "traderspost ENTRY"
            _qty(obj.get("quantity", ""), cap, res, "quantity")
            if not (obj.get("stopLoss") or {}).get("stopPrice") or not (obj.get("takeProfit") or {}).get("limitPrice"):
                res.errors.append("entry needs stopLoss.stopPrice AND takeProfit.limitPrice (I2, PF:353)")
        elif act == "exit":
            res.label = "traderspost FLATTEN"
        else:
            res.errors.append("action {!r} unexpected (PF:350)".format(act))
    return res


def check_text(text, relay="CROSSTRADE", cap=None, build_id=None, live=False):
    if relay == "CROSSTRADE":
        return [check_crosstrade(f, cap, build_id, live) for f in parse_crosstrade(text)]
    return [check_json(o, relay, cap) for o in parse_json_objects(text)]


def samples(build_id="<BUILD_ID>"):
    # G1 (2026-09-28 14:53 ET SELL, stop 30674.00, T1 30558.00, 3 micros) — parity/java_signals.csv
    return [("ENTRY (G1 SELL 3)", ct_text(ct_fields("ENTRY", -1, 3, 30674.00, 30558.00, build_id=build_id))),
            ("BRACKET fill (TV saw SL/T1)", ct_text(ct_fields("BRACKET", -1, 3, build_id=build_id))),
            ("FLATTEN 16:40 ET", ct_text(ct_fields("FLATTEN", -1, 3, build_id=build_id))),
            ("SOFT_STOP", ct_text(ct_fields("SOFT_STOP", -1, 3, build_id=build_id)))]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("file", nargs="?")
    ap.add_argument("--relay", default="CROSSTRADE", choices=("CROSSTRADE", "PICKMYTRADE", "TRADERSPOST"))
    ap.add_argument("--cap", type=int, default=5, help="max contracts per order (I4; default = max micros per trade 5)")
    ap.add_argument("--build-id", help="expected BUILD_ID (dist line 2 src-sha256)")
    ap.add_argument("--live", action="store_true", help="placeholder / DRY RUN keys and accounts are errors")
    ap.add_argument("--sample", action="store_true", help="print the reference CrossTrade payloads")
    a = ap.parse_args(argv)
    if a.sample:
        for title, txt in samples(a.build_id or "<BUILD_ID>"):
            print("# " + title + "\n" + txt)
        return 0
    if not a.file:
        ap.print_usage(sys.stderr)
        return 2
    with open(a.file, encoding="utf-8") as fh:
        text = fh.read()
    results = check_text(text, a.relay, a.cap, a.build_id, a.live)
    if not results:
        print("payload_check: no payload found", file=sys.stderr)
        return 2
    for i, r in enumerate(results, 1):
        print("[{}] #{} {}".format("PASS" if r.ok else "FAIL", i, r.label))
        for e in r.errors:
            print("    ERROR " + e)
        for n in r.notes:
            print("    note  " + n)
    ok = all(r.ok for r in results)
    print("payload_check: {} ({}/{} payloads pass)".format("OK" if ok else "FAILED", sum(r.ok for r in results), len(results)))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
