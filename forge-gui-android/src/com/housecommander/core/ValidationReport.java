package com.housecommander.core;

public final class ValidationReport {
    private final int deckCount;
    private final int podCount;
    private final int uniquePairs;
    private final int meetingsPerPair;
    private final int maxMeetingsPerPair;
    private final int gamesPerDeck;
    private final int maxGamesPerDeck;
    private final boolean exactDecks;
    private final boolean allDckFilesPresent;
    private final boolean allDckFilesCountTo100;

    public ValidationReport(
            int deckCount,
            int podCount,
            int uniquePairs,
            int meetingsPerPair,
            int gamesPerDeck,
            boolean exactDecks,
            boolean allDckFilesPresent,
            boolean allDckFilesCountTo100
    ) {
        this(
                deckCount,
                podCount,
                uniquePairs,
                meetingsPerPair,
                meetingsPerPair,
                gamesPerDeck,
                gamesPerDeck,
                exactDecks,
                allDckFilesPresent,
                allDckFilesCountTo100
        );
    }

    public ValidationReport(
            int deckCount,
            int podCount,
            int uniquePairs,
            int meetingsPerPair,
            int maxMeetingsPerPair,
            int gamesPerDeck,
            int maxGamesPerDeck,
            boolean exactDecks,
            boolean allDckFilesPresent,
            boolean allDckFilesCountTo100
    ) {
        this.deckCount = deckCount;
        this.podCount = podCount;
        this.uniquePairs = uniquePairs;
        this.meetingsPerPair = meetingsPerPair;
        this.maxMeetingsPerPair = maxMeetingsPerPair;
        this.gamesPerDeck = gamesPerDeck;
        this.maxGamesPerDeck = maxGamesPerDeck;
        this.exactDecks = exactDecks;
        this.allDckFilesPresent = allDckFilesPresent;
        this.allDckFilesCountTo100 = allDckFilesCountTo100;
    }

    public int deckCount() { return deckCount; }
    public int podCount() { return podCount; }
    public int uniquePairs() { return uniquePairs; }
    public int meetingsPerPair() { return meetingsPerPair; }
    public int maxMeetingsPerPair() { return maxMeetingsPerPair; }
    public int gamesPerDeck() { return gamesPerDeck; }
    public int maxGamesPerDeck() { return maxGamesPerDeck; }
    public boolean exactDecks() { return exactDecks; }
    public boolean allDckFilesPresent() { return allDckFilesPresent; }
    public boolean allDckFilesCountTo100() { return allDckFilesCountTo100; }

    public boolean passesStrictGate() {
        long expectedPairs = ((long) deckCount * (deckCount - 1L)) / 2L;
        return deckCount >= 4
                && podCount > 0
                && uniquePairs == expectedPairs
                && meetingsPerPair >= 3
                && gamesPerDeck > 0
                && maxGamesPerDeck - gamesPerDeck <= 1
                && exactDecks
                && allDckFilesPresent
                && allDckFilesCountTo100;
    }

    public String summary() {
        String meetingText = meetingsPerPair == maxMeetingsPerPair
                ? Integer.toString(meetingsPerPair)
                : meetingsPerPair + "–" + maxMeetingsPerPair;
        String gamesText = gamesPerDeck == maxGamesPerDeck
                ? Integer.toString(gamesPerDeck)
                : gamesPerDeck + "–" + maxGamesPerDeck;
        return deckCount + " decks • " + podCount + " pods • " + uniquePairs
                + " pairs • " + meetingText + " meetings/pair • "
                + gamesText + " games/deck • DCK 100-card gate="
                + allDckFilesCountTo100;
    }
}
