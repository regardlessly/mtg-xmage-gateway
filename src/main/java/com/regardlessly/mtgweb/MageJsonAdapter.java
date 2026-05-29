package com.regardlessly.mtgweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mage.constants.PhaseStep;
import mage.constants.TurnPhase;
import mage.view.GameClientMessage;
import mage.view.GameView;
import mage.view.PermanentView;
import mage.view.PlayerView;

import java.util.Map;

/**
 * Translates XMage's GameView / GameClientMessage callback payloads into
 * the existing browser protocol shape (which originated with Magarena).
 *
 * <p>Goal: the existing TypeScript {@code engine/store.ts} {@code _ingest}
 * function shouldn't notice the difference. So we emit:
 * <ul>
 *   <li>{@code {type:"handshake", players:[{index, name, deck}], observe, human_player}}
 *       — once, on session start
 *   <li>{@code {type:"observation", event:{description, choice_type, player},
 *       state:{turn, phase, step, turn_player, stack_size, players:[…]}}}
 *       — per GameView update (bot-vs-bot mode)
 *   <li>{@code {type:"game_over", winner, finished, state:{…}}}
 *       — on game end
 * </ul>
 *
 * <p>Prompts (for human-vs-bot mode) come later — XMage's prompt mechanism
 * is via {@code GAME_ASK} callbacks with separate UI hint structures.
 */
public final class MageJsonAdapter {
    private final ObjectMapper json = new ObjectMapper();

    public String handshake(String youName, String oppName, String youDeck, String oppDeck, boolean observeMode) {
        ObjectNode m = json.createObjectNode();
        m.put("type", "handshake");
        m.put("observe", observeMode);
        // In human mode the WS client always joins as "you" — find which slot
        // that landed on (XMage seat order isn't deterministic) so the client
        // knows which index it controls.
        int humanIdx = observeMode ? -1 :
            ("you".equals(youName) ? 0 : "you".equals(oppName) ? 1 : 0);
        m.put("human_player", humanIdx);
        ArrayNode players = m.putArray("players");
        ObjectNode p0 = players.addObject();
        p0.put("index", 0);
        p0.put("name", youName);
        p0.put("deck", youDeck);
        ObjectNode p1 = players.addObject();
        p1.put("index", 1);
        p1.put("name", oppName);
        p1.put("deck", oppDeck);
        return m.toString();
    }

    /**
     * Build an observation message from a GameView snapshot.
     * @param description short event text for the log (e.g. "ai_1 played a card")
     */
    public String observation(GameView gv, String description, int eventPlayer, String choiceType) {
        ObjectNode m = json.createObjectNode();
        m.put("type", "observation");
        ObjectNode event = m.putObject("event");
        event.put("player", eventPlayer);
        event.put("source", "");
        event.put("description", description == null ? "" : description);
        event.put("choice_type", choiceType == null ? "" : choiceType);
        m.set("state", snapshot(gv));
        return m.toString();
    }

    public String prompt(GameView gv, String description, int eventPlayer, String choiceType, ArrayNode options) {
        ObjectNode m = json.createObjectNode();
        m.put("type", "prompt");
        ObjectNode event = m.putObject("event");
        event.put("player", eventPlayer);
        event.put("source", "");
        event.put("description", description == null ? "" : description);
        event.put("choice_type", choiceType == null ? "" : choiceType);
        m.set("state", snapshot(gv));
        m.set("options", options);
        return m.toString();
    }

    public String gameOver(GameView gv, Integer winnerIndex) {
        ObjectNode m = json.createObjectNode();
        m.put("type", "game_over");
        m.put("finished", true);
        if (winnerIndex != null) m.put("winner", winnerIndex);
        m.set("state", snapshot(gv));
        return m.toString();
    }

    /** Map a GameView to our Snapshot JSON shape. */
    public ObjectNode snapshot(GameView gv) {
        ObjectNode s = json.createObjectNode();
        s.put("turn", gv.getTurn());
        TurnPhase phase = gv.getPhase();
        s.put("phase", phase == null ? null : phase.name());
        PhaseStep step = gv.getStep();
        s.put("step", step == null ? null : step.name());
        s.put("stack_size", gv.getStack() == null ? 0 : gv.getStack().size());
        // Stack items: emit each spell/ability name so the UI can show what's
        // about to resolve. CardsView is a Map<UUID, CardView> — iterate
        // values and reflectively pull getName().
        try {
            Object stack = gv.getStack();
            if (stack instanceof java.util.Map && !((java.util.Map<?, ?>) stack).isEmpty()) {
                ArrayNode arr = s.putArray("stack");
                for (Object cv : ((java.util.Map<?, ?>) stack).values()) {
                    if (cv == null) continue;
                    try {
                        Object n = cv.getClass().getMethod("getName").invoke(cv);
                        if (n instanceof String) arr.add((String) n);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        // Combat groups — for each attacking creature, build a UUID→
        // CombatGroupView lookup so we can stamp "attacking" + "blocking"
        // flags onto the battlefield entries below. Without this the UI has
        // no way to tell which of the opponent's creatures are attacking.
        java.util.Map<java.util.UUID, ObjectNode> combatByCreatureId = new java.util.HashMap<>();
        try {
            java.util.List<?> combat = gv.getCombat();
            if (combat != null) {
                for (Object g : combat) {
                    // Reflectively pull attackers/blockers without compile-coupling.
                    Object atks = g.getClass().getMethod("getAttackers").invoke(g);
                    Object blks = g.getClass().getMethod("getBlockers").invoke(g);
                    String defenderName = null;
                    try {
                        Object dn = g.getClass().getMethod("getDefenderName").invoke(g);
                        if (dn instanceof String) defenderName = (String) dn;
                    } catch (Exception ignored) {}
                    java.util.List<String> attackerNames = new java.util.ArrayList<>();
                    if (atks instanceof java.util.Map) {
                        for (Object cv : ((java.util.Map<?, ?>) atks).values()) {
                            try {
                                Object n = cv.getClass().getMethod("getName").invoke(cv);
                                if (n instanceof String) attackerNames.add((String) n);
                            } catch (Exception ignored) {}
                        }
                    }
                    if (atks instanceof java.util.Map) {
                        for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) atks).entrySet()) {
                            if (!(e.getKey() instanceof java.util.UUID)) continue;
                            ObjectNode flags = json.createObjectNode();
                            flags.put("attacking", true);
                            if (defenderName != null) flags.put("defending", defenderName);
                            combatByCreatureId.put((java.util.UUID) e.getKey(), flags);
                        }
                    }
                    if (blks instanceof java.util.Map) {
                        for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) blks).entrySet()) {
                            if (!(e.getKey() instanceof java.util.UUID)) continue;
                            ObjectNode flags = json.createObjectNode();
                            flags.put("blocking", true);
                            if (!attackerNames.isEmpty()) {
                                ArrayNode arr = flags.putArray("blocks");
                                for (String n : attackerNames) arr.add(n);
                            }
                            combatByCreatureId.put((java.util.UUID) e.getKey(), flags);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Combat info is best-effort — never let it break snapshotting.
        }

        // turn_player: index of whichever player is active.
        // Ensure "you" always appears at JSON index 0 in human mode — the
        // client assumes that mapping; we reorder XMage's seat list to honor
        // it instead of teaching every consumer about human_player.
        ArrayNode players = s.putArray("players");
        java.util.List<PlayerView> seats = new java.util.ArrayList<>(gv.getPlayers());
        if (seats.size() == 2 && "you".equals(seats.get(1).getName())) {
            java.util.Collections.swap(seats, 0, 1);
        }
        int idx = 0;
        int activeIdx = 0;
        for (PlayerView pv : seats) {
            ObjectNode po = players.addObject();
            po.put("index", idx);
            po.put("life", pv.getLife());
            po.put("library_size", pv.getLibraryCount());
            po.put("graveyard_size", pv.getGraveyard() == null ? 0 : pv.getGraveyard().size());
            // Per-player exile size — let the UI show an Exile pill alongside
            // GY for cards that have left the game (Path to Exile, etc.).
            try {
                Object ex = pv.getClass().getMethod("getExile").invoke(pv);
                if (ex instanceof java.util.Map) {
                    po.put("exile_size", ((java.util.Map<?, ?>) ex).size());
                }
            } catch (Exception ignored) {}
            po.put("hand_size", pv.getHandCount());
            // The human player's own hand IS exposed via gameView.getMyHand()
            // (XMage delivers it to the seat-holding session). In observe
            // mode there's no "me", so myHand is empty for both players.
            // We populate the hand field only for the seat the WS client
            // actually controls — detect by name ("you") since the seat index
            // may be 0 or 1 depending on XMage's join order.
            if ("you".equals(pv.getName())) {
                ArrayNode hand = po.putArray("hand");
                try {
                    java.util.Map<?, ?> myHand = gv.getMyHand();
                    if (myHand != null) {
                        for (Object cv : myHand.values()) {
                            // CardView.getName() — reflect to avoid pulling
                            // every view class onto our compile classpath.
                            try {
                                java.lang.reflect.Method gn =
                                    cv.getClass().getMethod("getName");
                                Object n = gn.invoke(cv);
                                if (n instanceof String) {
                                    hand.add((String) n);
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }
            ArrayNode bf = po.putArray("battlefield");
            Map<?, PermanentView> battlefield = pv.getBattlefield();
            if (battlefield != null) {
                for (Map.Entry<?, PermanentView> bfe : battlefield.entrySet()) {
                    PermanentView perm = bfe.getValue();
                    ObjectNode po2 = bf.addObject();
                    po2.put("name", perm.getName());
                    po2.put("tapped", perm.isTapped());
                    // Stamp combat flags so the UI can ring attackers red &
                    // blockers blue without having to interpret the prompt.
                    try {
                        java.util.UUID id = (java.util.UUID) bfe.getKey();
                        ObjectNode flags = combatByCreatureId.get(id);
                        if (flags != null) {
                            if (flags.has("attacking")) po2.put("attacking", true);
                            if (flags.has("blocking")) po2.put("blocking", true);
                            if (flags.has("defending")) po2.put("defending", flags.get("defending").asText());
                            if (flags.has("blocks")) po2.set("blocks", flags.get("blocks"));
                        }
                    } catch (Exception ignored) {}
                    // Only emit P/T when the permanent is actually a creature.
                    // XMage returns the string "0" rather than null/empty for
                    // non-creature lands and artifacts, so without this gate
                    // every land would render as "0/0" in the UI. We probe via
                    // reflection on the CardType list to avoid hard-coupling to
                    // a specific XMage view-class layout.
                    boolean isCreature = false;
                    try {
                        Object types = perm.getClass().getMethod("getCardTypes").invoke(perm);
                        if (types != null) {
                            isCreature = types.toString().toUpperCase().contains("CREATURE");
                        }
                    } catch (Exception ignored) {
                        // Fall back to a rules-text probe — typeLine in many
                        // XMage versions is exposed via getRules().get(0).
                        try {
                            Object rules = perm.getClass().getMethod("getRules").invoke(perm);
                            if (rules != null) {
                                isCreature = rules.toString().toLowerCase().contains("creature");
                            }
                        } catch (Exception ignored2) {}
                    }
                    if (isCreature) {
                        String p = perm.getPower();
                        String t = perm.getToughness();
                        if (p != null && !p.isEmpty()) {
                            try { po2.put("power", Integer.parseInt(p)); } catch (Exception ignore) {}
                        }
                        if (t != null && !t.isEmpty()) {
                            try { po2.put("toughness", Integer.parseInt(t)); } catch (Exception ignore) {}
                        }
                    }
                    // Loyalty (planeswalkers) + defense (battles) + counters
                    // — best-effort reflective extraction so the UI can show
                    // them next to creature P/T.
                    try {
                        Object loy = perm.getClass().getMethod("getLoyalty").invoke(perm);
                        if (loy instanceof String && !((String) loy).isEmpty() && !"0".equals(loy)) {
                            try { po2.put("loyalty", Integer.parseInt((String) loy)); } catch (Exception ignore) {}
                        }
                    } catch (Exception ignored) {}
                    try {
                        Object def = perm.getClass().getMethod("getDefense").invoke(perm);
                        if (def instanceof String && !((String) def).isEmpty() && !"0".equals(def)) {
                            try { po2.put("defense", Integer.parseInt((String) def)); } catch (Exception ignore) {}
                        }
                    } catch (Exception ignored) {}
                    try {
                        Object counters = perm.getClass().getMethod("getCounters").invoke(perm);
                        if (counters instanceof java.util.List && !((java.util.List<?>) counters).isEmpty()) {
                            ArrayNode cArr = po2.putArray("counters");
                            for (Object cv : (java.util.List<?>) counters) {
                                if (cv == null) continue;
                                try {
                                    Object cName = cv.getClass().getMethod("getName").invoke(cv);
                                    Object cCount = cv.getClass().getMethod("getCount").invoke(cv);
                                    if (cName instanceof String && cCount instanceof Integer) {
                                        ObjectNode cn = cArr.addObject();
                                        cn.put("type", (String) cName);
                                        cn.put("count", (Integer) cCount);
                                    }
                                } catch (Exception ignored2) {}
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (pv.isActive()) activeIdx = idx;
            idx++;
        }
        s.put("turn_player", activeIdx);
        return s;
    }

    public String describeMessage(GameClientMessage gcm) {
        if (gcm == null) return "";
        String msg = gcm.getMessage();
        return msg == null ? "" : stripHtml(msg);
    }

    private String stripHtml(String s) {
        return s.replaceAll("<[^>]+>", "").replaceAll("&nbsp;", " ").replaceAll("&amp;", "&").trim();
    }
}
