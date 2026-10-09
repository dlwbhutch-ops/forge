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
        assertError(Arrays.asList(miles, deck("Miles Morales — Ultimate Spider-Man",
                "Miles Morales — Ultimate Spider-Man", "other/miles2.dck")),
                "Miles Morales — Ultimate Spider-Man", "Ambiguous Forge winner");
        System.out.println("PASS: 11 winner-identity regression cases");
    }
}
