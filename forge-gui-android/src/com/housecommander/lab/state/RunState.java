package com.housecommander.lab.state;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RunState {
    public String status = "IDLE";
    public int targetGauntlets = 0;
    public int currentGauntlet = 1;
    public int nextPodIndex = 0;
    public long totalGames = 0;
    public boolean pauseRequested = false;
    public String lastMessage = "Ready";
    public String rosterKey = "";
    public String lastLogPath = "";
    public final Map<String, Integer> wins = new LinkedHashMap<>();
    public final Map<String, Integer> games = new LinkedHashMap<>();

    public int completedGauntlets() {
        return Math.max(0, currentGauntlet - 1);
    }

    public boolean inProgress() {
        return "RUNNING".equals(status) || "TESTING".equals(status) || "PILOTING".equals(status);
    }

    /** A disk checkpoint records progress, not the existence of a worker in this process. */
    public boolean recoverInterruptedRun(boolean workerActive) {
        if (workerActive || !inProgress()) return false;
        boolean tournament = "RUNNING".equals(status);
        status = "INTERRUPTED";
        lastMessage = tournament
                ? "App stopped • " + totalGames + " completed games saved • tap Run / resume to continue"
                : "App stopped during the game • tournament checkpoint preserved • start another game when ready";
        return true;
    }

    public void markStoppedGame() {
        status = "PAUSED";
        lastMessage = "Stopped unfinished game • " + totalGames
                + " completed games saved • Run / resume retries the same pod";
    }
}
