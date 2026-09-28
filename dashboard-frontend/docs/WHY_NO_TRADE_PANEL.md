# "Why no trade" panel (V5 Agent 08)

Purpose: let the owner answer "which gate killed it at 15:05 ET today?" from the
dashboard's **Setup** tab without reading logs.

## What it shows

`src/components/WhyNoTradePanel.tsx` is mounted at the top of `SetupPanel`
(under the MNQ / MES / MGC tabs). It follows the selected instrument tab. The
existing panel (stepper, pills, vote row, M1..M9 checklist, OTE block, STDV
ladder, plan, confluence stack) is unchanged and renders below it.

1. **Current setup strip** (from `GET /api/setup/{symbol}`): session window with
   a `PRIME` flag inside a prime killzone, state, last failed gate (red when
   set), HTF bias, dealing range `rangeHigh / rangeEq / rangeLow`, OTE band
   `[f62, f79]` with the `1.0` invalidation (or "not built"), planned
   entry / stop / RR (once an entry exists), and raid score.
2. **Gate-count chips**: `gateCounts` from `GET /api/setup`, sorted by count.
   If that field is missing, the panel falls back to
   `GET /api/status` → `telemetry.gateCounts`. RISK / SIZE / WARMUP / ORDER
   chips are outlined in amber.
3. **Gate-decision table**: the backend's ring of the last 200
   `GateDecisionEvent`s, **newest first**. Columns: time (ET, `HH:mm`, prefixed
   with `MM-dd` whenever the ET date differs from the row above, or from today
   for the first row), symbol, session, state, gate, reason, numberA, numberB.
   You can filter by symbol (All / MNQ / MES / MGC) and by session (built from
   the sessions present in the data). It shows 50 rows by default, and the
   "Show up to 200" button expands it. Rows whose gate starts with `RISK`,
   `SIZE`, `WARMUP` or `ORDER` (for example `ORDER: cancelled`) are
   highlighted.

The panel is read-only. Nothing on it places, changes or cancels an order.

## Refresh mechanism

**Polling every 5 s**: `/api/setup` for the decisions and counts, and
`/api/setup/{symbol}` for the strip. The dashboard's WebSocket (`/ws/stream`,
`TradingWebSocketHandler`) only pushes `account_update` frames, so it carries
no setup or gate updates to subscribe to.

## Backend change (api-backend only, no engine code)

`SetupController.SetupSnapshotDto` did not expose the fields the strip needs,
although `SetupContext` already had them. This PR appends `sessionWindow`,
`primeKillzone`, `rangeHigh`, `rangeLow` and `rangeEq` to the record. Range
values are `null` until the range exists. They are never `NaN`, because Jackson
would write a non-JSON `NaN` token. On the frontend the fields are optional, so
an older backend still renders (as `·`).

## Evidence (real backend, SIM replay, 2026-09-28)

I built from the worktree with `gradlew :api-backend:build -x test -q`
(EXIT 0), ran the jar on **:8091** with an isolated `user.home`, then called
`POST /api/control/start?mode=SIM` and waited about 90 s. I ran Vite with
`VITE_API_TARGET=http://localhost:8091 npx vite --port 3091` (the existing
env-var hook in `vite.config.ts`, so no hard-coded port is committed). I drove
headless Chrome over CDP, clicked the **Setup** tab, waited 8 s, then dumped
the DOM and took a screenshot. Afterwards I ran `POST /api/control/stop` and
killed the java and Vite processes. Port 8080 was not touched.

Screenshot: [`docs/why-no-trade-panel.png`](why-no-trade-panel.png)

DOM summary (from the rendered page):

```
rows: 50   hotRows: 1   count: "50 of 200 shown (200 in buffer)"   existing panel still rendered: true
strip: MNQ SESSION PRE_ASIA | STATE BIAS_SET | LAST GATE FAILED · | BIAS BULLISH |
       RANGE H / EQ / L 20009.49 / 19989.49 / 19969.49 | OTE [0.62, 0.79] · 1.0 not built |
       ENTRY / STOP / RR · | RAID SCORE 0
chips: SETUP: 68, SETUP-SWEEP_DONE: 38, SETUP-MSS_CONFIRMED: 36, SETUP-MANIP_DONE: 26,
       SETUP-BIAS_SET: 22, SETUP-DISPLACED: 18, SETUP-IN_TRADE: 8, SIGNAL: 8, WARMUP: 8
first rows (Time ET | Sym | Session | State | Gate | Reason | A | B):
  18:33 | MGC | PRE_ASIA | BIAS_SET      | SETUP-BIAS_SET      | setup progressing (no gate failed) | 2399.87 | 0
  18:26 | MGC | PRE_ASIA | INVALIDATED   | SETUP               | MSS stale before price reached the OTE band (30 detector bars) | 2399.71 | 9
  16:11 | MGC | NO_ENTRY | MSS_CONFIRMED | SETUP-MSS_CONFIRMED | setup progressing (no gate failed) | 2397.49 | 9
  15:37 | MGC | NY_PM    | SWEEP_DONE    | SETUP-SWEEP_DONE    | setup progressing (no gate failed) | 2390.39 | 8
  15:36 | MGC | NY_PM    | MANIP_DONE    | SETUP-MANIP_DONE    | setup progressing (no gate failed) | 2389.59 | 0
  15:29 | MGC | NY_PM    | INVALIDATED   | SETUP               | OTE window expired (40 bars) | 2395.19 | 10
  14:48 | MGC | NY_PM    | OTE_ARMED     | SETUP               | M7 | 2393.76 | 10
  13:54 | MGC | NY_PM    | SIGNAL        | WARMUP  (highlighted) | WARMUP: not complete (missing live candle from [MNQ, MGC, MES]) | 1790618040 | 0
```

Highlighted row, raw DOM:

```html
<tr class="hot"><td class="mono">13:54</td><td>MGC</td><td>NY_PM</td><td>SIGNAL</td>
<td class="gate-cell">WARMUP</td><td class="reason-cell">WARMUP: not complete (missing live candle from [MNQ, MGC, MES])</td>
<td class="num mono">1790618040</td><td class="num mono">0</td></tr>
```

Day-change date prefixes appeared deeper in the table (`09-27`, `09-25`,
`09-24`).

`GET /api/setup/MNQ` (excerpt) confirms the new DTO fields:
`"sessionWindow":"PRE_ASIA","primeKillzone":false,"rangeHigh":20009.486…,"rangeLow":19969.486…,"rangeEq":19989.486…`

## Build output

```
> npm run build   (tsc && vite build)
vite v5.4.21 building for production...
✓ 918 modules transformed.
dist/index.html                  0.49 kB │ gzip:   0.31 kB
dist/assets/index-CR3AJAz2.css  28.40 kB │ gzip:   6.13 kB
dist/assets/index-CTsXpXo1.js  814.33 kB │ gzip: 241.40 kB
(!) Some chunks are larger than 500 kB (pre-existing warning)
✓ built in 3.24s
```

`tsc` reported zero errors.
