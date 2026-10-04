package com.housecommander.desktop;

import com.housecommander.core.DeckFileSnapshot;
import com.housecommander.core.DeckSpec;
import com.housecommander.core.DeckVersion;
import com.housecommander.core.HousePackage;
import com.housecommander.core.PodSpec;
import com.housecommander.core.RosterBuilder;
import com.housecommander.forgebridge.ForgeBridge;
import com.housecommander.forgebridge.LiveGameState;
import com.housecommander.forgebridge.PilotDecision;
import com.housecommander.forgebridge.PilotDecisionBridge;
import com.housecommander.forgebridge.SpectatorCardGroup;
import com.housecommander.forgebridge.SpectatorPlayback;
import com.housecommander.forgebridge.SpectatorTransition;
import com.housecommander.forgebridge.TokenArtResolver;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

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
        verifySpectatorGrouping();
        verifySpectatorPlaybackAndTransitions();
        verifyTokenArtResolver();
        verifyPilotDecisionBridge();
        verifyExpandedRoster(pack);

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

    private static void verifyExpandedRoster(HousePackage template) {
        List<DeckSpec> expanded =
                new ArrayList<DeckSpec>(template.decks());
        DeckSpec source = template.decks().get(0);

        expanded.add(
                new DeckSpec(
                        "HOUSE Expansion 20",
                        source.source(),
                        source.commanders(),
                        source.dck(),
                        source.status(),
                        source.detail(),
                        "HOUSE Expansion Engine 20"
                )
        );
        expanded.add(
                new DeckSpec(
                        "HOUSE Expansion 21",
                        source.source(),
                        source.commanders(),
                        source.dck(),
                        source.status(),
                        source.detail(),
                        "HOUSE Expansion Engine 21"
                )
        );

        HousePackage generated = RosterBuilder.build(
                template,
                expanded
        );
        if (generated.decks().size() != 21) {
            throw new AssertionError(
                    "Expanded roster did not retain all 21 decks"
            );
        }
        if (!generated.validation().passesStrictGate()) {
            throw new AssertionError(
                    generated.validation().summary()
            );
        }
        if (generated.validation().uniquePairs() != 210
                || generated.validation().meetingsPerPair() < 3) {
            throw new AssertionError(
                    "Expanded roster pair coverage failed: "
                            + generated.validation().summary()
            );
        }
        if (generated.validation().maxGamesPerDeck()
                - generated.validation().gamesPerDeck() > 1) {
            throw new AssertionError(
                    "Expanded roster games are not balanced: "
                            + generated.validation().summary()
            );
        }

        System.out.println(
                "DESKTOP_EXPANDED_ROSTER_PASS "
                        + generated.validation().summary()
        );
    }

    private static void verifyPilotDecisionBridge() throws Exception {
        PilotDecisionBridge.reset();
        AtomicInteger result = new AtomicInteger(-99);

        Thread requester = new Thread(
                () -> result.set(
                        PilotDecisionBridge.request(
                                PilotDecision.Kind.ACTION,
                                "Jace, Multiverse Architect",
                                "Choose an action",
                                List.of("Cast Jace", "Pass priority")
                        )
                ),
                "HOUSE-Pilot-Decision-Smoke"
        );
        requester.start();

        long deadline = System.currentTimeMillis() + 3000L;
        PilotDecision pending = PilotDecisionBridge.current();
        while (!pending.pending() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
            pending = PilotDecisionBridge.current();
        }
        if (!pending.pending()) {
            throw new AssertionError("Pilot decision was never published");
        }
        if (!PilotDecisionBridge.submit(pending.id(), 0)) {
            throw new AssertionError("Pilot decision submit was rejected");
        }

        requester.join(3000L);
        if (requester.isAlive() || result.get() != 0) {
            throw new AssertionError(
                    "Pilot decision did not resolve selected option: "
                            + result.get()
            );
        }
        if (PilotDecisionBridge.hasPending()) {
            throw new AssertionError("Pilot decision remained pending after submit");
        }

        PilotDecisionBridge.reset();
        System.out.println("DESKTOP_PILOT_DECISION_PASS");
    }

    private static void verifySpectatorPlaybackAndTransitions() {
        LiveGameState.CardState ready = new LiveGameState.CardState(
                "Test Commander",
                "test-key",
                "",
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                3,
                3,
                Collections.emptyList()
        );
        LiveGameState.CardState attacking = new LiveGameState.CardState(
                "Test Commander",
                "test-key",
                "",
                true,
                false,
                false,
                true,
                false,
                true,
                false,
                3,
                3,
                Collections.emptyList()
        );

        LiveGameState.PlayerState beforePlayer = new LiveGameState.PlayerState(
                "Player A",
                40,
                0,
                7,
                92,
                false,
                Collections.singletonList(ready),
                Collections.singletonList("Test Commander"),
                Collections.singletonList("Test Commander • casts 0 • next tax +0"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        LiveGameState.PlayerState afterPlayer = new LiveGameState.PlayerState(
                "Player A",
                37,
                0,
                6,
                91,
                false,
                Collections.singletonList(attacking),
                Collections.singletonList("Test Commander"),
                Collections.singletonList("Test Commander • casts 0 • next tax +0"),
                Collections.singletonList(
                        new LiveGameState.CommanderDamageState("Enemy Commander", 3)
                ),
                Collections.emptyList(),
                Collections.emptyList()
        );

        LiveGameState before = new LiveGameState(
                2L,
                "GameEventPhase",
                1,
                "MAIN1",
                "Player A",
                Collections.singletonList(beforePlayer),
                Collections.emptyList(),
                Collections.emptyList(),
                false,
                ""
        );
        LiveGameState after = new LiveGameState(
                3L,
                "GameEventAttackersDeclared",
                1,
                "COMBAT_DECLARE_ATTACKERS",
                "Player A",
                Collections.singletonList(afterPlayer),
                Collections.singletonList("Test Bolt"),
                Collections.singletonList(
                        new LiveGameState.StackState(
                                "Test Bolt",
                                "Test Bolt deals 3 damage",
                                "Player A",
                                Collections.singletonList("Player B")
                        )
                ),
                Collections.singletonList(
                        new LiveGameState.CombatLinkState(
                                "Test Commander",
                                "Player B",
                                Collections.singletonList("Test Blocker")
                        )
                ),
                false,
                ""
        );

        List<SpectatorTransition.Transition> transitions =
                SpectatorTransition.diff(before, after);
        boolean sawLife = false;
        boolean sawCommanderDamage = false;
        boolean sawAttack = false;
        boolean sawStack = false;
        for (SpectatorTransition.Transition transition : transitions) {
            sawLife |= transition.kind() == SpectatorTransition.Kind.LIFE;
            sawCommanderDamage |= transition.kind()
                    == SpectatorTransition.Kind.COMMANDER_DAMAGE;
            sawAttack |= transition.kind() == SpectatorTransition.Kind.ATTACK;
            sawStack |= transition.kind() == SpectatorTransition.Kind.STACK_ADD;
        }
        if (!sawLife || !sawCommanderDamage || !sawAttack || !sawStack) {
            throw new AssertionError(
                    "Spectator transition diff missed expected events: "
                            + transitions.size()
            );
        }
        if (after.combatLinks().size() != 1
                || !"Test Blocker".equals(
                        after.combatLinks().get(0).blockers().get(0)
                )) {
            throw new AssertionError(
                    "Exact attacker/blocker assignment was not preserved"
            );
        }

        SpectatorPlayback.reset(before);
        SpectatorPlayback.pause();
        SpectatorPlayback.record(after);
        if (SpectatorPlayback.latestState().sequence() != 3L
                || SpectatorPlayback.visibleState().sequence() != 2L) {
            throw new AssertionError(
                    "Spectator pause did not preserve the visible frame"
            );
        }
        SpectatorPlayback.nextAction();
        if (SpectatorPlayback.visibleState().sequence() != 3L) {
            throw new AssertionError("Spectator step action did not advance one frame");
        }
        SpectatorPlayback.goLive();

        System.out.println(
                "DESKTOP_SPECTATOR_PLAYBACK_PASS transitions="
                        + transitions.size()
        );
    }

    private static void verifyTokenArtResolver() {
        LiveGameState.CardState squirrel = new LiveGameState.CardState(
                "Squirrel Token",
                "",
                "",
                false,
                true,
                false,
                true,
                false,
                false,
                false,
                1,
                1,
                Collections.emptyList(),
                "Token Creature - Squirrel",
                "G"
        );
        TokenArtResolver.TokenArtSpec squirrelArt =
                TokenArtResolver.resolve(squirrel);
        if (squirrelArt == null
                || !"squirrel".equals(squirrelArt.familyKey())
                || squirrelArt.proceduralFallback()) {
            throw new AssertionError("Squirrel token did not resolve themed art");
        }

        LiveGameState.CardState ferret = new LiveGameState.CardState(
                "Nebula Ferret Token",
                "",
                "",
                false,
                true,
                false,
                true,
                false,
                false,
                false,
                2,
                2,
                Collections.emptyList(),
                "Token Creature - Ferret",
                "U"
        );
        TokenArtResolver.TokenArtSpec fallback =
                TokenArtResolver.resolve(ferret);
        if (fallback == null
                || !fallback.proceduralFallback()
                || fallback.signature().isEmpty()) {
            throw new AssertionError("Unknown token did not receive fallback art");
        }

        System.out.println(
                "DESKTOP_TOKEN_ART_PASS families="
                        + TokenArtResolver.knownFamilyCount()
                        + " fallback="
                        + fallback.familyKey()
        );
    }

    private static void verifySpectatorGrouping() {
        List<LiveGameState.CardState> cards =
                new ArrayList<LiveGameState.CardState>();
        for (int i = 0; i < 600; i++) {
            cards.add(new LiveGameState.CardState(
                    "Squirrel Token",
                    "",
                    "",
                    false,
                    true,
                    false,
                    true,
                    false,
                    false,
                    false,
                    1,
                    1,
                    Collections.emptyList()
            ));
        }
        cards.add(new LiveGameState.CardState(
                "Squirrel Token",
                "",
                "",
                true,
                true,
                false,
                true,
                false,
                false,
                false,
                1,
                1,
                Collections.singletonList("+1/+1 ×1")
        ));

        List<SpectatorCardGroup> groups = SpectatorCardGroup.group(cards);
        if (groups.size() != 2
                || groups.get(0).count() != 600
                || groups.get(1).count() != 1) {
            throw new AssertionError(
                    "Spectator grouping failed for token swarm: groups="
                            + groups.size()
            );
        }
        System.out.println(
                "DESKTOP_SPECTATOR_GROUPING_PASS "
                        + cards.size()
                        + " permanents -> "
                        + groups.size()
                        + " piles"
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
