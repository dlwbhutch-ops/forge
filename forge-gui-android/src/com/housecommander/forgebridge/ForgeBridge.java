/*
 * HOUSE Commander Lab Forge bridge.
 *
 * This file is compiled inside the Card-Forge/forge source tree so HOUSE
 * Commander Lab can call Forge's real Commander rules engine and AI.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.housecommander.forgebridge;

import forge.Forge;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;

import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Strict native Forge bridge used by HOUSE Commander Lab.
 *
 * Public ABI expected by ForgeEngineAdapter:
 *
 *     isAvailable()
 *     version()
 *     runCommanderGame(String[] deckPaths, String logPath, int clockSeconds)
 *
 * Do not change those method names or signatures without also changing
 * ForgeEngineAdapter.
 */
public final class ForgeBridge {

    private static final String HOUSE_BRIDGE_VERSION = "0.6";

    private ForgeBridge() {
    }

    /**
     * Returns true only after Forge has completed its real database-loading
     * stage.
     *
     * IMPORTANT:
     *
     * Do NOT call FModel.getMagicDb() before Forge.afterDBloaded becomes true.
     *
     * FModel's Magic database is memoized. Touching it before Forge has created
     * its CardStorageReader instances can initialize the database too early.
     */
    public static boolean isAvailable() {
        try {
            /*
             * Forge.afterDBloaded is set by Forge itself after FModel.initialize()
             * and the database preparation stage have completed.
             *
             * The short-circuit here is intentional. We must not touch
             * FModel.getMagicDb() until afterDBloaded is true.
             */
            if (!Forge.afterDBloaded) {
                return false;
            }

            return FModel.getMagicDb() != null;

        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Human-readable engine/bridge version returned to HOUSE.
     */
    public static String version() {
        return "Forge "
                + BuildInfo.getVersionString()
                + " / HOUSE bridge "
                + HOUSE_BRIDGE_VERSION;
    }

    /**
     * Runs exactly one real multiplayer Commander game using Forge AI.
     *
     * Each AI player's name is deliberately set to Deck.getName().
     * ForgeEngineAdapter uses the returned winner name to identify the
     * winning HOUSE deck.
     *
     * Draws, timeouts, missing decks, parse failures and Forge engine
     * exceptions are strict errors. HOUSE never fabricates a winner.
     */
    public static String runCommanderGame(
            String[] deckPaths,
            String logPath,
            int clockSeconds
    ) throws Exception {

        if (!isAvailable()) {
            throw new IllegalStateException(
                    "Forge engine/card database is not ready yet"
            );
        }

        if (deckPaths == null || deckPaths.length < 2) {
            throw new IllegalArgumentException(
                    "At least two Commander decks are required"
            );
        }

        final int timeoutSeconds = Math.max(1, clockSeconds);

        /*
         * -----------------------------------------------------------------
         * LOAD DECKS AND CREATE REAL FORGE COMMANDER PLAYERS
         * -----------------------------------------------------------------
         */
        final List<RegisteredPlayer> players =
                new ArrayList<RegisteredPlayer>(deckPaths.length);

        for (int i = 0; i < deckPaths.length; i++) {

            final String path = deckPaths[i];

            if (path == null || path.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Deck path " + i + " is empty"
                );
            }

            final File file = new File(path);

            if (!file.isFile()) {
                throw new IOException(
                        "Deck file not found: " + file.getAbsolutePath()
                );
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
                throw new IOException(
                        "Forge could not parse deck: "
                                + file.getAbsolutePath()
                );
            }

            String playerName = deck.getName();

            if (playerName == null || playerName.trim().isEmpty()) {
                /*
                 * This should normally never happen with a proper Forge .dck,
                 * but do not give Forge a blank LobbyPlayer name.
                 */
                playerName = file.getName();
            }

            final RegisteredPlayer registered =
                    RegisteredPlayer.forCommander(deck);

            /*
             * Explicit avatar=0 and sleeve=0.
             *
             * DO NOT use:
             *
             *     createAiPlayer(playerName, i)
             *
             * The second integer parameter is an avatar index, not a player
             * number.
             *
             * Using the 3-argument overload also avoids asking the GUI layer
             * to randomly choose avatar/sleeve resources.
             */
            registered.setPlayer(
                    GamePlayerUtil.createAiPlayer(
                            playerName,
                            0,
                            0
                    )
            );

            players.add(registered);
        }

        /*
         * -----------------------------------------------------------------
         * REAL COMMANDER RULES
         * -----------------------------------------------------------------
         */
        final GameRules rules = new GameRules(GameType.Commander);

        rules.setAppliedVariants(
                EnumSet.of(GameType.Commander)
        );

        /*
         * HOUSE wants one literal game, not a best-of-three match.
         */
        rules.setGamesPerMatch(1);

        /*
         * Forge's own simulation timeout.
         *
         * We ALSO enforce a wall-clock timeout below so HOUSE cannot become
         * stuck forever if an engine game hangs.
         */
        rules.setSimTimeout(timeoutSeconds);

        /*
         * -----------------------------------------------------------------
         * CREATE THE MATCH
         * -----------------------------------------------------------------
         */
        final Match match = new Match(
                rules,
                players,
                "HOUSE Commander Lab"
        );

        final Game game = match.createGame();

        /*
         * Do not run the potentially long Forge AI game on HOUSE's caller/UI
         * thread.
         */
        final ExecutorService executor =
                Executors.newSingleThreadExecutor();

        final long startedNs = System.nanoTime();

        final Future<?> future = executor.submit(new Runnable() {
            @Override
            public void run() {
                match.startGame(game);
            }
        });

        long elapsedMs;

        /*
         * -----------------------------------------------------------------
         * RUN THE GAME
         * -----------------------------------------------------------------
         */
        try {

            future.get(
                    timeoutSeconds,
                    TimeUnit.SECONDS
            );

            elapsedMs = elapsedMillis(startedNs);

        } catch (TimeoutException timeoutCause) {

            future.cancel(true);

            forceDrawIfNeeded(game);

            elapsedMs = elapsedMillis(startedNs);

            final TimeoutException failure =
                    new TimeoutException(
                            "Forge Commander game exceeded "
                                    + timeoutSeconds
                                    + " seconds"
                    );

            writeFailureLog(
                    game,
                    logPath,
                    elapsedMs,
                    "HOUSE_ERROR=TIMEOUT",
                    failure
            );

            throw failure;

        } catch (InterruptedException interrupted) {

            future.cancel(true);

            /*
             * Restore the interrupt flag because Future.get() clears it when
             * throwing InterruptedException.
             */
            Thread.currentThread().interrupt();

            forceDrawIfNeeded(game);

            elapsedMs = elapsedMillis(startedNs);

            writeFailureLog(
                    game,
                    logPath,
                    elapsedMs,
                    "HOUSE_ERROR=INTERRUPTED",
                    interrupted
            );

            throw interrupted;

        } catch (ExecutionException execution) {

            final Throwable cause =
                    execution.getCause() == null
                            ? execution
                            : execution.getCause();

            forceDrawIfNeeded(game);

            elapsedMs = elapsedMillis(startedNs);

            writeFailureLog(
                    game,
                    logPath,
                    elapsedMs,
                    "HOUSE_ERROR="
                            + cause.getClass().getName()
                            + ": "
                            + safeMessage(cause),
                    cause
            );

            if (cause instanceof Exception) {
                throw (Exception) cause;
            }

            throw new RuntimeException(cause);

        } finally {

            executor.shutdownNow();
        }

        /*
         * -----------------------------------------------------------------
         * STRICT OUTCOME VALIDATION
         * -----------------------------------------------------------------
         */
        if (game.getOutcome() == null) {

            final IllegalStateException failure =
                    new IllegalStateException(
                            "Forge Commander game ended without an outcome"
                    );

            writeFailureLog(
                    game,
                    logPath,
                    elapsedMs,
                    "HOUSE_ERROR=NO_OUTCOME",
                    failure
            );

            throw failure;
        }

        if (game.getOutcome().isDraw()) {

            final IllegalStateException failure =
                    new IllegalStateException(
                            "Forge Commander game ended in a draw; "
                                    + "HOUSE will not guess a winner"
                    );

            writeFailureLog(
                    game,
                    logPath,
                    elapsedMs,
                    "HOUSE_ERROR=DRAW",
                    failure
            );

            throw failure;
        }

        if (game.getOutcome().getWinningLobbyPlayer() == null) {

            final IllegalStateException failure =
                    new IllegalStateException(
                            "Forge returned no winning lobby player"
                    );

            writeFailureLog(
                    game,
                    logPath,
                    elapsedMs,
                    "HOUSE_ERROR=NO_WINNER",
                    failure
            );

            throw failure;
        }

        final String winner =
                game.getOutcome()
                        .getWinningLobbyPlayer()
                        .getName();

        if (winner == null || winner.trim().isEmpty()) {

            final IllegalStateException failure =
                    new IllegalStateException(
                            "Forge returned a winning player with no name"
                    );

            writeFailureLog(
                    game,
                    logPath,
                    elapsedMs,
                    "HOUSE_ERROR=EMPTY_WINNER",
                    failure
            );

            throw failure;
        }

        /*
         * Success logs remain strict: if HOUSE requested a log and the
         * successful game's log cannot be written, surface that I/O error
         * instead of silently pretending the audit record exists.
         */
        writeLog(
                game,
                logPath,
                elapsedMs,
                "HOUSE_WINNER=" + winner.trim()
        );

        return winner.trim();
    }

    /**
     * Marks an unfinished game as a draw when HOUSE has to abort it.
     *
     * Never overwrite an outcome Forge already produced.
     */
    private static void forceDrawIfNeeded(Game game) {
        try {
            if (game != null && game.getOutcome() == null) {
                game.setGameOver(GameEndReason.Draw);
            }
        } catch (Throwable ignored) {
            /*
             * This method is only cleanup after another failure.
             * Never hide the original exception.
             */
        }
    }

    /**
     * Write a failure log without allowing a log I/O problem to hide the
     * original Forge failure.
     */
    private static void writeFailureLog(
            Game game,
            String logPath,
            long elapsedMs,
            String marker,
            Throwable originalFailure
    ) {

        try {
            writeLog(
                    game,
                    logPath,
                    elapsedMs,
                    marker
            );
        } catch (IOException logFailure) {

            if (originalFailure != null) {
                originalFailure.addSuppressed(logFailure);
            }
        }
    }

    /**
     * Writes HOUSE's literal Forge game log.
     */
    private static void writeLog(
            Game game,
            String logPath,
            long elapsedMs,
            String marker
    ) throws IOException {

        if (logPath == null || logPath.trim().isEmpty()) {
            return;
        }

        final File file = new File(logPath);
        final File parent = file.getParentFile();

        if (parent != null
                && !parent.exists()
                && !parent.mkdirs()
                && !parent.isDirectory()) {

            throw new IOException(
                    "Could not create log directory: "
                            + parent.getAbsolutePath()
            );
        }

        /*
         * getAllEntries() is intentional.
         *
         * Forge's getLogEntries(null) returns newest-first.
         * HOUSE wants the literal game record in chronological order.
         */
        final List<GameLogEntry> entries =
                game.getGameLog().getAllEntries();

        try (BufferedWriter out =
                     new BufferedWriter(
                             new FileWriter(file, false)
                     )) {

            out.write(
                    "HOUSE_ENGINE=" + version()
            );
            out.newLine();

            out.write(
                    "HOUSE_ELAPSED_MS=" + elapsedMs
            );
            out.newLine();

            for (GameLogEntry entry : entries) {
                out.write(
                        String.valueOf(entry)
                );
                out.newLine();
            }

            out.write(marker);
            out.newLine();
        }
    }

    private static long elapsedMillis(long startedNs) {
        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedNs
        );
    }

    private static String safeMessage(Throwable t) {

        if (t == null) {
            return "Unknown error";
        }

        final String message = t.getMessage();

        if (message == null || message.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }

        return message
                .replace('\n', ' ')
                .replace('\r', ' ');
    }
}