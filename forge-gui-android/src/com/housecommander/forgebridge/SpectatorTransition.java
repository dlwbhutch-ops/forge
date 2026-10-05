/*
 * HOUSE Commander Lab spectator transitions.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes UI-only visual transitions between immutable Forge snapshots.
 *
 * <p>No rules decisions are made here. This is deliberately a pure diff layer
 * so animations cannot affect Forge execution, tournament results, or pilot
 * decisions.
 */
public final class SpectatorTransition {
    public enum Kind {
        TURN,
        PHASE,
        LIFE,
        POISON,
        COMMANDER_DAMAGE,
        CARD_DAMAGE,
        BATTLEFIELD_ENTER,
        BATTLEFIELD_LEAVE,
        GRAVEYARD_ADD,
        EXILE_ADD,
        COMMAND_ADD,
        TAP,
        UNTAP,
        ATTACK,
        BLOCK,
        STACK_ADD,
        STACK_REMOVE,
        GAME_OVER
    }

    public static final class Transition {
        private final Kind kind;
        private final String player;
        private final String subject;
        private final String detail;

        private Transition(Kind kind, String player, String subject, String detail) {
            this.kind = kind;
            this.player = safe(player);
            this.subject = safe(subject);
            this.detail = safe(detail);
        }

        public Kind kind() {
            return kind;
        }

        public String player() {
            return player;
        }

        public String subject() {
            return subject;
        }

        public String detail() {
            return detail;
        }

        public String displayText() {
            StringBuilder out = new StringBuilder();
            if (!player.isEmpty()) {
                out.append(player).append(" • ");
            }
            switch (kind) {
                case TURN:
                    out.append("turn ").append(detail);
                    break;
                case PHASE:
                    out.append("phase → ").append(detail);
                    break;
                case LIFE:
                    out.append("life ").append(detail);
                    break;
                case POISON:
                    out.append("poison ").append(detail);
                    break;
                case COMMANDER_DAMAGE:
                    out.append(subject).append(" commander damage ").append(detail);
                    break;
                case CARD_DAMAGE:
                    out.append(subject).append(" damage ").append(detail);
                    break;
                case BATTLEFIELD_ENTER:
                    out.append(subject).append(" → battlefield");
                    break;
                case BATTLEFIELD_LEAVE:
                    out.append(subject).append(" left battlefield");
                    break;
                case GRAVEYARD_ADD:
                    out.append(subject).append(" → graveyard");
                    break;
                case EXILE_ADD:
                    out.append(subject).append(" → exile");
                    break;
                case COMMAND_ADD:
                    out.append(subject).append(" → command zone");
                    break;
                case TAP:
                    out.append(subject).append(" tapped");
                    break;
                case UNTAP:
                    out.append(subject).append(" untapped");
                    break;
                case ATTACK:
                    out.append(subject).append(" attacks");
                    break;
                case BLOCK:
                    out.append(subject).append(" blocks");
                    break;
                case STACK_ADD:
                    out.append("STACK + ").append(subject);
                    if (!detail.isEmpty()) {
                        out.append(" → ").append(detail);
                    }
                    break;
                case STACK_REMOVE:
                    out.append("STACK − ").append(subject);
                    break;
                case GAME_OVER:
                    out.append("game over");
                    if (!subject.isEmpty()) {
                        out.append(" • winner ").append(subject);
                    }
                    break;
                default:
                    out.append(subject);
                    if (!detail.isEmpty()) {
                        out.append(" • ").append(detail);
                    }
                    break;
            }
            return out.toString();
        }
    }

    private SpectatorTransition() {
    }

    public static List<Transition> diff(
            LiveGameState previous,
            LiveGameState current
    ) {
        if (current == null || current.sequence() <= 1L) {
            return Collections.emptyList();
        }
        if (previous == null
                || previous.sequence() <= 1L
                || current.sequence() <= previous.sequence()) {
            return Collections.emptyList();
        }

        List<Transition> out = new ArrayList<Transition>();

        if (current.turn() != previous.turn()) {
            out.add(new Transition(
                    Kind.TURN,
                    current.activePlayer(),
                    "",
                    String.valueOf(current.turn())
            ));
        } else if (!current.phase().equals(previous.phase())) {
            out.add(new Transition(
                    Kind.PHASE,
                    current.activePlayer(),
                    "",
                    current.phase()
            ));
        }

        Map<String, LiveGameState.PlayerState> oldPlayers = playerMap(previous.players());
        for (LiveGameState.PlayerState now : current.players()) {
            LiveGameState.PlayerState old = oldPlayers.get(now.name());
            if (old == null) {
                continue;
            }

            if (now.life() != old.life()) {
                int delta = now.life() - old.life();
                out.add(new Transition(
                        Kind.LIFE,
                        now.name(),
                        "",
                        signed(delta) + " → " + now.life()
                ));
            }
            if (now.poison() != old.poison()) {
                int delta = now.poison() - old.poison();
                out.add(new Transition(
                        Kind.POISON,
                        now.name(),
                        "",
                        signed(delta) + " → " + now.poison()
                ));
            }

            appendCommanderDamageTransitions(
                    out,
                    now.name(),
                    old.commanderDamage(),
                    now.commanderDamage()
            );

            appendCountChanges(
                    out,
                    now.name(),
                    Kind.BATTLEFIELD_ENTER,
                    Kind.BATTLEFIELD_LEAVE,
                    cardCounts(old.battlefield()),
                    cardCounts(now.battlefield())
            );
            appendAddedStrings(
                    out,
                    now.name(),
                    Kind.GRAVEYARD_ADD,
                    old.graveyard(),
                    now.graveyard()
            );
            appendAddedStrings(
                    out,
                    now.name(),
                    Kind.EXILE_ADD,
                    old.exile(),
                    now.exile()
            );
            appendAddedStrings(
                    out,
                    now.name(),
                    Kind.COMMAND_ADD,
                    old.command(),
                    now.command()
            );

            appendCardStateTransitions(out, now.name(), old.battlefield(), now.battlefield());
        }

        appendStackTransitions(out, previous.stackStates(), current.stackStates());

        if (!previous.gameOver() && current.gameOver()) {
            out.add(new Transition(
                    Kind.GAME_OVER,
                    "",
                    current.winner(),
                    ""
            ));
        }

        return out;
    }

    private static void appendCommanderDamageTransitions(
            List<Transition> out,
            String player,
            List<LiveGameState.CommanderDamageState> before,
            List<LiveGameState.CommanderDamageState> after
    ) {
        Map<String, Integer> oldDamage = commanderDamageMap(before);
        Map<String, Integer> newDamage = commanderDamageMap(after);
        for (Map.Entry<String, Integer> entry : newDamage.entrySet()) {
            int prior = count(oldDamage, entry.getKey());
            int current = entry.getValue();
            if (current > prior) {
                out.add(new Transition(
                        Kind.COMMANDER_DAMAGE,
                        player,
                        entry.getKey(),
                        "+" + (current - prior) + " → " + current + "/21"
                ));
            }
        }
    }

    private static Map<String, Integer> commanderDamageMap(
            List<LiveGameState.CommanderDamageState> values
    ) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        if (values == null) {
            return out;
        }
        for (LiveGameState.CommanderDamageState value : values) {
            if (value == null || value.commander().isEmpty()) {
                continue;
            }
            out.put(value.commander(), value.damage());
        }
        return out;
    }

    private static void appendCardStateTransitions(
            List<Transition> out,
            String player,
            List<LiveGameState.CardState> before,
            List<LiveGameState.CardState> after
    ) {
        Map<String, LiveGameState.CardState> oldCards = representativeCards(before);
        Map<String, LiveGameState.CardState> newCards = representativeCards(after);

        for (Map.Entry<String, LiveGameState.CardState> entry : newCards.entrySet()) {
            LiveGameState.CardState old = oldCards.get(entry.getKey());
            LiveGameState.CardState now = entry.getValue();
            if (old == null) {
                continue;
            }

            if (!old.tapped() && now.tapped()) {
                out.add(new Transition(Kind.TAP, player, now.name(), ""));
            } else if (old.tapped() && !now.tapped()) {
                out.add(new Transition(Kind.UNTAP, player, now.name(), ""));
            }
            if (!old.attacking() && now.attacking()) {
                out.add(new Transition(Kind.ATTACK, player, now.name(), ""));
            }
            if (!old.blocking() && now.blocking()) {
                out.add(new Transition(Kind.BLOCK, player, now.name(), ""));
            }
            if (old.damageMarked() != now.damageMarked()
                    || old.deathtouchDamage() != now.deathtouchDamage()) {
                String detail;
                if (now.damageMarked() <= 0) {
                    detail = "cleared";
                } else {
                    int delta = now.damageMarked() - old.damageMarked();
                    StringBuilder damageText = new StringBuilder();
                    if (delta > 0) {
                        damageText.append("+").append(delta).append(" → ");
                    }
                    damageText.append(now.damageMarked());
                    if (now.lethalDamage() > 0) {
                        damageText.append("/").append(now.lethalDamage());
                    }
                    if (now.hasLethalDamageMarked()) {
                        damageText.append(" LETHAL");
                    }
                    detail = damageText.toString();
                }
                out.add(
                        new Transition(
                                Kind.CARD_DAMAGE,
                                player,
                                now.name(),
                                detail
                        )
                );
            }
        }
    }

    private static void appendStackTransitions(
            List<Transition> out,
            List<LiveGameState.StackState> before,
            List<LiveGameState.StackState> after
    ) {
        Map<String, Integer> oldCounts = stackCounts(before);
        Map<String, Integer> newCounts = stackCounts(after);

        for (Map.Entry<String, Integer> entry : newCounts.entrySet()) {
            int delta = entry.getValue() - count(oldCounts, entry.getKey());
            for (int i = 0; i < delta; i++) {
                LiveGameState.StackState state = findStack(after, entry.getKey());
                out.add(new Transition(
                        Kind.STACK_ADD,
                        state == null ? "" : state.activatingPlayer(),
                        state == null ? entry.getKey() : stackSubject(state),
                        state == null ? "" : joinTargets(state.targets())
                ));
            }
        }

        for (Map.Entry<String, Integer> entry : oldCounts.entrySet()) {
            int delta = entry.getValue() - count(newCounts, entry.getKey());
            for (int i = 0; i < delta; i++) {
                LiveGameState.StackState state = findStack(before, entry.getKey());
                out.add(new Transition(
                        Kind.STACK_REMOVE,
                        state == null ? "" : state.activatingPlayer(),
                        state == null ? entry.getKey() : stackSubject(state),
                        ""
                ));
            }
        }
    }

    private static String stackSubject(LiveGameState.StackState state) {
        if (state == null) {
            return "";
        }
        if (!state.source().isEmpty()) {
            return state.source();
        }
        return state.description();
    }

    private static String joinTargets(List<String> targets) {
        if (targets == null || targets.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String target : targets) {
            if (target == null || target.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(target);
        }
        return out.toString();
    }

    private static LiveGameState.StackState findStack(
            List<LiveGameState.StackState> states,
            String key
    ) {
        if (states == null) {
            return null;
        }
        for (LiveGameState.StackState state : states) {
            if (stackKey(state).equals(key)) {
                return state;
            }
        }
        return null;
    }

    private static Map<String, Integer> stackCounts(List<LiveGameState.StackState> states) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        if (states == null) {
            return out;
        }
        for (LiveGameState.StackState state : states) {
            String key = stackKey(state);
            out.put(key, count(out, key) + 1);
        }
        return out;
    }

    private static String stackKey(LiveGameState.StackState state) {
        if (state == null) {
            return "";
        }
        return state.source()
                + "|"
                + state.description()
                + "|"
                + state.activatingPlayer()
                + "|"
                + joinTargets(state.targets());
    }

    private static Map<String, LiveGameState.PlayerState> playerMap(
            List<LiveGameState.PlayerState> players
    ) {
        Map<String, LiveGameState.PlayerState> out =
                new HashMap<String, LiveGameState.PlayerState>();
        if (players == null) {
            return out;
        }
        for (LiveGameState.PlayerState player : players) {
            out.put(player.name(), player);
        }
        return out;
    }

    private static Map<String, LiveGameState.CardState> representativeCards(
            List<LiveGameState.CardState> cards
    ) {
        Map<String, LiveGameState.CardState> out =
                new LinkedHashMap<String, LiveGameState.CardState>();
        if (cards == null) {
            return out;
        }
        for (LiveGameState.CardState card : cards) {
            out.put(cardKey(card), card);
        }
        return out;
    }

    private static Map<String, Integer> cardCounts(List<LiveGameState.CardState> cards) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        if (cards == null) {
            return out;
        }
        for (LiveGameState.CardState card : cards) {
            String key = card.name();
            out.put(key, count(out, key) + 1);
        }
        return out;
    }

    private static String cardKey(LiveGameState.CardState card) {
        if (card == null) {
            return "";
        }
        if (card.cardId() > 0) {
            return "id:" + card.cardId();
        }
        return card.name() + "|" + card.imageKey();
    }

    private static void appendAddedStrings(
            List<Transition> out,
            String player,
            Kind kind,
            List<String> before,
            List<String> after
    ) {
        Map<String, Integer> oldCounts = stringCounts(before);
        Map<String, Integer> newCounts = stringCounts(after);
        for (Map.Entry<String, Integer> entry : newCounts.entrySet()) {
            int delta = entry.getValue() - count(oldCounts, entry.getKey());
            for (int i = 0; i < delta; i++) {
                out.add(new Transition(kind, player, entry.getKey(), ""));
            }
        }
    }

    private static Map<String, Integer> stringCounts(List<String> values) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            String key = safe(value);
            out.put(key, count(out, key) + 1);
        }
        return out;
    }

    private static void appendCountChanges(
            List<Transition> out,
            String player,
            Kind addedKind,
            Kind removedKind,
            Map<String, Integer> before,
            Map<String, Integer> after
    ) {
        for (Map.Entry<String, Integer> entry : after.entrySet()) {
            int delta = entry.getValue() - count(before, entry.getKey());
            for (int i = 0; i < delta; i++) {
                out.add(new Transition(addedKind, player, entry.getKey(), ""));
            }
        }
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            int delta = entry.getValue() - count(after, entry.getKey());
            for (int i = 0; i < delta; i++) {
                out.add(new Transition(removedKind, player, entry.getKey(), ""));
            }
        }
    }

    private static int count(Map<String, Integer> map, String key) {
        Integer value = map.get(key);
        return value == null ? 0 : value.intValue();
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
