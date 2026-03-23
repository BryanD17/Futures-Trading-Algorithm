package com.topstep.trading.lifecycle;

import java.util.EnumSet;
import java.util.Set;

/**
 * Account lifecycle phases for prop firm challenge progression.
 * Tracks: EVALUATION -> FUNDED_PROBATION -> FUNDED_SCALING -> BLOWN
 *
 * Each phase has different risk parameters and trading behavior.
 * Transition validation prevents invalid state changes.
 */
public enum AccountPhase {

    EVALUATION("Evaluation", "Working toward profit target to pass challenge") {
        @Override
        public Set<AccountPhase> validTransitions() {
            return EnumSet.of(FUNDED_PROBATION, BLOWN);
        }
    },

    FUNDED_PROBATION("Funded Probation", "Passed evaluation, conservative trading to prove consistency") {
        @Override
        public Set<AccountPhase> validTransitions() {
            return EnumSet.of(FUNDED_SCALING, BLOWN);
        }
    },

    FUNDED_SCALING("Funded Scaling", "Proven funded trader, scaling up with wider targets") {
        @Override
        public Set<AccountPhase> validTransitions() {
            return EnumSet.of(BLOWN);
        }
    },

    BLOWN("Blown", "Account breached max loss limit - terminated") {
        @Override
        public Set<AccountPhase> validTransitions() {
            return EnumSet.noneOf(AccountPhase.class);
        }
    };

    private final String displayName;
    private final String description;

    AccountPhase(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    /**
     * Get the set of valid phases this phase can transition to.
     */
    public abstract Set<AccountPhase> validTransitions();

    /**
     * Check if transitioning to the target phase is valid.
     */
    public boolean canTransitionTo(AccountPhase target) {
        return validTransitions().contains(target);
    }

    /**
     * Validate and return the target phase, throwing if invalid.
     */
    public AccountPhase transitionTo(AccountPhase target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                "Invalid phase transition: " + this + " -> " + target +
                ". Valid transitions: " + validTransitions());
        }
        return target;
    }

    /**
     * Whether this phase is a terminal state (no further transitions).
     */
    public boolean isTerminal() {
        return validTransitions().isEmpty();
    }

    /**
     * Whether this phase is an active trading phase.
     */
    public boolean isActive() {
        return this != BLOWN;
    }

    /**
     * Whether this is an evaluation (pre-funded) phase.
     */
    public boolean isEvaluation() {
        return this == EVALUATION;
    }

    /**
     * Whether this is any funded phase.
     */
    public boolean isFunded() {
        return this == FUNDED_PROBATION || this == FUNDED_SCALING;
    }
}
