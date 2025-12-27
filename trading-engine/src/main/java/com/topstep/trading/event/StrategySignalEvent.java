package com.topstep.trading.event;

import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.strategy.TradeTier;
import java.util.Objects;

/**
 * Event emitted when the strategy generates a trading signal.
 *
 * Enhanced to include:
 * - Trade tier (quality level)
 * - Dynamic R:R based on tier
 * - Quantity (from volatility-adaptive sizing)
 * - Partial profit targets
 */
public class StrategySignalEvent extends BaseEvent {
    private final SignalType signalType;
    private final String symbol;
    private final OrderSide side;
    private final double entryPrice;
    private final double stopPrice;
    private final double targetPrice;
    private final String reason;

    // Enhanced fields
    private final TradeTier tier;
    private final int quantity;
    private final double riskRewardRatio;
    private final double[][] partialProfitTargets;  // [[R-multiple, % to close], ...]

    /**
     * Original constructor for backward compatibility.
     */
    public StrategySignalEvent(SignalType signalType, String symbol, OrderSide side,
                               double entryPrice, double stopPrice, double targetPrice, String reason) {
        this(signalType, symbol, side, entryPrice, stopPrice, targetPrice, reason,
             TradeTier.TIER_2, 1, 2.0, null);
    }

    /**
     * Enhanced constructor with tier and quantity.
     */
    public StrategySignalEvent(SignalType signalType, String symbol, OrderSide side,
                               double entryPrice, double stopPrice, double targetPrice, String reason,
                               TradeTier tier, int quantity) {
        this(signalType, symbol, side, entryPrice, stopPrice, targetPrice, reason,
             tier, quantity, tier.getRiskRewardRatio(), tier.getPartialProfitTargets());
    }

    /**
     * Full constructor with all enhanced fields.
     */
    public StrategySignalEvent(SignalType signalType, String symbol, OrderSide side,
                               double entryPrice, double stopPrice, double targetPrice, String reason,
                               TradeTier tier, int quantity, double riskRewardRatio,
                               double[][] partialProfitTargets) {
        super(EventType.STRATEGY_SIGNAL);
        this.signalType = Objects.requireNonNull(signalType);
        this.symbol = Objects.requireNonNull(symbol);
        this.side = side;
        this.entryPrice = entryPrice;
        this.stopPrice = stopPrice;
        this.targetPrice = targetPrice;
        this.reason = reason;
        this.tier = tier != null ? tier : TradeTier.TIER_2;
        this.quantity = quantity > 0 ? quantity : 1;
        this.riskRewardRatio = riskRewardRatio > 0 ? riskRewardRatio : tier.getRiskRewardRatio();
        this.partialProfitTargets = partialProfitTargets != null ? partialProfitTargets : tier.getPartialProfitTargets();
    }

    // Original getters
    public SignalType getSignalType() { return signalType; }
    public String getSymbol() { return symbol; }
    public OrderSide getSide() { return side; }
    public double getEntryPrice() { return entryPrice; }
    public double getStopPrice() { return stopPrice; }
    public double getTargetPrice() { return targetPrice; }
    public String getReason() { return reason; }

    // Enhanced getters
    public TradeTier getTier() { return tier; }
    public int getQuantity() { return quantity; }
    public double getRiskRewardRatio() { return riskRewardRatio; }
    public double[][] getPartialProfitTargets() { return partialProfitTargets; }

    /**
     * Get the risk distance (entry to stop).
     */
    public double getRiskDistance() {
        if (side == OrderSide.BUY) {
            return entryPrice - stopPrice;
        } else {
            return stopPrice - entryPrice;
        }
    }

    /**
     * Get the reward distance (entry to target).
     */
    public double getRewardDistance() {
        if (side == OrderSide.BUY) {
            return targetPrice - entryPrice;
        } else {
            return entryPrice - targetPrice;
        }
    }

    /**
     * Calculate actual R:R from prices.
     */
    public double getActualRR() {
        double risk = getRiskDistance();
        if (risk <= 0) return 0;
        return getRewardDistance() / risk;
    }

    /**
     * Get price at a specific R-multiple.
     */
    public double getPriceAtRMultiple(double rMultiple) {
        double risk = getRiskDistance();
        if (side == OrderSide.BUY) {
            return entryPrice + (risk * rMultiple);
        } else {
            return entryPrice - (risk * rMultiple);
        }
    }

    /**
     * Get breakeven price (entry + some buffer).
     */
    public double getBreakevenPrice() {
        // Add small buffer for commissions/slippage
        double buffer = getRiskDistance() * 0.1;
        if (side == OrderSide.BUY) {
            return entryPrice + buffer;
        } else {
            return entryPrice - buffer;
        }
    }

    @Override
    public String toString() {
        return String.format("StrategySignalEvent{type=%s, symbol='%s', side=%s, entry=%.2f, stop=%.2f, target=%.2f, tier=%s, qty=%d, R:R=1:%.1f, reason='%s'}",
                signalType, symbol, side, entryPrice, stopPrice, targetPrice, tier, quantity, riskRewardRatio, reason);
    }

    public enum SignalType {
        LONG_ENTRY,
        SHORT_ENTRY,
        SCALE_IN,
        SCALE_OUT,
        EXIT,
        PARTIAL_EXIT,    // For partial profit taking
        MOVE_STOP        // For trailing stops
    }
}
