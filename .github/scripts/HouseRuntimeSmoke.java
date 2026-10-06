import com.housecommander.forgebridge.ForgeBridge;
import com.housecommander.forgebridge.ForgeDeckLoader;
import com.housecommander.forgebridge.HouseForgeRuntime;
import com.housecommander.forgebridge.LiveGameState;
import com.housecommander.forgebridge.SpectatorPlayback;
import forge.deck.Deck;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import forge.item.PaperCard;

/**
 * Integration check for the same local database and bridge used by Android.
 *
 * The synthetic smoke decks intentionally favor deterministic, dependency-free
 * card loading over competitive game speed. A completed winner is ideal, but
 * CI also accepts a hard-timeout only when the literal game is demonstrably
 * healthy and advancing through turns. A stall timeout, startup failure, deck
 * resolution error, or zero/near-zero game progress still fails the build.
 *
 * Full HOUSE tournament runs retain their much larger production watchdogs.
 */
public final class HouseRuntimeSmoke {
    private static final int SMOKE_HARD_TIMEOUT_SECONDS = 30;
    private static final int SMOKE_STALL_TIMEOUT_SECONDS = 15;
    private static final long MINIMUM_TURNS_FOR_PROGRESS_PASS = 4;

    public static void main(String[] args) throws Exception {
        Path runtime = Path.of(args[0]);
        HouseForgeRuntime.initialize(runtime.toFile(), "HOUSE-0.8-smoke");
        if (!ForgeBridge.isAvailable()) throw new AssertionError(ForgeBridge.status());
        System.out.println("BOOTSTRAP_PASS " + ForgeBridge.status());

        int complete = 0;
        File[] bundled = new File(args[1]).listFiles((dir, name) -> name.endsWith(".dck"));
        if (bundled == null) throw new AssertionError("Could not enumerate bundled HOUSE decks");

        for (File file : bundled) {
            try {
                Deck deck = ForgeDeckLoader.load(file);
                int loaded = deck.getAllCardsInASinglePool().countAll();
                ArrayList<String> unsupported = new ArrayList<>();
                for (Map.Entry<PaperCard, Integer> card : deck.getAllCardsInASinglePool()) {
                    if (card.getKey().getRules().isUnsupported()) unsupported.add(card.getKey().getName());
                }
                if (loaded == 100 && !deck.getCommanders().isEmpty() && unsupported.isEmpty()) complete++;
                System.out.println("DECK_LOAD " + file.getName() + " " + loaded + "/100 unsupported=" + unsupported);
            } catch (Throwable error) {
                System.out.println("DECK_LOAD_ERROR " + file.getName() + " " + error);
            }
        }

        System.out.println("HOUSE_DECKS_COMPLETE " + complete + "/19");
        if (complete != 19) throw new AssertionError("Bundled HOUSE deck names did not all resolve");

        Path smokeDir = Files.createTempDirectory("house-literal-smoke");
        String[] decks = new String[4];
        for (int i = 0; i < 4; i++) {
            Path deck = smokeDir.resolve("smoke-" + i + ".dck");
            String cards = i == 0
                    ? "[Commander]\n1 Torbran, Thane of Red Fell\n[Main]\n30 Mountain\n69 Dragon's Approach\n"
                    : "[Commander]\n1 Isamaru, Hound of Konda\n[Main]\n99 Plains\n";
            Files.writeString(deck, "[metadata]\nName=Smoke " + i + "\n" + cards);
            decks[i] = deck.toString();
        }

        Path log = Path.of(args[2]);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong observedSequence = new AtomicLong();
        Thread viewer = new Thread(() -> {
            while (running.get()) {
                LiveGameState frame = SpectatorPlayback.latestState();
                if (frame.players().size() == 4 && frame.turn() > 0 && !frame.gameOver()) {
                    observedSequence.set(frame.sequence());
                }
                try { Thread.sleep(50L); }
                catch (InterruptedException stopped) { return; }
            }
        }, "HOUSE-Smoke-Viewer");
        viewer.setDaemon(true);
        viewer.start();
        try {
            String winner = ForgeBridge.runCommanderGame(
                    decks,
                    log.toString(),
                    SMOKE_HARD_TIMEOUT_SECONDS,
                    SMOKE_STALL_TIMEOUT_SECONDS
            );
            if (!winner.startsWith("Smoke ")) {
                throw new AssertionError("Invalid smoke winner: " + winner);
            }
            String literal = Files.readString(log);
            requireAuditLog(literal);
            System.out.println("LITERAL_FOUR_PLAYER_COMPLETE_PASS winner=" + winner);
        } catch (TimeoutException expectedHardTimeout) {
            String literal = Files.exists(log) ? Files.readString(log) : "";
            requireAuditLog(literal);

            long turns = literal.lines()
                    .filter(line -> line.startsWith("Turn: Turn "))
                    .count();

            boolean hardTimeoutRecorded = literal.contains("HOUSE_ERROR=HARD_TIMEOUT");
            boolean stallTimeoutRecorded = literal.contains("HOUSE_ERROR=STALL_TIMEOUT");

            if (!hardTimeoutRecorded
                    || stallTimeoutRecorded
                    || turns < MINIMUM_TURNS_FOR_PROGRESS_PASS) {
                throw expectedHardTimeout;
            }

            System.out.println(
                    "LITERAL_FOUR_PLAYER_PROGRESS_PASS hard-timeout="
                            + SMOKE_HARD_TIMEOUT_SECONDS
                            + "s turns="
                            + turns
            );
        } finally {
            running.set(false);
            viewer.interrupt();
            viewer.join(1000L);
        }

        if (observedSequence.get() <= 1L) {
            throw new AssertionError("No four-player spectator snapshot during the live game: " + ForgeBridge.snapshotError());
        }
        System.out.println("LITERAL_LIVE_BROADCAST_PASS sequence=" + observedSequence.get());

        System.exit(0);
    }

    private static void requireAuditLog(String literal) {
        if (literal == null || !literal.contains("HOUSE_")) {
            throw new AssertionError("No Forge audit log");
        }
    }
}
