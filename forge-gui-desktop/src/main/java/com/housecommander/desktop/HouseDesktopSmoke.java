package com.housecommander.desktop;

import com.housecommander.core.DeckSpec;
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
}
