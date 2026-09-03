package com.housecommander.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HousePackage {
    private final List<DeckSpec> decks;
    private final Map<String, DeckSpec> decksByCanonicalName;
    private final List<PodSpec> schedule;
    private final ValidationReport validation;

    public HousePackage(List<DeckSpec> decks, Map<String, DeckSpec> decksByCanonicalName,
                        List<PodSpec> schedule, ValidationReport validation) {
        this.decks = Collections.unmodifiableList(new ArrayList<DeckSpec>(decks));
        this.decksByCanonicalName = Collections.unmodifiableMap(new HashMap<String, DeckSpec>(decksByCanonicalName));
        this.schedule = Collections.unmodifiableList(new ArrayList<PodSpec>(schedule));
        this.validation = validation;
    }

    public List<DeckSpec> decks() { return decks; }
    public Map<String, DeckSpec> decksByCanonicalName() { return decksByCanonicalName; }
    public List<PodSpec> schedule() { return schedule; }
    public ValidationReport validation() { return validation; }

    public DeckSpec deckNamed(String name) {
        return decksByCanonicalName.get(Names.canonical(name));
    }
}
