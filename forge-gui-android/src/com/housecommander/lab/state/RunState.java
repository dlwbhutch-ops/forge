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
    public final Map<String, Integer> wins = new LinkedHashMap<>();
    public final Map<String, Integer> games = new LinkedHashMap<>();

    public int completedGauntlets() {
        return Math.max(0, currentGauntlet - 1);
    }
}
