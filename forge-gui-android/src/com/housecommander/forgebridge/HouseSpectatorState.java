package com.housecommander.forgebridge;

import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight live-state publisher for HOUSE spectator mode.
 *
 * Snapshots are built on Forge's game-event thread and exposed as immutable
 * text. The Android UI only reads the latest published String, so it never
 * walks Forge's mutable game state from the UI thread.
 */
public final class HouseSpectatorState {
    private static final long MIN_PUBLISH_INTERVAL_NS =
            TimeUnit.MILLISECONDS.toNanos(250L);
    private static final int MAX_BATTLEFIELD_NAMES = 10;

    private static final AtomicLong LAST_PUBLISH_NS = new AtomicLong(0L);
    private static volatile String snapshot = "No live match";
    private static volatile boolean active = false;

    private HouseSpectatorState() {
    }

    public static void begin(Game game) {
        active = true;
        LAST_PUBLISH_NS.set(0L);
        publish(game, "MATCH_START", 0L, true);
    }

    public static void onEvent(Game game, String eventName, long eventCount) {
        if (!active || game == null) {
            return;
        }
        publish(game, eventName, eventCount, false);
    }

    public static void complete(Game game, String winner) {
        if (game != null) {
            publish(game, "MATCH_COMPLETE", -1L, true);
        }
        String current = snapshot;
        snapshot = current
                + "\n\nRESULT • "
                + (winner == null || winner.trim().isEmpty() ? "complete" : winner + " wins");
        active = false;
    }

    public static void failed(String message) {
        String safe = message == null ? "Unknown Forge failure" : sanitize(message);
        snapshot = snapshot + "\n\nMATCH BLOCKED • " + safe;
        active = false;
    }

    public static String snapshot() {
        return snapshot;
    }

    public static boolean isActive() {
        return active;
    }

    private static void publish(
            Game game,
            String eventName,
            long eventCount,
            boolean force
    ) {
        if (game == null) {
            return;
        }

        long now = System.nanoTime();
        long previous = LAST_PUBLISH_NS.get();
        if (!force
                && previous != 0L
                && now - previous < MIN_PUBLISH_INTERVAL_NS) {
            return;
        }
        if (!force && !LAST_PUBLISH_NS.compareAndSet(previous, now)) {
            return;
        }
        if (force) {
            LAST_PUBLISH_NS.set(now);
        }

        try {
            StringBuilder out = new StringBuilder(1024);
            PhaseHandler phases = game.getPhaseHandler();

            int turn = phases == null ? -1 : phases.getTurn();
            Object phaseValue = phases == null ? null : phases.getPhase();
            Player activePlayer = phases == null ? null : phases.getPlayerTurn();

            out.append("LIVE MATCH");
            if (turn >= 0) {
                out.append(" • Turn ").append(turn);
            }
            if (phaseValue != null) {
                out.append(" • ").append(phaseValue);
            }
            if (activePlayer != null) {
                out.append("\nActive: ").append(activePlayer.getName());
            }
            if (eventName != null && !eventName.trim().isEmpty()) {
                out.append("\nEvent: ").append(sanitize(eventName));
            }
            if (eventCount >= 0L) {
                out.append(" #").append(eventCount);
            }

            for (Player player : game.getRegisteredPlayers()) {
                if (player == null) {
                    continue;
                }
                out.append("\n\n")
                        .append(player.getName())
                        .append(" • ")
                        .append(player.getLife())
                        .append(" life");

                int hand = safeZoneSize(player, ZoneType.Hand);
                int library = safeZoneSize(player, ZoneType.Library);
                int graveyard = safeZoneSize(player, ZoneType.Graveyard);
                int exile = safeZoneSize(player, ZoneType.Exile);
                int battlefield = safeZoneSize(player, ZoneType.Battlefield);

                out.append("\nHand ").append(hand)
                        .append(" • Library ").append(library)
                        .append(" • Grave ").append(graveyard)
                        .append(" • Exile ").append(exile)
                        .append(" • Battlefield ").append(battlefield);

                appendBattlefield(out, player);
            }

            String lastLog = lastLog(game);
            if (!lastLog.isEmpty()) {
                out.append("\n\nLatest: ").append(lastLog);
            }

            snapshot = out.toString();
        } catch (Throwable t) {
            snapshot = "LIVE MATCH • snapshot unavailable: "
                    + t.getClass().getSimpleName()
                    + ": "
                    + sanitize(t.getMessage());
        }
    }

    private static int safeZoneSize(Player player, ZoneType type) {
        try {
            return player.getCardsIn(type).size();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void appendBattlefield(StringBuilder out, Player player) {
        try {
            int shown = 0;
            out.append("\nBoard: ");
            for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
                if (card == null) {
                    continue;
                }
                if (shown > 0) {
                    out.append(", ");
                }
                out.append(card.getName());
                shown++;
                if (shown >= MAX_BATTLEFIELD_NAMES) {
                    int total = player.getCardsIn(ZoneType.Battlefield).size();
                    if (total > shown) {
                        out.append(" … +").append(total - shown);
                    }
                    break;
                }
            }
            if (shown == 0) {
                out.append("—");
            }
        } catch (Throwable ignored) {
            out.append("unavailable");
        }
    }

    private static String lastLog(Game game) {
        try {
            if (game.getGameLog() == null) {
                return "";
            }
            List<GameLogEntry> entries = game.getGameLog().getAllEntries();
            if (entries == null || entries.isEmpty()) {
                return "";
            }
            return sanitize(String.valueOf(entries.get(entries.size() - 1)));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
