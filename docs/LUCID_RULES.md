# LUCID_RULES.md — Lucid Trading venue truth (Agent 01, V6)

Retrieval date for every entry: **2026-11-05**. Primary source = Lucid's own help
center (support.lucidtrading.com, Intercom-hosted). Third-party figures are
recorded only where they conflict, and they never win. Lucid writes "EST" for
Eastern Time year-round; this document writes ET and the CT equivalent (ET − 1 h).

Status legend: **VERIFIED** = read on a Lucid page today · **CONSERVATIVE-UNVERIFIED**
= page unreachable or silent, most conservative published value used ·
**⛔ OWNER-ACTION** = ambiguity that only Lucid support can resolve in writing.

Unreachable today: `https://lucidtrading.com/` and `https://lucidtrading.com/lucidflex`
returned HTTP 403 to automated fetch. Everything below therefore comes from the
help center, which is Lucid's own property. Eval **purchase prices** are not
published in the help center and are not needed by any V6 code path.

---

## 1. Automation / bots / trade copiers — VERIFIED

Source: https://support.lucidtrading.com/en/articles/11404728-other-trading-activities
(article title "Other Trading Activities", formerly surfaced as "Permitted Activities").

- Quote: *"Automated trading systems and trade copiers are permitted."* All
  automated activity must follow Lucid's rules.
- Quote: *"Traders are fully responsible for any software errors, malfunctions,
  or unintended outcomes."*
- Quote (scalping): *"Scalping is considered valid as long as trades are taken in
  good faith and not intended to manipulate fill logic."*
- Scaling in / DCA permitted; martingaling "strongly discouraged"; "flipping" to
  satisfy minimum-day counts allowed.

V6 consequence: TradingView → relay → Tradovate is a permitted automated system.
Responsibility for every mis-fire is the owner's (see Section 2 Defence in Depth
in the master prompt).

## 2. Hedging — VERIFIED definition, ⛔ residual ambiguities

Source: https://support.lucidtrading.com/en/articles/11404734-prohibited-hedging

- Definition quote: *"Hedging refers to the use of multiple accounts to take
  opposing positions on the same trade."*
- Scope: prohibited across multiple accounts held by one user, between different
  users' accounts, and between different firms/funded platforms; "any other hedge
  meant to get around risk rules" is also banned.
- Same instrument: long and short of the **same contract** is prohibited whether
  in one account or in separate accounts. Exception stated by Lucid: opposite
  positions in the same contract **inside one account** are allowed when one leg
  is Micro and the other Mini (e.g. long MNQ / short NQ in one account). Doing
  that **across separate accounts is prohibited**.
- Correlated instruments: holding different contracts inside one account is
  allowed. *Opposing* positions in correlated assets **across separate accounts
  are prohibited**. Lucid's example: long ES in one account + short NQ in
  another. Quote: *"Correlated assets go for groupings in equities, metals,
  energies, etc."* — no exhaustive list is published.
- Enforcement: flagged accounts get an email notice and are **reset to the prior
  day's balance** (remaining drawdown not reset). Repeat offences: all involved
  accounts breached; trader may be permanently restricted.

Verdict for V6 (I1): the V6 design — ONE strategy, ONE traded root (MNQ), same
side on every linked account, MES read-only for SMT — produces **no opposing
position on any account at any time**, so it does not meet Lucid's stated
definition of hedging. Same-direction fan-out to several accounts is exactly the
"trade copier" case Lucid permits (Section 1).

Residual ambiguities that the published text does not settle:

⛔ **OA-4 (hedging definition) — owner sends this to Lucid support in writing and
pastes the reply below:**

> "I run one automated TradingView strategy that sends the same MNQ signal, same
> direction, same time, to N of my LucidFlex accounts via a webhook relay
> (CrossTrade). I never hold a long on one account and a short on another.
> Please confirm in writing: (1) Same-direction copies of one MNQ trade across
> several of my Lucid accounts are NOT hedging. (2) If a broker-side stop fills
> on account A while account B is still in the same-direction position (so A is
> flat, B is long), that is NOT hedging. (3) If, due to a rejected or delayed
> fill, account A enters a trade a few seconds after account B, that is NOT
> hedging. (4) I read MES/ES data for a correlation filter but NEVER place an
> order on MES/ES; please confirm that holding only MNQ positions can never
> trigger the correlated-instrument rule. (5) Does the correlated-instrument
> rule also apply when the opposing positions are in the SAME account in
> different contracts (e.g. long MNQ, short MES in one account)? (6) Please list
> the exact correlated groupings your detection uses for equity indices."

Owner reply: _(paste here, with date)_

## 3. High-frequency trading — VERIFIED (no numeric threshold published)

Source: https://support.lucidtrading.com/en/articles/11404736-prohibited-high-frequency-trading

- Definition quote: HFT is *"a type of automated trading strategy that involves
  submitting and executing a high volume of trades within extremely short time
  frames."* Illustration: *"hundreds of orders being placed within minutes."*
- Threshold: **none published**. Detection is by "automated risk detection
  systems"; appeal possible.
- Consequences: first offence written warning; repeated offences: HFT profits
  removed, account closed, permanent bar.

V6 consequence (I5): max 3 entries/session, ≥ 60 s between orders, bar-close
entries only is orders of magnitude below "hundreds of orders within minutes".
CONSERVATIVE-UNVERIFIED guidance for the relay: keep total order messages
(entries + exits + modifies, all accounts) well under 60 per hour.

## 4. Microscalping — VERIFIED

Source: https://support.lucidtrading.com/en/articles/11404742-prohibited-microscalping

- Trigger quote: *"More than 50% of your profits are generated from trades held
  for 5 seconds or less."*
- Genuine scalping that reflects realistic market activity is permitted.
- Consequences: manual review → written warning → if continued, microscalping
  profits forfeited and possible permanent restriction. Appeal possible.

V6 consequence (I6): min stop / min T1 distance in ticks, no strategy exit within
30 s of a fill, hold-time histogram in PARITY_REPORT.md. Target: 0 % of profit
from trades held ≤ 5 s.

## 5. Trading hours, forced flat, overnight — VERIFIED

Source: https://support.lucidtrading.com/en/articles/11404729-allowed-trading-times

- Quote (LucidPro, LucidFlex, LucidDirect): *"All positions must be closed by
  4:45 PM EST, Monday through Friday."* Open positions are closed by Lucid at
  that time. → **16:45 ET = 15:45 CT**.
- Quote: *"Holding a position past this time does not result in a failed
  account."* (Lucid liquidates; it is not a breach.) V6 still treats reaching
  Lucid's liquidation as an incident (L4).
- Trading reopens 6:00 PM ET (17:00 CT) Sunday–Thursday.
- Overnight / weekend: not permitted for Flex — positions must be flat by 16:45 ET
  daily, and Lucid closes them. (Swing trading is separately stated as "not
  allowed" for the new LucidLive accounts; Flex is covered by the daily close.)
- Holidays: on early-close days all positions must be closed before the market
  closes, even if earlier than 16:45 ET. ⛔ none; owner must disable the alert or
  trust the Pine `manualBlackout` input on CME early-close days (Agent 03/08).

I3 check: V6 no-entry 16:30 ET / flatten 16:40 ET sits 5 minutes inside Lucid's
16:45 ET forced flat. **Confirmed consistent.**

## 6. LucidFlex numbers — EVAL — VERIFIED

Source: https://support.lucidtrading.com/en/articles/12945790-lucidflex-evaluation-account

| Size | Profit target | Max Loss Limit (MLL) | Max size |
|---|---|---|---|
| $25,000 | $1,250 | $1,000 | 2 minis or 20 micros |
| **$50,000** | **$3,000** | **$2,000** | **4 minis or 40 micros** |
| $100,000 | $6,000 | $3,000 | 6 minis or 60 micros |
| $150,000 | $9,000 | $4,500 | 10 minis or 100 micros |

- Drawdown type: End-of-Day trailing (Section 8).
- Daily loss limit: **optional**, chosen at checkout ("Daily Loss Limit On or
  Off"). When On it is a *soft breach* — lock-out until the next session
  (https://support.lucidtrading.com/en/articles/16226050-lucidflex-customization).
  The dollar amount is **not published** → ⛔ OA-7 below.
- Consistency: 40 % standard, or 50 % with paid add-on (Section 9). Eval only.
- Minimum trading days: quote *"Pass in as few as 3 days with 40% consistency,
  or 2 days with the 50% consistency add-on."*
- Fee: one-time, no monthly rebilling; no time limit; no activation fee to
  upgrade. Purchase price not published in the help center (lucidtrading.com 403).
- Third-party conflict resolved: MLL **$2,000** (not $2,500); max size **4 minis /
  40 micros** (not 9 contracts). Lucid page wins.

⛔ **OA-7 (Daily Loss Limit amount) — owner question to Lucid support:**
> "For a LucidFlex 50K evaluation and the resulting funded account purchased
> with 'Daily Loss Limit: On', what is the exact daily loss limit dollar
> amount, is it measured on realised or realised+unrealised P&L, and at what
> time does the day reset?"
Until answered, Agent 03 uses softDailyLoss$ = 30 % of MLL = **$600** (master
prompt 03.5 default), flagged CONSERVATIVE-UNVERIFIED.

## 7. LucidFlex numbers — FUNDED — VERIFIED

Source: https://support.lucidtrading.com/en/articles/12945795-lucidflex-funded-account

- Sizes $25K–$150K; MLL $1,000 / **$2,000** / $3,000 / $4,500; max size 2/20,
  **4/40**, 6/60, 10/100 (minis/micros) — identical to eval, but the **scaling
  plan caps size below that until profit accrues** (Section 10).
- Drawdown: EOD trailing with lock (Section 8).
- Daily loss limit: optional, inherited from the eval purchase choice.
- Consistency: quote *"There is no Consistency Percentage on LucidFlex funded
  accounts."*
- Buffer: *"no payout buffer."* Profit split 90/10.

## 8. Drawdown type + lock rule — VERIFIED (applies to eval AND funded)

Source: https://support.lucidtrading.com/en/articles/12945815-lucidflex-drawdown

- End-of-Day trailing: at the end of each trading session the MLL is re-based
  from the account's **highest closing balance**; intraday moves do not trail.
- Lock quote: *"Once your account exceeds the Initial Trail Balance, the MLL
  locks and no longer moves."* Locked level = initial balance + $100. Requesting
  a payout also moves the MLL to the Locked MLL Balance.
- Breach quote: *"If your account balance reached the MLL, your account will be
  breached."* (touch = breach)

| Size | MLL | Initial Trail Balance | Locked MLL Balance |
|---|---|---|---|
| $25,000 | $1,000 | $26,100 | $25,100 |
| **$50,000** | **$2,000** | **$52,100** | **$50,100** |
| $100,000 | $3,000 | $103,100 | $100,100 |
| $150,000 | $4,500 | $154,600 | $150,100 |

Java model (Agent 05.3): `mll = max(mll, eodHighClose − 2000)` until
`eodHighClose > 52,100`, then `mll = 50,100` forever (and immediately on a payout
request).

## 9. Consistency rule — VERIFIED (eval only)

Source: https://support.lucidtrading.com/en/articles/12945805-lucidflex-consistency-percentage

- Formula quote: *"Largest Single Day Profit / Account Profit = Consistency
  Percentage"*; must be ≤ 40 % (standard) or ≤ 50 % (add-on) to upgrade.
- Quote: *"The consistency requirement (40%, or 50% with the add-on) applies
  only during the evaluation stage."*
- Worked example on the page: $50K, largest day $750 at the $3,000 target = 25 %.
- Practical cap for a 50K eval: largest day ≤ $1,200 (40 %) or ≤ $1,500 (50 %)
  of a $3,000 target; if exceeded, keep trading until the ratio falls back.

## 10. Funded scaling plan — VERIFIED (funded only)

Source: https://support.lucidtrading.com/en/articles/12945808-lucidflex-scaling-plan
Quote: no scaling plan in the evaluation phase; limits based on simulated profit,
updated at end of each session.

| Simulated profit | $25K | **$50K** | $100K | $150K |
|---|---|---|---|---|
| $0 – $999 | 1 / 10 | **2 / 20** | 3 / 30 | 4 / 40 |
| $1,000 – $1,999 | 2 / 20 | **3 / 30** | 4 / 40 | 5 / 50 |
| $2,000 – $2,999 | — | **4 / 40** | 5 / 50 | 6 / 60 |
| $3,000 – $4,499 | — | — | 6 / 60 | 8 / 80 |
| $4,500 + | — | — | — | 10 / 100 |

(minis / micros). For 50K funded: start at **2 minis = 20 micros**; 4/40 only
after ≥ $2,000 closed profit. AccountProfile.scalingCap for
LUCID_FLEX_50K_FUNDED therefore defaults to **20 micros**.

## 11. Payouts — VERIFIED

Source: https://support.lucidtrading.com/en/articles/12945796-lucidflex-payouts

- Quote: *"Traders must earn at least the minimum required profit on 5 separate
  days during the payout cycle."* Min daily profit: $100 / **$150** / $200 / $250
  (25K / 50K / 100K / 150K). Positive net profit in the cycle required.
- Split quote: *"All LucidFlex funded account payouts are split 90% to the trader
  and 10% to Lucid Trading."*
- Request: 50 % of balance up to cap $1,000 / **$2,000** / $2,500 / $3,000;
  min request $500; no fixed window; max **5 payouts** per account, then moved
  to live. Requests are final.
- Payout request moves MLL to Locked MLL Balance (Section 8).

## 12. News trading (Flex) — VERIFIED

Source: https://support.lucidtrading.com/en/articles/11404728-other-trading-activities

- Quote: *"Traders may enter or exit positions around scheduled or unscheduled
  news events on these plans without it being a breach."* (Flex, Pro, Direct).
  LucidDaily only: red-folder news is a hard breach.
- V6: news gating is DROP for Flex (PORT_MAP), optional manual blackout kept.

## 13. Maximum accounts per household — VERIFIED

Source: https://support.lucidtrading.com/en/articles/11404617-maximum-number-of-accounts

- Quote: *"Traders may hold a maximum of 10 accounts in total across evaluation
  accounts and funded accounts."* Up to 10 active evals; quote *"Different types
  of funded accounts combined may not exceed 5 accounts total."* Limits are per
  household/family.
- Relay fan-out ceiling for V6: ≤ 5 funded + reserve evals, 10 total.

## 14. Approved products + commissions — VERIFIED

Source: https://support.lucidtrading.com/en/articles/11508978-approved-products-and-commissions

- MNQ, MES, M2K, MYM, MCL: **$0.50 per side**. ES, NQ, RTY, YM, NKD: $1.75 per
  side. MGC $0.80, SIL $1.60. MNQ and MES are approved.

## 15. Supported platforms — VERIFIED

Source: https://support.lucidtrading.com/en/articles/11404614-lucid-trading-supported-platforms

- Quote: *"Lucid Trading platforms include NinjaTrader, TradingView, Tradovate,
  Tradesea, Sierra Chart, and more."* CQG group: NinjaTrader, Tradovate,
  TradingView. *"All listed platforms work with all Lucid account types."*
- No statement about automation via TradingView; automation permission comes
  from Section 1.

## 16. Inactivity — VERIFIED

Source: https://support.lucidtrading.com/en/articles/11404632-inactivity-policy

- Within 30 calendar days an account must show at least a $1 net P&L from
  trading or it is "deemed abandoned and will be permanently deleted".
- V6: a paused bot for > 30 days kills the account. Runbook item for Agent 08.

## 17. Integrity clause — VERIFIED

Source: https://support.lucidtrading.com/en/articles/11404732-trade-with-integrity
Quote: *"Lucid Trading strictly prohibits the use of strategies that exploit
platform mechanics or simulated market conditions."*

---

## ⛔ OWNER-ACTION summary (Agent 01)

| ID | Question / action | Blocks |
|---|---|---|
| OA-4 | Hedging definition questions (Section 2, exact text above) → paste reply | go-live gate (08.2), not the build |
| OA-7 | Daily Loss Limit dollar amount + measurement (Section 6) | Agent 03 softDailyLoss$ default stays $600 CONSERVATIVE-UNVERIFIED |

No other ambiguity found: automation, forced-flat time, MLL, lock rule, max size,
scaling, consistency, payouts, news, max accounts are all stated on Lucid's pages.
