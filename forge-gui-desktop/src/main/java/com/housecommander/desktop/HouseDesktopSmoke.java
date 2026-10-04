package com.housecommander.desktop;

import com.housecommander.core.DeckFileSnapshot;
import com.housecommander.core.DeckSpec;
import com.housecommander.core.DeckVersion;
import com.housecommander.core.HousePackage;
import com.housecommander.core.PodSpec;
import com.housecommander.core.WatchPodSelection;
import com.housecommander.forgebridge.ForgeBridge;
import com.housecommander.forgebridge.LiveGameState;

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

        verifyManaBoxImport(pack);

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

        LiveGameState live = ForgeBridge.liveGameState();
        if (live.sequence() <= 1L || live.players().size() != pod.members().size()) {
            throw new AssertionError(
                    "Live spectator state missing: sequence="
                            + live.sequence()
                            + " players="
                            + live.players().size()
            );
        }
        System.out.println(
                "DESKTOP_LIVE_STATE_PASS turn="
                        + live.turn()
                        + " phase="
                        + live.phase()
                        + " players="
                        + live.players().size()
                        + " event="
                        + live.lastEvent()
        );
    }

    private static void verifyManaBoxImport(HousePackage pack) throws Exception {
        File fixture = new File(
                ".github/house-test-data/Jace_Multiverse_Architect_12-Swap_Official_2026-10-03.txt"
        );
        if (!fixture.isFile()) {
            throw new AssertionError("Jace ManaBox fixture is missing");
        }

        DesktopDeckLibraryStore store = new DesktopDeckLibraryStore();
        DeckSpec imported = store.importDeck(pack, fixture);
        try {
            DeckFileSnapshot snapshot = store.snapshot(imported);
            if (snapshot.cardCount() != 100) {
                throw new AssertionError(
                        "Jace text import produced " + snapshot.cardCount() + " cards"
                );
            }
            if (!snapshot.commanders().contains("Jace, Multiverse Architect")) {
                throw new AssertionError(
                        "Jace text import did not preserve the commander: "
                                + snapshot.commanders()
                );
            }

            String forgeName = ForgeBridge.validateCommanderDeck(
                    HouseDesktopRuntime.deckFile(imported).getAbsolutePath()
            );
            System.out.println(
                    "DESKTOP_MANABOX_IMPORT_PASS deck="
                            + imported.deck()
                            + " forge="
                            + forgeName
                            + " cards="
                            + snapshot.cardCount()
            );

            List<DeckSpec> library = store.allDecks(pack);
            String[] watchNames = new String[]{
                    imported.deck(),
                    pack.decks().get(0).deck(),
                    pack.decks().get(1).deck(),
                    pack.decks().get(2).deck()
            };
            List<DeckSpec> watchPod = WatchPodSelection.selectByNames(
                    library,
                    watchNames
            );
            if (watchPod.size() != WatchPodSelection.POD_SIZE
                    || !watchPod.get(0).deck().equals(imported.deck())) {
                throw new AssertionError("Imported deck did not resolve into Watch pod");
            }

            boolean duplicateBlocked = false;
            try {
                WatchPodSelection.selectByNames(
                        library,
                        new String[]{
                                imported.deck(),
                                imported.deck(),
                                pack.decks().get(1).deck(),
                                pack.decks().get(2).deck()
                        }
                );
            } catch (IllegalArgumentException expected) {
                duplicateBlocked = true;
            }
            if (!duplicateBlocked) {
                throw new AssertionError("Watch pod allowed the same deck twice");
            }
            System.out.println(
                    "DESKTOP_WATCH_POD_SELECTION_PASS decks=" + watchPod.size()
            );
        } finally {
            store.removeImportedDeck(pack, imported);
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
