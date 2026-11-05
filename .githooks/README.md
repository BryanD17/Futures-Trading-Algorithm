# .githooks — repository git hooks (V6 Agent 05.5, rule R5)

Enable once per clone:

```sh
git config core.hooksPath .githooks
```

`pre-commit` runs `secret_scan.py --staged` over the ADDED lines of the staged
diff and refuses the commit on:

- key-shaped strings (AWS, GitHub, Slack, `sk-…`, Stripe, Google, private-key
  blocks, JWTs);
- webhook URLs that carry a token (CrossTrade / TradersPost / PickMyTrade /
  SignalStack / Discord / Slack / any `…/webhook/<token>`);
- `key=`, `secret`, `password`, `token`, `apiKey`, `webhook_url`, `account_id`
  assignments whose value is not a placeholder.

Use placeholders: `<RELAY_SECRET>`, `<ACCOUNT_1>`, `${ENV_VAR}`, `{{alert_secret}}`.
A documentation line that must show a pattern may carry the marker
`secret-scan: allow` (never for a real value). Bypass with `git commit --no-verify`
only for a reviewed false positive. CI (`.github/workflows/ci.yml`) runs
`python3 .githooks/secret_scan.py --all` over every tracked file.

Self-test: `python3 -m unittest discover -s tools/tests`.
