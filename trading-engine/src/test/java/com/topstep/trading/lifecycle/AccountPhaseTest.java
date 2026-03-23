package com.topstep.trading.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AccountPhase Enum Tests")
class AccountPhaseTest {

    @Test
    @DisplayName("EVALUATION can transition to FUNDED_PROBATION")
    void evaluationToFundedProbation() {
        AccountPhase result = AccountPhase.EVALUATION.transitionTo(AccountPhase.FUNDED_PROBATION);
        assertThat(result).isEqualTo(AccountPhase.FUNDED_PROBATION);
    }

    @Test
    @DisplayName("EVALUATION can transition to BLOWN")
    void evaluationToBlown() {
        AccountPhase result = AccountPhase.EVALUATION.transitionTo(AccountPhase.BLOWN);
        assertThat(result).isEqualTo(AccountPhase.BLOWN);
    }

    @Test
    @DisplayName("EVALUATION cannot skip to FUNDED_SCALING")
    void evaluationCannotSkipToScaling() {
        assertThatThrownBy(() -> AccountPhase.EVALUATION.transitionTo(AccountPhase.FUNDED_SCALING))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Invalid phase transition");
    }

    @Test
    @DisplayName("FUNDED_PROBATION can transition to FUNDED_SCALING")
    void fundedProbationToScaling() {
        AccountPhase result = AccountPhase.FUNDED_PROBATION.transitionTo(AccountPhase.FUNDED_SCALING);
        assertThat(result).isEqualTo(AccountPhase.FUNDED_SCALING);
    }

    @Test
    @DisplayName("FUNDED_PROBATION can transition to BLOWN")
    void fundedProbationToBlown() {
        AccountPhase result = AccountPhase.FUNDED_PROBATION.transitionTo(AccountPhase.BLOWN);
        assertThat(result).isEqualTo(AccountPhase.BLOWN);
    }

    @Test
    @DisplayName("FUNDED_PROBATION cannot go back to EVALUATION")
    void fundedProbationCannotGoBack() {
        assertThatThrownBy(() -> AccountPhase.FUNDED_PROBATION.transitionTo(AccountPhase.EVALUATION))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("FUNDED_SCALING can only transition to BLOWN")
    void fundedScalingOnlyToBlown() {
        assertThat(AccountPhase.FUNDED_SCALING.canTransitionTo(AccountPhase.BLOWN)).isTrue();
        assertThat(AccountPhase.FUNDED_SCALING.canTransitionTo(AccountPhase.EVALUATION)).isFalse();
        assertThat(AccountPhase.FUNDED_SCALING.canTransitionTo(AccountPhase.FUNDED_PROBATION)).isFalse();
    }

    @Test
    @DisplayName("BLOWN is terminal - no transitions allowed")
    void blownIsTerminal() {
        assertThat(AccountPhase.BLOWN.isTerminal()).isTrue();
        assertThat(AccountPhase.BLOWN.validTransitions()).isEmpty();

        assertThatThrownBy(() -> AccountPhase.BLOWN.transitionTo(AccountPhase.EVALUATION))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Phase properties are correct")
    void phaseProperties() {
        assertThat(AccountPhase.EVALUATION.isEvaluation()).isTrue();
        assertThat(AccountPhase.EVALUATION.isFunded()).isFalse();
        assertThat(AccountPhase.EVALUATION.isActive()).isTrue();

        assertThat(AccountPhase.FUNDED_PROBATION.isEvaluation()).isFalse();
        assertThat(AccountPhase.FUNDED_PROBATION.isFunded()).isTrue();
        assertThat(AccountPhase.FUNDED_PROBATION.isActive()).isTrue();

        assertThat(AccountPhase.FUNDED_SCALING.isFunded()).isTrue();
        assertThat(AccountPhase.BLOWN.isActive()).isFalse();
    }
}
