package com.regardlessly.mtgweb;

import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.players.net.UserData;
import mage.remote.Connection;
import mage.remote.SessionImpl;
import mage.utils.MageVersion;

import java.util.UUID;

/**
 * Minimal probe — connect to XMage, print every callback that arrives.
 * Run with: java -cp target/mtg-xmage-gateway.jar com.regardlessly.mtgweb.Probe [host] [port]
 */
public class Probe {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 17171;
        String username = "probe-" + UUID.randomUUID().toString().substring(0, 8);

        System.out.println("[probe] connecting to " + host + ":" + port + " as " + username);

        Connection conn = new Connection();
        conn.setHost(host);
        conn.setPort(port);
        conn.setUsername(username);
        conn.setPassword("password");
        conn.setEmail("probe@example.com");
        conn.setUserIdStr(UUID.randomUUID().toString());
        conn.setProxyType(Connection.ProxyType.NONE);
        // Default user data — XMage requires *some* UserData
        conn.setUserData(UserData.getDefaultUserDataView());

        MageClient client = new ProbeClient();
        SessionImpl session = new SessionImpl(client);

        boolean ok = session.connectStart(conn);
        System.out.println("[probe] connectStart returned: " + ok);
        if (!ok) {
            System.out.println("[probe] connect failed; last error: " + session.getLastError());
            return;
        }

        System.out.println("[probe] connected — isConnected=" + session.isConnected());

        // Stay alive 60s to receive any callbacks
        Thread.sleep(60_000);
        System.out.println("[probe] disconnecting");
        session.connectStop(false, false);
    }

    /** Minimal MageClient — logs everything, does nothing. */
    static class ProbeClient implements MageClient {
        // Read version from mage-common's own MageClient class, matching XMage's tests.
        private static final MageVersion VERSION = new MageVersion(MageClient.class);
        @Override public MageVersion getVersion() { return VERSION; }

        @Override public void connected(String message) {
            System.out.println("[client.connected] " + message);
        }

        @Override public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
            System.out.println("[client.disconnected] askReconnect=" + askToReconnect + " keepSession=" + keepMySessionActive);
        }

        @Override public void showMessage(String message) {
            System.out.println("[client.showMessage] " + message);
        }

        @Override public void showError(String message) {
            System.out.println("[client.showError] " + message);
        }

        // CallbackClient methods
        @Override public void onCallback(ClientCallback callback) {
            System.out.println("[callback] method=" + callback.getMethod()
                + " data=" + (callback.getData() == null ? "null" : callback.getData().getClass().getSimpleName()));
        }

        @Override public void onNewConnection() {
            System.out.println("[client.onNewConnection]");
        }
    }
}
