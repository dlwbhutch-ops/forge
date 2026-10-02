package com.housecommander.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

public final class SharedCoreTest {
    @Test
    void parsesForgeCommanderDeckAndPreservesVersionsAcrossCodec() throws Exception {
        StringBuilder deck = new StringBuilder();
        deck.append("[Commander]\n");
        deck.append("1 Toph, the First Metalbender|TLA\n");
        deck.append("[Main]\n");
        for (int i = 1; i <= 99; i++) {
            deck.append("1 Test Card ").append(i).append("|TST\n");
        }

        DeckImportResult parsed = DeckTextParser.parse(deck.toString());
        assertEquals("FORGE_DCK", parsed.format());
        assertEquals(1, parsed.commanderCards());
        assertEquals(99, parsed.mainboardCards());
        assertTrue(parsed.isCommanderSized());
        assertEquals("Toph, the First Metalbender", parsed.commanderLabel());

        DeckLibrary library = new DeckLibrary();
        DeckRecord v1 = library.createDeck(
                "Toph, the First Metalbender",
                parsed.commanderLabel(),
                deck.toString(),
                parsed.format()
        );

        String updatedText = deck + "\n# tuning note\n";
        DeckRecord v2 = library.createVersion(
                v1.deckId(),
                v1.name(),
                v1.commander(),
                updatedText,
                "FORGE_DCK"
        );

        assertEquals(2, v2.version());
        assertEquals(2, library.history(v1.deckId()).size());
        assertFalse(v1.contentHash().equals(v2.contentHash()));

        library.setArchived(v1.deckId(), true);
        StringWriter out = new StringWriter();
        HouseLibraryCodec.write(library, out);

        DeckLibrary restored = HouseLibraryCodec.read(new StringReader(out.toString()));
        assertEquals(1, restored.deckCount());
        assertEquals(2, restored.history(v1.deckId()).size());
        assertEquals(v2.contentHash(), restored.latest(v1.deckId()).contentHash());
        assertTrue(restored.isArchived(v1.deckId()));

        TournamentRoster roster = TournamentRoster.create(
                "HOUSE test roster",
                List.of(restored.version(v1.deckId(), 1), restored.latest(v1.deckId()))
        );
        List<DeckRecord> resolved = roster.resolve(restored);
        assertEquals(1, resolved.get(0).version());
        assertEquals(2, resolved.get(1).version());
    }

    @Test
    void pastedListWithoutCommanderSectionGetsUsefulWarning() {
        StringBuilder deck = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            deck.append("1 Card ").append(i).append("\n");
        }

        DeckImportResult parsed = DeckTextParser.parse(deck.toString());
        assertEquals("TEXT", parsed.format());
        assertTrue(parsed.isCommanderSized());
        assertFalse(parsed.hasCommanderSection());
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("Commander")));
    }
}
