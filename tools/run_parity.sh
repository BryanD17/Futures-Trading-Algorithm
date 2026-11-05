#!/usr/bin/env bash
# V6 Agent 06 — turnkey parity run (Java oracle vs TradingView List of Trades).
#
#   tools/run_parity.sh [--tv-tz America/Chicago] [--cap 5]
#
# What it does, in order:
#   1. Checks the inputs exist: parity/java_signals.csv (Agent 00 oracle, committed) and
#      parity/tv_trades.csv (owner export, OA-2). Without tv_trades.csv it prints the exact
#      OA-2 steps and exits 2 (⛔ OWNER-ACTION) — nothing else is touched.
#   2. If parity/tv_bars_mnq_1m.csv / parity/tv_bars_mes_1m.csv exist (OA-3) it runs the
#      06.1 data-drift check (tools/bars_drift.py); otherwise it records "OA-3 pending".
#   3. Runs tools/pine_parity.py with the Agent 06.4 hard fails ON (--require-stop, --cap).
#   4. Writes PARITY_REPORT.md at the repo root = run header + drift section(s) + tool
#      output + the pre-filled expected-classification table (docs/v6/PARITY_EXPECTED.md),
#      which Agent 06 then completes (root cause per mismatch: DATA / ENTRY_MODE /
#      PORT_APPROX / ENTRY-CAP / OPEN-POSITION / BUG).
# Exit codes: 0 = no hard fail; 1 = hard fail (see "Hard fails" in the report); 2 = OA-2 missing.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
TV_TZ="America/Chicago"; CAP=5
while [ $# -gt 0 ]; do
  case "$1" in
    --tv-tz) TV_TZ="$2"; shift 2 ;;
    --cap) CAP="$2"; shift 2 ;;
    *) echo "unknown arg $1"; exit 2 ;;
  esac
done
JAVA=parity/java_signals.csv
TV=parity/tv_trades.csv
REPORT=PARITY_REPORT.md
TOOL_OUT=parity/parity_tool_output.md
EXPECTED=docs/v6/PARITY_EXPECTED.md

sha() { shasum -a 256 "$1" 2>/dev/null | cut -c1-16 || sha256sum "$1" | cut -c1-16; }

[ -f "$JAVA" ] || { echo "MISSING $JAVA — regenerate: ./gradlew :trading-engine:test --tests '*GoldenSignalExport*'"; exit 2; }
if [ ! -f "$TV" ]; then
  cat <<EOF
⛔ OWNER-ACTION OA-2 — $TV is missing, so the parity run cannot start.
   Owner: TradingView → chart MNQ1! 5m with LucidICT_v6 → Strategy Tester → Custom date range
   2026-09-21 00:00 → 2026-09-28 17:00 (chart time zone) → "List of trades" tab → export CSV →
   save as parity/tv_trades.csv, then re-run: tools/run_parity.sh --tv-tz <your chart tz>
   Full click-by-click: TRADINGVIEW_MIGRATION_MASTER_PROMPT_V6.txt Appendix D, OA-2.
   The pre-filled report (expected classifications) is already at $REPORT.
EOF
  exit 2
fi

echo "== inputs"
echo "java_signals.csv  sha256[:16] $(sha "$JAVA")  rows $(( $(wc -l < "$JAVA") - 1 ))"
echo "tv_trades.csv     sha256[:16] $(sha "$TV")  rows $(( $(wc -l < "$TV") - 1 ))  (chart tz $TV_TZ)"
DIST_ID="$(sed -n '2p' pine/dist/LucidICT_v6.pine | sed -E 's/.*src-sha256 ([0-9a-f]+).*/\1/')"

DRIFT=""
for sym in mnq mes; do
  tape="parity/bars_${sym}_1m.csv"; tvb="parity/tv_bars_${sym}_1m.csv"; out="parity/drift_${sym}.md"
  if [ -f "$tvb" ]; then
    echo "== drift $sym"
    python3 tools/bars_drift.py "$tape" "$tvb" --tv-tz "$TV_TZ" --label "$(echo "$sym" | tr a-z A-Z)" --out "$out" >/dev/null
    DRIFT="$DRIFT$(cat "$out")"$'\n'
  else
    DRIFT="$DRIFT## Data drift — $(echo "$sym" | tr a-z A-Z) 1m (Agent 06.1)"$'\n\n'"- ⛔ OA-3 pending: $tvb not present. Export per Appendix D OA-3, then re-run."$'\n\n'
  fi
done

echo "== pine_parity"
python3 tools/pine_parity.py "$JAVA" "$TV" --tv-tz "$TV_TZ" --cap "$CAP" --require-stop --out "$TOOL_OUT"
RC=$?

{
  echo "# PARITY_REPORT.md — V6 Agent 06 (Java oracle vs TradingView)"
  echo
  echo "STATUS: $([ $RC -eq 0 ] && echo 'RUN COMPLETE — no hard fail; Agent 06 root-causes every mismatch below' || echo 'RUN COMPLETE — HARD FAIL (see §Hard fails); the owning Opus agent gets a failing case')"
  echo
  echo "- Run: $(date -u +%Y-%m-%dT%H:%M:%SZ) UTC via tools/run_parity.sh · dist BUILD_ID (pine/dist line 2) \`${DIST_ID}\`"
  echo "- Inputs: \`$JAVA\` sha256[:16] $(sha "$JAVA") · \`$TV\` sha256[:16] $(sha "$TV") · chart tz $TV_TZ · cap $CAP micros · stop required"
  echo "- Times: ET (America/New_York); CT = ET − 1 h."
  echo
  cat "$TOOL_OUT" | sed '1d'
  echo
  printf '%s' "$DRIFT"
  echo
  cat "$EXPECTED"
} > "$REPORT"
echo "report -> $REPORT (rc=$RC)"
exit $RC
