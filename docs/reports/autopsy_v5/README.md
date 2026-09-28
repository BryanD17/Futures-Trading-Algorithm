# Autopsy V5 evidence (Agent 00)

- `summary_A..E.md` — FunnelAutopsyHarness output per configuration (see DIAGNOSIS_V5.md §0 for the flag sets).
- `G1_transcript_cfgA.txt` / `G1_transcript_cfgB.txt` — bar-by-bar state machine 2026-09-28 13:30–15:45 ET.
- Tape: `trading-engine/src/test/resources/tape/real_MNQ_1m.json` + `real_MES_1m.json` (7,830 real 1m bars, 2026-09-21 → 2026-09-28).
- Re-run: `AUTOPSY_DIR=trading-engine/src/test/resources/tape AUTOPSY_CONFIG=A AUTOPSY_OUT=/abs/path ./gradlew :trading-engine:cleanTest :trading-engine:test --tests '*FunnelAutopsyHarness*'`
  (AUTOPSY_DIR is resolved relative to the module dir when relative; prefer absolute paths.)
- Refresh a longer tape from TopstepX once the API key works: `python tools/fetch_topstep_tape.py 21 <outdir>`.
