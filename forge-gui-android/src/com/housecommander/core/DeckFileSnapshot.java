package com.housecommander.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DeckFileSnapshot {
    public static final class CardLine {
        private final String section;
        private final int quantity;
        private final String request;

        public CardLine(String section, int quantity, String request) {
            this.section = section;
            this.quantity = quantity;
            this.request = request;
        }

        public String section() { return section; }
        public int quantity() { return quantity; }
        public String request() { return request; }
    }

    private final String engineName;
    private final List<String> commanders;
    private final List<CardLine> cards;
    private final int cardCount;

    public DeckFileSnapshot(
            String engineName,
            List<String> commanders,
            List<CardLine> cards,
            int cardCount
    ) {
        this.engineName = engineName;
        this.commanders = Collections.unmodifiableList(new ArrayList<String>(commanders));
        this.cards = Collections.unmodifiableList(new ArrayList<CardLine>(cards));
        this.cardCount = cardCount;
    }

    public String engineName() { return engineName; }
    public List<String> commanders() { return commanders; }
    public List<CardLine> cards() { return cards; }
    public int cardCount() { return cardCount; }

    public String commanderText() {
        StringBuilder out = new StringBuilder();
        for (String commander : commanders) {
            if (out.length() > 0) {
                out.append(" | ");
            }
            out.append(commander);
        }
        return out.toString();
    }

    public String formattedDeckList() {
        StringBuilder out = new StringBuilder();
        String section = null;
        for (CardLine card : cards) {
            if (!card.section().equals(section)) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                section = card.section();
                out.append('[').append(displaySection(section)).append(']').append('\n');
            }
            out.append(card.quantity())
                    .append(' ')
                    .append(card.request())
                    .append('\n');
        }
        return out.toString();
    }

    private static String displaySection(String section) {
        if ("commander".equals(section)) {
            return "Commander";
        }
        if ("main".equals(section)) {
            return "Main";
        }
        return section == null ? "" : section;
    }
}
