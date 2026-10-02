package com.housecommander.core;

public final class DeckSpec {
    private final String deck;
    private final String source;
    private final String commanders;
    private final String dck;
    private final String status;
    private final String detail;
    private final String engineName;

    public DeckSpec(String deck, String source, String commanders, String dck, String status, String detail) {
        this(deck, source, commanders, dck, status, detail, deck);
    }

    public DeckSpec(String deck, String source, String commanders, String dck, String status, String detail, String engineName) {
        this.deck = deck;
        this.source = source;
        this.commanders = commanders;
        this.dck = dck;
        this.status = status;
        this.detail = detail;
        this.engineName = engineName == null || engineName.trim().isEmpty() ? deck : engineName;
    }

    public String deck() { return deck; }
    public String source() { return source; }
    public String commanders() { return commanders; }
    public String dck() { return dck; }
    public String status() { return status; }
    public String detail() { return detail; }
    public String engineName() { return engineName; }
}
