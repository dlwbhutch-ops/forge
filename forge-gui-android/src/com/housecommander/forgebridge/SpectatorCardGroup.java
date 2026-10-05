/*
 * HOUSE Commander Lab spectator card grouping.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * Collapses visually equivalent battlefield permanents into one spectator tile.
 *
 * <p>This is deliberately UI-agnostic. A token-heavy Forge game can expose
 * hundreds or thousands of permanents without forcing Swing or Android to
 * allocate one widget per permanent.
 */
public final class SpectatorCardGroup {
    private final LiveGameState.CardState card;
    private final int count;
    private final List<Integer> cardIds;

    private SpectatorCardGroup(LiveGameState.CardState card, int count, List<Integer> cardIds) {
        this.card = card;
        this.count = count;
        this.cardIds = Collections.unmodifiableList(new ArrayList<>(cardIds));
    }

    public LiveGameState.CardState card() {
        return card;
    }

    public int count() {
        return count;
    }
    public List<Integer> cardIds() { return cardIds; }

    public static List<SpectatorCardGroup> group(
            List<LiveGameState.CardState> cards
    ) {
        Map<String, MutableGroup> grouped = new LinkedHashMap<String, MutableGroup>();
        if (cards != null) {
            for (LiveGameState.CardState card : cards) {
                if (card == null) {
                    continue;
                }
                String key = key(card);
                MutableGroup existing = grouped.get(key);
                if (existing == null) {
                    grouped.put(key, new MutableGroup(card));
                } else {
                    existing.count++;
                    existing.ids.add(card.id());
                }
            }
        }

        List<SpectatorCardGroup> out =
                new ArrayList<SpectatorCardGroup>(grouped.size());
        for (MutableGroup group : grouped.values()) {
            out.add(new SpectatorCardGroup(group.card, group.count, group.ids));
        }
        return out;
    }

    private static String key(LiveGameState.CardState card) {
        StringBuilder out = new StringBuilder();
        out.append(card.name()).append('\u001f')
                .append(card.token() ? card.imageUrl() : card.imageKey()).append('\u001f')
                .append(card.tapped()).append('\u001f')
                .append(card.token()).append('\u001f')
                .append(card.faceDown()).append('\u001f')
                .append(card.creature()).append('\u001f')
                .append(card.land()).append('\u001f')
                .append(card.attacking()).append('\u001f')
                .append(card.blocking()).append('\u001f')
                .append(card.power()).append('\u001f')
                .append(card.toughness()).append('\u001f')
                .append(card.damage()).append('\u001f')
                .append(card.deathtouchDamage()).append('\u001f')
                .append(card.typeLine()).append('\u001f')
                .append(card.colors()).append('\u001f')
                .append(card.keywords()).append('\u001f').append(card.oracle());
        for (String counter : card.counters()) {
            out.append('\u001f').append(counter);
        }
        return out.toString();
    }

    private static final class MutableGroup {
        private final LiveGameState.CardState card;
        private int count = 1;
        private final List<Integer> ids = new ArrayList<>();

        private MutableGroup(LiveGameState.CardState card) {
            this.card = card;
            ids.add(card.id());
        }
    }
}
