/*
 * HOUSE Commander Lab live spectator state.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable, UI-safe snapshot of a literal Forge game.
 *
 * <p>The mutable Forge model never crosses into the HOUSE UI thread. The bridge
 * captures this value on Forge's game-event thread and publishes it atomically.
 */
public final class LiveGameState {
    private final long sequence;
    private final String lastEvent;
    private final int turn;
    private final String phase;
    private final String activePlayer;
    private final List<PlayerState> players;
    private final List<String> stack;
    private final boolean gameOver;
    private final String winner;

    public LiveGameState(
            long sequence,
            String lastEvent,
            int turn,
            String phase,
            String activePlayer,
            List<PlayerState> players,
            List<String> stack,
            boolean gameOver,
            String winner
    ) {
        this.sequence = sequence;
        this.lastEvent = safe(lastEvent);
        this.turn = turn;
        this.phase = safe(phase);
        this.activePlayer = safe(activePlayer);
        this.players = immutable(players);
        this.stack = immutableStrings(stack);
        this.gameOver = gameOver;
        this.winner = safe(winner);
    }

    public static LiveGameState idle() {
        return new LiveGameState(
                0L,
                "IDLE",
                0,
                "Waiting",
                "",
                Collections.emptyList(),
                Collections.emptyList(),
                false,
                ""
        );
    }

    public static LiveGameState starting() {
        return new LiveGameState(
                1L,
                "STARTING",
                0,
                "Starting",
                "",
                Collections.emptyList(),
                Collections.emptyList(),
                false,
                ""
        );
    }

    public long sequence() {
        return sequence;
    }

    public String lastEvent() {
        return lastEvent;
    }

    public int turn() {
        return turn;
    }

    public String phase() {
        return phase;
    }

    public String activePlayer() {
        return activePlayer;
    }

    public List<PlayerState> players() {
        return players;
    }

    public List<String> stack() {
        return stack;
    }

    public boolean gameOver() {
        return gameOver;
    }

    public String winner() {
        return winner;
    }

    private static <T> List<T> immutable(List<T> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }

    private static List<String> immutableStrings(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>(source.size());
        for (String value : source) {
            out.add(safe(value));
        }
        return Collections.unmodifiableList(out);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** Read-only player state for the spectator board. */
    public static final class PlayerState {
        private final String name;
        private final int life;
        private final int poison;
        private final int handCount;
        private final int libraryCount;
        private final boolean lost;
        private final List<CardState> battlefield;
        private final List<String> command;
        private final List<String> graveyard;
        private final List<String> exile;

        public PlayerState(
                String name,
                int life,
                int poison,
                int handCount,
                int libraryCount,
                boolean lost,
                List<CardState> battlefield,
                List<String> command,
                List<String> graveyard,
                List<String> exile
        ) {
            this.name = safe(name);
            this.life = life;
            this.poison = poison;
            this.handCount = handCount;
            this.libraryCount = libraryCount;
            this.lost = lost;
            this.battlefield = immutable(battlefield);
            this.command = immutableStrings(command);
            this.graveyard = immutableStrings(graveyard);
            this.exile = immutableStrings(exile);
        }

        public String name() {
            return name;
        }

        public int life() {
            return life;
        }

        public int poison() {
            return poison;
        }

        public int handCount() {
            return handCount;
        }

        public int libraryCount() {
            return libraryCount;
        }

        public boolean lost() {
            return lost;
        }

        public List<CardState> battlefield() {
            return battlefield;
        }

        public List<String> command() {
            return command;
        }

        public List<String> graveyard() {
            return graveyard;
        }

        public List<String> exile() {
            return exile;
        }
    }

    /** Read-only public permanent state for battlefield rendering. */
    public static final class CardState {
        private final String name;
        private final boolean tapped;
        private final boolean token;
        private final boolean faceDown;
        private final boolean creature;
        private final boolean land;
        private final int power;
        private final int toughness;

        public CardState(
                String name,
                boolean tapped,
                boolean token,
                boolean faceDown,
                boolean creature,
                boolean land,
                int power,
                int toughness
        ) {
            this.name = safe(name);
            this.tapped = tapped;
            this.token = token;
            this.faceDown = faceDown;
            this.creature = creature;
            this.land = land;
            this.power = power;
            this.toughness = toughness;
        }

        public String name() {
            return name;
        }

        public boolean tapped() {
            return tapped;
        }

        public boolean token() {
            return token;
        }

        public boolean faceDown() {
            return faceDown;
        }

        public boolean creature() {
            return creature;
        }

        public boolean land() {
            return land;
        }

        public int power() {
            return power;
        }

        public int toughness() {
            return toughness;
        }
    }
}
