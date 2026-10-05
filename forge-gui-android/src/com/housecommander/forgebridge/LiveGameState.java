/*
 * HOUSE Commander Lab live spectator state.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.housecommander.spectator.FocusState;

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
    private final String priorityPlayer;
    private final String respondingPlayer;
    private final List<TargetLink> links;

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
        this(sequence, lastEvent, turn, phase, activePlayer, players, stack,
                gameOver, winner, "", "", Collections.emptyList());
    }

    public LiveGameState(long sequence, String lastEvent, int turn, String phase,
            String activePlayer, List<PlayerState> players, List<String> stack,
            boolean gameOver, String winner, String priorityPlayer,
            String respondingPlayer, List<TargetLink> links) {
        this.sequence = sequence;
        this.lastEvent = safe(lastEvent);
        this.turn = turn;
        this.phase = safe(phase);
        this.activePlayer = safe(activePlayer);
        this.players = immutable(players);
        this.stack = immutableStrings(stack);
        this.gameOver = gameOver;
        this.winner = safe(winner);
        this.priorityPlayer = safe(priorityPlayer);
        this.respondingPlayer = safe(respondingPlayer);
        this.links = immutable(links);
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

    public String priorityPlayer() { return priorityPlayer; }
    public String respondingPlayer() { return respondingPlayer; }
    public List<TargetLink> links() { return links; }
    public FocusState focusState() {
        if (gameOver) return FocusState.GAME_END;
        if (lastEvent.contains("PlayerLost")) return FocusState.ELIMINATION;
        if (!stack.isEmpty()) return FocusState.STACK;
        for (TargetLink link : links) {
            if (link.kind != TargetLink.Kind.TARGET) return FocusState.COMBAT;
        }
        if (!priorityPlayer.isEmpty() && !priorityPlayer.equals(activePlayer)) {
            return FocusState.PRIORITY;
        }
        return FocusState.ACTIVE_TURN;
    }

    /** Source/target identity comes from Forge, never from a card's display name. */
    public static final class TargetLink {
        public enum Kind { ATTACK, BLOCK, TARGET }
        public final int sourceId;
        public final int targetId;
        public final String targetPlayer;
        public final String label;
        public final Kind kind;
        public TargetLink(int sourceId, int targetId, String targetPlayer,
                String label, Kind kind) {
            this.sourceId = sourceId;
            this.targetId = targetId;
            this.targetPlayer = safe(targetPlayer);
            this.label = safe(label);
            this.kind = kind;
        }
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
        private final List<String> commanders;
        private final List<String> graveyard;
        private final List<String> exile;
        private final List<String> commanderDamage;

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
            this(name, life, poison, handCount, libraryCount, lost, battlefield,
                    command, commanders, graveyard, exile, Collections.emptyList());
        }

        public PlayerState(String name, int life, int poison, int handCount,
                int libraryCount, boolean lost, List<CardState> battlefield,
                List<String> command, List<String> commanders, List<String> graveyard,
                List<String> exile, List<String> commanderDamage) {
            this.name = safe(name);
            this.life = life;
            this.poison = poison;
            this.handCount = handCount;
            this.libraryCount = libraryCount;
            this.lost = lost;
            this.battlefield = immutable(battlefield);
            this.command = immutableStrings(command);
            this.commanders = immutableStrings(commanders);
            this.graveyard = immutableStrings(graveyard);
            this.exile = immutableStrings(exile);
            this.commanderDamage = immutableStrings(commanderDamage);
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

        public List<String> graveyard() {
            return graveyard;
        }

        public List<String> exile() {
            return exile;
        }
        public List<String> commanderDamage() { return commanderDamage; }
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
        private final int id;
        private final int damage;
        private final boolean deathtouchDamage;
        private final String typeLine;
        private final String colors;
        private final List<String> keywords;
        private final String oracle;

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
            this(name, imageKey, imageUrl, tapped, token, faceDown, creature, land,
                    attacking, blocking, power, toughness, counters, -1, 0, false,
                    "", "", Collections.emptyList());
        }

        public CardState(String name, String imageKey, String imageUrl,
                boolean tapped, boolean token, boolean faceDown, boolean creature,
                boolean land, boolean attacking, boolean blocking, int power,
                int toughness, List<String> counters, int id, int damage,
                boolean deathtouchDamage, String typeLine, String colors,
                List<String> keywords) {
            this(name, imageKey, imageUrl, tapped, token, faceDown, creature, land,
                    attacking, blocking, power, toughness, counters, id, damage,
                    deathtouchDamage, typeLine, colors, keywords, "");
        }

        public CardState(String name, String imageKey, String imageUrl,
                boolean tapped, boolean token, boolean faceDown, boolean creature,
                boolean land, boolean attacking, boolean blocking, int power,
                int toughness, List<String> counters, int id, int damage,
                boolean deathtouchDamage, String typeLine, String colors,
                List<String> keywords, String oracle) {
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
            this.id = id;
            this.damage = damage;
            this.deathtouchDamage = deathtouchDamage;
            this.typeLine = safe(typeLine);
            this.colors = safe(colors);
            this.keywords = immutableStrings(keywords);
            this.oracle = safe(oracle);
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
        public int id() { return id; }
        public int damage() { return damage; }
        public boolean deathtouchDamage() { return deathtouchDamage; }
        public String typeLine() { return typeLine; }
        public String colors() { return colors; }
        public List<String> keywords() { return keywords; }
        public String oracle() { return oracle; }
    }
}
