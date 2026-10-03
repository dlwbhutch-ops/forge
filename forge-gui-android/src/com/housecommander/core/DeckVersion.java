package com.housecommander.core;

public final class DeckVersion {
    private final String deck;
    private final long savedAtMillis;
    private final String source;
    private final String commanders;
    private final String dck;
    private final String engineName;
    private final String detail;

    public DeckVersion(
            String deck,
            long savedAtMillis,
            String source,
            String commanders,
            String dck,
            String engineName,
            String detail
    ) {
        this.deck = deck;
        this.savedAtMillis = savedAtMillis;
        this.source = source;
        this.commanders = commanders;
        this.dck = dck;
        this.engineName = engineName;
        this.detail = detail;
    }

    public String deck() { return deck; }
    public long savedAtMillis() { return savedAtMillis; }
    public String source() { return source; }
    public String commanders() { return commanders; }
    public String dck() { return dck; }
    public String engineName() { return engineName; }
    public String detail() { return detail; }
}
