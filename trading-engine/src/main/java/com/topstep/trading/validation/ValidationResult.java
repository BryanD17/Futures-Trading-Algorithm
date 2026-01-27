package com.topstep.trading.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of trade entry validation.
 *
 * Contains pass/fail status, list of failures (if any),
 * list of confirmations (if passed), and formatted summary.
 */
public class ValidationResult {

    private final boolean passed;
    private final List<String> failures;
    private final List<String> confirmations;
    private final String summary;

    public ValidationResult(boolean passed, List<String> failures, List<String> confirmations, String summary) {
        this.passed = passed;
        this.failures = new ArrayList<>(failures);
        this.confirmations = new ArrayList<>(confirmations);
        this.summary = summary;
    }

    /**
     * Check if validation passed all requirements.
     */
    public boolean passed() {
        return passed;
    }

    /**
     * Check if validation failed any requirements.
     */
    public boolean failed() {
        return !passed;
    }

    /**
     * Get list of failure reasons (empty if passed).
     */
    public List<String> getFailures() {
        return Collections.unmodifiableList(failures);
    }

    /**
     * Get list of confirmed confluences (empty if failed).
     */
    public List<String> getConfirmations() {
        return Collections.unmodifiableList(confirmations);
    }

    /**
     * Get formatted summary string for logging.
     */
    public String getSummary() {
        return summary;
    }

    /**
     * Get number of failures.
     */
    public int getFailureCount() {
        return failures.size();
    }

    /**
     * Get number of confirmations.
     */
    public int getConfirmationCount() {
        return confirmations.size();
    }

    @Override
    public String toString() {
        return summary;
    }

    /**
     * Create a passing validation result.
     */
    public static ValidationResult pass(List<String> confirmations, String summary) {
        return new ValidationResult(true, Collections.emptyList(), confirmations, summary);
    }

    /**
     * Create a failing validation result.
     */
    public static ValidationResult fail(List<String> failures, String summary) {
        return new ValidationResult(false, failures, Collections.emptyList(), summary);
    }
}
