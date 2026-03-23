package com.topstep.trading.lifecycle;

/**
 * Risk zones that determine risk sizing multipliers.
 * Zones are determined by equity position relative to profit target and drawdown limits.
 */
public enum RiskZone {

    /**
     * Near profit target (>=85% completion). Minimal risk, only highest quality setups.
     */
    CRUISE(0.3, "Near target - minimal risk", "#4CAF50"),

    /**
     * Good progress (>=50% completion). Slightly reduced risk to protect gains.
     */
    PROTECTION(0.6, "Protecting gains - reduced risk", "#8BC34A"),

    /**
     * Normal operation. Standard risk sizing.
     */
    NORMAL(1.0, "Normal operation - standard risk", "#2196F3"),

    /**
     * Elevated drawdown (>=40% of MLL used). Reduce risk to preserve account.
     */
    CAUTION(0.7, "Elevated drawdown - cautious risk", "#FF9800"),

    /**
     * Critical drawdown (>=60% of MLL used). Minimal risk, survival mode.
     */
    DANGER(0.4, "Critical drawdown - survival mode", "#F44336");

    private final double riskMultiplier;
    private final String description;
    private final String color;

    RiskZone(double riskMultiplier, String description, String color) {
        this.riskMultiplier = riskMultiplier;
        this.description = description;
        this.color = color;
    }

    public double getRiskMultiplier() { return riskMultiplier; }
    public String getDescription() { return description; }
    public String getColor() { return color; }
}
