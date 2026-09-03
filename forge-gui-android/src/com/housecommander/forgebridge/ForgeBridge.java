/*
 * HOUSE Commander Lab Forge bridge.
 *
 * This file is intended to be compiled inside the Card-Forge/forge source tree
 * so it can call Forge's real Commander rules engine and AI directly.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

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
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Minimal strict bridge used by HOUSE Commander Lab's reflection adapter.
 *
 * Expected public ABI:
 *   isAvailable()
 *   version()
 *   runCommanderGame(String[] deckPaths, String logPath, int clockSeconds)
 */
public final class ForgeBridge {
    private ForgeBridge() {}

    public static boolean isAvailable() {
        try {
            // In a Forge-Android-based build the normal Forge startup owns FModel
            // initialization and card-data loading. Touching the DB here is a strict
            // readiness check; this class deliberately does not invent a partial DB.
            return FModel.getMagicDb() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String version() {
        return "Forge " + BuildInfo.getVersionString() + " / HOUSE bridge 0.5";
    }

    /**
     * Plays exactly one real multiplayer Commander game through Forge AI.
     * Returns the winning AI player's name, which is set to Deck.getName().
     * Draws, timeouts, deck-load failures, or engine exceptions are hard errors.
     */
    public static String runCommanderGame(String[] deckPaths, String logPath, int clockSeconds) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("Forge card database is not initialized yet");
        }
        if (deckPaths == null || deckPaths.length < 2) {
            throw new IllegalArgumentException("At least two Commander decks are required");
        }

        final List<RegisteredPlayer> players = new ArrayList<RegisteredPlayer>();
        for (int i = 0; i < deckPaths.length; i++) {
            final File file = new File(deckPaths[i]);
            if (!file.isFile()) {
                throw new IOException("Deck file not found: " + file.getAbsolutePath());
            }
            final Deck deck = DeckSerializer.fromFile(file);
            if (deck == null) {
                throw new IOException("Forge could not parse deck: " + file.getAbsolutePath());
            }

            final RegisteredPlayer registered = RegisteredPlayer.forCommander(deck);
            registered.setPlayer(GamePlayerUtil.createAiPlayer(deck.getName(), i));
            players.add(registered);
        }

        final GameRules rules = new GameRules(GameType.Commander);
        rules.setAppliedVariants(EnumSet.of(GameType.Commander));
        rules.setGamesPerMatch(1);
        rules.setSimTimeout(Math.max(1, clockSeconds));

        final Match match = new Match(rules, players, "HOUSE Commander Lab");
        final Game game = match.createGame();

        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<?> future = executor.submit(new Runnable() {
            @Override
            public void run() {
                match.startGame(game);
            }
        });

        long elapsedMs;
        final long startedNs = System.nanoTime();
        try {
            future.get(Math.max(1, clockSeconds), TimeUnit.SECONDS);
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
        } catch (TimeoutException e) {
            future.cancel(true);
            game.setGameOver(GameEndReason.Draw);
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
            writeLog(game, logPath, elapsedMs, "HOUSE_ERROR=TIMEOUT");
            throw new TimeoutException("Forge Commander game exceeded " + clockSeconds + " seconds");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            game.setGameOver(GameEndReason.Draw);
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
            writeLog(game, logPath, elapsedMs, "HOUSE_ERROR=" + cause.getClass().getName() + ": " + safeMessage(cause));
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        } finally {
            executor.shutdownNow();
        }

        if (game.getOutcome() == null || game.getOutcome().isDraw()) {
            writeLog(game, logPath, elapsedMs, "HOUSE_ERROR=DRAW");
            throw new IllegalStateException("Forge Commander game ended in a draw; HOUSE will not guess a winner");
        }
        if (game.getOutcome().getWinningLobbyPlayer() == null) {
            writeLog(game, logPath, elapsedMs, "HOUSE_ERROR=NO_WINNER");
            throw new IllegalStateException("Forge returned no winning lobby player");
        }

        final String winner = game.getOutcome().getWinningLobbyPlayer().getName();
        writeLog(game, logPath, elapsedMs, "HOUSE_WINNER=" + winner);
        return winner;
    }

    private static void writeLog(Game game, String logPath, long elapsedMs, String marker) throws IOException {
        if (logPath == null || logPath.trim().isEmpty()) {
            return;
        }
        final File file = new File(logPath);
        final File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create log directory: " + parent);
        }

        List<GameLogEntry> entries = game.getGameLog().getLogEntries(null);
        if (entries == null) {
            entries = Collections.emptyList();
        }
        try (BufferedWriter out = new BufferedWriter(new FileWriter(file, false))) {
            out.write("HOUSE_ENGINE=" + version());
            out.newLine();
            out.write("HOUSE_ELAPSED_MS=" + elapsedMs);
            out.newLine();
            for (GameLogEntry entry : entries) {
                out.write(String.valueOf(entry));
                out.newLine();
            }
            out.write(marker);
            out.newLine();
        }
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null || message.trim().isEmpty() ? t.getClass().getSimpleName() : message.replace('\n', ' ');
    }
}
