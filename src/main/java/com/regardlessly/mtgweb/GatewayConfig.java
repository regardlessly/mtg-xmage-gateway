package com.regardlessly.mtgweb;

/** Process-wide config snapshot. Set once at startup, read from any thread. */
public final class GatewayConfig {
    private static volatile GatewayConfig instance;

    public final String xmageHost;
    public final int xmagePort;

    public GatewayConfig(String xmageHost, int xmagePort) {
        this.xmageHost = xmageHost;
        this.xmagePort = xmagePort;
    }

    public static void set(GatewayConfig c) { instance = c; }
    public static GatewayConfig get() {
        GatewayConfig c = instance;
        if (c == null) throw new IllegalStateException("GatewayConfig not initialized");
        return c;
    }
}
