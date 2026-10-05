package com.housecommander.spectator;

import com.housecommander.forgebridge.LiveGameState;
import com.housecommander.forgebridge.SpectatorCardGroup;
import java.util.*;

/** Shared overhead table layout. Original registration order fixes seats even after elimination. */
public final class TournamentTableView {
    public static final class Seat {
        public final LiveGameState.PlayerState player;
        public final float x, y, width, height;
        public final boolean active, priority, responding;
        public final List<SpectatorCardGroup> groups;
        private Seat(LiveGameState.PlayerState player, int index, LiveGameState state) {
            this.player = player;
            int cell = index == 2 ? 3 : (index == 3 ? 2 : index);
            x = cell % 2 == 0 ? .01f : .51f;
            y = cell < 2 ? .01f : .57f;
            width = .48f; height = .42f;
            active = !player.lost() && player.name().equals(state.activePlayer());
            priority = !player.lost() && player.name().equals(state.priorityPlayer());
            responding = !player.lost() && player.name().equals(state.respondingPlayer());
            groups = Collections.unmodifiableList(SpectatorCardGroup.group(player.battlefield()));
        }
        public boolean spotlight(BroadcastSettings settings) {
            return active || (settings.focusResponses && (priority || responding));
        }
        public String status() {
            if (player.lost()) return "ELIMINATED";
            String label = active ? "ACTIVE TURN" : "";
            if (priority) label += (label.isEmpty() ? "" : " · ") + "PRIORITY";
            if (responding) label += (label.isEmpty() ? "" : " · ") + "RESPONSE";
            return label;
        }
    }

    private final LiveGameState state;
    private final List<Seat> seats;
    public TournamentTableView(LiveGameState state) {
        this.state = state;
        List<Seat> out = new ArrayList<>();
        for (int i = 0; i < state.players().size(); i++) {
            // Commander HOUSE pods contain four players. Do not silently hide another player.
            if (i >= 4) throw new IllegalArgumentException("Broadcast table supports up to four players");
            out.add(new Seat(state.players().get(i), i, state));
        }
        seats = Collections.unmodifiableList(out);
    }
    public LiveGameState state() { return state; }
    public List<Seat> seats() { return seats; }
    public String headline() {
        if (state.gameOver()) return state.winner().isEmpty() ? "GAME OVER · DRAW" : "WINNER · " + state.winner();
        return "TURN " + state.turn() + " · " + state.phase() + " · " + state.focusState().name().replace('_', ' ');
    }

    public static String details(LiveGameState.CardState card, int count) {
        StringBuilder out = new StringBuilder(count > 1 ? "×" + count + " · " : "");
        if (card.creature()) out.append(card.power()).append('/').append(card.toughness()).append(' ');
        if (card.tapped()) out.append("TAPPED ");
        if (card.attacking()) out.append("ATTACKING ");
        if (card.blocking()) out.append("BLOCKING ");
        if (card.damage() > 0) out.append("DMG ").append(card.damage()).append(' ');
        if (card.deathtouchDamage()) out.append("DEATHTOUCH ");
        for (String counter : card.counters()) out.append(counter).append(' ');
        return out.toString().trim();
    }
}
