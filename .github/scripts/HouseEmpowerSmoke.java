import com.housecommander.forgebridge.*;
import forge.ai.ComputerUtil;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.*;
import forge.game.card.*;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;

/** Exercises the packaged Way of the Mentor trigger and real token abilities. */
public final class HouseEmpowerSmoke {
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static Card add(Player p, String name, ZoneType zone) {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        p.getZone(zone).add(c);
        return c;
    }
    private static List<Card> tokens(Player p, String type) {
        List<Card> result = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isToken() && c.getType().hasSubtype(type)) result.add(c);
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        try {
            HouseForgeRuntime.initialize(Path.of(args[0]).toFile(), "HOUSE Empower regression");
            Deck deck = ForgeDeckLoader.load(Path.of(args[1]).toFile());
            List<RegisteredPlayer> registered = new ArrayList<>();
            for (int i = 0; i < 4; i++) registered.add(RegisteredPlayer.forCommander(deck)
                    .setPlayer(GamePlayerUtil.createAiPlayer("Empower test " + i, 0, 0)));
            Game game = new Match(new GameRules(GameType.Commander), registered, "Empower regression").createGame();
            game.setAge(GameStage.Play);
            Player p = game.getPlayers().get(0);
            game.getPhaseHandler().setPlayerTurn(p);
            Field phase = game.getPhaseHandler().getClass().getDeclaredField("phase");
            phase.setAccessible(true);
            phase.set(game.getPhaseHandler(), forge.game.phase.PhaseType.MAIN1);
            for (int i = 0; i < 10; i++) add(p, "Island", ZoneType.Library);
            Card commander = add(p, "Jace, Multiverse Architect", ZoneType.Battlefield);
            Card mentor = add(p, "Way of the Mentor", ZoneType.Battlefield);
            SpellAbility empower = null;
            for (Trigger t : mentor.getTriggers()) {
                if ("DBEmpower".equals(t.getParam("Execute"))) empower = t.ensureAbility();
            }
            require(empower != null, "Packaged Way of the Mentor has no Empower trigger");
            empower.setActivatingPlayer(p);
            AbilityUtils.resolve(empower);
            require(tokens(p, "Jace").size() == 1, "Empower did not create exactly one Jace token");
            Card token = tokens(p, "Jace").get(0);
            require(token.isPlaneswalker() && token.getColor().hasBlue(), "Wrong token type or color");
            require(!token.getType().isLegendary(), "Empower token incorrectly legendary");
            require(token.getCounters(CounterEnumType.LOYALTY) == 5, "New token did not receive five loyalty");
            require(token.getCounters(CounterEnumType.P1P1) == 0, "Empower added creature counters");
            require(commander.getCounters(CounterEnumType.LOYALTY) == 0, "Empower changed the nontoken commander");
            System.out.println("WAY_OF_THE_MENTOR_NEW_JACE_PASS loyalty=5");

            AbilityUtils.resolve(empower);
            require(tokens(p, "Jace").size() == 1 && token.getCounters(CounterEnumType.LOYALTY) == 10,
                    "Repeated Empower did not reuse the existing token");
            System.out.println("REPEATED_EMPOWER_PASS tokens=1 loyalty=10");

            SpellAbility surveil = null, draw = null;
            for (SpellAbility a : token.getSpellAbilities()) {
                if (a.getApi() == ApiType.Surveil) surveil = a;
                if (a.getApi() == ApiType.Draw) draw = a;
            }
            require(surveil != null && draw != null, "Token is missing loyalty abilities");
            surveil.setActivatingPlayer(p);
            require(ComputerUtil.playNoStack(p, surveil, game, false), "Surveil cost was not paid");
            require(token.getCounters(CounterEnumType.LOYALTY) == 9, "Surveil did not cost one loyalty");
            int hand = p.getCardsIn(ZoneType.Hand).size();
            draw.setActivatingPlayer(p);
            require(ComputerUtil.playNoStack(p, draw, game, false), "Draw cost was not paid");
            require(token.getCounters(CounterEnumType.LOYALTY) == 6, "Draw did not cost three loyalty");
            require(p.getCardsIn(ZoneType.Hand).size() == hand + 1, "Draw did not add one card to hand");
            System.out.println("TOKEN_ABILITIES_PASS surveilCost=1 drawCost=3 cardsDrawn=1");

            SpellAbility fallback = AbilityFactory.getAbility("DB$ Empower | Type$ Teferi | Num$ 2", mentor);
            fallback.setActivatingPlayer(p);
            AbilityUtils.resolve(fallback);
            require(tokens(p, "Teferi").size() == 1 && tokens(p, "Teferi").get(0).getCounters(CounterEnumType.LOYALTY) == 2,
                    "Generic Empower fallback did not create the requested token");
            boolean fallbackSurveil = false, fallbackDraw = false;
            for (SpellAbility a : tokens(p, "Teferi").get(0).getSpellAbilities()) {
                fallbackSurveil |= a.getApi() == ApiType.Surveil;
                fallbackDraw |= a.getApi() == ApiType.Draw;
            }
            require(fallbackSurveil && fallbackDraw, "Fallback token lacks abilities");
            System.out.println("GENERIC_EMPOWER_FALLBACK_PASS loyalty=2");
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
}
