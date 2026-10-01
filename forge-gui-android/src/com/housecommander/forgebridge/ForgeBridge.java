/*
 * HOUSE Commander Lab Forge bridge.
 *
 * Compiled inside the Card-Forge/forge source tree so HOUSE can execute the
 * real Forge Commander rules engine and AI in-process.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import com.google.common.eventbus.Subscribe;

import forge.Forge;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.Event;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.BuildInfo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Strict native Forge bridge used by HOUSE Commander Lab.
 *
 * Bridge 0.7 separates two different concepts that bridge 0.6 incorrectly
 * treated as the same clock:
 *
 *  1. hardTimeoutSeconds  - absolute wall-clock ceiling for a game
 *  2. stallTimeoutSeconds - maximum time with no observable Forge progress
 *
 * Observable progress is driven by Forge's own Game EventBus. Any real game
 * event refreshes an atomic heartbeat, so the watchdog does not poll Forge's
 * mutable GameLog from a second thread. A long but progressing Commander game
 * may run all the way to the hard ceiling; a genuinely silent/frozen game is
 * stopped earlier.
 */
public final class ForgeBridge {
    private static final String HOUSE_BRIDGE_VERSION = "0.7";
    private static final long WATCHDOG_POLL_MILLIS = 1000L;
    private static final long INTERRUPT_GRACE_SECONDS = 2L;
    private static final long GAMEOVER_GRACE_SECONDS = 3L;

    /*
     * Java cannot safely force-kill an arbitrary thread. If Forge ignores both
     * game-over signalling and interruption after a watchdog abort, allowing a
     * second game in the same process could corrupt shared engine state. In
     * that rare case the bridge poisons itself until the app process restarts.
     */
    private static final AtomicBoolean ENGINE_POISONED = new AtomicBoolean(false);
    private static volatile String poisonReason = "";

    private ForgeBridge() {
    }

    /**
     * True only when Forge's DB is fully initialized and this process has not
     * been poisoned by an unkillable prior simulation thread.
     */
    public static boolean isAvailable() {
        if (ENGINE_POISONED.get()) {
            return false;
        }
        try {
            if (!Forge.afterDBloaded) {
                return false;
            }
            return FModel.getMagicDb() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String status() {
        if (ENGINE_POISONED.get()) {
            return "POISONED — restart app process required: " + poisonReason;
        }
        try {
            if (!Forge.afterDBloaded) {
                return "linked — Forge card database loading";
            }
            if (FModel.getMagicDb() == null) {
                return "linked — Forge card database unavailable";
            }
            return "READY";
        } catch (Throwable t) {
            return "unavailable — " + safeMessage(t);
        }
    }

    public static String version() {
        return "Forge "
                + BuildInfo.getVersionString()
                + " / HOUSE bridge "
                + HOUSE_BRIDGE_VERSION;
    }

    /**
     * Legacy bridge-0.6 ABI retained so an older adapter fails safely rather
     * than with NoSuchMethodException. It now uses a three-minute stall guard.
     */
    @Deprecated
    public static String runCommanderGame(
            String[] deckPaths,
            String logPath,
            int clockSeconds
    ) throws Exception {
        int hard = Math.max(1, clockSeconds);
        int stall = Math.min(hard, 180);
        return runCommanderGame(deckPaths, logPath, hard, stall);
    }

    /**
     * Runs exactly one literal multiplayer Commander game using Forge AI.
     */
    public static String runCommanderGame(
            String[] deckPaths,
            String logPath,
            int hardTimeoutSeconds,
            int stallTimeoutSeconds
    ) throws Exception {
        validateRunArguments(deckPaths, hardTimeoutSeconds, stallTimeoutSeconds);

        if (!isAvailable()) {
            throw new IllegalStateException(
                    "Forge engine/card database is not ready: " + status()
            );
        }

        final List<RegisteredPlayer> players = loadPlayers(deckPaths);

        final GameRules rules = new GameRules(GameType.Commander);
        rules.setAppliedVariants(EnumSet.of(GameType.Commander));
        rules.setGamesPerMatch(1);

        /*
         * Forge's own simulation timeout is the absolute ceiling. The HOUSE
         * no-progress watchdog is implemented separately below.
         */
        rules.setSimTimeout(hardTimeoutSeconds);

        final Match match = new Match(
                rules,
                players,
                "HOUSE Commander Lab"
        );
        final Game game = match.createGame();
        final long startedNs = System.nanoTime();
        final ProgressHeartbeat heartbeat = new ProgressHeartbeat(startedNs);
        game.subscribeToEvents(heartbeat);

        final ExecutorService executor = Executors.newSingleThreadExecutor(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "HOUSE-Forge-Game");
                        t.setDaemon(true);
                        return t;
                    }
                }
        );

        final long hardTimeoutNs = TimeUnit.SECONDS.toNanos(hardTimeoutSeconds);
        final long stallTimeoutNs = TimeUnit.SECONDS.toNanos(stallTimeoutSeconds);

        final Future<?> future = executor.submit(new Runnable() {
            @Override
            public void run() {
                match.startGame(game);
            }
        });

        long elapsedMs = 0L;
        boolean normalCompletion = false;

        try {
            while (true) {
                long nowNs = System.nanoTime();
                long elapsedNs = nowNs - startedNs;

                if (elapsedNs >= hardTimeoutNs) {
                    elapsedMs = elapsedMillis(startedNs);
                    ProgressSnapshot snapshot = trySnapshot(game);
                    TimeoutException failure = new TimeoutException(
                            "Forge Commander game reached the hard ceiling of "
                                    + hardTimeoutSeconds
                                    + " seconds"
                                    + " ["
                                    + heartbeat.describe()
                                    + "; "
                                    + snapshotText(snapshot)
                                    + "]"
                    );

                    abortGame(
                            future,
                            executor,
                            game,
                            failure,
                            "hard timeout"
                    );
                    writeFailureLog(
                            game,
                            logPath,
                            elapsedMs,
                            failure,
                            "HOUSE_ERROR=HARD_TIMEOUT",
                            "HOUSE_HARD_TIMEOUT_SECONDS=" + hardTimeoutSeconds,
                            "HOUSE_STALL_TIMEOUT_SECONDS=" + stallTimeoutSeconds,
                            "HOUSE_WATCHDOG=" + heartbeat.describe(),
                            "HOUSE_LAST_PROGRESS=" + snapshotText(snapshot)
                    );
                    throw failure;
                }

                long remainingHardNs = hardTimeoutNs - elapsedNs;
                long waitNs = Math.min(
                        TimeUnit.MILLISECONDS.toNanos(WATCHDOG_POLL_MILLIS),
                        remainingHardNs
                );

                try {
                    future.get(waitNs, TimeUnit.NANOSECONDS);
                    normalCompletion = true;
                    elapsedMs = elapsedMillis(startedNs);
                    break;
                } catch (TimeoutException pollExpired) {
                    // Expected watchdog poll; this is not itself a game timeout.
                }

                nowNs = System.nanoTime();
                long stalledNs = nowNs - heartbeat.lastActivityNs();
                if (stalledNs >= stallTimeoutNs) {
                    elapsedMs = elapsedMillis(startedNs);
                    ProgressSnapshot current = trySnapshot(game);
                    TimeoutException failure = new TimeoutException(
                            "Forge Commander game emitted no progress event for "
                                    + stallTimeoutSeconds
                                    + " seconds"
                                    + " ["
                                    + heartbeat.describe()
                                    + "; "
                                    + snapshotText(current)
                                    + "]"
                    );

                    abortGame(
                            future,
                            executor,
                            game,
                            failure,
                            "stall timeout"
                    );
                    writeFailureLog(
                            game,
                            logPath,
                            elapsedMs,
                            failure,
                            "HOUSE_ERROR=STALL_TIMEOUT",
                            "HOUSE_HARD_TIMEOUT_SECONDS=" + hardTimeoutSeconds,
                            "HOUSE_STALL_TIMEOUT_SECONDS=" + stallTimeoutSeconds,
                            "HOUSE_WATCHDOG=" + heartbeat.describe(),
                            "HOUSE_LAST_PROGRESS=" + snapshotText(current)
                    );
                    throw failure;
                }
            }
        } catch (InterruptedException interrupted) {
            elapsedMs = elapsedMillis(startedNs);

            /*
             * Future.get() clears the interrupt flag when it throws. Abort and
             * wait for the Forge worker first, then restore the caller's flag;
             * otherwise awaitTermination() would immediately self-interrupt.
             */
            abortGame(
                    future,
                    executor,
                    game,
                    interrupted,
                    "caller interrupted"
            );
            Thread.currentThread().interrupt();
            writeFailureLog(
                    game,
                    logPath,
                    elapsedMs,
                    interrupted,
                    "HOUSE_ERROR=INTERRUPTED",
                    "HOUSE_HARD_TIMEOUT_SECONDS=" + hardTimeoutSeconds,
                    "HOUSE_STALL_TIMEOUT_SECONDS=" + stallTimeoutSeconds,
                    "HOUSE_WATCHDOG=" + heartbeat.describe(),
                    "HOUSE_LAST_PROGRESS=" + snapshotText(trySnapshot(game))
            );
            throw interrupted;
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause() == null
                    ? execution
                    : execution.getCause();
            elapsedMs = elapsedMillis(startedNs);

            shutdownCompletedExecutor(executor);
            writeFailureLog(
                    game,
                    logPath,
                    elapsedMs,
                    cause,
                    "HOUSE_ERROR=" + cause.getClass().getName() + ": " + safeMessage(cause),
                    "HOUSE_HARD_TIMEOUT_SECONDS=" + hardTimeoutSeconds,
                    "HOUSE_STALL_TIMEOUT_SECONDS=" + stallTimeoutSeconds,
                    "HOUSE_WATCHDOG=" + heartbeat.describe(),
                    "HOUSE_LAST_PROGRESS=" + snapshotText(trySnapshot(game))
            );

            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException(cause);
        } finally {
            if (normalCompletion) {
                shutdownCompletedExecutor(executor);
            } else if (!executor.isShutdown()) {
                executor.shutdownNow();
            }
        }

        validateOutcomeAndLog(
                game,
                logPath,
                elapsedMs,
                hardTimeoutSeconds,
                stallTimeoutSeconds,
                heartbeat
        );

        String winner = game.getOutcome()
                .getWinningLobbyPlayer()
                .getName()
                .trim();
        return winner;
    }

    private static void validateRunArguments(
            String[] deckPaths,
            int hardTimeoutSeconds,
            int stallTimeoutSeconds
    ) {
        if (ENGINE_POISONED.get()) {
            throw new IllegalStateException(
                    "Forge bridge is poisoned from a prior non-terminating game; "
                            + "restart the app process before running another game. "
                            + poisonReason
            );
        }
        if (deckPaths == null || deckPaths.length < 2) {
            throw new IllegalArgumentException("At least two Commander decks are required");
        }
        if (hardTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Hard timeout must be at least 1 second");
        }
        if (stallTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Stall timeout must be at least 1 second");
        }
        if (stallTimeoutSeconds > hardTimeoutSeconds) {
            throw new IllegalArgumentException(
                    "Stall timeout must not exceed the hard timeout"
            );
        }
    }

    private static List<RegisteredPlayer> loadPlayers(String[] deckPaths) throws IOException {
        List<RegisteredPlayer> players = new ArrayList<RegisteredPlayer>(deckPaths.length);

        for (int i = 0; i < deckPaths.length; i++) {
            String path = deckPaths[i];
            if (path == null || path.trim().isEmpty()) {
                throw new IllegalArgumentException("Deck path " + i + " is empty");
            }

            File file = new File(path);
            if (!file.isFile()) {
                throw new IOException("Deck file not found: " + file.getAbsolutePath());
            }
            if (!file.canRead()) {
                throw new IOException("Deck file is not readable: " + file.getAbsolutePath());
            }

            final Deck deck;
            try {
                deck = DeckSerializer.fromFile(file);
            } catch (Throwable t) {
                throw new IOException(
                        "Forge failed while loading deck: "
                                + file.getAbsolutePath()
                                + " -- "
                                + safeMessage(t),
                        t
                );
            }

            if (deck == null) {
                throw new IOException("Forge could not parse deck: " + file.getAbsolutePath());
            }

            String playerName = deck.getName();
            if (playerName == null || playerName.trim().isEmpty()) {
                playerName = file.getName();
            }

            RegisteredPlayer registered = RegisteredPlayer.forCommander(deck);
            registered.setPlayer(
                    GamePlayerUtil.createAiPlayer(
                            playerName,
                            0,
                            0
                    )
            );
            players.add(registered);
        }

        return players;
    }

    private static void validateOutcomeAndLog(
            Game game,
            String logPath,
            long elapsedMs,
            int hardTimeoutSeconds,
            int stallTimeoutSeconds,
            ProgressHeartbeat heartbeat
    ) throws Exception {
        if (game.getOutcome() == null) {
            IllegalStateException failure = new IllegalStateException(
                    "Forge Commander game ended without an outcome"
            );
            writeFailureLog(
                    game,
                    logPath,
                    elapsedMs,
                    failure,
                    "HOUSE_ERROR=NO_OUTCOME",
                    "HOUSE_HARD_TIMEOUT_SECONDS=" + hardTimeoutSeconds,
                    "HOUSE_STALL_TIMEOUT_SECONDS=" + stallTimeoutSeconds,
                    "HOUSE_WATCHDOG=" + heartbeat.describe()
            );
            throw failure;
        }

        if (game.getOutcome().isDraw()) {
            IllegalStateException failure = new IllegalStateException(
                    "Forge Commander game ended in a draw; HOUSE will not guess a winner"
            );
            writeFailureLog(
                    game,
                    logPath,
                    elapsedMs,
                    failure,
                    "HOUSE_ERROR=DRAW",
                    "HOUSE_HARD_TIMEOUT_SECONDS=" + hardTimeoutSeconds,
                    "HOUSE_STALL_TIMEOUT_SECONDS=" + stallTimeoutSeconds,
                    "HOUSE_WATCHDOG=" + heartbeat.describe()
            );
            throw failure;
        }

        if (game.getOutcome().getWinningLobbyPlayer() == null) {
            IllegalStateException failure = new IllegalStateException(
                    "Forge returned no winning lobby player"
            );
            writeFailureLog(
                    game,
                    logPath,
                    elapsedMs,
                    failure,
                    "HOUSE_ERROR=NO_WINNER",
                    "HOUSE_HARD_TIMEOUT_SECONDS=" + hardTimeoutSeconds,
                    "HOUSE_STALL_TIMEOUT_SECONDS=" + stallTimeoutSeconds,
                    "HOUSE_WATCHDOG=" + heartbeat.describe()
            );
            throw failure;
        }

        String winner = game.getOutcome().getWinningLobbyPlayer().getName();
        if (winner == null || winner.trim().isEmpty()) {
            IllegalStateException failure = new IllegalStateException(
                    "Forge returned a winning player with no name"
            );
            writeFailureLog(
                    game,
                    logPath,
                    elapsedMs,
                    failure,
                    "HOUSE_ERROR=EMPTY_WINNER",
                    "HOUSE_HARD_TIMEOUT_SECONDS=" + hardTimeoutSeconds,
                    "HOUSE_STALL_TIMEOUT_SECONDS=" + stallTimeoutSeconds,
                    "HOUSE_WATCHDOG=" + heartbeat.describe()
            );
            throw failure;
        }

        writeLog(
                game,
                logPath,
                elapsedMs,
                "HOUSE_HARD_TIMEOUT_SECONDS=" + hardTimeoutSeconds,
                "HOUSE_STALL_TIMEOUT_SECONDS=" + stallTimeoutSeconds,
                "HOUSE_WATCHDOG=" + heartbeat.describe(),
                "HOUSE_FINAL_PROGRESS=" + snapshotText(trySnapshot(game)),
                "HOUSE_WINNER=" + winner.trim()
        );
    }

    /**
     * Signals game-over, interrupts the simulation worker, and verifies that
     * the worker actually dies. If it does not, the bridge is poisoned so no
     * later game can run concurrently with a zombie Forge simulation.
     */
    private static void abortGame(
            Future<?> future,
            ExecutorService executor,
            Game game,
            Throwable originalFailure,
            String reason
    ) {
        /*
         * First ask the worker to stop using interruption only. This avoids
         * mutating Game state from the watchdog thread unless Forge ignores the
         * normal cancellation path.
         */
        future.cancel(true);
        executor.shutdownNow();

        boolean terminated = awaitTermination(
                executor,
                INTERRUPT_GRACE_SECONDS,
                originalFailure
        );

        if (!terminated) {
            /*
             * Escalation: Forge ignored interruption. setGameOver() is
             * synchronized inside Forge and gives its game loop an explicit
             * terminal state to observe.
             */
            forceDrawIfNeeded(game);
            terminated = awaitTermination(
                    executor,
                    GAMEOVER_GRACE_SECONDS,
                    originalFailure
            );
        }

        if (!terminated) {
            long totalGrace = INTERRUPT_GRACE_SECONDS + GAMEOVER_GRACE_SECONDS;
            String message = "Forge simulation thread did not terminate within "
                    + totalGrace
                    + " seconds after "
                    + reason;
            poisonEngine(message);
            if (originalFailure != null) {
                originalFailure.addSuppressed(new IllegalStateException(message));
            }
        }
    }

    private static boolean awaitTermination(
            ExecutorService executor,
            long seconds,
            Throwable originalFailure
    ) {
        try {
            return executor.awaitTermination(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException waitInterrupted) {
            Thread.currentThread().interrupt();
            if (originalFailure != null) {
                originalFailure.addSuppressed(waitInterrupted);
            }
            return false;
        }
    }

    private static void shutdownCompletedExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(INTERRUPT_GRACE_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(GAMEOVER_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    poisonEngine("Completed Forge simulation executor would not terminate");
                }
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void poisonEngine(String reason) {
        poisonReason = reason == null ? "unknown non-terminating simulation" : reason;
        ENGINE_POISONED.set(true);
    }

    private static void forceDrawIfNeeded(Game game) {
        try {
            if (game != null && game.getOutcome() == null) {
                game.setGameOver(GameEndReason.Draw);
            }
        } catch (Throwable ignored) {
            // Cleanup only; never hide the original failure.
        }
    }

    /**
     * Read only cheap, high-level fields that indicate the engine is moving.
     * Any concurrent-read failure returns null and is handled conservatively by
     * the watchdog.
     */
    private static ProgressSnapshot trySnapshot(Game game) {
        try {
            if (game == null) {
                return null;
            }

            PhaseHandler phaseHandler = game.getPhaseHandler();
            int turn = -1;
            String phase = "<none>";
            String activePlayer = "<none>";

            if (phaseHandler != null) {
                turn = phaseHandler.getTurn();
                Object phaseValue = phaseHandler.getPhase();
                phase = phaseValue == null ? "<none>" : String.valueOf(phaseValue);

                Player player = phaseHandler.getPlayerTurn();
                if (player != null) {
                    String name = player.getName();
                    activePlayer = name == null ? "<unnamed>" : name;
                }
            }

            int logEntries = 0;
            if (game.getGameLog() != null) {
                List<GameLogEntry> entries = game.getGameLog().getAllEntries();
                logEntries = entries == null ? 0 : entries.size();
            }

            boolean gameOver = game.isGameOver();
            boolean hasOutcome = game.getOutcome() != null;

            return new ProgressSnapshot(
                    turn,
                    phase,
                    activePlayer,
                    logEntries,
                    gameOver,
                    hasOutcome
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class ProgressHeartbeat {
        private final AtomicLong lastActivityNs;
        private final AtomicLong eventCount = new AtomicLong(0L);
        private volatile String lastEvent = "<none>";

        private ProgressHeartbeat(long startedNs) {
            this.lastActivityNs = new AtomicLong(startedNs);
        }

        @Subscribe
        public void onGameEvent(Event event) {
            lastEvent = event == null
                    ? "<null>"
                    : event.getClass().getSimpleName();
            eventCount.incrementAndGet();
            lastActivityNs.set(System.nanoTime());
        }

        private long lastActivityNs() {
            return lastActivityNs.get();
        }

        private String describe() {
            long idleMs = TimeUnit.NANOSECONDS.toMillis(
                    Math.max(0L, System.nanoTime() - lastActivityNs.get())
            );
            return "events="
                    + eventCount.get()
                    + ",lastEvent="
                    + sanitize(lastEvent)
                    + ",idleMs="
                    + idleMs;
        }
    }

    private static final class ProgressSnapshot {
        private final int turn;
        private final String phase;
        private final String activePlayer;
        private final int logEntries;
        private final boolean gameOver;
        private final boolean hasOutcome;

        private ProgressSnapshot(
                int turn,
                String phase,
                String activePlayer,
                int logEntries,
                boolean gameOver,
                boolean hasOutcome
        ) {
            this.turn = turn;
            this.phase = phase;
            this.activePlayer = activePlayer;
            this.logEntries = logEntries;
            this.gameOver = gameOver;
            this.hasOutcome = hasOutcome;
        }


        @Override
        public String toString() {
            return "turn=" + turn
                    + ",phase=" + sanitize(phase)
                    + ",active=" + sanitize(activePlayer)
                    + ",logEntries=" + logEntries
                    + ",gameOver=" + gameOver
                    + ",hasOutcome=" + hasOutcome;
        }
    }

    private static String snapshotText(ProgressSnapshot snapshot) {
        return snapshot == null ? "unavailable" : snapshot.toString();
    }

    /**
     * Failure logging is best-effort and can never mask the original engine
     * exception. Runtime errors from concurrent Forge state reads are also
     * attached as suppressed errors rather than replacing the root failure.
     */
    private static void writeFailureLog(
            Game game,
            String logPath,
            long elapsedMs,
            Throwable originalFailure,
            String... markers
    ) {
        try {
            writeLog(game, logPath, elapsedMs, markers);
        } catch (Throwable logFailure) {
            if (originalFailure != null && logFailure != originalFailure) {
                originalFailure.addSuppressed(logFailure);
            }

            /*
             * If the literal Forge log itself cannot be read safely after a
             * catastrophic worker failure, still leave a small HOUSE diagnostic
             * file instead of leaving no evidence at all.
             */
            try {
                writeMinimalFailureLog(logPath, elapsedMs, logFailure, markers);
            } catch (Throwable fallbackFailure) {
                if (originalFailure != null && fallbackFailure != originalFailure) {
                    originalFailure.addSuppressed(fallbackFailure);
                }
            }
        }
    }

    private static void writeMinimalFailureLog(
            String logPath,
            long elapsedMs,
            Throwable logFailure,
            String... markers
    ) throws IOException {
        if (logPath == null || logPath.trim().isEmpty()) {
            return;
        }

        File file = new File(logPath);
        File parent = file.getParentFile();
        if (parent != null
                && !parent.exists()
                && !parent.mkdirs()
                && !parent.isDirectory()) {
            throw new IOException("Could not create log directory: " + parent.getAbsolutePath());
        }

        try (BufferedWriter out = new BufferedWriter(new FileWriter(file, false))) {
            out.write("HOUSE_ENGINE=" + version());
            out.newLine();
            out.write("HOUSE_ELAPSED_MS=" + elapsedMs);
            out.newLine();
            out.write("HOUSE_LOG_CAPTURE_ERROR="
                    + logFailure.getClass().getName()
                    + ": "
                    + safeMessage(logFailure));
            out.newLine();

            if (markers != null) {
                for (String marker : markers) {
                    if (marker == null || marker.trim().isEmpty()) {
                        continue;
                    }
                    out.write(sanitize(marker));
                    out.newLine();
                }
            }
        }
    }

    private static void writeLog(
            Game game,
            String logPath,
            long elapsedMs,
            String... markers
    ) throws IOException {
        if (logPath == null || logPath.trim().isEmpty()) {
            return;
        }
        if (game == null) {
            throw new IOException("Cannot write HOUSE game log: Game is null");
        }

        File file = new File(logPath);
        File parent = file.getParentFile();
        if (parent != null
                && !parent.exists()
                && !parent.mkdirs()
                && !parent.isDirectory()) {
            throw new IOException("Could not create log directory: " + parent.getAbsolutePath());
        }

        List<GameLogEntry> entries = game.getGameLog().getAllEntries();

        try (BufferedWriter out = new BufferedWriter(new FileWriter(file, false))) {
            out.write("HOUSE_ENGINE=" + version());
            out.newLine();
            out.write("HOUSE_ELAPSED_MS=" + elapsedMs);
            out.newLine();

            if (entries != null) {
                for (GameLogEntry entry : entries) {
                    out.write(String.valueOf(entry));
                    out.newLine();
                }
            }

            if (markers != null) {
                for (String marker : markers) {
                    if (marker == null || marker.trim().isEmpty()) {
                        continue;
                    }
                    out.write(sanitize(marker));
                    out.newLine();
                }
            }
        }
    }

    private static long elapsedMillis(long startedNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return sanitize(message);
    }
}
