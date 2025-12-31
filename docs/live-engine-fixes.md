# Live Engine Stop/Bracket Fixes

## Issues Identified

1. **Protective orders skipped after fills.** When a signal filled, the engine attempted to place stop loss and take profit brackets without validating the prices. If an upstream calculation produced an invalid stop/target (e.g., stop above a long entry or target above a short entry), the code proceeded and the protective orders were silently rejected by the broker, leaving positions unprotected.
2. **No fallback brackets when fills arrived before order-search callbacks.** If Topstep's order-search API returned 400s or arrived late, the fill callback could appear without any bracket being created from the order-status path. The trade then sat naked while polling retried.
3. **Shutdown flattened positions instead of canceling open work.** Stopping live mode always tried to flatten anything still tracked in the account, even when those positions were already closed on the exchange or had missing bracket state. This caused extra market orders to be sent when the user only wanted to cancel outstanding orders and exit cleanly.

## What Changed

- **Bracket validation before submission.** Fills now run through direction-aware guards that require a long stop to be below the fill and target above it, and the reverse for shorts. Invalid pricing logs a clear error and stops bracket creation so we never send nonsense prices to the broker.
- **Fallback brackets on position-open events.** When the execution listener observes a fill and no bracket exists yet, it immediately builds an OCO pair from the recorded stop/target levels—after re-validating them—so the position gets protection even if order-search polling is failing.
- **Explicit JSON header for order searches.** Order-status polling now sends a Content-Type header to reduce 400 responses that previously delayed fill detection and downstream bracket placement.
- **Cancellation-first shutdown.** The stop routine now cancels any working brackets and simply clears local position tracking instead of firing new market orders. This keeps shutdown from flattening positions that are already gone or were never opened, matching the intent of "stop live mode = cancel everything unfilled".

## How It Works Now

1. A fill triggers bracket validation; only valid stop/target pairs generate OCO brackets with the connector. Any bad prices are reported and ignored, leaving the trade untouched rather than creating partial protection.
2. If the order-search callback is late or returns 400, the position-open listener still creates a validated OCO bracket immediately using the stored stop/target levels so the trade is protected without waiting for another poll cycle.
3. Stopping live mode cancels pending orders, cancels any active brackets, clears tracked positions, and disconnects—without sending new flatten orders. Emergency shutdown still activates the kill switch but leverages the same cancellation flow.

These changes ensure every live fill either receives a sane SL/TP pair or is clearly flagged, that fills remain protected even when Topstep callbacks lag, and that stopping the engine no longer creates unexpected close-out orders.
