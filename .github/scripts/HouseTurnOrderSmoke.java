import com.housecommander.forgebridge.ForgeDeckLoader;
import com.housecommander.forgebridge.HouseForgeRuntime;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityContinuous;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Reproduces the continuous-effect affected-player lookup with four live players.
 * The result must not depend on whether actual turn direction is reversed.
 */
public final class HouseTurnOrderSmoke {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        HouseForgeRuntime.initialize(Path.of(args[0]).toFile(), "HOUSE turn-order recursion regression");
        Deck deck = ForgeDeckLoader.load(Path.of(args[1]).toFile());
        List<RegisteredPlayer> registered = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            registered.add(RegisteredPlayer.forCommander(deck)
                    .setPlayer(GamePlayerUtil.createAiPlayer("Turn order player " + i, 0, 0)));
        }
        Game game = new Match(new GameRules(GameType.Commander), registered, "Turn order regression").createGame();
        game.setAge(GameStage.Play);
        Player active = game.getPlayers().get(0);
        game.getPhaseHandler().setPlayerTurn(active);

        Card source = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Ornithopter"), active);
        active.getZone(ZoneType.Battlefield).add(source);
        StaticAbility ability = source.addStaticAbility(
                "Mode$ Continuous | Affected$ Player | AddKeyword$ Hexproof");
        Method affected = StaticAbilityContinuous.class.getDeclaredMethod(
                "getAffectedPlayers", StaticAbility.class);
        affected.setAccessible(true);

        List<Player> forward = (List<Player>) affected.invoke(null, ability);
        if (forward.size() != 4 || !new HashSet<>(forward).equals(new HashSet<>(game.getPlayers()))) {
            throw new AssertionError("Forward turn order affected wrong players: " + forward);
        }
        game.reverseTurnOrder();
        List<Player> backward = (List<Player>) affected.invoke(null, ability);
        if (backward.size() != 4 || !new HashSet<>(backward).equals(new HashSet<>(forward))) {
            throw new AssertionError("Reversed turn order changed affected player set");
        }

        // Actual continuous-layer recalculation must terminate rather than recursively
        // recalculating TurnReversed card abilities.
        game.getAction().checkStaticAbilities();
        System.out.println("CONTINUOUS_TURN_ORDER_NO_RECURSION_PASS players=4");
    }
}
