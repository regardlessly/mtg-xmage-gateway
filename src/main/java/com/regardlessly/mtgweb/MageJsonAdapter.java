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
            // Monarch / Initiative — special game states from Commander-y
            // formats that grant the holder card draw at end of turn.
            try {
                if (Boolean.TRUE.equals(pv.getClass().getMethod("isMonarch").invoke(pv)))
                    po.put("monarch", true);
                if (Boolean.TRUE.equals(pv.getClass().getMethod("isInitiative").invoke(pv)))
                    po.put("initiative", true);
            } catch (Exception ignored) {}
            // Player counters — poison (lethal at 10), energy, experience,
            // rad, ticket, etc. Emit them all so the UI can surface poison
            // prominently and other resources as pills.
            try {
                Object pcounters = pv.getClass().getMethod("getCounters").invoke(pv);
                if (pcounters instanceof java.util.List && !((java.util.List<?>) pcounters).isEmpty()) {
                    ArrayNode cArr = po.putArray("counters");
                    for (Object cv : (java.util.List<?>) pcounters) {
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
            // Designations — City's Blessing (Ascend), etc.
            try {
                Object designations = pv.getClass().getMethod("getDesignationNames").invoke(pv);
                if (designations instanceof java.util.List && !((java.util.List<?>) designations).isEmpty()) {
                    ArrayNode dArr = po.putArray("designations");
                    for (Object d : (java.util.List<?>) designations) {
                        if (d instanceof String) dArr.add((String) d);
                    }
                }
            } catch (Exception ignored) {}
            // Floating mana in the pool — surfaced so the player can see what's
            // available mid-cast. Only emit colors that are non-zero.
            try {
                Object mp = pv.getManaPool();
                if (mp != null) {
                    ObjectNode pool = json.createObjectNode();
                    int total = 0;
                    String[] getters = {"getWhite", "getBlue", "getBlack", "getRed", "getGreen", "getColorless"};
                    String[] keys = {"W", "U", "B", "R", "G", "C"};
                    for (int gi = 0; gi < getters.length; gi++) {
                        try {
                            Object v = mp.getClass().getMethod(getters[gi]).invoke(mp);
                            if (v instanceof Integer && (Integer) v > 0) {
                                pool.put(keys[gi], (Integer) v);
                                total += (Integer) v;
                            }
                        } catch (Exception ignored2) {}
                    }
                    if (total > 0) po.set("mana_pool", pool);
                }
            } catch (Exception ignored) {}
            // Command zone — emblems (from planeswalker ultimates) and the
            // commander itself. Emit names so the UI can show a small list.
            try {
                Object cmd = pv.getClass().getMethod("getCommandObjectList").invoke(pv);
                if (cmd instanceof java.util.List && !((java.util.List<?>) cmd).isEmpty()) {
                    ArrayNode cArr = po.putArray("command_zone");
                    for (Object c : (java.util.List<?>) cmd) {
                        if (c == null) continue;
                        try {
                            Object n = c.getClass().getMethod("getName").invoke(c);
                            // Emblems often have an empty name; label them generically.
                            String label = (n instanceof String && !((String) n).isEmpty())
                                ? (String) n : "Emblem";
                            cArr.add(label);
                        } catch (Exception ignored2) {}
                    }
                }
            } catch (Exception ignored) {}
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
                    try {
                        Object tok = perm.getClass().getMethod("isToken").invoke(perm);
                        if (Boolean.TRUE.equals(tok)) po2.put("token", true);
                    } catch (Exception ignored) {}
                    // Summoning sickness: a freshly-played creature without
                    // haste can't attack or use tap abilities on its first
                    // turn. UI shows a faded leaf to communicate "can't act yet".
                    try {
                        Object sick = perm.hasSummoningSickness();
                        if (Boolean.TRUE.equals(sick)) po2.put("summoning_sick", true);
                    } catch (Exception ignored) {}
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
                    // Marked combat/effect damage on a creature. A 4/4 with 3
                    // damage marked is one ping from dying — the single most
                    // combat-relevant status, so the UI shows it explicitly.
                    try {
                        Object dmg = perm.getDamage();
                        if (dmg instanceof Integer && (Integer) dmg > 0) {
                            po2.put("damage", (Integer) dmg);
                        }
                    } catch (Exception ignored) {}
                    // Attachment count — auras + equipment riding on this
                    // permanent. UI badges the host so you see it's enchanted.
                    try {
                        Object att = perm.getAttachments();
                        if (att instanceof java.util.List && !((java.util.List<?>) att).isEmpty()) {
                            po2.put("attachment_count", ((java.util.List<?>) att).size());
                        }
                    } catch (Exception ignored) {}
                    // Is THIS permanent attached to something (i.e. it's an
                    // aura/equipment currently on a host)?
                    try {
                        Object attachedTo = perm.getAttachedTo();
                        if (attachedTo instanceof java.util.UUID) {
                            String hostName = nameOfUuidInView((java.util.UUID) attachedTo, gv);
                            po2.put("attached", true);
                            if (hostName != null) po2.put("attached_to", hostName);
                        }
                    } catch (Exception ignored) {}
                    // Control change — a control-changing effect (Control
                    // Magic, Threaten, Act of Treason) has this permanent on
                    // a seat that isn't its owner's. isControlled() is
                    // viewer-relative and useless in observe mode, so we
                    // compare owner vs controller names instead. UI marks the
                    // permanent "stolen" and notes who really owns it.
                    try {
                        Object owner = perm.getClass().getMethod("getNameOwner").invoke(perm);
                        Object controller = perm.getClass().getMethod("getNameController").invoke(perm);
                        if (owner instanceof String && controller instanceof String
                                && !((String) owner).isEmpty()
                                && !owner.equals(controller)) {
                            po2.put("controlled_by_other", true);
                            po2.put("owner_name", (String) owner);
                        }
                    } catch (Exception ignored) {}
                    // Phasing — when phased out the permanent is treated as
                    // though it doesn't exist. Dim it so the player knows.
                    try {
                        Object phasedIn = perm.getClass().getMethod("isPhasedIn").invoke(perm);
                        if (Boolean.FALSE.equals(phasedIn)) po2.put("phased_out", true);
                    } catch (Exception ignored) {}
                    // Face-down / morph family + transform + flip — the
                    // permanent's printed identity differs from what's shown.
                    putBoolIfTrue(po2, "transformed", perm, "isTransformed");
                    putBoolIfTrue(po2, "face_down", perm, "isFaceDown");
                    putBoolIfTrue(po2, "morphed", perm, "isMorphed");
                    putBoolIfTrue(po2, "manifested", perm, "isManifested");
                    putBoolIfTrue(po2, "disguised", perm, "isDisguised");
                    putBoolIfTrue(po2, "cloaked", perm, "isCloaked");
                    putBoolIfTrue(po2, "flipped", perm, "isFlipped");
                    putBoolIfTrue(po2, "copy", perm, "isCopy");
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

    /** Reflectively invoke a no-arg boolean getter; emit field=true if it
     *  returns Boolean.TRUE. Keeps the per-status emission a one-liner. */
    private static void putBoolIfTrue(ObjectNode node, String field, Object target, String getter) {
        try {
            Object v = target.getClass().getMethod(getter).invoke(target);
            if (Boolean.TRUE.equals(v)) node.put(field, true);
        } catch (Exception ignored) {}
    }

    /** Find the display name of a permanent by UUID anywhere on any player's
     *  battlefield in the given GameView (used to label aura/equip hosts). */
    private static String nameOfUuidInView(java.util.UUID id, GameView gv) {
        if (id == null || gv == null) return null;
        try {
            for (PlayerView pv : gv.getPlayers()) {
                Map<?, PermanentView> bf = pv.getBattlefield();
                if (bf == null) continue;
                for (Map.Entry<?, PermanentView> e : bf.entrySet()) {
                    if (id.equals(e.getKey())) {
                        return e.getValue().getName();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
