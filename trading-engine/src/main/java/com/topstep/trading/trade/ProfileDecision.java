package com.topstep.trading.trade;

import java.util.List;

/**
 * What one profile made of one setup (V4 Agent 08).
 *
 * @param profile   the profile evaluated
 * @param satisfied true when every requirement in the set was met
 * @param blocking  the requirements that were NOT met, in evaluation order —
 *                  ALL of them, not just the first. A profile simulator whose
 *                  job is to answer "why no trade" is useless if it reports
 *                  only the earliest complaint and hides the other four.
 */
public record ProfileDecision(TradeProfile profile, boolean satisfied, List<String> blocking) {

    public ProfileDecision {
        blocking = List.copyOf(blocking);
    }

    public static ProfileDecision pass(TradeProfile profile) {
        return new ProfileDecision(profile, true, List.of());
    }

    @Override
    public String toString() {
        return profile + (satisfied ? " SATISFIED" : " blocked by " + String.join(",", blocking));
    }
}
