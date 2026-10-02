package com.housecommander.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory model for the user's HOUSE deck library.
 *
 * Platform shells decide where this is stored; HouseLibraryCodec defines the
 * portable on-disk representation shared by Android, macOS, and Windows.
 */
public final class DeckLibrary {
    private final Map<String, List<DeckRecord>> versionsByDeck = new LinkedHashMap<>();
    private final Set<String> archivedDeckIds = new LinkedHashSet<>();

    public synchronized DeckRecord createDeck(
            String name,
            String commander,
            String deckText,
            String sourceFormat
    ) {
        DeckRecord record = DeckRecord.create(name, commander, deckText, sourceFormat);
        add(record);
        return record;
    }

    public synchronized DeckRecord createVersion(
            String deckId,
            String name,
            String commander,
            String deckText,
            String sourceFormat
    ) {
        DeckRecord current = latest(deckId);
        if (current == null) {
            throw new IllegalArgumentException("Unknown deckId: " + deckId);
        }
        DeckRecord next = current.nextVersion(name, commander, deckText, sourceFormat);
        add(next);
        return next;
    }

    public synchronized void add(DeckRecord record) {
        List<DeckRecord> versions = versionsByDeck.computeIfAbsent(
                record.deckId(),
                ignored -> new ArrayList<>()
        );
        for (DeckRecord existing : versions) {
            if (existing.version() == record.version()) {
                throw new IllegalArgumentException(
                        "Duplicate deck version: "
                                + record.deckId()
                                + " v"
                                + record.version()
                );
            }
        }
        versions.add(record);
        versions.sort(Comparator.comparingInt(DeckRecord::version));
    }

    public synchronized DeckRecord latest(String deckId) {
        List<DeckRecord> versions = versionsByDeck.get(deckId);
        return versions == null || versions.isEmpty()
                ? null
                : versions.get(versions.size() - 1);
    }

    public synchronized DeckRecord version(String deckId, int version) {
        List<DeckRecord> versions = versionsByDeck.get(deckId);
        if (versions == null) return null;
        for (DeckRecord record : versions) {
            if (record.version() == version) return record;
        }
        return null;
    }

    public synchronized List<DeckRecord> history(String deckId) {
        List<DeckRecord> versions = versionsByDeck.get(deckId);
        if (versions == null) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(versions));
    }

    public synchronized List<DeckRecord> allVersions() {
        List<DeckRecord> out = new ArrayList<>();
        for (List<DeckRecord> versions : versionsByDeck.values()) {
            out.addAll(versions);
        }
        return Collections.unmodifiableList(out);
    }

    public synchronized List<DeckRecord> allLatest() {
        List<DeckRecord> out = new ArrayList<>();
        for (String deckId : versionsByDeck.keySet()) {
            DeckRecord latest = latest(deckId);
            if (latest != null) out.add(latest);
        }
        return Collections.unmodifiableList(out);
    }

    public synchronized List<DeckRecord> activeLatest() {
        List<DeckRecord> out = new ArrayList<>();
        for (String deckId : versionsByDeck.keySet()) {
            if (!archivedDeckIds.contains(deckId)) {
                DeckRecord latest = latest(deckId);
                if (latest != null) out.add(latest);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public synchronized void setArchived(String deckId, boolean archived) {
        if (!versionsByDeck.containsKey(deckId)) {
            throw new IllegalArgumentException("Unknown deckId: " + deckId);
        }
        if (archived) archivedDeckIds.add(deckId);
        else archivedDeckIds.remove(deckId);
    }

    public synchronized boolean isArchived(String deckId) {
        return archivedDeckIds.contains(deckId);
    }

    public synchronized int deckCount() {
        return versionsByDeck.size();
    }
}
