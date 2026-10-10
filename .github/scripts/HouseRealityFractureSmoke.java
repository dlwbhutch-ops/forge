import com.housecommander.forgebridge.HouseForgeRuntime;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Full, literal Forge database audit for Reality Fracture and its Commander set.
 * Verifies every listed physical card rather than accepting an edition filename,
 * and instantiates cards/triggers through the same rule engine used by Android.
 */
public final class HouseRealityFractureSmoke {
    private HouseRealityFractureSmoke() {}

    private static Set<String> names(Path edition) throws Exception {
        if (!Files.isRegularFile(edition)) throw new AssertionError("Missing edition: " + edition);
        boolean cards = false;
        Set<String> names = new HashSet<>();
        for (String raw : Files.readAllLines(edition)) {
            String line = raw.trim();
            if (line.equals("[cards]")) { cards = true; continue; }
            if (line.startsWith("[") && line.endsWith("]")) { cards = false; continue; }
            if (!cards || line.isEmpty() || line.startsWith("#")) continue;
            // Edition format: "43 M The Theorist, Jace Beleren @artist"
            if (!line.matches("^[0-9]+[a-zA-Z]?\\s+[A-Z][0-9]?\\s+.+")) continue;
            String name = line.replaceFirst("^[0-9]+[a-zA-Z]?\\s+[A-Z][0-9]?\\s+", "")
                    .split("\\s+@", 2)[0].trim();
            // Some printings encode alternate art with a trailing marker.
            name = name.replaceFirst("\\s+\\(.*variant.*\\)$", "");
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected extracted Forge runtime and Garth deck");
        HouseForgeRuntime.initialize(Path.of(args[0]).toFile(), "HOUSE Reality Fracture FRA/FRC audit");
        Path editions = Path.of(args[0], "res", "editions");
        Set<String> fra = names(editions.resolve("Reality Fracture.txt"));
        Set<String> frc = names(editions.resolve("Reality Fracture Commander.txt"));
        if (fra.size() < 250 || frc.size() < 80) {
            throw new AssertionError("Incomplete FRA/FRC edition inventory: FRA=" + fra.size() + " FRC=" + frc.size());
        }

        var loadedDeck = com.housecommander.forgebridge.ForgeDeckLoader.load(Path.of(args[1]).toFile());
        List<RegisteredPlayer> registered = new ArrayList<>();
        for (int i = 0; i < 4; i++) registered.add(RegisteredPlayer.forCommander(loadedDeck)
                .setPlayer(GamePlayerUtil.createAiPlayer("FRA audit " + i, 0, 0)));
        Game game = new Match(new GameRules(GameType.Commander), registered, "FRA runtime audit").createGame();
        Player player = game.getPlayers().get(0);
        int checked = 0, missing = 0, unsupported = 0, parseFailures = 0;
        Set<String> unique = new HashSet<>(fra);
        unique.addAll(frc);
        for (String name : unique) {
            PaperCard paper = FModel.getMagicDb().getCommonCards().getCard(name);
            if (paper == null) paper = FModel.getMagicDb().getVariantCards().getCard(name);
            if (paper == null) {
                System.out.println("FRA_CARD_MISSING " + name); missing++; continue;
            }
            if (paper.getRules().isUnsupported()) {
                System.out.println("FRA_CARD_UNSUPPORTED " + name); unsupported++; continue;
            }
            try {
                Card card = Card.fromPaperCard(paper, player);
                for (var trigger : card.getTriggers()) {
                    if (trigger.hasParam("Execute")) trigger.ensureAbility();
                }
                checked++;
            } catch (Throwable error) {
                System.out.println("FRA_CARD_PARSE_FAIL " + name + " " + error.getClass().getSimpleName()
                        + ": " + error.getMessage());
                parseFailures++;
            }
        }

        // Validate indispensable new set token scripts against the packaged source.
        Path tokens = Path.of(args[0], "res", "tokenscripts");
        String jace = Files.readString(tokens.resolve("u_empower_jace.txt"));
        String heartwood = Files.readString(tokens.resolve("rg_a_heartwood.txt"));
        if (!jace.contains("Types:Planeswalker Jace") || !jace.contains("Loyalty:0")
                || !jace.contains("AB$ Surveil") || !jace.contains("AB$ Draw")) {
            throw new AssertionError("Incorrect blue Jace planeswalker token definition");
        }
        if (!heartwood.contains("Types:Artifact Heartwood")
                || !heartwood.contains("Colors:red,green")
                || !heartwood.contains("Combo R G")) {
            throw new AssertionError("Incorrect red-green Heartwood artifact token definition");
        }

        System.out.println("REALITY_FRA_FRC_AUDIT totalFRA=" + fra.size() + " totalFRC=" + frc.size()
                + " unique=" + unique.size() + " parsed=" + checked
                + " missing=" + missing + " unsupported=" + unsupported
                + " parseFailures=" + parseFailures);
        System.out.println("REALITY_TOKEN_RULES_PASS Jace=0-loyalty Heartwood=red-green");
        if (missing != 0 || unsupported != 0 || parseFailures != 0)
            throw new AssertionError("FRA/FRC card coverage incomplete; no silent acceptance");
        System.out.println("REALITY_FRA_FRC_ALL_SUPPORTED_PASS");
    }
}
