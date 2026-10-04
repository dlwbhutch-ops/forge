/*
 * HOUSE Commander Lab friendly spectator log.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts immutable spectator snapshots into a compact, player-facing story
 * of the game. This never replaces Forge's raw diagnostic log; it is a second,
 * UI-friendly presentation layer derived from the same literal game state.
 */
public final class FriendlyGameLog {
    public enum Category {
        TURN,
        PHASE,
        BATTLEFIELD,
        STACK,
        COMBAT,
        LIFE,
        ZONE,
        PLAYER,
        WIN
    }

    public static final class Entry {
        private final long sequence;
        private final int turn;
        private final String phase;
        private final Category category;
        private final String text;

        Entry(long sequence, int turn, String phase, Category category, String text) {
            this.sequence = sequence;
            this.turn = turn;
            this.phase = safe(phase);
            this.category = category;
            this.text = safe(text);
        }

        public long sequence() {
            return sequence;
        }

        public int turn() {
            return turn;
        }

        public String phase() {
            return phase;
        }

        public Category category() {
            return category;
        }

        public String text() {
            return text;
        }
    }

    private static final Object LOCK = new Object();
    private static final int MAX_ENTRIES = 600;

    private static final List<Entry> ENTRIES = new ArrayList<Entry>();
    private static LiveGameState previous = LiveGameState.idle();
    private static String finalSummary = "";

    private FriendlyGameLog() {
    }

    public static void reset(LiveGameState initial) {
        synchronized (LOCK) {
            ENTRIES.clear();
            previous = initial == null ? LiveGameState.idle() : initial;
            finalSummary = "";
        }
    }

    public static void record(LiveGameState state) {
        if (state == null) {
            return;
        }

        synchronized (LOCK) {
            if (previous != null
                    && previous.sequence() > 1L
                    && state.sequence() <= previous.sequence()) {
                ENTRIES.clear();
                finalSummary = "";
                previous = LiveGameState.idle();
            }

            LiveGameState before = previous == null ? LiveGameState.idle() : previous;

            if (state.turn() > 0
                    && (before.turn() != state.turn()
                    || !safe(before.activePlayer()).equals(safe(state.activePlayer())))) {
                add(state, Category.TURN,
                        "TURN " + state.turn()
                                + (state.activePlayer().isEmpty()
                                ? ""
                                : " — " + state.activePlayer()));
            }

            if (state.turn() > 0
                    && !safe(before.phase()).equals(safe(state.phase()))
                    && isInterestingPhase(state.phase())) {
                add(state, Category.PHASE, friendlyPhase(state.phase()));
            }

            Map<String, LiveGameState.PlayerState> beforePlayers = playersByName(before);
            for (LiveGameState.PlayerState now : state.players()) {
                LiveGameState.PlayerState old = beforePlayers.get(now.name());
                if (old == null) {
                    continue;
                }

                if (now.life() != old.life()) {
                    int delta = now.life() - old.life();
                    add(state, Category.LIFE,
                            "♥ " + now.name() + " "
                                    + (delta < 0
                                    ? "lost " + Math.abs(delta) + " life"
                                    : "gained " + delta + " life")
                                    + " (" + old.life() + " → " + now.life() + ")");
                }

                if (now.poison() != old.poison()) {
                    int delta = now.poison() - old.poison();
                    add(state, Category.LIFE,
                            "☠ " + now.name() + " "
                                    + (delta > 0 ? "gained " : "lost ")
                                    + Math.abs(delta) + " poison counter"
                                    + (Math.abs(delta) == 1 ? "" : "s")
                                    + " (" + old.poison() + " → " + now.poison() + ")");
                }

                appendBattlefieldChanges(state, old, now);
                appendZoneChanges(state, old, now);
                appendCombatChanges(state, old, now);

                if (!old.lost() && now.lost()) {
                    add(state, Category.PLAYER, "💀 " + now.name() + " was eliminated.");
                }
            }

            appendStackChanges(state, before, state);

            if (state.gameOver()
                    && !state.winner().isEmpty()
                    && (!before.gameOver()
                    || !state.winner().equals(before.winner()))) {
                add(state, Category.WIN, "🏆 " + state.winner() + " wins the game.");
                finalSummary = buildFinalSummary(state);
            }

            previous = state;
            trim();
        }
    }

    public static String renderFeed(long visibleSequence, int maxEntries) {
        synchronized (LOCK) {
            if (ENTRIES.isEmpty()) {
                return "Game feed is ready. Start a literal Forge game to see the story here.";
            }

            List<Entry> visible = visibleEntries(visibleSequence);
            if (visible.isEmpty()) {
                return "Game feed is waiting for the first visible action.";
            }

            int limit = Math.max(1, maxEntries);
            int start = Math.max(0, visible.size() - limit);
            StringBuilder out = new StringBuilder();

            int lastTurn = Integer.MIN_VALUE;
            for (int i = start; i < visible.size(); i++) {
                Entry entry = visible.get(i);
                if (entry.category() == Category.TURN) {
                    if (out.length() > 0) {
                        out.append("\n");
                    }
                    out.append(entry.text().replace("TURN ", "Turn ")).append("\n");
                    lastTurn = entry.turn();
                    continue;
                }
                if (entry.turn() > 0 && entry.turn() != lastTurn) {
                    if (out.length() > 0) {
                        out.append("\n");
                    }
                    out.append("Turn ").append(entry.turn()).append("\n");
                    lastTurn = entry.turn();
                }
                out.append("  ").append(entry.text()).append("\n");
            }
            return out.toString().trim();
        }
    }

    public static String renderTurnSummary(long visibleSequence) {
        synchronized (LOCK) {
            List<Entry> visible = visibleEntries(visibleSequence);
            if (visible.isEmpty()) {
                return "Turn summary will appear once play begins.";
            }

            int turn = 0;
            String turnOwner = "";
            for (int i = visible.size() - 1; i >= 0; i--) {
                Entry entry = visible.get(i);
                if (entry.turn() > 0) {
                    turn = entry.turn();
                    break;
                }
            }
            if (turn == 0) {
                return "Turn summary will appear once play begins.";
            }

            int battlefield = 0;
            int combat = 0;
            int life = 0;
            int zones = 0;
            int stack = 0;
            int players = 0;
            for (Entry entry : visible) {
                if (entry.turn() != turn) {
                    continue;
                }
                if (entry.category() == Category.TURN && entry.text().startsWith("TURN ")) {
                    int marker = entry.text().indexOf(" — ");
                    if (marker >= 0 && marker + 3 < entry.text().length()) {
                        turnOwner = entry.text().substring(marker + 3);
                    }
                }
                switch (entry.category()) {
                    case BATTLEFIELD:
                        battlefield++;
                        break;
                    case COMBAT:
                        combat++;
                        break;
                    case LIFE:
                        life++;
                        break;
                    case ZONE:
                        zones++;
                        break;
                    case STACK:
                        stack++;
                        break;
                    case PLAYER:
                        players++;
                        break;
                    default:
                        break;
                }
            }

            StringBuilder out = new StringBuilder();
            out.append("Turn ").append(turn);
            if (!turnOwner.isEmpty()) {
                out.append(" — ").append(turnOwner);
            }
            out.append("\n");
            appendCount(out, battlefield, "battlefield change", "battlefield changes");
            appendCount(out, stack, "stack action", "stack actions");
            appendCount(out, combat, "combat event", "combat events");
            appendCount(out, life, "life/poison change", "life/poison changes");
            appendCount(out, zones, "graveyard/exile/command move", "graveyard/exile/command moves");
            appendCount(out, players, "player eliminated", "players eliminated");

            if (out.indexOf("\n•") < 0) {
                out.append("\n• No major public-state changes yet.");
            }
            return out.toString();
        }
    }

    public static String renderGameSummary(long visibleSequence) {
        synchronized (LOCK) {
            if (finalSummary.isEmpty()) {
                return "";
            }
            for (int i = ENTRIES.size() - 1; i >= 0; i--) {
                Entry entry = ENTRIES.get(i);
                if (entry.category() == Category.WIN) {
                    return entry.sequence() <= visibleSequence ? finalSummary : "";
                }
            }
            return "";
        }
    }

    public static List<Entry> entries() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<Entry>(ENTRIES));
        }
    }

    private static void appendBattlefieldChanges(
            LiveGameState state,
            LiveGameState.PlayerState old,
            LiveGameState.PlayerState now
    ) {
        Map<String, Integer> before = cardCounts(old.battlefield());
        Map<String, Integer> after = cardCounts(now.battlefield());

        for (Map.Entry<String, Integer> item : after.entrySet()) {
            int oldCount = count(before, item.getKey());
            int delta = item.getValue() - oldCount;
            if (delta > 0) {
                LiveGameState.CardState sample = firstCard(now.battlefield(), item.getKey());
                String quantity = delta > 1 ? delta + "× " : "";
                String token = sample != null && sample.token() ? " token" : "";
                add(state, Category.BATTLEFIELD,
                        "✨ " + now.name() + " put " + quantity + item.getKey()
                                + token + " onto the battlefield.");
            }
        }
    }

    private static void appendZoneChanges(
            LiveGameState state,
            LiveGameState.PlayerState old,
            LiveGameState.PlayerState now
    ) {
        appendStringZoneAdds(state, now.name(), old.graveyard(), now.graveyard(),
                "💀 ", " went to the graveyard.");
        appendStringZoneAdds(state, now.name(), old.exile(), now.exile(),
                "⛔ ", " was exiled.");
        appendStringZoneAdds(state, now.name(), old.command(), now.command(),
                "👑 ", " moved to the command zone.");
    }

    private static void appendStringZoneAdds(
            LiveGameState state,
            String player,
            List<String> before,
            List<String> after,
            String prefix,
            String suffix
    ) {
        Map<String, Integer> beforeCounts = stringCounts(before);
        Map<String, Integer> afterCounts = stringCounts(after);
        for (Map.Entry<String, Integer> item : afterCounts.entrySet()) {
            int delta = item.getValue() - count(beforeCounts, item.getKey());
            if (delta <= 0) {
                continue;
            }
            add(state, Category.ZONE,
                    prefix + player + ": "
                            + (delta > 1 ? delta + "× " : "")
                            + item.getKey() + suffix);
        }
    }

    private static void appendCombatChanges(
            LiveGameState state,
            LiveGameState.PlayerState old,
            LiveGameState.PlayerState now
    ) {
        List<String> attackers = new ArrayList<String>();
        List<String> blockers = new ArrayList<String>();

        Map<String, Integer> oldAttackers = flagCounts(old.battlefield(), true);
        Map<String, Integer> newAttackers = flagCounts(now.battlefield(), true);
        collectPositiveDelta(oldAttackers, newAttackers, attackers);

        Map<String, Integer> oldBlockers = flagCounts(old.battlefield(), false);
        Map<String, Integer> newBlockers = flagCounts(now.battlefield(), false);
        collectPositiveDelta(oldBlockers, newBlockers, blockers);

        if (!attackers.isEmpty()) {
            add(state, Category.COMBAT,
                    "⚔ " + now.name() + " attacks with " + joinNames(attackers) + ".");
        }
        if (!blockers.isEmpty()) {
            add(state, Category.COMBAT,
                    "🛡 " + now.name() + " blocks with " + joinNames(blockers) + ".");
        }
    }

    private static void appendStackChanges(
            LiveGameState state,
            LiveGameState before,
            LiveGameState now
    ) {
        Map<String, Integer> oldStack = stringCounts(before.stack());
        Map<String, Integer> newStack = stringCounts(now.stack());
        for (Map.Entry<String, Integer> item : newStack.entrySet()) {
            int delta = item.getValue() - count(oldStack, item.getKey());
            for (int i = 0; i < delta; i++) {
                add(state, Category.STACK, "✨ On the stack: " + item.getKey());
            }
        }
    }

    private static String buildFinalSummary(LiveGameState state) {
        LiveGameState.PlayerState winner = null;
        for (LiveGameState.PlayerState player : state.players()) {
            if (player.name().equals(state.winner())) {
                winner = player;
                break;
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("HOW THE GAME ENDED\n");
        out.append("🏆 Winner: ").append(state.winner());
        out.append("\n• Finished on turn ").append(state.turn());

        if (winner != null) {
            out.append("\n• Final life: ").append(winner.life());
            if (winner.poison() > 0) {
                out.append(" • poison: ").append(winner.poison());
            }
            out.append("\n• Final battlefield: ")
                    .append(winner.battlefield().size())
                    .append(" permanent")
                    .append(winner.battlefield().size() == 1 ? "" : "s");
            out.append("\n• Hand: ").append(winner.handCount())
                    .append(" • Library: ").append(winner.libraryCount());
        }

        int recentCombat = 0;
        int recentStack = 0;
        int recentLife = 0;
        int floor = Math.max(1, state.turn() - 1);
        for (Entry entry : ENTRIES) {
            if (entry.turn() < floor) {
                continue;
            }
            if (entry.category() == Category.COMBAT) {
                recentCombat++;
            } else if (entry.category() == Category.STACK) {
                recentStack++;
            } else if (entry.category() == Category.LIFE) {
                recentLife++;
            }
        }

        String finish;
        if (recentCombat >= recentStack && recentCombat > 0) {
            finish = "combat pressure";
        } else if (recentStack > 0) {
            finish = "a spell/ability sequence";
        } else if (recentLife > 0) {
            finish = "life-total pressure";
        } else {
            finish = "the final board-state transition";
        }
        out.append("\n• Finish signal: ").append(finish)
                .append(" (state-derived; Forge log remains available for exact rules detail).");
        return out.toString();
    }

    private static Map<String, LiveGameState.PlayerState> playersByName(LiveGameState state) {
        Map<String, LiveGameState.PlayerState> out =
                new LinkedHashMap<String, LiveGameState.PlayerState>();
        if (state == null) {
            return out;
        }
        for (LiveGameState.PlayerState player : state.players()) {
            out.put(player.name(), player);
        }
        return out;
    }

    private static Map<String, Integer> cardCounts(List<LiveGameState.CardState> cards) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (LiveGameState.CardState card : cards) {
            increment(out, card.name());
        }
        return out;
    }

    private static Map<String, Integer> flagCounts(
            List<LiveGameState.CardState> cards,
            boolean attackers
    ) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (LiveGameState.CardState card : cards) {
            boolean active = attackers ? card.attacking() : card.blocking();
            if (active) {
                increment(out, card.name());
            }
        }
        return out;
    }

    private static Map<String, Integer> stringCounts(List<String> values) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            increment(out, safe(value));
        }
        return out;
    }

    private static void collectPositiveDelta(
            Map<String, Integer> before,
            Map<String, Integer> after,
            List<String> out
    ) {
        for (Map.Entry<String, Integer> item : after.entrySet()) {
            int delta = item.getValue() - count(before, item.getKey());
            for (int i = 0; i < delta; i++) {
                out.add(item.getKey());
            }
        }
    }

    private static LiveGameState.CardState firstCard(
            List<LiveGameState.CardState> cards,
            String name
    ) {
        for (LiveGameState.CardState card : cards) {
            if (card.name().equals(name)) {
                return card;
            }
        }
        return null;
    }

    private static void increment(Map<String, Integer> map, String key) {
        Integer current = map.get(key);
        map.put(key, current == null ? 1 : current + 1);
    }

    private static int count(Map<String, Integer> map, String key) {
        Integer value = map.get(key);
        return value == null ? 0 : value;
    }

    private static String joinNames(List<String> values) {
        if (values.isEmpty()) {
            return "";
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        if (values.size() == 2) {
            return values.get(0) + " and " + values.get(1);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(i == values.size() - 1 ? ", and " : ", ");
            }
            out.append(values.get(i));
        }
        return out.toString();
    }

    private static boolean isInterestingPhase(String phase) {
        String p = safe(phase).toUpperCase();
        return p.contains("COMBAT")
                || p.contains("MAIN1")
                || p.contains("MAIN2")
                || p.contains("END");
    }

    private static String friendlyPhase(String phase) {
        String p = safe(phase).toUpperCase();
        if (p.contains("DECLARE_ATTACK")) {
            return "⚔ Declare attackers";
        }
        if (p.contains("DECLARE_BLOCK")) {
            return "🛡 Declare blockers";
        }
        if (p.contains("COMBAT_DAMAGE")) {
            return "💥 Combat damage";
        }
        if (p.contains("BEGIN_COMBAT") || p.contains("COMBAT_BEGIN")) {
            return "⚔ Beginning combat";
        }
        if (p.contains("MAIN1")) {
            return "Main phase";
        }
        if (p.contains("MAIN2")) {
            return "Second main phase";
        }
        if (p.contains("END")) {
            return "End step";
        }
        if (p.contains("COMBAT")) {
            return "Combat";
        }
        return phase;
    }

    private static void appendCount(
            StringBuilder out,
            int count,
            String singular,
            String plural
    ) {
        if (count <= 0) {
            return;
        }
        out.append("\n• ").append(count).append(" ")
                .append(count == 1 ? singular : plural);
    }

    private static List<Entry> visibleEntries(long sequence) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry entry : ENTRIES) {
            if (entry.sequence() <= sequence) {
                out.add(entry);
            }
        }
        return out;
    }

    private static void add(LiveGameState state, Category category, String text) {
        ENTRIES.add(new Entry(
                state.sequence(),
                state.turn(),
                state.phase(),
                category,
                text
        ));
    }

    private static void trim() {
        while (ENTRIES.size() > MAX_ENTRIES) {
            ENTRIES.remove(0);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
