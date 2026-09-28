#!/usr/bin/env bash
# Fable Agent 06/07 verification driver (TRADE_FLOW_UNBLOCK_MASTER_PROMPT_V5).
# Usage: tools/fable_verify_pr.sh <branch> [autopsy-config A|B|C|D] [extra AUTOPSY_PROPS]
# Runs in a throwaway worktree: rebase onto origin/Main, build both modules,
# full tests, autopsy harness with the G1 transcript window, and prints the
# summary tables. Never touches the owner's running engine (port 8080).
set -u
BRANCH="${1:?branch}"; CFG="${2:-A}"; PROPS="${3:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WT="$ROOT/../wt-verify-${BRANCH//\//_}"
OUT="$HOME/topstep-trading/autopsy/verify_${BRANCH//\//_}_$CFG"
cd "$ROOT" && git fetch -q origin
[ -d "$WT" ] && git worktree remove --force "$WT" >/dev/null 2>&1
git worktree add -q "$WT" "origin/$BRANCH" || { echo "worktree failed"; exit 2; }
cd "$WT"
echo "== rebase $BRANCH onto origin/Main"
git rebase -q origin/Main || { echo "REBASE CONFLICT"; git status --short | head -20; exit 3; }
echo "== build"
./gradlew.bat :trading-engine:clean :trading-engine:build :api-backend:build -q > "$OUT.build.log" 2>&1; RC=$?
echo "build rc=$RC"; [ $RC -ne 0 ] && grep -B2 -A8 "FAILED\|error:" "$OUT.build.log" | head -40
python - <<'EOF'
import xml.etree.ElementTree as E,glob
t=f=e=s=0
for x in glob.glob("trading-engine/build/test-results/test/*.xml")+glob.glob("api-backend/build/test-results/test/*.xml"):
    r=E.parse(x).getroot(); t+=int(r.get("tests")); f+=int(r.get("failures")); e+=int(r.get("errors")); s+=int(r.get("skipped"))
    for tc in r.iter("testcase"):
        for ch in tc:
            if ch.tag in ("failure","error"): print("FAIL", tc.get("classname"), tc.get("name"), (ch.get("message") or "")[:200])
print(f"TESTS total={t} failures={f} errors={e} skipped={s}")
EOF
echo "== autopsy cfg $CFG props='$PROPS'"
mkdir -p "$OUT"
AUTOPSY_DIR="$WT/trading-engine/src/test/resources/tape" AUTOPSY_CONFIG="$CFG" AUTOPSY_PROPS="$PROPS" AUTOPSY_OUT="$OUT" \
AUTOPSY_TRANSCRIPT="2026-09-28T09:30,2026-09-28T15:45" \
./gradlew.bat :trading-engine:cleanTest :trading-engine:test --tests '*FunnelAutopsyHarness*' -q > "$OUT.autopsy.log" 2>&1
echo "autopsy rc=$? out=$OUT"
sed -n '/Per-session counts/,/## Top-3/p' "$OUT/summary.md" | grep "^|"
sed -n '/## Top-3/,/## Setup deaths/p' "$OUT/summary.md" | grep "^-"
grep "stateArrivals\|^## Risk-engine" "$OUT/summary.md"
sed -n '/## Signal \/ order/,/## FunnelTelemetry/p' "$OUT/summary.md" | grep "^-" | head -60
echo "== diff stat vs origin/Main"
git diff --stat origin/Main | tail -40
