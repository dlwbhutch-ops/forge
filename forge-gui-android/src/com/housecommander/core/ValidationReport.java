package com.housecommander.core;

public final class ValidationReport {
    private final int deckCount;
    private final int podCount;
    private final int uniquePairs;
    private final int meetingsPerPair;
    private final int gamesPerDeck;
    private final boolean exactDecks;
    private final boolean allDckFilesPresent;
    private final boolean allDckFilesCountTo100;

    public ValidationReport(int deckCount, int podCount, int uniquePairs, int meetingsPerPair,
                            int gamesPerDeck, boolean exactDecks, boolean allDckFilesPresent,
                            boolean allDckFilesCountTo100) {
        this.deckCount = deckCount;
        this.podCount = podCount;
        this.uniquePairs = uniquePairs;
        this.meetingsPerPair = meetingsPerPair;
        this.gamesPerDeck = gamesPerDeck;
        this.exactDecks = exactDecks;
        this.allDckFilesPresent = allDckFilesPresent;
        this.allDckFilesCountTo100 = allDckFilesCountTo100;
    }

    public int deckCount() { return deckCount; }
    public int podCount() { return podCount; }
    public int uniquePairs() { return uniquePairs; }
    public int meetingsPerPair() { return meetingsPerPair; }
    public int gamesPerDeck() { return gamesPerDeck; }
    public boolean exactDecks() { return exactDecks; }
    public boolean allDckFilesPresent() { return allDckFilesPresent; }
    public boolean allDckFilesCountTo100() { return allDckFilesCountTo100; }

    public boolean passesStrictGate() {
        return deckCount == 19
                && podCount == 95
                && uniquePairs == 171
                && meetingsPerPair == 3
                && gamesPerDeck == 19
                && exactDecks
                && allDckFilesPresent
                && allDckFilesCountTo100;
    }

    public String summary() {
        return deckCount + " decks • " + podCount + " pods • " + uniquePairs
                + " pairs • " + meetingsPerPair + " meetings/pair • "
                + gamesPerDeck + " games/deck • DCK 100-card gate=" + allDckFilesCountTo100;
    }
}
