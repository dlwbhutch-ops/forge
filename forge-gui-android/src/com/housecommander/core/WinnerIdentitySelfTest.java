package com.housecommander.core;

import java.util.Arrays;
import java.util.List;

/** Host-side regression checks; runnable with only a JDK. */
public final class WinnerIdentitySelfTest {
    private WinnerIdentitySelfTest() {}

    private static DeckSpec deck(String name, String engine, String dck) {
        return new DeckSpec(name, "", name, dck, "EXACT", "", engine);
    }

    private static void assertWin(List<DeckSpec> pod, String outcome, String expected) {
        String winner = WinnerIdentity.resolve(pod, outcome);
        if (!expected.equals(winner)) {
            throw new AssertionError("Expected " + expected + ", got " + winner);
        }
    }

    private static void assertError(List<DeckSpec> pod, String outcome, String fragment) {
        try {
            WinnerIdentity.resolve(pod, outcome);
        } catch (IllegalStateException ex) {
            if (ex.getMessage().contains(fragment)) return;
            throw new AssertionError("Unexpected failure: " + ex.getMessage());
        }
        throw new AssertionError("Expected failure containing '" + fragment + "': " + outcome);
    }

    public static void main(String[] args) {
        DeckSpec miles = deck("Miles Morales // Ultimate Spider-Man",
                "Miles Morales // Ultimate Spider-Man", "forge_decks/Miles_Morales.dck");
        DeckSpec aang = deck("Aang, Master of Elements", "Aang, Master of Elements", "forge_decks/Aang.dck");
        DeckSpec panther = deck("Black Panther — T'Challa, the Black Panther",
                "Black Panther — T'Challa, the Black Panther", "forge_decks/Black_Panther.dck");
        DeckSpec atraxa = deck("Atraxa, Praetors’ Voice", "Atraxa, Praetors’ Voice", "forge_decks/Atraxa.dck");
        List<DeckSpec> pod = Arrays.asList(miles, aang, panther, atraxa);
        assertWin(pod, "Miles Morales — Ultimate Spider-Man", miles.deck());
        assertWin(pod, "Miles Morales // Ultimate Spider-Man", miles.deck());
        assertWin(pod, "Miles Morales – Ultimate Spider-Man", miles.deck());
        assertWin(pod, "Aang, Master of Elements", aang.deck());
        assertWin(pod, "Black Panther — T'Challa, the Black Panther", panther.deck());
        assertWin(pod, "Atraxa, Praetors' Voice", atraxa.deck());
        assertWin(pod, "Miles_Morales", miles.deck());
        assertError(pod, "Miles Morales", "not a member");
        assertError(pod, "Random Commander", "not a member");
        assertError(pod, "", "empty winner");
        // New tournament protocol: seat is derived from the winning Forge
        // LobbyPlayer object, not from a mutable/translated display label.
        if (!WinnerIdentity.resolveVerifiedSeat(pod, "HOUSE-VERIFIED-SEAT:0").equals(miles.deck())) {
            throw new AssertionError("Verified Miles Morales seat did not map to the original roster name");
        }
        if (!WinnerIdentity.resolveVerifiedSeat(pod, "HOUSE-VERIFIED-SEAT:3").equals(atraxa.deck())) {
            throw new AssertionError("Verified last seat mapped to wrong deck");
        }
        for (String forged : Arrays.asList("Miles Morales — Ultimate Spider-Man",
                "HOUSE-VERIFIED-SEAT:4", "HOUSE-VERIFIED-SEAT:-1",
                "HOUSE-VERIFIED-SEAT:01", "HOUSE-VERIFIED-SEAT:0x1", "HOUSE-VERIFIED-SEAT:")) {
            try {
                WinnerIdentity.resolveVerifiedSeat(pod, forged);
                throw new AssertionError("Unverified winner must fail closed: " + forged);
            } catch (IllegalStateException expected) {
                // Expected: no fabricated winner can be checkpointed.
            }
        }
        assertError(Arrays.asList(miles, deck("Miles Morales — Ultimate Spider-Man",
                "Miles Morales — Ultimate Spider-Man", "other/miles2.dck")),
                "Miles Morales — Ultimate Spider-Man", "Ambiguous Forge winner");
        System.out.println("PASS: winner names plus eight verified-seat regression cases");
    }
}
