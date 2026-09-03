package com.housecommander.lab.state;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

public final class StateStore {
    private static final String PREFS = "house_run_state_v1";
    private final SharedPreferences prefs;

    public StateStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized RunState load() {
        RunState s = new RunState();
        s.status = prefs.getString("status", "IDLE");
        s.targetGauntlets = prefs.getInt("targetGauntlets", 0);
        s.currentGauntlet = prefs.getInt("currentGauntlet", 1);
        s.nextPodIndex = prefs.getInt("nextPodIndex", 0);
        s.totalGames = prefs.getLong("totalGames", 0L);
        s.pauseRequested = prefs.getBoolean("pauseRequested", false);
        s.lastMessage = prefs.getString("lastMessage", "Ready");
        decodeMap(prefs.getString("wins", ""), s.wins);
        decodeMap(prefs.getString("games", ""), s.games);
        return s;
    }

    public synchronized void save(RunState s) {
        prefs.edit()
                .putString("status", s.status)
                .putInt("targetGauntlets", s.targetGauntlets)
                .putInt("currentGauntlet", s.currentGauntlet)
                .putInt("nextPodIndex", s.nextPodIndex)
                .putLong("totalGames", s.totalGames)
                .putBoolean("pauseRequested", s.pauseRequested)
                .putString("lastMessage", s.lastMessage)
                .putString("wins", encodeMap(s.wins))
                .putString("games", encodeMap(s.games))
                .apply();
    }

    public synchronized void requestPause() {
        prefs.edit().putBoolean("pauseRequested", true).apply();
    }

    public synchronized void clear() {
        prefs.edit().clear().apply();
    }

    private static String encodeMap(Map<String, Integer> map) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            out.append(e.getKey().replace("\\", "\\\\").replace("\t", " ").replace("\n", " "))
                    .append('\t').append(e.getValue()).append('\n');
        }
        return out.toString();
    }

    private static void decodeMap(String raw, Map<String, Integer> out) {
        if (raw == null || raw.isEmpty()) return;
        for (String line : raw.split("\\n")) {
            int tab = line.lastIndexOf('\t');
            if (tab <= 0) continue;
            try { out.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1))); }
            catch (NumberFormatException ignored) {}
        }
    }
}
