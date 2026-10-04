package com.housecommander.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared validation/resolution for one-off four-player Watch Game pods. */
public final class WatchPodSelection {
    public static final int POD_SIZE = 4;

    private WatchPodSelection() {
    }

    public static void validateDecks(List<DeckSpec> decks) {
        if (decks == null || decks.size() != POD_SIZE) {
            throw new IllegalArgumentException(
                    "Watch Game requires exactly " + POD_SIZE + " decks"
            );
        }

        Set<String> seen = new HashSet<String>();
        for (DeckSpec deck : decks) {
            if (deck == null) {
                throw new IllegalArgumentException("Watch Game contains a null deck");
            }
            String key = Names.canonical(deck.deck());
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Watch Game contains an unnamed deck");
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException(
                        "Watch Game cannot use the same deck twice: " + deck.deck()
                );
            }
        }
    }

    public static List<DeckSpec> selectByNames(
            List<DeckSpec> library,
            String[] requestedNames
    ) {
        if (library == null) {
            throw new IllegalArgumentException("Deck Library is unavailable");
        }
        if (requestedNames == null || requestedNames.length != POD_SIZE) {
            throw new IllegalArgumentException(
                    "Watch Game requires exactly " + POD_SIZE + " selected deck names"
            );
        }

        List<DeckSpec> out = new ArrayList<DeckSpec>(POD_SIZE);
        for (String requestedName : requestedNames) {
            String key = Names.canonical(requestedName);
            DeckSpec match = null;
            for (DeckSpec deck : library) {
                if (deck != null && Names.canonical(deck.deck()).equals(key)) {
                    match = deck;
                    break;
                }
            }
            if (match == null) {
                throw new IllegalStateException(
                        "Selected Watch deck is missing from the Deck Library: "
                                + requestedName
                );
            }
            out.add(match);
        }

        validateDecks(out);
        return out;
    }
}
