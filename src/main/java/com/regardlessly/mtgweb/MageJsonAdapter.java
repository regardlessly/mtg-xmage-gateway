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
        m.put("human_player", observeMode ? -1 : 0);
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

        // turn_player: index of whichever player is active
        ArrayNode players = s.putArray("players");
        int idx = 0;
        int activeIdx = 0;
        for (PlayerView pv : gv.getPlayers()) {
            ObjectNode po = players.addObject();
            po.put("index", idx);
            po.put("life", pv.getLife());
            po.put("library_size", pv.getLibraryCount());
            po.put("graveyard_size", pv.getGraveyard() == null ? 0 : pv.getGraveyard().size());
            po.put("hand_size", pv.getHandCount());
            // The human player's own hand IS exposed via gameView.getMyHand()
            // (XMage delivers it to the seat-holding session). In observe
            // mode there's no "me", so myHand is empty for both players.
            // We populate the hand field only for the seat that matches the
            // current session's player; other players show face-down by count.
            if (idx == 0) {
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
                for (PermanentView perm : battlefield.values()) {
                    ObjectNode po2 = bf.addObject();
                    po2.put("name", perm.getName());
                    po2.put("tapped", perm.isTapped());
                    String p = perm.getPower();
                    String t = perm.getToughness();
                    if (p != null && !p.isEmpty()) {
                        try { po2.put("power", Integer.parseInt(p)); } catch (Exception ignore) {}
                    }
                    if (t != null && !t.isEmpty()) {
                        try { po2.put("toughness", Integer.parseInt(t)); } catch (Exception ignore) {}
                    }
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
