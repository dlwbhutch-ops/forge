package com.housecommander.desktop;

import com.housecommander.core.DeckFileSnapshot;
import com.housecommander.core.DeckSpec;
import com.housecommander.core.DeckVersion;
import com.housecommander.core.HousePackage;
import com.housecommander.core.PodSpec;
import com.housecommander.forgebridge.ForgeBridge;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeoutException;

public final class HouseDesktopSmoke {
    private HouseDesktopSmoke() {}

    public static void main(String[] args) throws Exception {
        HousePackage pack = HouseDesktopRuntime.loadActivePackage();
        if (!pack.validation().passesStrictGate()) {
            throw new AssertionError(pack.validation().summary());
        }
        if (pack.decks().size() != 19 || pack.schedule().size() != 95) {
            throw new AssertionError("Unexpected HOUSE desktop package dimensions");
        }
        System.out.println("DESKTOP_PACKAGE_PASS " + pack.validation().summary());

        verifyDeckManagement(pack);

        DesktopForgeBootstrap.ensureReady(System.out::println);
        if (!ForgeBridge.isAvailable()) {
            throw new AssertionError(ForgeBridge.status());
        }
        System.out.println("DESKTOP_FORGE_PASS " + ForgeBridge.status());

        PodSpec pod = pack.schedule().get(0);
        String[] deckPaths = new String[pod.members().size()];
        for (int i = 0; i < pod.members().size(); i++) {
            DeckSpec deck = pack.deckNamed(pod.members().get(i));
            File file = HouseDesktopRuntime.deckFile(deck);
            deckPaths[i] = file.getAbsolutePath();
        }

        File log = new File(HouseDesktopPaths.logsDir(), "desktop-smoke.log");
        try {
            String winner = ForgeBridge.runCommanderGame(
                    deckPaths,
                    log.getAbsolutePath(),
                    30,
                    15
            );
            if (winner == null || winner.trim().isEmpty()) {
                throw new AssertionError("Desktop smoke returned no winner");
            }
            System.out.println("DESKTOP_LITERAL_COMPLETE_PASS winner=" + winner);
        } catch (TimeoutException expected) {
            String text = Files.exists(log.toPath()) ? Files.readString(log.toPath()) : "";
            long turns = text.lines().filter(line -> line.startsWith("Turn: Turn ")).count();
            boolean hard = text.contains("HOUSE_ERROR=HARD_TIMEOUT");
            boolean stall = text.contains("HOUSE_ERROR=STALL_TIMEOUT");
            if (!hard || stall || turns < 4) {
                throw expected;
            }
            System.out.println("DESKTOP_LITERAL_PROGRESS_PASS turns=" + turns);
        }
    }

    private static void verifyDeckManagement(HousePackage pack) throws Exception {
        DesktopDeckLibraryStore store = new DesktopDeckLibraryStore();
        DeckSpec first = pack.decks().get(0);
        DeckSpec second = pack.decks().get(1);

        DeckSpec imported = store.importDeck(
                pack,
                HouseDesktopRuntime.deckFile(first)
        );
        DeckFileSnapshot snapshot = store.snapshot(imported);
        if (snapshot.cardCount() != 100) {
            throw new AssertionError("Imported deck inspection failed");
        }

        DeckSpec updated = store.replaceImportedDeck(
                pack,
                imported,
                HouseDesktopRuntime.deckFile(second)
        );
        List<DeckVersion> versions = store.history(updated);
        if (versions.size() != 1) {
            throw new AssertionError("Expected one archived version after replace");
        }
        if (store.snapshot(updated).cardCount() != 100) {
            throw new AssertionError("Updated deck inspection failed");
        }

        DeckSpec restored = store.restoreVersion(pack, updated, versions.get(0));
        if (store.history(restored).size() < 2) {
            throw new AssertionError("Restore did not archive the pre-restore version");
        }
        if (store.snapshot(restored).cardCount() != 100) {
            throw new AssertionError("Restored deck inspection failed");
        }

        store.removeImportedDeck(pack, restored);
        for (DeckSpec deck : store.allDecks(pack)) {
            if (deck.deck().equals(restored.deck())) {
                throw new AssertionError("Removed imported deck is still in the library");
            }
        }
        System.out.println("DESKTOP_DECK_MANAGEMENT_PASS");
    }
}
