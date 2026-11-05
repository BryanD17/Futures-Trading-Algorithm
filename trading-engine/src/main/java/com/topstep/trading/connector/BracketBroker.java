package com.topstep.trading.connector;

import com.topstep.trading.domain.OrderSide;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * V6 Agent 05.1: the broker surface the {@code BracketOrderManager} needs,
 * extracted from the (removed) TopstepX connector so the bracket safety logic
 * (stop never lost, adoption, flat sweep) stays compiled and unit-tested
 * against a connector-agnostic interface.
 *
 * <p>No production class implements this in V6: live trading runs on
 * TradingView + relay (RUNBOOK_TRADINGVIEW.md). The legacy TopstepX
 * implementation lives in {@code trading-engine/legacy/} (not compiled).
 */
public interface BracketBroker extends TradingConnector {

    /** Broker order type code: Stop (market). (ProjectX value, kept for the legacy wire.) */
    int ORDER_TYPE_STOP = 4;
    /** Broker order type code: Limit. */
    int ORDER_TYPE_LIMIT = 1;

    String submitStopOrder(String symbol, OrderSide side, int quantity, double stopPrice,
                           OrderListener listener) throws Exception;

    String submitTakeProfitOrder(String symbol, OrderSide side, int quantity, double limitPrice,
                                 OrderListener listener) throws Exception;

    /** Start fill tracking for an order this process did not place (adopted). */
    void trackExistingOrder(String orderId, String symbol, int quantity, OrderSide side,
                            double price, OrderListener listener);

    /**
     * Positions + working orders straight from the broker. Throws when the
     * query fails — callers must never infer broker state from a failure.
     */
    BrokerSnapshot fetchBrokerSnapshot() throws Exception;

    /**
     * A cancel was refused because the order is no longer open at the broker
     * (already cancelled or filled). It is GONE, not "still working".
     */
    class OrderNotWorkingException extends IOException {
        private final String orderId;
        private final int code;
        public OrderNotWorkingException(String orderId, int code, String brokerMessage) {
            super("Order " + orderId + " is not open at the broker (cancel code " + code
                + (brokerMessage != null && !"null".equals(brokerMessage) ? ", " + brokerMessage : "")
                + ") — already cancelled or filled");
            this.orderId = orderId;
            this.code = code;
        }
        public String getOrderId() { return orderId; }
        public int getCode() { return code; }
    }

    /** One working order as the BROKER reports it. */
    final class BrokerOrder {
        public final String orderId;
        public final String symbol;
        public final String contractId;
        public final int type;           // 1=Limit 2=Market 4=Stop ...
        public final OrderSide side;
        public final int size;
        public final double stopPrice;   // NaN when absent
        public final double limitPrice;  // NaN when absent

        public BrokerOrder(String orderId, String symbol, String contractId, int type,
                           OrderSide side, int size, double stopPrice, double limitPrice) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.contractId = contractId;
            this.type = type;
            this.side = side;
            this.size = size;
            this.stopPrice = stopPrice;
            this.limitPrice = limitPrice;
        }

        public boolean isStop() { return type == ORDER_TYPE_STOP; }

        @Override public String toString() {
            String kind = isStop() ? "STOP" : (type == ORDER_TYPE_LIMIT ? "LIMIT" : "type" + type);
            return "#" + orderId + " " + side + " " + kind + " " + size + " @ "
                + (isStop() ? stopPrice : limitPrice);
        }
    }

    /** One open position as the BROKER reports it. */
    final class BrokerPosition {
        public final String symbol;
        public final String contractId;
        public final boolean isLong;
        public final int size;           // always > 0
        public final double averagePrice;

        public BrokerPosition(String symbol, String contractId, boolean isLong, int size, double averagePrice) {
            this.symbol = symbol;
            this.contractId = contractId;
            this.isLong = isLong;
            this.size = size;
            this.averagePrice = averagePrice;
        }

        @Override public String toString() {
            return (isLong ? "LONG " : "SHORT ") + size + " " + symbol + " @ " + averagePrice;
        }
    }

    /** Broker positions + working orders, fetched together. */
    final class BrokerSnapshot {
        public final List<BrokerPosition> positions;
        public final List<BrokerOrder> openOrders;

        public BrokerSnapshot(List<BrokerPosition> positions, List<BrokerOrder> openOrders) {
            this.positions = List.copyOf(positions);
            this.openOrders = List.copyOf(openOrders);
        }

        public BrokerPosition position(String symbol) {
            for (BrokerPosition p : positions) {
                if (p.symbol.equalsIgnoreCase(symbol)) return p;
            }
            return null;
        }

        public List<BrokerOrder> ordersFor(String symbol) {
            List<BrokerOrder> out = new ArrayList<>();
            for (BrokerOrder o : openOrders) {
                if (o.symbol.equalsIgnoreCase(symbol)) out.add(o);
            }
            return out;
        }

        /** Working STOP orders for {@code symbol} on {@code exitSide}. */
        public List<BrokerOrder> stopsFor(String symbol, OrderSide exitSide) {
            List<BrokerOrder> out = new ArrayList<>();
            for (BrokerOrder o : ordersFor(symbol)) {
                if (o.isStop() && o.side == exitSide) out.add(o);
            }
            return out;
        }
    }
}
