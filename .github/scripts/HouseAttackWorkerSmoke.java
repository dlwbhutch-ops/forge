import com.housecommander.forgebridge.*;
import forge.ai.AiAttackController;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.combat.*;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Real attack selection must not depend on unrelated background workers. */
public final class HouseAttackWorkerSmoke {
    public static void main(String[] args) throws Exception {
        try {
            HouseForgeRuntime.initialize(Path.of(args[0]).toFile(), "HOUSE attack worker regression");
            Deck deck = ForgeDeckLoader.load(Path.of(args[1]).toFile());
            List<RegisteredPlayer> registered = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                registered.add(RegisteredPlayer.forCommander(deck)
                        .setPlayer(GamePlayerUtil.createAiPlayer("Attack test " + i, 0, 0)));
            }
            Game game = new Match(new GameRules(GameType.Commander), registered, "Attack worker regression").createGame();
            game.setAge(GameStage.Play);
            game.AI_CAN_USE_TIMEOUT = false;
            Player player = game.getPlayers().get(0);
            game.getPhaseHandler().setPlayerTurn(player);
            Field phase = game.getPhaseHandler().getClass().getDeclaredField("phase");
            phase.setAccessible(true);
            phase.set(game.getPhaseHandler(), forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS);
            for (int i = 0; i < 21; i++) {
                String name = i == 0 ? "Myr Battlesphere" : "Ornithopter";
                Card card = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), player);
                player.getZone(ZoneType.Battlefield).add(card);
                card.setSickness(false);
                card.setSVar("MustAttack", "True");
            }
            Combat combat = new Combat(player);
            Field field = game.getPhaseHandler().getClass().getDeclaredField("combat");
            field.setAccessible(true); field.set(game.getPhaseHandler(), combat);
            AiAttackController controller = new AiAttackController(player);
            CountDownLatch occupied = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            for (int i = 0; i < 2; i++) ForkJoinPool.commonPool().execute(() -> {
                occupied.countDown();
                try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            if (!occupied.await(5, TimeUnit.SECONDS)) throw new AssertionError("Could not occupy background pool");
            Thread rescue = new Thread(() -> {
                try { Thread.sleep(4000); } catch (InterruptedException ignored) { }
                release.countDown();
            });
            rescue.setDaemon(true); rescue.start();
            long started = System.nanoTime();
            try { controller.declareAttackers(combat); }
            finally { release.countDown(); }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (elapsed >= 2500) throw new AssertionError("Attack selection waited for unrelated workers: " + elapsed + "ms");
            if (combat.getAttackers().size() != 21 || !CombatUtil.validateAttackers(combat)) {
                throw new AssertionError("Attack selection lost legal forced attackers: " + combat.getAttackers().size());
            }
            System.out.println("BUSY_BACKGROUND_POOL_ATTACK_PASS attackers=21 elapsedMs=" + elapsed);

            combat.clearAttackers();
            Thread.currentThread().interrupt();
            try {
                new AiAttackController(player).declareAttackers(combat);
                throw new AssertionError("Interrupted attack selection continued");
            } catch (CancellationException expected) {
                if (game.getOutcome() != null) throw new AssertionError("Cancellation created a result");
            } finally { Thread.interrupted(); }
            System.out.println("ATTACK_CANCELLATION_NO_OUTCOME_PASS");

            CountDownLatch waiting = new CountDownLatch(1), stop = new CountDownLatch(1);
            Thread blocked = new Thread(() -> {
                waiting.countDown();
                try { stop.await(); } catch (InterruptedException ignored) { }
            }, "HOUSE-Forge-Game");
            blocked.start(); waiting.await();
            HouseStallDiagnostics.reset();
            HouseStallDiagnostics.capture("regression stall");
            stop.countDown(); blocked.join();
            String diagnostic = String.join("\n", HouseStallDiagnostics.append(new String[]{"HOUSE_ERROR=STALL_TIMEOUT"}));
            if (!diagnostic.contains("HOUSE-Forge-Game") || !diagnostic.contains("HouseAttackWorkerSmoke")
                    || !diagnostic.contains("HOUSE_APK_BUILD=195")) throw new AssertionError(diagnostic);
            HouseStallDiagnostics.reset();
            if (String.join("\n", HouseStallDiagnostics.append(null)).contains("HOUSE_WORKER_STACK=")) {
                throw new AssertionError("Previous game diagnostics leaked into a new game");
            }
            System.out.println("PRE_CLEANUP_WORKER_DIAGNOSTICS_PASS");
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
}
