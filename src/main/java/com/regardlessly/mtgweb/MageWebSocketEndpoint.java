package com.regardlessly.mtgweb;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * One endpoint per WS connection. Owns a {@link MageGatewaySession} which
 * holds the XMage SessionImpl and game state.
 */
@ServerEndpoint("/")
public class MageWebSocketEndpoint {
    private static final Logger log = LoggerFactory.getLogger(MageWebSocketEndpoint.class);
    private static final Map<String, MageGatewaySession> SESSIONS = new ConcurrentHashMap<>();
    // XMage connect can take 1-2 seconds; do it off the WS open thread.
    private static final ExecutorService BOOT = Executors.newCachedThreadPool();

    @OnOpen
    public void onOpen(Session ws) {
        log.info("ws open id={}", ws.getId());
        MageGatewaySession gs = new MageGatewaySession(ws);
        SESSIONS.put(ws.getId(), gs);
        GatewayConfig cfg = GatewayConfig.get();
        BOOT.submit(() -> {
            try {
                gs.start(cfg.xmageHost, cfg.xmagePort);
            } catch (Exception e) {
                log.error("session start failed", e);
            }
        });
    }

    @OnMessage
    public void onMessage(String message, Session ws) {
        MageGatewaySession gs = SESSIONS.get(ws.getId());
        if (gs != null) gs.onClientMessage(message);
    }

    @OnClose
    public void onClose(Session ws) {
        log.info("ws close id={}", ws.getId());
        MageGatewaySession gs = SESSIONS.remove(ws.getId());
        if (gs != null) gs.close();
    }

    @OnError
    public void onError(Session ws, Throwable t) {
        log.warn("ws error id={}: {}", ws.getId(), t.toString());
    }
}
