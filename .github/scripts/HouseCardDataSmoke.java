import com.housecommander.forgebridge.*;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.player.*;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.nio.file.*;
import java.util.*;

/** Parse newly added/changed card abilities with the actual bundled engine. */
public final class HouseCardDataSmoke {
    public static void main(String[] args) throws Exception {
        try {
            HouseForgeRuntime.initialize(Path.of(args[0]).toFile(), "HOUSE current card data check");
            Deck deck = ForgeDeckLoader.load(Path.of(args[1], "Garth_One_Eye.dck").toFile());
            List<RegisteredPlayer> registered = new ArrayList<>();
            for (int i = 0; i < 4; i++) registered.add(RegisteredPlayer.forCommander(deck)
                    .setPlayer(GamePlayerUtil.createAiPlayer("Data test " + i, 0, 0)));
            Game game = new Match(new GameRules(GameType.Commander), registered, "Data check").createGame();
            Player player = game.getPlayers().get(0);
            int passed = 0, missing = 0, failed = 0;
            for (String name : Files.readAllLines(Path.of(args[2]))) {
                PaperCard paper = FModel.getMagicDb().getCommonCards().getCard(name);
                if (paper == null) paper = FModel.getMagicDb().getVariantCards().getCard(name);
                if (paper == null) { System.out.println("CARD_UNAVAILABLE " + name); missing++; continue; }
                try {
                    Card card = Card.fromPaperCard(paper, player);
                    for (var trigger : card.getTriggers()) if (trigger.hasParam("Execute")) trigger.ensureAbility();
                    passed++;
                } catch (Throwable error) { failed++; System.out.println("CARD_PARSE_FAILED " + name + " " + error); }
            }
            int decks = 0;
            try (var paths = Files.list(Path.of(args[1]))) {
                for (Path file : paths.filter(p -> p.toString().endsWith(".dck")).toList()) {
                    ForgeBridge.validateCommanderDeck(file.toString());
                    decks++;
                }
            }
            System.out.println("CARD_DATA_RESULT parsed=" + passed + " unavailable=" + missing + " failed=" + failed + " decks=" + decks);
            if (failed != 0 || missing != 0 || decks != 21) throw new AssertionError("Current card data did not fully validate");
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
}
