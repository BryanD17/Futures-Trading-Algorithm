package com.topstep.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.EngineFacade;
import com.topstep.trading.domain.AccountState;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket handler for real-time trading updates.
 * Broadcasts account state every second to all connected clients.
 */
public class TradingWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final EngineFacade engine = EngineFacade.getInstance();

    public TradingWebSocketHandler() {
        // Start broadcasting updates every second
        scheduler.scheduleAtFixedRate(this::broadcastUpdates, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        System.out.println("WebSocket client connected: " + session.getId());

        // Send initial state immediately
        sendUpdate(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        System.out.println("WebSocket client disconnected: " + session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // Handle incoming messages (e.g., subscription requests)
        String payload = message.getPayload();
        if ("ping".equals(payload)) {
            session.sendMessage(new TextMessage("pong"));
        }
    }

    private void broadcastUpdates() {
        if (sessions.isEmpty()) {
            return;
        }

        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    sendUpdate(session);
                } catch (IOException e) {
                    System.err.println("Error sending WebSocket update: " + e.getMessage());
                }
            }
        }
    }

    private void sendUpdate(WebSocketSession session) throws IOException {
        Map<String, Object> update = new HashMap<>();
        update.put("type", "account_update");
        update.put("timestamp", System.currentTimeMillis());

        try {
            AccountState account = engine.getAccountState();

            update.put("balance", account.getCurrentBalance());
            update.put("equity", account.getEquity());
            update.put("realizedPnL", account.getRealizedPnL());
            update.put("unrealizedPnL", account.getUnrealizedPnL());
            update.put("dailyPnL", account.getNetDailyPnl());
            update.put("openPositions", account.getPositions().size());
            update.put("mode", engine.getMode().toString());
            update.put("running", engine.isRunning());
            update.put("paused", engine.isPaused());
            update.put("inGoodStanding", engine.isAccountInGoodStanding());
            update.put("remainingDailyLoss", engine.getRemainingDailyLoss());
            update.put("remainingDrawdown", engine.getRemainingDrawdown());

        } catch (IllegalStateException e) {
            update.put("status", "not_initialized");
        }

        String json = objectMapper.writeValueAsString(update);
        session.sendMessage(new TextMessage(json));
    }

    public void shutdown() {
        scheduler.shutdown();
        for (WebSocketSession session : sessions) {
            try {
                session.close();
            } catch (IOException ignored) {}
        }
    }
}
