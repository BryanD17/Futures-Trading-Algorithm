package com.topstep.trading.strategy.session;

/**
 * The nine ET session windows every time decision in the engine is
 * classified into (TRADE_FLOW_UNBLOCK_MASTER_PROMPT_V5, Agent 02, task 1).
 * Boundaries live in ONE place: {@link SessionClassifier}.
 *
 * <p>Only {@link #NO_ENTRY} (15:45–18:00 ET = 14:45–17:00 CT: flatten
 * protection + Globex halt) and {@link #WEEKEND} (Fri 17:00 → Sun 18:00 ET)
 * block entries. Those two are SACRED in every gate mode.
 */
public enum SessionWindow {
    /** 19:00–02:00 ET (18:00–01:00 CT, 16:00–23:00 PT). */
    ASIA,
    /** 02:00–08:00 ET (01:00–07:00 CT, 23:00–05:00 PT); prime 02:00–05:00 ET. */
    LONDON,
    /** 08:00–09:30 ET (07:00–08:30 CT, 05:00–06:30 PT). */
    PRE_NY,
    /** 09:30–12:00 ET (08:30–11:00 CT, 06:30–09:00 PT); prime 09:45–11:00 ET. */
    NY_AM,
    /** 12:00–13:30 ET (11:00–12:30 CT, 09:00–10:30 PT). */
    NY_LUNCH,
    /** 13:30–15:45 ET (12:30–14:45 CT, 10:30–12:45 PT); prime 13:45–15:45 ET. */
    NY_PM,
    /** 15:45–18:00 ET = 14:45–17:00 CT (12:45–15:00 PT). SACRED no-entry block. */
    NO_ENTRY,
    /** 18:00–19:00 ET (17:00–18:00 CT, 15:00–16:00 PT) — Globex reopen hour. */
    PRE_ASIA,
    /** Fri 17:00 ET → Sun 18:00 ET (Fri 16:00 CT → Sun 17:00 CT). SACRED. */
    WEEKEND;

    /** True for the two windows in which NO new entry may ever be taken. */
    public boolean blocksEntry() {
        return this == NO_ENTRY || this == WEEKEND;
    }
}
