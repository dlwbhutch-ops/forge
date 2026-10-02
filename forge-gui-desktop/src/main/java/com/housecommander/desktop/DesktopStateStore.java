package com.housecommander.desktop;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public final class DesktopStateStore {
    public static final class State {
        public String status = "IDLE";
        public int targetGauntlets = 0;
        public int currentGauntlet = 1;
        public int nextPodIndex = 0;
        public long totalGames = 0L;
        public boolean pauseRequested = false;
        public String lastMessage = "Ready";
        public String rosterKey = "";
        public final Map<String, Integer> wins = new LinkedHashMap<String, Integer>();
        public final Map<String, Integer> games = new LinkedHashMap<String, Integer>();

        public int completedGauntlets() {
            return Math.max(0, currentGauntlet - 1);
        }
    }

    public synchronized State load() {
        State state = new State();
        try {
            File file = HouseDesktopPaths.stateFile();
            if (!file.isFile() || file.length() == 0L) {
                return state;
            }

            Properties p = new Properties();
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
                p.load(in);
            }
            state.status = p.getProperty("status", "IDLE");
            state.targetGauntlets = parseInt(p, "targetGauntlets", 0);
            state.currentGauntlet = Math.max(1, parseInt(p, "currentGauntlet", 1));
            state.nextPodIndex = Math.max(0, parseInt(p, "nextPodIndex", 0));
            state.totalGames = Math.max(0L, parseLong(p, "totalGames", 0L));
            state.pauseRequested = Boolean.parseBoolean(p.getProperty("pauseRequested", "false"));
            state.lastMessage = p.getProperty("lastMessage", "Ready");
            state.rosterKey = p.getProperty("rosterKey", "");

            for (String key : p.stringPropertyNames()) {
                if (key.startsWith("wins.")) {
                    state.wins.put(decode(key.substring(5)), parseInt(p, key, 0));
                } else if (key.startsWith("games.")) {
                    state.games.put(decode(key.substring(6)), parseInt(p, key, 0));
                }
            }
        } catch (Throwable error) {
            state.status = "BLOCKED";
            state.lastMessage = "Could not load checkpoint: " + safeMessage(error);
        }
        return state;
    }

    public synchronized void save(State state) throws IOException {
        if (state == null) {
            throw new IllegalArgumentException("State must not be null");
        }
        Properties p = new Properties();
        p.setProperty("status", safe(state.status, "IDLE"));
        p.setProperty("targetGauntlets", Integer.toString(Math.max(0, state.targetGauntlets)));
        p.setProperty("currentGauntlet", Integer.toString(Math.max(1, state.currentGauntlet)));
        p.setProperty("nextPodIndex", Integer.toString(Math.max(0, state.nextPodIndex)));
        p.setProperty("totalGames", Long.toString(Math.max(0L, state.totalGames)));
        p.setProperty("pauseRequested", Boolean.toString(state.pauseRequested));
        p.setProperty("lastMessage", safe(state.lastMessage, "Ready"));
        p.setProperty("rosterKey", state.rosterKey == null ? "" : state.rosterKey);

        for (Map.Entry<String, Integer> e : state.wins.entrySet()) {
            p.setProperty("wins." + encode(e.getKey()), Integer.toString(Math.max(0, e.getValue())));
        }
        for (Map.Entry<String, Integer> e : state.games.entrySet()) {
            p.setProperty("games." + encode(e.getKey()), Integer.toString(Math.max(0, e.getValue())));
        }

        File file = HouseDesktopPaths.stateFile();
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(temp, false);
             BufferedOutputStream out = new BufferedOutputStream(fos)) {
            p.store(out, "HOUSE Commander Lab desktop checkpoint");
            out.flush();
            fos.getFD().sync();
        }
        replace(temp, file);
    }

    public synchronized void clear() throws IOException {
        Files.deleteIfExists(HouseDesktopPaths.stateFile().toPath());
    }

    public synchronized void requestPause() throws IOException {
        State state = load();
        state.pauseRequested = true;
        save(state);
    }

    public synchronized boolean consumePauseRequest() throws IOException {
        State state = load();
        if (!state.pauseRequested) {
            return false;
        }
        state.pauseRequested = false;
        save(state);
        return true;
    }

    public synchronized void clearPauseRequest() throws IOException {
        State state = load();
        state.pauseRequested = false;
        save(state);
    }

    private static int parseInt(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseLong(Properties p, String key, long fallback) {
        try {
            return Long.parseLong(p.getProperty(key, Long.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static void replace(File source, File destination) throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException atomicFailed) {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        return message == null || message.trim().isEmpty()
                ? t.getClass().getSimpleName()
                : message.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
