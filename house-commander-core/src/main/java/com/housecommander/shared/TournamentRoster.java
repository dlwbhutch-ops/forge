package com.housecommander.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A named tournament roster pinned to exact deck versions.
 */
public final class TournamentRoster {
    public static final class DeckRef {
        private final String deckId;
        private final int version;

        public DeckRef(String deckId, int version) {
            if (deckId == null || deckId.trim().isEmpty()) {
                throw new IllegalArgumentException("deckId must not be blank");
            }
            if (version < 1) {
                throw new IllegalArgumentException("version must be at least 1");
            }
            this.deckId = deckId.trim();
            this.version = version;
        }

        public String deckId() { return deckId; }
        public int version() { return version; }

        public String key() {
            return deckId + "#" + version;
        }
    }

    private final String rosterId;
    private final String name;
    private final List<DeckRef> decks;

    public TournamentRoster(String rosterId, String name, List<DeckRef> decks) {
        if (rosterId == null || rosterId.trim().isEmpty()) {
            throw new IllegalArgumentException("rosterId must not be blank");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (decks == null || decks.isEmpty()) {
            throw new IllegalArgumentException("A tournament roster must contain at least one deck");
        }

        Set<String> unique = new LinkedHashSet<>();
        for (DeckRef ref : decks) {
            if (!unique.add(ref.key())) {
                throw new IllegalArgumentException("Duplicate roster entry: " + ref.key());
            }
        }

        this.rosterId = rosterId.trim();
        this.name = name.trim();
        this.decks = Collections.unmodifiableList(new ArrayList<>(decks));
    }

    public static TournamentRoster create(String name, List<DeckRecord> records) {
        List<DeckRef> refs = new ArrayList<>();
        for (DeckRecord record : records) {
            refs.add(new DeckRef(record.deckId(), record.version()));
        }
        return new TournamentRoster(UUID.randomUUID().toString(), name, refs);
    }

    public String rosterId() { return rosterId; }
    public String name() { return name; }
    public List<DeckRef> decks() { return decks; }

    public List<DeckRecord> resolve(DeckLibrary library) {
        List<DeckRecord> resolved = new ArrayList<>();
        for (DeckRef ref : decks) {
            DeckRecord record = library.version(ref.deckId(), ref.version());
            if (record == null) {
                throw new IllegalStateException(
                        "Roster references missing deck version: " + ref.key()
                );
            }
            resolved.add(record);
        }
        return Collections.unmodifiableList(resolved);
    }
}
