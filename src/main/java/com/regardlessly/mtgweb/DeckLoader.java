package com.regardlessly.mtgweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a precon deck id (e.g. "precon-ArcaneMaelstrom_C20") into an XMage
 * {@link DeckCardLists} by looking each card name up in the running XMage's
 * {@link CardRepository}. The slim precon JSON ships as a classpath resource
 * — generated from {@code public/precon-decks.json} at build time.
 *
 * <p>Game mode is "Constructed - Freeform Unlimited" so 100-card singleton
 * Commander lists are legal as plain duel decks. The commander still appears
 * in the library — true Command Zone semantics will come with a real
 * Commander game type later.
 */
public final class DeckLoader {
    private static final Logger log = LoggerFactory.getLogger(DeckLoader.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Loaded once on first use, then served from memory. */
    private static volatile Map<String, DeckRecord> INDEX;

    private DeckLoader() {}

    public static int deckCount() {
        ensureLoaded();
        return INDEX.size();
    }

    /** True if the given id matches a known precon. */
    public static boolean has(String id) {
        ensureLoaded();
        return id != null && INDEX.containsKey(id);
    }

    /**
     * Build a {@link DeckCardLists} for the given precon id. Cards XMage doesn't
     * have are skipped (and logged at WARN). If the resulting deck has fewer
     * than 60 cards we pad with Forests so the engine accepts it.
     * @return null if id is null or unknown.
     */
    public static DeckCardLists build(String id) {
        ensureLoaded();
        if (id == null) return null;
        DeckRecord rec = INDEX.get(id);
        if (rec == null) {
            log.warn("unknown precon id: {}", id);
            return null;
        }
        DeckCardLists d = new DeckCardLists();
        List<DeckCardInfo> cards = new ArrayList<>();
        int resolved = 0, missing = 0;
        for (CardEntry ce : rec.cards) {
            CardInfo ci = CardRepository.instance.findCard(ce.name);
            if (ci == null) {
                missing++;
                log.debug("[{}] card not in XMage DB: {}", id, ce.name);
                continue;
            }
            String setCode = ci.getSetCode();
            String cardNumber = ci.getCardNumber();
            for (int i = 0; i < ce.qty; i++) {
                cards.add(new DeckCardInfo(ci.getName(), cardNumber, setCode));
            }
            resolved += ce.qty;
        }
        // Pad to 60 if we lost too many cards.
        int padded = 0;
        while (cards.size() < 60) {
            cards.add(new DeckCardInfo("Forest", "266", "M20"));
            padded++;
        }
        d.setCards(cards);
        d.setSideboard(new ArrayList<>());
        log.info("deck {} built — {} cards (resolved={}, missing={}, padded={})",
            id, cards.size(), resolved, missing, padded);
        return d;
    }

    private static void ensureLoaded() {
        if (INDEX != null) return;
        synchronized (DeckLoader.class) {
            if (INDEX != null) return;
            Map<String, DeckRecord> idx = new HashMap<>();
            try (InputStream in = DeckLoader.class.getResourceAsStream("/precons.json")) {
                if (in == null) {
                    log.warn("precons.json not on classpath — precon decks disabled");
                    INDEX = idx;
                    return;
                }
                JsonNode root = JSON.readTree(in);
                JsonNode arr = root.path("decks");
                for (JsonNode dn : arr) {
                    DeckRecord r = new DeckRecord();
                    r.id = dn.path("id").asText("");
                    r.name = dn.path("name").asText("");
                    r.commander = dn.path("commander").asText(null);
                    for (JsonNode cn : dn.path("cards")) {
                        CardEntry ce = new CardEntry();
                        ce.name = cn.path("name").asText("");
                        ce.qty = cn.path("qty").asInt(1);
                        if (!ce.name.isEmpty()) r.cards.add(ce);
                    }
                    if (!r.id.isEmpty()) idx.put(r.id, r);
                }
                log.info("loaded {} precons from classpath", idx.size());
            } catch (Exception e) {
                log.warn("precons.json load failed: {}", e.getMessage());
            }
            INDEX = idx;
        }
    }

    private static final class DeckRecord {
        String id;
        String name;
        String commander;
        final List<CardEntry> cards = new ArrayList<>();
    }
    private static final class CardEntry {
        String name;
        int qty;
    }
}
