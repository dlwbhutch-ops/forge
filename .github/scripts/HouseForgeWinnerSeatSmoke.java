package com.housecommander.forgebridge;

import com.housecommander.core.DeckSpec;
import com.housecommander.core.WinnerIdentity;
import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.game.player.RegisteredPlayer;
import forge.player.GamePlayerUtil;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Regression for the Miles Morales // Ultimate Spider-Man tournament blocker.
 * Forge's winner identity comes from the real registered lobby-player object;
 * even identical display names and double-faced commander punctuation cannot
 * send wins to another seat.
 */
public final class HouseForgeWinnerSeatSmoke {
    private static void expectFailure(Runnable task, String message) {
        try {
            task.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        HouseForgeRuntime.initialize(Path.of(args[0]).toFile(), "HOUSE Miles Morales seat identity");
        Deck deck = ForgeDeckLoader.load(Path.of(args[1]).toFile());
        String[] names = {
            "Garth One-Eye",
            "Miles Morales — Ultimate Spider-Man",
            "Miles Morales — Ultimate Spider-Man", // collision in labels is intentional
            "Atraxa, Praetors' Voice"
        };

        List<RegisteredPlayer> registered = new ArrayList<>();
        for (String name : names) {
            registered.add(RegisteredPlayer.forCommander(deck)
                    .setPlayer(GamePlayerUtil.createAiPlayer(name, 0, 0)));
        }
        List<DeckSpec> pod = Arrays.asList(
                new DeckSpec("Garth One-Eye", "", "", "g.dck", "EXACT", ""),
                new DeckSpec("Miles Morales // Ultimate Spider-Man", "", "", "m.dck", "EXACT", ""),
                new DeckSpec("Black Panther — T'Challa, the Black Panther", "", "", "b.dck", "EXACT", ""),
                new DeckSpec("Atraxa, Praetors' Voice", "", "", "a.dck", "EXACT", "")
        );

        String verified = ForgeBridge.verifiedWinnerSeat(registered, registered.get(1).getPlayer());
        if (!"HOUSE-VERIFIED-SEAT:1".equals(verified)) {
            throw new AssertionError("Miles Morales winner resolved to the wrong registered seat: " + verified);
        }
        if (!pod.get(1).deck().equals(WinnerIdentity.resolveVerifiedSeat(pod, verified))) {
            throw new AssertionError("Forge seat did not map to actual Miles roster record");
        }
        String second = ForgeBridge.verifiedWinnerSeat(registered, registered.get(2).getPlayer());
        if (!"HOUSE-VERIFIED-SEAT:2".equals(second)) {
            throw new AssertionError("Two equal display names collapsed into the same player");
        }
        LobbyPlayer outsider = GamePlayerUtil.createAiPlayer(
                "Miles Morales — Ultimate Spider-Man", 0, 0);
        expectFailure(() -> ForgeBridge.verifiedWinnerSeat(registered, outsider),
                "An unregistered player must not receive a win even if the name matches");
        expectFailure(() -> ForgeBridge.verifiedWinnerSeat(registered, null),
                "A missing winner must not count");
        List<RegisteredPlayer> duplicated = new ArrayList<>(registered);
        duplicated.set(3, RegisteredPlayer.forCommander(deck).setPlayer(registered.get(1).getPlayer()));
        expectFailure(() -> ForgeBridge.verifiedWinnerSeat(duplicated, registered.get(1).getPlayer()),
                "One winner must not match multiple registered seats");
        System.out.println("HOUSE_MILES_VERIFIED_WINNING_SEAT_PASS");
    }
}
