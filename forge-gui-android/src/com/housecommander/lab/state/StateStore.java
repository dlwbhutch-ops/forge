package com.housecommander.lab.state;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * Durable tournament checkpoint storage.
 *
 * Important design rule: pauseRequested is an independent command flag. It is
 * never written by save(RunState), which prevents a stale load/save cycle on
 * the tournament worker from accidentally erasing a pause request made by the
 * Activity or another Service start.
 */
public final class StateStore {
    private static final String PREFS = "house_run_state_v1";
    private static final String KEY_PAUSE = "pauseRequested";
    private static final Object LOCK = new Object();

    private final SharedPreferences prefs;

    public StateStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        Context app = context.getApplicationContext();
        Context safe = app != null ? app : context;
        prefs = safe.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public RunState load() {
        synchronized (LOCK) {
            RunState s = new RunState();
            s.status = nonBlank(prefs.getString("status", "IDLE"), "IDLE");
            s.targetGauntlets = Math.max(0, prefs.getInt("targetGauntlets", 0));
            s.currentGauntlet = Math.max(1, prefs.getInt("currentGauntlet", 1));
            s.nextPodIndex = Math.max(0, prefs.getInt("nextPodIndex", 0));
            s.totalGames = Math.max(0L, prefs.getLong("totalGames", 0L));
            s.pauseRequested = prefs.getBoolean(KEY_PAUSE, false);
            s.lastMessage = nonBlank(prefs.getString("lastMessage", "Ready"), "Ready");

            decodeMap(prefs.getString("wins", ""), s.wins);
            decodeMap(prefs.getString("games", ""), s.games);
            return s;
        }
    }

    /**
     * Synchronously persist the checkpoint. SharedPreferences.apply() is not
     * used here because a tournament checkpoint must be on disk before the
     * next literal game begins.
     *
     * pauseRequested is intentionally excluded; use requestPause(),
     * consumePauseRequest(), or clearPauseRequest() for that flag.
     */
    public void save(RunState s) {
        if (s == null) {
            throw new IllegalArgumentException("RunState must not be null");
        }

        synchronized (LOCK) {
            SharedPreferences.Editor editor = prefs.edit()
                    .putString("status", nonBlank(s.status, "IDLE"))
                    .putInt("targetGauntlets", Math.max(0, s.targetGauntlets))
                    .putInt("currentGauntlet", Math.max(1, s.currentGauntlet))
                    .putInt("nextPodIndex", Math.max(0, s.nextPodIndex))
                    .putLong("totalGames", Math.max(0L, s.totalGames))
                    .putString("lastMessage", nonBlank(s.lastMessage, "Ready"))
                    .putString("wins", encodeMap(s.wins))
                    .putString("games", encodeMap(s.games));
            commitOrThrow(editor, "save tournament checkpoint");
        }
    }

    public void requestPause() {
        synchronized (LOCK) {
            commitOrThrow(
                    prefs.edit().putBoolean(KEY_PAUSE, true),
                    "request tournament pause"
            );
        }
    }

    /**
     * Atomically checks and clears a pending pause request.
     */
    public boolean consumePauseRequest() {
        synchronized (LOCK) {
            boolean requested = prefs.getBoolean(KEY_PAUSE, false);
            if (requested) {
                commitOrThrow(
                        prefs.edit().putBoolean(KEY_PAUSE, false),
                        "consume tournament pause"
                );
            }
            return requested;
        }
    }

    public void clearPauseRequest() {
        synchronized (LOCK) {
            commitOrThrow(
                    prefs.edit().putBoolean(KEY_PAUSE, false),
                    "clear tournament pause"
            );
        }
    }

    public void clear() {
        synchronized (LOCK) {
            commitOrThrow(prefs.edit().clear(), "clear tournament checkpoint");
        }
    }

    private static void commitOrThrow(SharedPreferences.Editor editor, String action) {
        if (!editor.commit()) {
            throw new IllegalStateException("Failed to " + action);
        }
    }

    private static String encodeMap(Map<String, Integer> map) {
        StringBuilder out = new StringBuilder();
        if (map == null) {
            return "";
        }
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            out.append(escape(e.getKey()))
                    .append('\t')
                    .append(e.getValue().intValue())
                    .append('\n');
        }
        return out.toString();
    }

    private static void decodeMap(String raw, Map<String, Integer> out) {
        if (raw == null || raw.isEmpty() || out == null) {
            return;
        }

        for (String line : raw.split("\\n")) {
            int tab = findUnescapedTab(line);
            if (tab <= 0 || tab >= line.length() - 1) {
                continue;
            }
            try {
                String key = unescape(line.substring(0, tab));
                int value = Integer.parseInt(line.substring(tab + 1));
                if (!key.isEmpty() && value >= 0) {
                    out.put(key, value);
                }
            } catch (RuntimeException ignored) {
                // Ignore only this malformed entry; preserve the rest of state.
            }
        }
    }

    private static int findUnescapedTab(String value) {
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
            } else if (c == '\t') {
                return i;
            }
        }
        return -1;
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '\t': out.append("\\t"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!escaped) {
                if (c == '\\') {
                    escaped = true;
                } else {
                    out.append(c);
                }
                continue;
            }

            switch (c) {
                case 't': out.append('\t'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case '\\': out.append('\\'); break;
                default:
                    // Preserve unknown legacy escape sequences instead of
                    // silently deleting the slash.
                    out.append('\\').append(c);
                    break;
            }
            escaped = false;
        }
        if (escaped) {
            out.append('\\');
        }
        return out.toString();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
