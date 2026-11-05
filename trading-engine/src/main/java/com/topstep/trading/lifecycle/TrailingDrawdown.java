package com.topstep.trading.lifecycle;

/**
 * V6 Agent 05.3: the Max-Loss-Limit floor of a prop account, end-of-day trailing.
 *
 * <p>LucidFlex (docs/LUCID_RULES.md §8, source
 * https://support.lucidtrading.com/en/articles/12945815-lucidflex-drawdown):
 * <ul>
 *   <li>EOD trailing: at the end of each session the floor re-bases to
 *       {@code highestClosingBalance − MLL}; intraday moves do not trail;</li>
 *   <li>lock: once the end-of-day balance EXCEEDS the Initial Trail Balance the
 *       floor locks at the Locked MLL Balance (initial + $100) and never moves
 *       again; a payout request locks it immediately;</li>
 *   <li>breach: the balance reaching the floor (touch = breach).</li>
 * </ul>
 * Java model (LUCID_RULES §8): {@code mll = max(mll, eodHighClose − MLL)} until
 * {@code eodHighClose > initialTrail}, then {@code mll = lockedMll} forever.
 *
 * <p>{@link #legacy} keeps the pre-V6 Topstep-style math (HWM − MLL, no lock)
 * numerically identical for the profiles that still use it.
 */
public final class TrailingDrawdown {

    private final double maxLossLimit;
    private final double lockTrigger;    // +inf = never locks
    private final double lockedFloor;    // NaN when it never locks
    private double eodHighClose;
    private boolean locked;

    private TrailingDrawdown(double startingBalance, double maxLossLimit, double lockTrigger, double lockedFloor) {
        this.maxLossLimit = maxLossLimit;
        this.lockTrigger = lockTrigger;
        this.lockedFloor = lockedFloor;
        this.eodHighClose = startingBalance;
    }

    /** Lucid EOD trailing MLL + lock, numbers from the profile (config/lucid_profiles.yaml). */
    public static TrailingDrawdown lucid(LucidAccountProfile p) {
        return new TrailingDrawdown(p.startingBalance(), p.maxLossLimit(), p.initialTrailBalance(), p.lockedMllBalance());
    }

    /** Pre-V6 model: floor = EOD high-water mark − MLL, never locks. */
    public static TrailingDrawdown legacy(double startingBalance, double maxLossLimit) {
        return new TrailingDrawdown(startingBalance, maxLossLimit, Double.POSITIVE_INFINITY, Double.NaN);
    }

    /** The Lucid model when the lifecycle carries a Lucid profile, else legacy. */
    public static TrailingDrawdown forLifecycle(AccountLifecycle t) {
        LucidAccountProfile p = t.getLucidProfile();
        return p != null ? lucid(p) : legacy(t.getStartingBalance(), t.getMaxLossLimit());
    }

    /** End of a trading session at {@code closingBalance}. */
    public void onEndOfDay(double closingBalance) {
        if (closingBalance > eodHighClose) eodHighClose = closingBalance;
        if (!locked && eodHighClose > lockTrigger) locked = true;
    }

    /** A payout request moves the MLL to the Locked MLL Balance (Lucid). No-op for legacy. */
    public void onPayoutRequest() {
        if (!Double.isNaN(lockedFloor)) locked = true;
    }

    /** The balance at which the account is breached. */
    public double floor() {
        return locked ? lockedFloor : eodHighClose - maxLossLimit;
    }

    /** Dollars between {@code balance} and the floor (≤ 0 = breached). */
    public double room(double balance) {
        return locked ? balance - lockedFloor : maxLossLimit - (eodHighClose - balance);
    }

    /** Touch = breach. */
    public boolean breached(double balance) {
        return locked ? balance <= lockedFloor : (eodHighClose - balance) >= maxLossLimit;
    }

    /**
     * Share of the MLL consumed (≥ 0; callers clamp the top). Unlocked: drawdown
     * from the EOD high / MLL (the pre-V6 expression, bit-for-bit). Locked: the
     * consumed part of the MLL measured from the locked floor (0 while the room
     * exceeds the MLL).
     */
    public double usagePct(double balance) {
        if (maxLossLimit <= 0) return 0.0;
        if (!locked) return Math.max(0, (eodHighClose - balance)) / maxLossLimit;
        return Math.max(0.0, maxLossLimit - room(balance)) / maxLossLimit;
    }

    public boolean isLocked() { return locked; }
    public double eodHighClose() { return eodHighClose; }
    public double maxLossLimit() { return maxLossLimit; }
}
