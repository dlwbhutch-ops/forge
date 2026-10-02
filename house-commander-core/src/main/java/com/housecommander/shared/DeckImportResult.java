package com.housecommander.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structural parse result. Forge remains the authority for actual card names
 * and Commander legality during platform-specific preflight.
 */
public final class DeckImportResult {
    private final String format;
    private final int commanderCards;
    private final int mainboardCards;
    private final int sideboardCards;
    private final List<String> commanderNames;
    private final List<String> warnings;

    DeckImportResult(
            String format,
            int commanderCards,
            int mainboardCards,
            int sideboardCards,
            List<String> commanderNames,
            List<String> warnings
    ) {
        this.format = format;
        this.commanderCards = commanderCards;
        this.mainboardCards = mainboardCards;
        this.sideboardCards = sideboardCards;
        this.commanderNames = Collections.unmodifiableList(new ArrayList<>(commanderNames));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public String format() { return format; }
    public int commanderCards() { return commanderCards; }
    public int mainboardCards() { return mainboardCards; }
    public int sideboardCards() { return sideboardCards; }
    public List<String> commanderNames() { return commanderNames; }
    public List<String> warnings() { return warnings; }

    public int commanderDeckCards() {
        return commanderCards + mainboardCards;
    }

    public boolean hasCommanderSection() {
        return commanderCards > 0;
    }

    public boolean isCommanderSized() {
        return commanderDeckCards() == 100;
    }

    public String commanderLabel() {
        return String.join(" / ", commanderNames);
    }
}
