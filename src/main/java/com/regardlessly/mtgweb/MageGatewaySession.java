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
    /** "observe" (bot vs bot, default) or "human" (P0 is the WS client). */
    private String mode = "observe";
    /** UUID of the human player's seat at the XMage table, when mode=human. */
    private UUID humanPlayerId;
    /** Most-recent prompt callback type — used to route the client's
     *  reply ({@code {choice:N}}) to the right SessionImpl.sendPlayerXxx call. */
    private volatile ClientCallbackMethod pendingPromptMethod;
    /** The data payload of the pending prompt — the structure varies per
     *  callback type. Stored as Object so we can downcast on reply. */
    private volatile Object pendingPromptData;

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
            // The client's choice payload omits a "type" field (it just sends
            // `{"choice": N}` per the legacy Magarena protocol). Recognize
            // that shape regardless of whether type is set.
            if (type.isEmpty() && msg.has("choice")) {
                onClientChoice(msg.path("choice").asInt(-1));
                return;
            }
            switch (type) {
                case "start_game":
                    // Frontend may supply pre-con ids; treat empty/missing as null.
                    JsonNode p = msg.path("player_deck");
                    JsonNode b = msg.path("bot_deck");
                    playerDeckId = (p.isMissingNode() || p.isNull() || p.asText().isEmpty()) ? null : p.asText();
                    botDeckId    = (b.isMissingNode() || b.isNull() || b.asText().isEmpty()) ? null : b.asText();
                    String requestedMode = msg.path("mode").asText("observe");
                    mode = "human".equals(requestedMode) ? "human" : "observe";
                    log.info("start_game received mode={} playerDeck={} botDeck={}", mode, playerDeckId, botDeckId);
                    startGame();
                    break;
                case "choice":
                    onClientChoice(msg.path("choice").asInt(-1));
                    break;
                default:
                    log.debug("ws msg ignored type={}", type);
            }
        } catch (Exception e) {
            log.warn("bad ws msg: {}", e.getMessage());
        }
    }

    /**
     * Spin up the XMage table. Mode determines whether seat P0 is a bot
     * (observe) or a human controlled by this WS connection (human).
     *
     * <p>Kept "startBotVsBot" name as an alias for old call sites — see
     * {@link #startBotVsBot()} just below.</p>
     */
    private void startGame() {
        if ("human".equals(mode)) {
            startHumanVsBot();
        } else {
            startBotVsBot();
        }
    }

    private void startHumanVsBot() {
        if (tableId != null) {
            log.info("game already started, ignoring");
            return;
        }
        for (int i = 0; i < 50 && (xmage == null || !xmage.isServerReady()); i++) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        if (xmage == null || !xmage.isServerReady()) { sendError("xmage not ready"); return; }
        try {
            GameTypeView gameType = xmage.getGameTypes().stream()
                .filter(t -> "Two Player Duel".equals(t.getName()))
                .findFirst().orElse(null);
            if (gameType == null) { sendError("no game type"); return; }

            MatchOptions opts = new MatchOptions("Gateway match", gameType.getName(), true);
            opts.getPlayerTypes().add(PlayerType.HUMAN);
            opts.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
            opts.setDeckType("Constructed - Freeform Unlimited");
            opts.setLimited(false);
            opts.setAttackOption(MultiplayerAttackOption.MULTIPLE);
            opts.setRange(RangeOfInfluence.ALL);
            opts.setWinsNeeded(1);
            opts.setMatchTimeLimit(MatchTimeLimit.MIN__15);

            TableView t = xmage.createTable(roomId, opts);
            tableId = t.getTableId();
            log.info("human table created {}", tableId);

            DeckCardLists deck1 = resolveDeck(playerDeckId, /*isPlayer*/ true);
            DeckCardLists deck2 = resolveDeck(botDeckId, /*isPlayer*/ false);
            // P0: human (this WS client). The session's xmage username
            // becomes the seat name; we forward callbacks back over WS.
            xmage.joinTable(roomId, tableId, "you", PlayerType.HUMAN, 5, deck1, "");
            xmage.joinTable(roomId, tableId, "ai_2", PlayerType.COMPUTER_MAD, 5, deck2, "");
            xmage.startMatch(roomId, tableId);
            log.info("human match started, polling for game id");
            pollForGameId(/*watch=*/false);
        } catch (Exception e) {
            log.error("startHumanVsBot failed", e);
            sendError("start failed: " + e.getMessage());
        }
    }

    private void pollForGameId(final boolean watch) {
        POLLER.scheduleAtFixedRate(new Runnable() {
            int attempts = 0;
            @Override public void run() {
                if (gameId != null || closed.get()) return;
                attempts++;
                if (attempts > 30) { sendError("no game id after 30 attempts"); return; }
                try {
                    TableView tv = xmage.getTable(roomId, tableId).orElse(null);
                    if (tv != null && !tv.getGames().isEmpty()) {
                        gameId = tv.getGames().iterator().next();
                        log.info("game id {}", gameId);
                        if (watch) {
                            xmage.watchGame(gameId);
                        }
                        // In human mode, joining the table already wires the
                        // session as a participant — we just receive callbacks
                        // for P0's seat naturally.
                    }
                } catch (Exception e) {
                    log.warn("poll error: {}", e.getMessage());
                }
            }
        }, 500, 500, TimeUnit.MILLISECONDS);
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
            pollForGameId(/*watch=*/true);
        } catch (Exception e) {
            log.error("startBotVsBot failed", e);
            sendError("start failed: " + e.getMessage());
        }
    }

    /**
     * Browser sent {@code {choice: N}}. We hand off to XMage via whichever
     * sendPlayerXxx call matches the prompt type we last forwarded. The
     * pending prompt is cleared on dispatch — the next XMage callback may
     * set a fresh one.
     */
    private void onClientChoice(int n) {
        if (gameId == null) {
            log.warn("choice {} ignored — no gameId yet", n);
            return;
        }
        final ClientCallbackMethod m = pendingPromptMethod;
        final java.util.List<UUID> uuids = pendingPromptUuids;
        final java.util.List<String> labels = pendingPromptLabels;
        pendingPromptMethod = null;
        pendingPromptData = null;
        pendingPromptUuids = java.util.Collections.emptyList();
        pendingPromptLabels = java.util.Collections.emptyList();
        if (m == null) {
            log.warn("choice {} but no pending prompt", n);
            return;
        }
        UUID picked = (n >= 0 && n < uuids.size()) ? uuids.get(n) : null;
        String label = (n >= 0 && n < labels.size()) ? labels.get(n) : "?";
        log.info("client choice {} ({}) for {} → uuid={}", n, label, m, picked);
        try {
            switch (m) {
                case GAME_ASK:
                    // Option 0 = Yes, anything else = No.
                    xmage.sendPlayerBoolean(gameId, n == 0);
                    break;
                case GAME_CHOOSE_CHOICE:
                    // Choice payload doesn't ship UUIDs — XMage matches the
                    // selection by string. Echo the label back.
                    xmage.sendPlayerString(gameId, label);
                    break;
                case GAME_GET_AMOUNT:
                case GAME_GET_MULTI_AMOUNT:
                    xmage.sendPlayerInteger(gameId, Math.max(0, n));
                    break;
                case GAME_SELECT:
                    if (picked != null) {
                        xmage.sendPlayerUUID(gameId, picked);
                    } else {
                        // Pass priority: XMage's HumanPlayer reads
                        // sendPlayerBoolean(true) as "I'm done with this
                        // priority window." sendPlayerUUID(null) gets
                        // re-prompted instead of advancing.
                        xmage.sendPlayerBoolean(gameId, true);
                    }
                    break;
                default:
                    // Everything else routes via sendPlayerUUID. Null = pass.
                    xmage.sendPlayerUUID(gameId, picked);
            }
        } catch (Exception e) {
            log.warn("choice dispatch failed: {}", e.getMessage());
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
            // Trace EVERY callback in human mode so we can spot the priority
            // request when it lands under a different method than expected.
            if ("human".equals(mode)) {
                log.info("cb method={} dataClass={}", method,
                        data == null ? "null" : data.getClass().getSimpleName());
            }

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

            // In human mode, XMage asks the player for input via specific
            // callback methods. Forward them as a `prompt` JSON so the client
            // can render buttons and reply with `{choice: N}`.
            if ("human".equals(mode) && isPromptCallback(method)) {
                pendingPromptMethod = method;
                pendingPromptData = data;
                // Log payload shape so we can extend the per-type translators
                // as new prompts appear in actual games.
                log.info("prompt callback method={} dataClass={}",
                        method, data == null ? "null" : data.getClass().getName());
                if (data != null) {
                    inspectData(data);
                }
                emitPrompt(method, data);
            }
        } catch (Exception e) {
            log.warn("onCallback error", e);
        }
    }

    private boolean isPromptCallback(ClientCallbackMethod m) {
        if (m == null) return false;
        switch (m) {
            case GAME_TARGET:
            case GAME_CHOOSE_ABILITY:
            case GAME_CHOOSE_PILE:
            case GAME_CHOOSE_CHOICE:
            case GAME_ASK:
            case GAME_SELECT:
            case GAME_PLAY_MANA:
            case GAME_PLAY_XMANA:
            case GAME_GET_AMOUNT:
            case GAME_GET_MULTI_AMOUNT:
                return true;
            default:
                return false;
        }
    }

    /** Holds the resolved option list for the pending prompt — populated by
     *  {@link #emitPrompt} and consumed by {@link #onClientChoice}. */
    private volatile java.util.List<UUID> pendingPromptUuids = java.util.Collections.emptyList();
    private volatile java.util.List<String> pendingPromptLabels = java.util.Collections.emptyList();

    /**
     * Translate an XMage prompt callback into a {@code prompt} JSON message.
     * Per-method extractors pull the option list out of the (varied) data
     * payload via reflection on well-known fields — that lets the gateway
     * compile against the mage-common jar without depending on internal
     * client-side types like {@code TargetView}, {@code Choice}, etc.
     */
    private void emitPrompt(ClientCallbackMethod m, Object data) {
        if (lastView == null) return;
        com.fasterxml.jackson.databind.node.ArrayNode opts = new ObjectMapper().createArrayNode();
        java.util.List<UUID> uuids = new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>();
        String description;
        switch (m) {
            case GAME_ASK:
                description = stringField(data, "message", "Yes or No?");
                addOption(opts, 0, "Yes", labels);
                addOption(opts, 1, "No", labels);
                uuids.add(null); uuids.add(null);
                break;
            case GAME_TARGET: {
                description = stringField(data, "message", "Pick a target");
                java.util.Map<UUID, String> choices = uuidStringMap(data);
                if (choices != null && !choices.isEmpty()) {
                    int i = 0;
                    for (java.util.Map.Entry<UUID, String> e : choices.entrySet()) {
                        addOption(opts, i++, e.getValue(), labels);
                        uuids.add(e.getKey());
                    }
                }
                addOption(opts, opts.size(), "pass", labels);
                uuids.add(null);
                break;
            }
            case GAME_SELECT: {
                // Priority window: the choosable items live on the GameView,
                // not the GameClientMessage's options field. Extract castable
                // cards from the player's hand cross-referenced with the
                // canPlayObjects list.
                description = stringField(data, "message", "Play instants and activated abilities");
                java.util.Map<UUID, String> playable = extractPlayableFromGameView(data);
                if (playable != null && !playable.isEmpty()) {
                    int i = 0;
                    for (java.util.Map.Entry<UUID, String> e : playable.entrySet()) {
                        addOption(opts, i++, e.getValue(), labels);
                        uuids.add(e.getKey());
                    }
                }
                // Use "pass" (lowercase, exact) so the client's findPassOption()
                // picks this up as the dedicated gold Pass button bound to
                // Spacebar — instead of bucketing it into the card-options
                // grid where the hover-preview kicks in.
                addOption(opts, opts.size(), "pass", labels);
                uuids.add(null);
                break;
            }
            case GAME_CHOOSE_ABILITY: {
                description = "Choose an ability";
                java.util.Map<UUID, String> choices = uuidStringMap(data);
                if (choices != null) {
                    int i = 0;
                    for (java.util.Map.Entry<UUID, String> e : choices.entrySet()) {
                        addOption(opts, i++, stripHtml(e.getValue()), labels);
                        uuids.add(e.getKey());
                    }
                }
                addOption(opts, opts.size(), "pass", labels);
                uuids.add(null);
                break;
            }
            case GAME_CHOOSE_CHOICE: {
                description = "Make a choice";
                java.util.List<String> choices = stringList(data, "choices");
                if (choices != null) {
                    for (int i = 0; i < choices.size(); i++) {
                        addOption(opts, i, choices.get(i), labels);
                        uuids.add(null);
                    }
                }
                break;
            }
            case GAME_CHOOSE_PILE:
                description = "Choose a pile";
                addOption(opts, 0, "Pile 1", labels);
                addOption(opts, 1, "Pile 2", labels);
                uuids.add(null); uuids.add(null);
                break;
            case GAME_PLAY_MANA:
            case GAME_PLAY_XMANA: {
                description = stringField(data, "message", "Pay mana");
                java.util.Map<UUID, String> choices = uuidStringMap(data);
                if (choices != null) {
                    int i = 0;
                    for (java.util.Map.Entry<UUID, String> e : choices.entrySet()) {
                        addOption(opts, i++, e.getValue(), labels);
                        uuids.add(e.getKey());
                    }
                }
                addOption(opts, opts.size(), "pass", labels);
                uuids.add(null);
                break;
            }
            case GAME_GET_AMOUNT:
            case GAME_GET_MULTI_AMOUNT:
                description = stringField(data, "message", "Choose an amount");
                // Surface 0..5 as quick picks; the user can also press a digit.
                for (int n = 0; n <= 5; n++) {
                    addOption(opts, n, String.valueOf(n), labels);
                    uuids.add(null);
                }
                break;
            default:
                description = "Engine prompt (" + m.name() + ")";
                addOption(opts, 0, "ok", labels);
                addOption(opts, 1, "pass", labels);
                uuids.add(null); uuids.add(null);
        }
        pendingPromptUuids = uuids;
        pendingPromptLabels = labels;
        wsSend(adapter.prompt(lastView, description,
                activePlayerIndex(lastView), "Mage" + m.name(), opts));
    }

    private static void addOption(com.fasterxml.jackson.databind.node.ArrayNode opts,
                                  int index, String desc, java.util.List<String> labels) {
        com.fasterxml.jackson.databind.node.ObjectNode o = new ObjectMapper().createObjectNode();
        o.put("index", index);
        o.put("desc", desc == null ? "" : desc);
        opts.add(o);
        labels.add(desc == null ? "" : desc);
    }

    /** Pull a string field from a payload via reflection (best-effort). */
    private static String stringField(Object data, String fieldName, String fallback) {
        if (data == null) return fallback;
        try {
            java.lang.reflect.Field f = findField(data.getClass(), fieldName);
            if (f != null) {
                f.setAccessible(true);
                Object v = f.get(data);
                if (v instanceof String) return stripHtml((String) v);
            }
            // Many XMage views expose a getMessage() too.
            java.lang.reflect.Method g = findMethod(data.getClass(), "getMessage");
            if (g != null) {
                Object v = g.invoke(data);
                if (v instanceof String) return stripHtml((String) v);
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<UUID, String> uuidStringMap(Object data) {
        if (data == null) return null;
        // Tried in priority order — XMage's GameClientMessage ships several
        // map fields but "options" / "abilities" carry the choosable items,
        // while "cardsView*" is metadata.
        String[] preferred = {"options", "abilities", "choices", "list"};
        try {
            for (String name : preferred) {
                java.lang.reflect.Field f = findField(data.getClass(), name);
                if (f == null) continue;
                f.setAccessible(true);
                Object v = f.get(data);
                if (v instanceof java.util.Map) {
                    java.util.Map<?, ?> m = (java.util.Map<?, ?>) v;
                    if (!m.isEmpty()) {
                        Object firstKey = m.keySet().iterator().next();
                        Object firstVal = m.values().iterator().next();
                        log.debug("uuidStringMap field={} key={} val={}",
                                name,
                                firstKey == null ? "null" : firstKey.getClass().getSimpleName(),
                                firstVal == null ? "null" : firstVal.getClass().getSimpleName());
                        if (firstKey instanceof UUID) {
                            return (java.util.Map<UUID, String>) m;
                        }
                    }
                }
            }
            // Fallback: targets Collection (XMage's "valid target UUIDs"
            // for GAME_TARGET). Map UUIDs to short labels using the GameView.
            java.lang.reflect.Field tf = findField(data.getClass(), "targets");
            if (tf != null) {
                tf.setAccessible(true);
                Object v = tf.get(data);
                if (v instanceof java.util.Collection) {
                    java.util.LinkedHashMap<UUID, String> out = new java.util.LinkedHashMap<>();
                    for (Object o : (java.util.Collection<?>) v) {
                        if (o instanceof UUID) {
                            out.put((UUID) o, "Target " + (out.size() + 1));
                        }
                    }
                    if (!out.isEmpty()) return out;
                }
            }
            // Last resort: any non-empty UUID-keyed map.
            for (java.lang.reflect.Field f : data.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(data);
                if (v instanceof java.util.Map) {
                    java.util.Map<?, ?> m = (java.util.Map<?, ?>) v;
                    if (!m.isEmpty() && m.keySet().iterator().next() instanceof UUID) {
                        return (java.util.Map<UUID, String>) m;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static java.util.List<String> stringList(Object data, String fieldName) {
        if (data == null) return null;
        try {
            java.lang.reflect.Field f = findField(data.getClass(), fieldName);
            if (f != null) {
                f.setAccessible(true);
                Object v = f.get(data);
                if (v instanceof java.util.Collection) {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (Object o : (java.util.Collection<?>) v) {
                        out.add(o == null ? "" : o.toString());
                    }
                    return out;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Pull the priority player's currently-castable cards/abilities out of
     * the GameClientMessage's embedded GameView. XMage exposes:
     *   - {@code gameView.getMyHand()} → CardsView (LinkedHashMap UUID → CardView)
     *   - {@code gameView.getCanPlayObjects()} → PlayableObjectsList with
     *     {@code getObjects()} returning Map<UUID, PlayableObjectStats>
     * We intersect: any UUID in canPlayObjects that's also in myHand is a
     * castable card; we surface its CardView name as the option label.
     */
    @SuppressWarnings("unchecked")
    private java.util.Map<UUID, String> extractPlayableFromGameView(Object data) {
        try {
            java.lang.reflect.Field gvf = findField(data.getClass(), "gameView");
            if (gvf == null) return null;
            gvf.setAccessible(true);
            Object gv = gvf.get(data);
            if (gv == null) return null;

            // canPlayObjects → PlayableObjectsList → getObjects() → Map<UUID, _>
            java.lang.reflect.Method cpo = findMethod(gv.getClass(), "getCanPlayObjects");
            java.util.Map<UUID, ?> playableMap = java.util.Collections.emptyMap();
            if (cpo != null) {
                Object pol = cpo.invoke(gv);
                if (pol != null) {
                    java.lang.reflect.Method getObjects = findMethod(pol.getClass(), "getObjects");
                    if (getObjects != null) {
                        Object m = getObjects.invoke(pol);
                        if (m instanceof java.util.Map) {
                            playableMap = (java.util.Map<UUID, ?>) m;
                        }
                    }
                }
            }
            if (playableMap.isEmpty()) return null;

            // myHand → CardsView (LinkedHashMap UUID → CardView)
            java.util.Map<UUID, ?> hand = java.util.Collections.emptyMap();
            java.lang.reflect.Method mh = findMethod(gv.getClass(), "getMyHand");
            if (mh != null) {
                Object h = mh.invoke(gv);
                if (h instanceof java.util.Map) {
                    hand = (java.util.Map<UUID, ?>) h;
                }
            }

            java.util.LinkedHashMap<UUID, String> out = new java.util.LinkedHashMap<>();
            for (UUID id : playableMap.keySet()) {
                String name = "Play ability";
                Object cv = hand.get(id);
                if (cv != null) {
                    java.lang.reflect.Method gn = findMethod(cv.getClass(), "getName");
                    if (gn != null) {
                        Object n = gn.invoke(cv);
                        if (n instanceof String && !((String) n).isEmpty()) {
                            name = "Cast " + n;
                        }
                    }
                }
                out.put(id, name);
            }
            return out;
        } catch (Exception e) {
            log.warn("extractPlayableFromGameView failed: {}", e.getMessage());
            return null;
        }
    }

    private static java.lang.reflect.Field findField(Class<?> c, String name) {
        while (c != null) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) return f;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static java.lang.reflect.Method findMethod(Class<?> c, String name) {
        while (c != null) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static String stripHtml(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "").replaceAll("&nbsp;", " ").trim();
    }

    /** Dump the field names / types of a prompt payload — helps figure out
     *  how to extend the extractors above. */
    private void inspectData(Object data) {
        try {
            log.info("  fields of {}:", data.getClass().getSimpleName());
            for (java.lang.reflect.Field f : data.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(data);
                String summary = v == null ? "null" : v.getClass().getSimpleName();
                if (v instanceof String) summary = "\"" + ((String) v).substring(0, Math.min(60, ((String) v).length())) + "\"";
                else if (v instanceof java.util.Collection) summary = "Collection[" + ((java.util.Collection<?>) v).size() + "]";
                else if (v instanceof java.util.Map) {
                    java.util.Map<?,?> m = (java.util.Map<?,?>) v;
                    summary = "Map[" + m.size() + "]";
                    if (!m.isEmpty()) {
                        Object k = m.keySet().iterator().next();
                        Object val = m.values().iterator().next();
                        summary += " <" + (k == null ? "null" : k.getClass().getSimpleName())
                                + "," + (val == null ? "null" : val.getClass().getSimpleName()) + ">"
                                + " sample=" + sampleEntry(k, val);
                    }
                }
                log.info("    {} = {}", f.getName(), summary);
            }
        } catch (Exception e) {
            log.warn("inspectData failed: {}", e.getMessage());
        }
    }

    private static String sampleEntry(Object k, Object v) {
        String ks = k == null ? "null" : k.toString();
        String vs = v == null ? "null" : v.toString();
        if (ks.length() > 36) ks = ks.substring(0, 36) + "...";
        if (vs.length() > 60) vs = vs.substring(0, 60) + "...";
        return "{" + ks + "=" + vs + "}";
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
