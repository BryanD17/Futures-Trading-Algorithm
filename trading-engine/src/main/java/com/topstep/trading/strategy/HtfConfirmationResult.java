package com.topstep.trading.strategy;

/**
 * Result of Higher Timeframe (HTF) confirmation check.
 *
 * HTF confirmation is MANDATORY for all trades. This class represents
 * the result of checking multiple HTF confirmation types:
 * - 5-minute FVG in trade direction
 * - 15-minute FVG in trade direction
 * - 5-minute Order Block in trade direction
 * - HTF MSS (Market Structure Shift) aligned
 * - HTF Bias aligned with trade direction
 *
 * At least ONE of these must be present for a valid trade.
 */
public class HtfConfirmationResult {

    private final boolean confirmed;
    private final String confirmationType;  // e.g., "5mFVG", "15mFVG", "5mOB", "HTF_MSS", "HTF_BIAS"
    private final String details;           // Human-readable details

    public HtfConfirmationResult(boolean confirmed, String confirmationType, String details) {
        this.confirmed = confirmed;
        this.confirmationType = confirmationType;
        this.details = details;
    }

    /**
     * Check if HTF confirmation is present.
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Get the type of HTF confirmation found.
     * Returns null if not confirmed.
     */
    public String getConfirmationType() {
        return confirmationType;
    }

    /**
     * Get human-readable details about the confirmation.
     */
    public String getDetails() {
        return details;
    }

    /**
     * Get display tag for logging (e.g., "[HTF:5mFVG✓]").
     * Returns empty string if not confirmed.
     */
    public String getDisplayTag() {
        if (!confirmed) {
            return "";
        }
        return "[HTF:" + confirmationType + "✓]";
    }

    /**
     * Create a confirmed result.
     */
    public static HtfConfirmationResult confirmed(String type, String details) {
        return new HtfConfirmationResult(true, type, details);
    }

    /**
     * Create an unconfirmed result.
     */
    public static HtfConfirmationResult notConfirmed(String details) {
        return new HtfConfirmationResult(false, null, details);
    }

    @Override
    public String toString() {
        if (confirmed) {
            return "HTF:" + confirmationType + " (" + details + ")";
        } else {
            return "No HTF Confirmation (" + details + ")";
        }
    }
}
