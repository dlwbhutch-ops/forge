import com.housecommander.forgebridge.ForgeBridge;
import com.housecommander.forgebridge.ForgeDeckLoader;
import com.housecommander.forgebridge.HouseForgeRuntime;
import forge.deck.Deck;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import forge.item.PaperCard;
import java.util.ArrayList;
import java.util.Map;

/** Integration check: same bootstrap/database/bridge as Android, real four-player game. */
public final class HouseRuntimeSmoke {
    public static void main(String[] args) throws Exception {
        Path runtime = Path.of(args[0]);
        HouseForgeRuntime.initialize(runtime.toFile(), "HOUSE-0.7.1-smoke");
        if (!ForgeBridge.isAvailable()) throw new AssertionError(ForgeBridge.status());
        System.out.println("BOOTSTRAP_PASS " + ForgeBridge.status());
        int complete = 0;
        for (File file : new File(args[1]).listFiles((dir, name) -> name.endsWith(".dck"))) {
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
        String winner = ForgeBridge.runCommanderGame(decks, log.toString(), 240, 45);
        if (!winner.startsWith("Smoke ")) throw new AssertionError("Invalid smoke winner: " + winner);
        if (!Files.readString(log).contains("HOUSE_")) throw new AssertionError("No Forge audit log");
        System.out.println("LITERAL_FOUR_PLAYER_PASS winner=" + winner);
        System.exit(0);
    }
}
