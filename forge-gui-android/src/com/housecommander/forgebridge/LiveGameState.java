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
    private final List<StackState> stackStates;
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
        this(
                sequence,
                lastEvent,
                turn,
                phase,
                activePlayer,
                players,
                stack,
                Collections.emptyList(),
                gameOver,
                winner
        );
    }

    public LiveGameState(
            long sequence,
            String lastEvent,
            int turn,
            String phase,
            String activePlayer,
            List<PlayerState> players,
            List<String> stack,
            List<StackState> stackStates,
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
        this.stackStates = immutable(stackStates);
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

    public List<StackState> stackStates() {
        return stackStates;
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

    /** Read-only structured stack state, including literal Forge targets. */
    public static final class StackState {
        private final String source;
        private final String description;
        private final String activatingPlayer;
        private final List<String> targets;

        public StackState(
                String source,
                String description,
                String activatingPlayer,
                List<String> targets
        ) {
            this.source = safe(source);
            this.description = safe(description);
            this.activatingPlayer = safe(activatingPlayer);
            this.targets = immutableStrings(targets);
        }

        public String source() {
            return source;
        }

        public String description() {
            return description;
        }

        public String activatingPlayer() {
            return activatingPlayer;
        }

        public List<String> targets() {
            return targets;
        }
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
        private final List<String> commanders;
        private final List<CommanderDamageState> commanderDamage;
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
                List<String> commanders,
                List<String> graveyard,
                List<String> exile
        ) {
            this(
                    name,
                    life,
                    poison,
                    handCount,
                    libraryCount,
                    lost,
                    battlefield,
                    command,
                    commanders,
                    Collections.emptyList(),
                    graveyard,
                    exile
            );
        }

        public PlayerState(
                String name,
                int life,
                int poison,
                int handCount,
                int libraryCount,
                boolean lost,
                List<CardState> battlefield,
                List<String> command,
                List<String> commanders,
                List<CommanderDamageState> commanderDamage,
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
            this.commanders = immutableStrings(commanders);
            this.commanderDamage = immutable(commanderDamage);
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

        public List<String> commanders() {
            return commanders;
        }

        public List<CommanderDamageState> commanderDamage() {
            return commanderDamage;
        }

        public List<String> graveyard() {
            return graveyard;
        }

        public List<String> exile() {
            return exile;
        }
    }

    /** Commander combat damage received by a player from one commander. */
    public static final class CommanderDamageState {
        private final String commander;
        private final int damage;

        public CommanderDamageState(String commander, int damage) {
            this.commander = safe(commander);
            this.damage = Math.max(0, damage);
        }

        public String commander() {
            return commander;
        }

        public int damage() {
            return damage;
        }
    }

    /** Read-only public permanent state for battlefield rendering. */
    public static final class CardState {
        private final String name;
        private final String imageKey;
        private final String imageUrl;
        private final boolean tapped;
        private final boolean token;
        private final boolean faceDown;
        private final boolean creature;
        private final boolean land;
        private final boolean attacking;
        private final boolean blocking;
        private final int power;
        private final int toughness;
        private final List<String> counters;
        private final String typeLine;
        private final String colorKey;

        public CardState(
                String name,
                String imageKey,
                String imageUrl,
                boolean tapped,
                boolean token,
                boolean faceDown,
                boolean creature,
                boolean land,
                boolean attacking,
                boolean blocking,
                int power,
                int toughness,
                List<String> counters
        ) {
            this(
                    name,
                    imageKey,
                    imageUrl,
                    tapped,
                    token,
                    faceDown,
                    creature,
                    land,
                    attacking,
                    blocking,
                    power,
                    toughness,
                    counters,
                    "",
                    ""
            );
        }

        public CardState(
                String name,
                String imageKey,
                String imageUrl,
                boolean tapped,
                boolean token,
                boolean faceDown,
                boolean creature,
                boolean land,
                boolean attacking,
                boolean blocking,
                int power,
                int toughness,
                List<String> counters,
                String typeLine,
                String colorKey
        ) {
            this.name = safe(name);
            this.imageKey = safe(imageKey);
            this.imageUrl = safe(imageUrl);
            this.tapped = tapped;
            this.token = token;
            this.faceDown = faceDown;
            this.creature = creature;
            this.land = land;
            this.attacking = attacking;
            this.blocking = blocking;
            this.power = power;
            this.toughness = toughness;
            this.counters = immutableStrings(counters);
            this.typeLine = safe(typeLine);
            this.colorKey = safe(colorKey);
        }

        public String name() {
            return name;
        }

        public String imageKey() {
            return imageKey;
        }

        public String imageUrl() {
            return imageUrl;
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

        public boolean attacking() {
            return attacking;
        }

        public boolean blocking() {
            return blocking;
        }

        public int power() {
            return power;
        }

        public int toughness() {
            return toughness;
        }

        public List<String> counters() {
            return counters;
        }

        public String typeLine() {
            return typeLine;
        }

        public String colorKey() {
            return colorKey;
        }
    }
}
