package com.regardlessly.mtgweb;

import org.glassfish.tyrus.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the XMage WebSocket gateway.
 *
 * <p>Architecture:
 * <pre>
 *   browser (WS, port 8080)
 *      │
 *      │  JSON over text frames
 *      ▼
 *   GatewayMain (this process)
 *      │
 *      │  one MageWebSocketEndpoint per WS connection
 *      ▼
 *   MageWebSocketEndpoint
 *      │  owns one XMage SessionImpl
 *      │  implements MageClient to receive callbacks
 *      ▼
 *   XMage server (JBoss Remoting, port 17171)
 * </pre>
 *
 * <p>Env vars (with defaults):
 * <ul>
 *   <li>{@code WS_PORT}        — listen port for browser WS clients (default 8080)
 *   <li>{@code XMAGE_HOST}     — XMage server hostname (default "localhost")
 *   <li>{@code XMAGE_PORT}     — XMage server port (default 17171)
 *   <li>{@code GATEWAY_USER}   — username for gateway → XMage auth (default "gateway")
 * </ul>
 */
public final class GatewayMain {
    private static final Logger log = LoggerFactory.getLogger(GatewayMain.class);

    private GatewayMain() {}

    public static void main(String[] args) throws Exception {
        int wsPort = parsePort(System.getenv("WS_PORT"), 8080);
        String xmageHost = envOr("XMAGE_HOST", "localhost");
        int xmagePort = parsePort(System.getenv("XMAGE_PORT"), 17171);

        log.info("mtg-xmage-gateway starting");
        log.info("  WS listen:    0.0.0.0:{}", wsPort);
        log.info("  XMage upstream: {}:{}", xmageHost, xmagePort);

        // Bootstrap: stash config so MageWebSocketEndpoint can read it during
        // its per-connection lifecycle (Tyrus instantiates endpoints reflectively).
        GatewayConfig.set(new GatewayConfig(xmageHost, xmagePort));

        Server wsServer = new Server("0.0.0.0", wsPort, "/", null, MageWebSocketEndpoint.class);
        try {
            wsServer.start();
            log.info("ready — press Ctrl+C to stop");
            Thread.currentThread().join(); // run forever
        } finally {
            wsServer.stop();
        }
    }

    private static int parsePort(String env, int def) {
        if (env == null || env.isBlank()) return def;
        return Integer.parseInt(env);
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
