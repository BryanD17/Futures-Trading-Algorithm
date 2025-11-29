package com.topstep.trading.event;

/**
 * Event emitted when a risk limit is breached.
 */
public class RiskBreachEvent extends BaseEvent {
    private final String breachType;
    private final String message;
    private final double currentValue;
    private final double limitValue;

    public RiskBreachEvent(EventType type, String breachType, String message,
                          double currentValue, double limitValue) {
        super(type);
        this.breachType = breachType;
        this.message = message;
        this.currentValue = currentValue;
        this.limitValue = limitValue;
    }

    public String getBreachType() { return breachType; }
    public String getMessage() { return message; }
    public double getCurrentValue() { return currentValue; }
    public double getLimitValue() { return limitValue; }

    @Override
    public String toString() {
        return String.format("RiskBreachEvent{type=%s, breach='%s', message='%s', current=%.2f, limit=%.2f}",
                getType(), breachType, message, currentValue, limitValue);
    }
}
