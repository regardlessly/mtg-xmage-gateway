package com.regardlessly.mtgweb;

import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.constants.MatchTimeLimit;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.match.MatchOptions;
import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.players.PlayerType;
import mage.players.net.UserData;
import mage.remote.Connection;
import mage.remote.SessionImpl;
import mage.utils.MageVersion;
import mage.view.GameTypeView;
import mage.view.TableView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Sets up a real AI-vs-AI game on the XMage server and prints every
 * callback that arrives. Proves end-to-end engine drive.
 */
public class BotVsBotProbe {

    private static final String GAME_MODE = "Two Player Duel";
    private static final String DECK_TYPE = "Constructed - Freeform Unlimited";
    private static final String DECK_COLORS = "GR";

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 17171;
        // XMage server enforces username pattern [^a-z0-9_] (rejects), max 14 chars
        String username = "bot_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        System.out.println("[probe] connect " + host + ":" + port + " as " + username);

        Connection conn = new Connection();
        conn.setHost(host);
        conn.setPort(port);
        conn.setUsername(username);
        conn.setPassword("password");
        conn.setEmail("probe@example.com");
        conn.setUserIdStr(UUID.randomUUID().toString());
        conn.setProxyType(Connection.ProxyType.NONE);
        conn.setUserData(UserData.getDefaultUserDataView());

        SessionImpl session = new SessionImpl(new BotClient());
        if (!session.connectStart(conn)) {
            System.err.println("[probe] connect failed: " + session.getLastError());
            return;
        }
        System.out.println("[probe] connected — isConnected=" + session.isConnected());

        UUID roomId = session.getMainRoomId();
        System.out.println("[probe] main room id: " + roomId);

        // Find game type
        GameTypeView gameType = session.getGameTypes().stream()
            .filter(t -> GAME_MODE.equals(t.getName()))
            .findFirst().orElseThrow(() -> new RuntimeException("game mode not found: " + GAME_MODE));
        System.out.println("[probe] game type: " + gameType.getName());

        // Build match options
        MatchOptions opts = new MatchOptions("Bot probe match", gameType.getName(), true);
        opts.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
        opts.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
        if (!Arrays.asList(session.getDeckTypes()).contains(DECK_TYPE)) {
            throw new RuntimeException("deck type not on server: " + DECK_TYPE);
        }
        opts.setDeckType(DECK_TYPE);
        opts.setLimited(false);
        opts.setAttackOption(MultiplayerAttackOption.MULTIPLE);
        opts.setRange(RangeOfInfluence.ALL);
        opts.setWinsNeeded(1);
        opts.setMatchTimeLimit(MatchTimeLimit.MIN__15);

        TableView table = session.createTable(roomId, opts);
        UUID tableId = table.getTableId();
        System.out.println("[probe] table created: " + tableId);

        // Hand-roll a minimal Commander-legal deck: 99 cards + 1 commander.
        // Use only basic lands + Sol Ring (which IS in XMage) — enough to start a game.
        DeckCardLists deck1 = makeBasicDeck();
        DeckCardLists deck2 = makeBasicDeck();
        System.out.println("[probe] decks built: " + deck1.getCards().size() + " / " + deck2.getCards().size() + " cards");

        if (!session.joinTable(roomId, tableId, "ai_1", PlayerType.COMPUTER_MAD, 5, deck1, "")) {
            System.err.println("[probe] joinTable ai_1 failed: " + session.getLastError());
            return;
        }
        if (!session.joinTable(roomId, tableId, "ai_2", PlayerType.COMPUTER_MAD, 5, deck2, "")) {
            System.err.println("[probe] joinTable ai_2 failed: " + session.getLastError());
            return;
        }
        System.out.println("[probe] both AIs joined");

        if (!session.startMatch(roomId, tableId)) {
            System.err.println("[probe] startMatch failed: " + session.getLastError());
            return;
        }
        System.out.println("[probe] match started — polling for game id");

        // Poll table state until a game is running, then watch it.
        UUID gameId = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            Thread.sleep(500);
            TableView tv = session.getTable(roomId, tableId).orElse(null);
            if (tv != null && !tv.getGames().isEmpty()) {
                gameId = tv.getGames().iterator().next();
                System.out.println("[probe] found game id: " + gameId + " state=" + tv.getTableState());
                break;
            }
        }
        if (gameId == null) {
            System.err.println("[probe] no game id found");
            return;
        }
        if (!session.watchGame(gameId)) {
            System.err.println("[probe] watchGame failed: " + session.getLastError());
            return;
        }
        System.out.println("[probe] watching game — listening for callbacks");

        Thread.sleep(120_000);
        System.out.println("[probe] done — closing");
        session.connectStop(false, false);
    }

    private static DeckCardLists makeBasicDeck() {
        // Constructed Freeform Unlimited allows any 60+ card deck with no
        // copy limit. Stack 60 Forests — simplest legal deck possible.
        DeckCardLists d = new DeckCardLists();
        List<DeckCardInfo> cards = new ArrayList<>();
        for (int i = 0; i < 60; i++) cards.add(new DeckCardInfo("Forest", "266", "M20"));
        d.setCards(cards);
        d.setSideboard(new ArrayList<>());
        return d;
    }

    static class BotClient implements MageClient {
        private static final MageVersion VERSION = new MageVersion(MageClient.class);
        @Override public MageVersion getVersion() { return VERSION; }
        @Override public void connected(String message) { System.out.println("[client.connected] " + message); }
        @Override public void disconnected(boolean ask, boolean keep) { System.out.println("[client.disconnected] askRecon=" + ask); }
        @Override public void showMessage(String m) { System.out.println("[client.showMessage] " + m); }
        @Override public void showError(String m) { System.out.println("[client.showError] " + m); }
        @Override public void onNewConnection() { System.out.println("[client.onNewConnection]"); }
        @Override public void onCallback(ClientCallback cb) {
            try {
                cb.decompressData();
                Object data = cb.getData();
                String dataKind = data == null ? "null" : data.getClass().getSimpleName();
                String preview = data == null ? "" : "  " + String.valueOf(data).substring(0, Math.min(200, String.valueOf(data).length()));
                System.out.println("[cb] " + cb.getMethod() + " data=" + dataKind + preview);
            } catch (Exception e) {
                System.out.println("[cb] " + cb.getMethod() + " ERR " + e.getMessage());
            }
        }
    }
}
