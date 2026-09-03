package com.housecommander.lab.engine;

public final class GameOutcome {
    private final String winner;
    private final String engineVersion;
    private final String logText;

    public GameOutcome(String winner, String engineVersion, String logText) {
        this.winner = winner;
        this.engineVersion = engineVersion;
        this.logText = logText;
    }

    public String winner() { return winner; }
    public String engineVersion() { return engineVersion; }
    public String logText() { return logText; }
}
