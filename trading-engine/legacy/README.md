# trading-engine/legacy — NOT COMPILED (V6 Agent 05.1, rules R6 / I9)

This directory holds the removed TopstepX code. Gradle does not compile it. It
is not a source set, and nothing in `src/` can reference it.

- `src/main/java/.../connector/TopstepConnector.java`, `TopstepCredentials.java`:
  the former live execution path (ProjectX REST). V6 trades through TradingView
  and a webhook relay (RUNBOOK_TRADINGVIEW.md).
- `src/test/java/.../connector/*`: tests of the TopstepX wire protocol (FakeTopstepX
  HTTP gateway, contract binding, 429 backoff, cancel codes, contract-root parsing,
  bar delivery). Tests whose logic does not depend on a connector were moved to
  `src/test` and retargeted at `BracketBroker` / `MockConnector`:
  - the `Bracket*` and `AdoptedPositionRelease` tests now mock `BracketBroker`
  - `BrokerAgnosticContractTest` covers the snapshot, the not-working cancel
    exception and the fail-loud cancel without an id

These files are kept for history only and may be deleted in V7.
