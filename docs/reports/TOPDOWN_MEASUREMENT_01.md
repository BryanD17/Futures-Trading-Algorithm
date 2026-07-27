# TOPDOWN MEASUREMENT 01 — first LOG-mode evidence run (SIM)

**Date:** 2026-07-27 · **Produced by:** V3 Agent 07 · **Stack:** warm SIM
(MockConnector + SimWarmBoot), STDV_OTE default strategy, MNQ + MGC active,
MES as SMT feed.

> **PURPOSE AND LIMITS (read first).** This report validates the
> MEASUREMENT PIPELINE: that every V3 switch runs in LOG mode, counts,
> logs, persists, and survives restarts. The tape is SYNTHETIC — the
> deterministic SimWarmBoot generator — so the numbers below bound what
> the pipeline does, **not** what the market will do. LIVE-observation
> sessions must precede any mode flip. **Recommendation: DO NOT FLIP ANY
> SWITCH YET.**

## Sessions

Each session = one full engine boot: 3 days of synthetic 1m replay through
the listener path (~4,320 bars/symbol ≈ 3 trading sessions of 15m-cadence
evaluations — far beyond the 6-session-hour target), plus the TIER-2
synthetic H1 seed. Distinct seeds produce distinct tapes.

| Session | Seed | warm | 1m bars | D1 depth | H4 depth | HTF seed line |
|--------:|-----:|------|--------:|---------:|---------:|---------------|
| 1 | 42   | true | 4,324 | 22 | 133 | `[HTF-Backfill MNQ] seeded 444 H1 bars -> 115 H4, 19 D1 (30 days, 0 refused, SYNTHETIC)` |
| 2 | 1337 | true | 4,322 | 22 | — | same shape, 0 refused |
| 3 | 777  | true | 4,322 | 22 | — | same shape, 0 refused |

MES (SMT-only, no runner) is correctly skipped:
`[HTF-Backfill MES] no aggregation manager registered — skipping seed`.

## 1. `pd.gate.mode=LOG` — M2b premium/discount

| Session | wouldBlock L/S (MNQ) | blocked | abstains | verdictsByRangeSource |
|--------:|---------------------|--------:|---------:|-----------------------|
| 1 | 0 / 0 | 0 | 0 | (empty) |
| 2 | 0 / 0 | 0 | 0 | (empty) |
| 3 | 0 / 0 | 0 | 0 | (empty) |

The `[GATES]` line carried live PD previews throughout, e.g.
`pd=PREMIUM(R1)` — the evaluator resolves the governing range every 15m.

**Observations (conservative):**
1. Gate counters populate ONLY at emission attempts (M2b judges the
   proposed entry). The synthetic tape produced zero emissions past M2, so
   zero WOULD-BLOCKs is the *correct* pipeline behavior, not evidence
   about the rule.
2. The preview path worked continuously (`pd=PREMIUM(R1)` /
   `pd=DISCOUNT(R1)` tokens on `[GATES]`), proving range resolution (R1)
   and equilibrium math run on real level data.
3. No ABSTAIN storm: PDH/PDL formed from the warm replay's day rollovers,
   so R1 governed. Cold-start ABSTAIN behavior is covered by unit tests.

## 2. `bias.vote.mode=LOG` — 3-of-4 bias vote

288 `[VOTE]` evaluations per symbol per session (one per completed 15m bar
across the 3-day replay). Example line:

```
[VOTE MNQ] V1=ABSTAIN(ranging) V2=ABSTAIN(no-dist-leg) V3=BULL(below-open) V4=ABSTAIN(no-pd-levels) -> vote=NEUTRAL legacy=NEUTRAL AGREE=true
```

| Session | Symbol | agree | disagree | voteNeutral_legacyDirectional | voteDirectional_legacyNeutral |
|--------:|--------|------:|---------:|------------------------------:|------------------------------:|
| 1 | MNQ | 41 | 247 | 247 | 0 |
| 1 | MGC | 32 | 256 | 256 | 0 |
| 2 | MNQ | 47 | 241 | 241 | 0 |
| 2 | MGC | 40 | 248 | 248 | 0 |
| 3 | MNQ | 66 | 222 | 222 | 0 |
| 3 | MGC | 44 | 244 | 244 | 0 |

**Observations (conservative):**
1. Every disagreement is `voteNeutral_legacyDirectional`: the vote reads
   NEUTRAL while the single-vote legacy bias reads directional. On this
   tape that is arithmetic, not judgment — V2 chronically ABSTAINs
   (synthetic days rarely complete an AMD distribution leg) and V4
   ABSTAINs until PDH/PDL exist and then often reads both-tapped, so 3-of-4
   is frequently impossible (Troubleshooting J predicted exactly this).
   In VOTE mode this tape would have traded far less. Whether that is
   protective or costly is a LIVE-tape question.
2. `voteDirectional_legacyNeutral = 0` across all six rows: the richer
   bias never overruled a stand-down. The interesting adoption rows will
   only appear on live data.
3. The `[GATES]` rollup (`vote=NEUTRAL(2/0/2) agree=true`) and the
   `/api/setup` votes block rendered correctly all session.
4. `[BIAS]` NEUTRAL-flip counterfactual lines: 0 — synthetic setups died
   of expiry (`expired (40 bars without progress)`), not bias flips, so
   the hysteresis interplay had nothing to hold. Pipeline verified by unit
   test instead (BiasVoteEngineTest.hysteresisInterplay).

## 3. `ote30m.confluence=LOG` — M7b + persistent verdict stats

| Session | oteStats lifetime (MNQ) at boot end | restored at boot |
|--------:|--------------------------------------|------------------|
| 1 | agreed 0 / disagreed 0 / chartOnly 20 / sessions 4 | (first run) |
| 2 | agreed 0 / disagreed 0 / chartOnly 44 / sessions 4 | `[OTE-VERDICT] MNQ: restored 4 session(s) of lifetime agreement data` |
| 3 | agreed 0 / disagreed 0 / chartOnly 48 / sessions 4 | `[OTE-VERDICT] MNQ: restored 4 session(s)…` |

`data/ote_agreement_stats.jsonl` accumulated ~288 checkpoint lines in
session 1 alone (one per completed 30m bar per symbol); the loader's
last-line-per-session rule collapsed them into 4 session dates. **Lifetime
totals demonstrably survived two full restarts** — the exact property V2
lacked.

**Observations (conservative):**
1. `chartReacted_machineSilent` grows steadily (the 30m chart REACTs while
   the machine holds no armed setup) — on synthetic tape this only proves
   the counter plumbing; the promote/delete precision ratio needs real
   emissions.
2. M7b counters stayed 0 for the same reason as M2b: no emission attempts
   reached it. ABSTAIN/GATE semantics are unit-proven.
3. The boot reminder threshold (15 sessions) was NOT hit (4 synthetic
   session-dates); the reminder line itself is proven by test
   (`bootReminderAtThreshold`).

## Flip commands (copy-paste, ONE per week, only after live LOG review)

```bash
# 1) Premium/discount gate — first flip candidate (F1/F2 runbook):
-Dpd.gate.mode=BLOCK

# 2) Bias vote — after reviewing agreement counters on live tape (F3):
-Dbias.vote.mode=VOTE

# 3) 30m OTE confluence — ONLY when the QUICK_START criteria pass (F4):
-Dote30m.confluence=GATE
```

Reverts are the same flags with `LOG`.

## Verdict

No counter is degenerate given the synthetic-tape caveat (zero PD/M7b gate
evaluations trace to zero emissions, which is expected on this generator —
the tier/raid pipeline is deliberately hard to satisfy with synthetic
volume). The measurement pipeline is VALIDATED end to end: config logs,
LOG-mode counters, `[GATES]` rollups, `/api/setup` + `/api/chart`
telemetry, JSONL persistence, restart restore, and the two-tier HTF seed
all functioned across three isolated sessions.

**DO NOT FLIP YET.** Run 3–5 LIVE-observation sessions in LOG mode, re-read
these tables against real tape, then follow runbook F1–F4 — one switch at
a time, never two in the same week.
