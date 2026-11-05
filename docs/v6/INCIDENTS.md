# INCIDENTS.md — V6 live-trading incident log (owner writes, Fable reads)

One row per incident, newest first. Times **ET (CT)**. Row ids refer to `docs/v6/RELAY_SETUP.md` §5
(L1–L10) and the playbook in `docs/RUNBOOK_TRADINGVIEW.md` §8. Never paste a key, webhook URL or account
name (use `<LUCID_1>`).

| date | time ET (CT) | what you saw (where: TV Alert log / CrossTrade Alert History / Tradovate / Lucid email) | lifecycle row | what you did (kill switch 1/2/3? flattened by hand?) | position at the end | Fable follow-up |
|---|---|---|---|---|---|---|
| | | | | | | |

Reaching Lucid's 16:45 ET (15:45 CT) liquidation, any opposite position anywhere, any order without a visible
stop, or any Lucid rule email is **always** an incident row, even if nothing was lost.
