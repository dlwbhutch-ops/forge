package com.housecommander.desktop;

import com.housecommander.core.DeckSpec;
import com.housecommander.core.HousePackage;
import com.housecommander.core.Names;
import com.housecommander.core.PodSpec;
import com.housecommander.core.RosterBuilder;
import com.housecommander.forgebridge.ForgeBridge;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DesktopTournamentRunner {
    public interface Listener {
        void onState(DesktopStateStore.State state);
        void onEngineStatus(String status);
    }

    private static final int HARD_TIMEOUT_SECONDS = 60 * 60;
    private static final int STALL_TIMEOUT_SECONDS = 3 * 60;

    private final DesktopStateStore stateStore = new DesktopStateStore();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private volatile boolean active;

    public DesktopTournamentRunner(Listener listener) {
        this.listener = listener;
    }

    public boolean isActive() {
        return active;
    }

    public DesktopStateStore.State state() {
        return stateStore.load();
    }

    public boolean hasTournamentArtifacts() {
        DesktopStateStore.State state = stateStore.load();
        boolean progress = state.totalGames > 0L
                || state.completedGauntlets() > 0
                || state.nextPodIndex > 0;
        try {
            File results = DesktopResultsWriter.resultsFile();
            return active || progress || (results.isFile() && results.length() > 0L);
        } catch (IOException ignored) {
            return active || progress;
        }
    }

    public void bootstrapEngine() {
        if (active) {
            return;
        }
        active = true;
        executor.execute(() -> {
            try {
                DesktopForgeBootstrap.ensureReady(this::emitEngine);
                DesktopStateStore.State state = stateStore.load();
                if ("IDLE".equals(state.status) || "BLOCKED".equals(state.status)) {
                    state.status = "READY";
                    state.lastMessage = "Forge engine ready";
                    stateStore.save(state);
                    emit(state);
                }
            } catch (Throwable error) {
                fail(error);
            } finally {
                active = false;
            }
        });
    }

    public void runOneLiteralGame() {
        if (active) {
            return;
        }
        active = true;
        executor.execute(() -> {
            try {
                DesktopForgeBootstrap.ensureReady(this::emitEngine);
                HousePackage pack = HouseDesktopRuntime.loadActivePackage();
                if (pack.schedule().isEmpty()) {
                    throw new IllegalStateException("HOUSE schedule contains no pods");
                }

                DesktopStateStore.State state = stateStore.load();
                state.status = "TESTING";
                state.lastMessage = "Running one literal Forge test game";
                stateStore.save(state);
                emit(state);

                PodSpec pod = pack.schedule().get(0);
                List<DeckSpec> decks = resolvePod(pack, pod);
                File log = new File(HouseDesktopPaths.logsDir(), "desktop-test-game.log");
                String winner = ForgeBridge.runCommanderGame(
                        deckPaths(decks),
                        log.getAbsolutePath(),
                        HARD_TIMEOUT_SECONDS,
                        STALL_TIMEOUT_SECONDS
                );
                String displayWinner = validateWinner(decks, winner);

                state = stateStore.load();
                state.status = "TEST_COMPLETE";
                state.lastMessage = "Test winner: " + displayWinner + " • " + ForgeBridge.version();
                stateStore.save(state);
                emit(state);
            } catch (Throwable error) {
                fail(error);
            } finally {
                active = false;
            }
        });
    }

    public void runPilotGame(DeckSpec pilotDeck) {
        if (active || pilotDeck == null) {
            return;
        }
        active = true;
        executor.execute(() -> {
            try {
                DesktopForgeBootstrap.ensureReady(this::emitEngine);
                HousePackage pack = HouseDesktopRuntime.loadActivePackage();
                List<DeckSpec> decks = pilotPod(pack, pilotDeck);

                DesktopStateStore.State state = stateStore.load();
                state.status = "PILOTING";
                state.lastMessage = "Piloting " + pilotDeck.deck() + " vs 3 Forge AI";
                stateStore.save(state);
                emit(state);

                File log = new File(HouseDesktopPaths.logsDir(), "desktop-pilot-game.log");
                String winner = ForgeBridge.runCommanderGameWithPilot(
                        deckPaths(decks),
                        0,
                        log.getAbsolutePath(),
                        HARD_TIMEOUT_SECONDS,
                        STALL_TIMEOUT_SECONDS
                );
                String displayWinner = validateWinner(decks, winner);

                state = stateStore.load();
                state.status = "PILOT_COMPLETE";
                state.lastMessage = "Pilot game winner: "
                        + displayWinner
                        + " • "
                        + ForgeBridge.version();
                stateStore.save(state);
                emit(state);
            } catch (Throwable error) {
                fail(error);
            } finally {
                active = false;
            }
        });
    }

    public void runTournament(int requestedGauntlets) {
        if (active) {
            return;
        }
        active = true;
        executor.execute(() -> {
            try {
                DesktopForgeBootstrap.ensureReady(this::emitEngine);
                HousePackage pack = HouseDesktopRuntime.loadActivePackage();
                if (pack.schedule().isEmpty()) {
                    throw new IllegalStateException("HOUSE schedule contains no pods");
                }

                int podCount = pack.schedule().size();
                String rosterKey = RosterBuilder.fingerprint(pack.decks());
                DesktopStateStore.State state = prepareRunState(
                        stateStore.load(),
                        Math.max(1, requestedGauntlets),
                        podCount,
                        rosterKey
                );

                if ("COMPLETE".equals(state.status)
                        && requestedGauntlets <= state.completedGauntlets()) {
                    state.lastMessage = "Already completed "
                            + state.completedGauntlets()
                            + " gauntlets — reset explicitly to start over";
                    stateStore.save(state);
                    emit(state);
                    return;
                }

                stateStore.clearPauseRequest();
                state.status = "RUNNING";
                state.lastMessage = "Resuming HOUSE run at gauntlet " + state.currentGauntlet;
                stateStore.save(state);
                emit(state);

                for (int g = state.currentGauntlet; g <= state.targetGauntlets; g++) {
                    state = stateStore.load();
                    state.currentGauntlet = g;
                    validateCheckpoint(state, podCount);
                    stateStore.save(state);

                    for (int i = state.nextPodIndex; i < podCount; i++) {
                        if (stateStore.consumePauseRequest()) {
                            state = stateStore.load();
                            state.status = "PAUSED";
                            state.lastMessage = "Paused at gauntlet "
                                    + g + ", next pod " + (i + 1) + "/" + podCount;
                            stateStore.save(state);
                            emit(state);
                            return;
                        }

                        PodSpec pod = pack.schedule().get(i);
                        List<DeckSpec> decks = resolvePod(pack, pod);

                        state = stateStore.load();
                        state.status = "RUNNING";
                        state.currentGauntlet = g;
                        state.nextPodIndex = i;
                        state.lastMessage = "G" + g + "/" + state.targetGauntlets
                                + " • R" + pod.round() + " P" + pod.pod()
                                + " • game " + (i + 1) + "/" + podCount;
                        stateStore.save(state);
                        emit(state);

                        File log = logFile(g, i + 1, pod);
                        String winner = ForgeBridge.runCommanderGame(
                                deckPaths(decks),
                                log.getAbsolutePath(),
                                HARD_TIMEOUT_SECONDS,
                                STALL_TIMEOUT_SECONDS
                        );
                        String displayWinner = validateWinner(decks, winner);

                        state = stateStore.load();
                        state.currentGauntlet = g;
                        for (DeckSpec d : decks) {
                            increment(state.games, d.deck());
                        }
                        increment(state.wins, displayWinner);
                        state.totalGames++;
                        state.nextPodIndex = i + 1;
                        state.lastMessage = "Winner: " + displayWinner
                                + " • G" + g + " game " + (i + 1) + "/" + podCount;
                        stateStore.save(state);
                        emit(state);
                    }

                    state = stateStore.load();
                    validateCheckpoint(state, podCount);
                    if (state.nextPodIndex != podCount) {
                        throw new IllegalStateException(
                                "Cannot finalize gauntlet " + g
                                        + ": nextPodIndex=" + state.nextPodIndex
                                        + ", expected=" + podCount
                        );
                    }

                    DesktopResultsWriter.writeGauntlet(
                            pack,
                            g,
                            state.wins,
                            state.games
                    );

                    state.wins.clear();
                    state.games.clear();
                    state.currentGauntlet = g + 1;
                    state.nextPodIndex = 0;
                    state.lastMessage = "Completed gauntlet "
                            + g + "/" + state.targetGauntlets;
                    stateStore.save(state);
                    emit(state);
                }

                state = stateStore.load();
                state.status = "COMPLETE";
                state.lastMessage = "Completed "
                        + state.completedGauntlets()
                        + " gauntlets • "
                        + state.totalGames
                        + " literal games";
                stateStore.save(state);
                emit(state);
            } catch (Throwable error) {
                fail(error);
            } finally {
                active = false;
            }
        });
    }

    public void requestPause() {
        try {
            stateStore.requestPause();
            DesktopStateStore.State state = stateStore.load();
            if (active) {
                state.lastMessage = "Pause requested — finishing current game";
                stateStore.save(state);
            }
            emit(state);
        } catch (Throwable error) {
            fail(error);
        }
    }

    public synchronized void reset() throws IOException {
        if (active) {
            throw new IOException("Pause the active run before resetting");
        }
        stateStore.clear();
        DesktopResultsWriter.reset();
        deleteRecursively(HouseDesktopPaths.logsDir());
        HouseDesktopPaths.logsDir();

        DesktopStateStore.State state = new DesktopStateStore.State();
        state.status = "IDLE";
        state.lastMessage = "Tournament checkpoint and run artifacts reset";
        stateStore.save(state);
        emit(state);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private DesktopStateStore.State prepareRunState(
            DesktopStateStore.State state,
            int requestedGauntlets,
            int podCount,
            String rosterKey
    ) throws IOException {
        validateCheckpoint(state, podCount);

        boolean hasProgress = state.totalGames > 0L
                || state.completedGauntlets() > 0
                || state.nextPodIndex > 0;

        String saved = state.rosterKey == null ? "" : state.rosterKey.trim();
        if (hasProgress && !saved.isEmpty() && !saved.equals(rosterKey)) {
            throw new IllegalStateException(
                    "Active tournament roster differs from the saved checkpoint. "
                            + "Restore the prior roster or reset the tournament first."
            );
        }
        if (hasProgress && saved.isEmpty()) {
            state.rosterKey = rosterKey;
            stateStore.save(state);
        }

        if (!hasProgress || state.targetGauntlets <= 0) {
            File results = DesktopResultsWriter.resultsFile();
            if (results.isFile() && results.length() > 0L) {
                throw new IllegalStateException(
                        "Checkpoint is empty but a prior HOUSE results file exists. "
                                + "Reset explicitly before starting a new run."
                );
            }
            DesktopStateStore.State fresh = new DesktopStateStore.State();
            fresh.status = "RUNNING";
            fresh.targetGauntlets = requestedGauntlets;
            fresh.currentGauntlet = 1;
            fresh.rosterKey = rosterKey;
            stateStore.save(fresh);
            return fresh;
        }

        int completed = state.completedGauntlets();
        if ("COMPLETE".equals(state.status)) {
            if (requestedGauntlets > completed) {
                state.targetGauntlets = Math.max(state.targetGauntlets, requestedGauntlets);
                state.currentGauntlet = completed + 1;
                state.nextPodIndex = 0;
                state.status = "RUNNING";
                state.lastMessage = "Extending completed run from "
                        + completed + " to " + state.targetGauntlets + " gauntlets";
                stateStore.save(state);
            }
            return state;
        }

        state.targetGauntlets = Math.max(
                Math.max(1, state.targetGauntlets),
                requestedGauntlets
        );
        state.status = "RUNNING";
        state.lastMessage = "Resuming existing checkpoint";
        stateStore.save(state);
        return state;
    }

    private static void validateCheckpoint(DesktopStateStore.State state, int podCount) {
        if (state == null) {
            throw new IllegalStateException("Tournament checkpoint is null");
        }
        if (state.currentGauntlet < 1) {
            throw new IllegalStateException("Invalid currentGauntlet: " + state.currentGauntlet);
        }
        if (state.nextPodIndex < 0 || state.nextPodIndex > podCount) {
            throw new IllegalStateException("Invalid nextPodIndex: " + state.nextPodIndex);
        }
    }

    private static List<DeckSpec> pilotPod(
            HousePackage pack,
            DeckSpec pilotDeck
    ) {
        List<DeckSpec> decks = new ArrayList<DeckSpec>();
        decks.add(pilotDeck);

        String pilotName = Names.canonical(pilotDeck.deck());
        for (DeckSpec deck : pack.decks()) {
            if (decks.size() >= 4) {
                break;
            }
            if (deck == null || Names.canonical(deck.deck()).equals(pilotName)) {
                continue;
            }
            decks.add(deck);
        }

        if (decks.size() != 4) {
            throw new IllegalStateException(
                    "Pilot mode needs four distinct Commander decks; resolved "
                            + decks.size()
            );
        }
        return decks;
    }

    private static List<DeckSpec> resolvePod(HousePackage pack, PodSpec pod) {
        List<DeckSpec> decks = new ArrayList<DeckSpec>();
        for (String member : pod.members()) {
            DeckSpec deck = pack.deckNamed(member);
            if (deck == null) {
                throw new IllegalStateException("Schedule references missing deck: " + member);
            }
            decks.add(deck);
        }
        return decks;
    }

    private static String[] deckPaths(List<DeckSpec> decks) throws IOException {
        String[] paths = new String[decks.size()];
        for (int i = 0; i < decks.size(); i++) {
            File file = HouseDesktopRuntime.deckFile(decks.get(i));
            if (!file.isFile() || !file.canRead()) {
                throw new IOException("Deck file missing: " + file.getAbsolutePath());
            }
            paths[i] = file.getAbsolutePath();
        }
        return paths;
    }

    private static String validateWinner(List<DeckSpec> decks, String winner) {
        String canonical = Names.canonical(winner);
        for (DeckSpec d : decks) {
            if (Names.canonical(d.deck()).equals(canonical)
                    || Names.canonical(d.engineName()).equals(canonical)) {
                return d.deck();
            }
            String fileName = new File(d.dck()).getName();
            int dot = fileName.lastIndexOf('.');
            if (dot > 0) {
                fileName = fileName.substring(0, dot);
            }
            if (Names.canonical(fileName.replace('_', ' ')).equals(canonical)) {
                return d.deck();
            }
        }
        throw new IllegalStateException("Forge winner was not a member of the current pod: " + winner);
    }

    private static File logFile(int gauntlet, int sequence, PodSpec pod) throws IOException {
        File dir = new File(
                HouseDesktopPaths.logsDir(),
                "g" + String.format(Locale.US, "%04d", gauntlet)
        );
        HouseDesktopPaths.ensureDirectory(dir);
        return new File(
                dir,
                String.format(
                        Locale.US,
                        "game_%03d_r%02d_p%02d.log",
                        sequence,
                        pod.round(),
                        pod.pod()
                )
        );
    }

    private void fail(Throwable error) {
        DesktopStateStore.State state = stateStore.load();
        state.status = "BLOCKED";
        state.lastMessage = safeMessage(error);
        try {
            stateStore.save(state);
        } catch (IOException ignored) {
            // Keep original failure as the visible error.
        }
        emit(state);
    }

    private void emit(DesktopStateStore.State state) {
        if (listener != null) {
            listener.onState(state);
        }
    }

    private void emitEngine(String status) {
        if (listener != null) {
            listener.onEngineStatus(status);
        }
    }

    private static void increment(Map<String, Integer> values, String key) {
        Integer current = values.get(key);
        values.put(key, current == null ? 1 : current + 1);
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("Could not list directory: " + file.getAbsolutePath());
            }
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            throw new IOException("Could not delete: " + file.getAbsolutePath());
        }
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.trim().isEmpty()
                ? root.getClass().getSimpleName()
                : message.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
