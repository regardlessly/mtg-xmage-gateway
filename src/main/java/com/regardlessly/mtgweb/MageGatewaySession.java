package com.regardlessly.mtgweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.Session;
import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.constants.MatchTimeLimit;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.match.MatchOptions;
import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.players.PlayerType;
import mage.players.net.UserData;
import mage.remote.Connection;
import mage.remote.SessionImpl;
import mage.utils.MageVersion;
import mage.view.GameClientMessage;
import mage.view.GameTypeView;
import mage.view.GameView;
import mage.view.PlayerView;
import mage.view.TableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns one XMage SessionImpl + drives a single game per WS connection.
 * Translates XMage callbacks into JSON over the WS via MageJsonAdapter.
 *
 * <p>v0.1: bot-vs-bot only. Player-vs-bot prompts come later.
 */
public final class MageGatewaySession implements MageClient {
    private static final Logger log = LoggerFactory.getLogger(MageGatewaySession.class);
    private static final MageVersion VERSION = new MageVersion(MageClient.class);
    private static final ScheduledExecutorService POLLER = Executors.newScheduledThreadPool(2);

    private final Session ws;
    private final MageJsonAdapter adapter = new MageJsonAdapter();
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private SessionImpl xmage;
    private UUID roomId;
    private UUID tableId;
    private UUID gameId;
    private boolean handshakeSent = false;
    private GameView lastView;
    /** Pre-con deck ids supplied by the browser in start_game. Null = use the
     *  built-in 40-Forest+20-Llanowar fallback. */
    private String playerDeckId;
    private String botDeckId;
    /** Resolved deck display names (cached so the handshake can advertise them). */
    private String playerDeckName = "Random Forest deck";
    private String botDeckName = "Random Forest deck";

    public MageGatewaySession(Session ws) {
        this.ws = ws;
    }

    /** Called from WS endpoint onOpen. Connects to XMage and waits for client to send start_game. */
    public void start(String xmageHost, int xmagePort) {
        Connection conn = new Connection();
        conn.setHost(xmageHost);
        conn.setPort(xmagePort);
        // username must match [a-z0-9_], max 14 chars
        conn.setUsername("gw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        conn.setPassword("password");
        conn.setEmail("gw@example.com");
        conn.setUserIdStr(UUID.randomUUID().toString());
        conn.setProxyType(Connection.ProxyType.NONE);
        conn.setUserData(UserData.getDefaultUserDataView());

        xmage = new SessionImpl(this);
        if (!xmage.connectStart(conn)) {
            sendError("xmage connect failed: " + xmage.getLastError());
            close();
            return;
        }
        roomId = xmage.getMainRoomId();
        log.info("gateway session ready ws={} roomId={}", ws.getId(), roomId);
    }

    /** Browser sent a JSON message — route it. */
    public void onClientMessage(String raw) {
        try {
            JsonNode msg = json.readTree(raw);
            String type = msg.path("type").asText("");
            switch (type) {
                case "start_game":
                    // Frontend may supply pre-con ids; treat empty/missing as null.
                    JsonNode p = msg.path("player_deck");
                    JsonNode b = msg.path("bot_deck");
                    playerDeckId = (p.isMissingNode() || p.isNull() || p.asText().isEmpty()) ? null : p.asText();
                    botDeckId    = (b.isMissingNode() || b.isNull() || b.asText().isEmpty()) ? null : b.asText();
                    log.info("start_game received playerDeck={} botDeck={}", playerDeckId, botDeckId);
                    startBotVsBot();
                    break;
                case "choice":
                    // No-op for observe mode. Phase-2 player mode will route here.
                    break;
                default:
                    log.debug("ws msg ignored type={}", type);
            }
        } catch (Exception e) {
            log.warn("bad ws msg: {}", e.getMessage());
        }
    }

    private void startBotVsBot() {
        if (tableId != null) {
            log.info("game already started, ignoring");
            return;
        }
        // SessionImpl may not yet have serverState — block briefly.
        for (int i = 0; i < 50 && (xmage == null || !xmage.isServerReady()); i++) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        if (xmage == null || !xmage.isServerReady()) {
            sendError("xmage not ready");
            return;
        }
        try {
            GameTypeView gameType = xmage.getGameTypes().stream()
                .filter(t -> "Two Player Duel".equals(t.getName()))
                .findFirst().orElse(null);
            if (gameType == null) { sendError("no game type"); return; }

            MatchOptions opts = new MatchOptions("Gateway match", gameType.getName(), true);
            opts.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
            opts.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
            opts.setDeckType("Constructed - Freeform Unlimited");
            opts.setLimited(false);
            opts.setAttackOption(MultiplayerAttackOption.MULTIPLE);
            opts.setRange(RangeOfInfluence.ALL);
            opts.setWinsNeeded(1);
            opts.setMatchTimeLimit(MatchTimeLimit.MIN__15);

            TableView t = xmage.createTable(roomId, opts);
            tableId = t.getTableId();
            log.info("table created {}", tableId);

            DeckCardLists deck1 = resolveDeck(playerDeckId, /*isPlayer*/ true);
            DeckCardLists deck2 = resolveDeck(botDeckId, /*isPlayer*/ false);
            xmage.joinTable(roomId, tableId, "ai_1", PlayerType.COMPUTER_MAD, 5, deck1, "");
            xmage.joinTable(roomId, tableId, "ai_2", PlayerType.COMPUTER_MAD, 5, deck2, "");
            xmage.startMatch(roomId, tableId);
            log.info("match started, polling for game id");

            // Poll for game id, then subscribe as watcher
            POLLER.scheduleAtFixedRate(new Runnable() {
                int attempts = 0;
                @Override public void run() {
                    if (gameId != null || closed.get()) return;
                    attempts++;
                    if (attempts > 30) {
                        sendError("no game id after 30 attempts");
                        return;
                    }
                    try {
                        TableView tv = xmage.getTable(roomId, tableId).orElse(null);
                        if (tv != null && !tv.getGames().isEmpty()) {
                            gameId = tv.getGames().iterator().next();
                            log.info("game id {}, calling watchGame", gameId);
                            xmage.watchGame(gameId);
                        }
                    } catch (Exception e) {
                        log.warn("poll error: {}", e.getMessage());
                    }
                }
            }, 500, 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("startBotVsBot failed", e);
            sendError("start failed: " + e.getMessage());
        }
    }

    /**
     * Pick the deck for one seat. Tries the supplied precon id first; falls
     * back to the bundled 40 Forest + 20 Llanowar Elves deck if the id is
     * null or unresolvable. Side-effect: stashes the resolved display name
     * on the session so the handshake can advertise it.
     */
    private DeckCardLists resolveDeck(String deckId, boolean isPlayerSeat) {
        DeckCardLists d = DeckLoader.build(deckId);
        String name = "Forest/Llanowar (default)";
        if (d != null && deckId != null) {
            // Use the id directly for the moment; the frontend already knows
            // the human-readable name from precon-decks.json so this is just
            // a fallback label.
            name = deckId;
        } else {
            d = makeBasicDeck();
        }
        if (isPlayerSeat) playerDeckName = name; else botDeckName = name;
        return d;
    }

    private DeckCardLists makeBasicDeck() {
        // Minimal 60-card legal-in-Freeform deck. Forests + a few Llanowar Elves
        // to give the AI something attackable. Future: load real precon decks.
        DeckCardLists d = new DeckCardLists();
        List<DeckCardInfo> cards = new ArrayList<>();
        for (int i = 0; i < 40; i++) cards.add(new DeckCardInfo("Forest", "266", "M20"));
        for (int i = 0; i < 20; i++) cards.add(new DeckCardInfo("Llanowar Elves", "180", "M20"));
        d.setCards(cards);
        d.setSideboard(new ArrayList<>());
        return d;
    }

    /** Browser disconnected — tear down. */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try { if (xmage != null) xmage.connectStop(false, false); } catch (Exception ignored) {}
        }
    }

    // ===================== MageClient impl =====================

    @Override public MageVersion getVersion() { return VERSION; }

    @Override public void connected(String message) {
        log.debug("xmage connected: {}", message);
    }

    @Override public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
        log.info("xmage disconnected ws={}", ws.getId());
        wsSend("{\"type\":\"engine_disconnect\"}");
        close();
    }

    @Override public void showMessage(String message) {
        log.debug("xmage message: {}", message);
    }

    @Override public void showError(String message) {
        log.warn("xmage error: {}", message);
        sendError(message);
    }

    @Override public void onNewConnection() {
        log.debug("xmage onNewConnection");
    }

    @Override public void onCallback(ClientCallback cb) {
        if (cb == null || closed.get()) return;
        try {
            cb.decompressData();
            ClientCallbackMethod method = cb.getMethod();
            Object data = cb.getData();

            // Send a handshake once we know the game has started and we have a GameView
            if (data instanceof GameView) {
                GameView gv = (GameView) data;
                lastView = gv;
                if (!handshakeSent) {
                    sendHandshake(gv);
                    handshakeSent = true;
                }
                int activeIdx = activePlayerIndex(gv);
                if (method == ClientCallbackMethod.GAME_OVER) {
                    Integer winner = computeWinner(gv);
                    wsSend(adapter.gameOver(gv, winner));
                    return;
                }
                wsSend(adapter.observation(gv, methodLabel(method), activeIdx, method.name()));
            } else if (data instanceof GameClientMessage) {
                GameClientMessage gcm = (GameClientMessage) data;
                String text = adapter.describeMessage(gcm);
                if (lastView != null && !text.isEmpty()) {
                    wsSend(adapter.observation(lastView, text, activePlayerIndex(lastView), method.name()));
                }
            }
        } catch (Exception e) {
            log.warn("onCallback error", e);
        }
    }

    private void sendHandshake(GameView gv) {
        String name0 = gv.getPlayers().size() > 0 ? gv.getPlayers().get(0).getName() : "ai_1";
        String name1 = gv.getPlayers().size() > 1 ? gv.getPlayers().get(1).getName() : "ai_2";
        wsSend(adapter.handshake(name0, name1, playerDeckName, botDeckName, true));
    }

    private Integer computeWinner(GameView gv) {
        // Player with positive winsThisMatch or non-zero life wins; we just pick the highest-life player.
        int bestIdx = -1;
        int bestLife = Integer.MIN_VALUE;
        for (int i = 0; i < gv.getPlayers().size(); i++) {
            PlayerView pv = gv.getPlayers().get(i);
            if (pv.getLife() > bestLife) { bestLife = pv.getLife(); bestIdx = i; }
        }
        return bestIdx >= 0 ? bestIdx : null;
    }

    private String methodLabel(ClientCallbackMethod m) {
        if (m == null) return "";
        return m.name();
    }

    // ===================== WS plumbing =====================

    private void wsSend(String json) {
        if (closed.get()) return;
        try {
            // getBasicRemote is not thread-safe; sync on the WS session.
            synchronized (ws) {
                ws.getBasicRemote().sendText(json);
            }
        } catch (Exception e) {
            log.warn("ws send failed: {}", e.getMessage());
            close();
        }
    }

    private void sendError(String msg) {
        ObjectMapper m = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode node = m.createObjectNode();
        node.put("type", "engine_error");
        node.put("message", msg);
        wsSend(node.toString());
    }

    private static int activePlayerIndex(GameView gv) {
        for (int i = 0; i < gv.getPlayers().size(); i++) {
            if (gv.getPlayers().get(i).isActive()) return i;
        }
        return 0;
    }
}
