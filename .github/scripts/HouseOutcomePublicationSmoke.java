import com.housecommander.forgebridge.*;
import forge.game.*;
import forge.game.player.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Regression: watchdog cleanup must never publish a tournament winner. */
public final class HouseOutcomePublicationSmoke {
    public static void main(String[] args) throws Exception {
        try {
            HouseForgeRuntime.initialize(Path.of(args[0]).toFile(), "HOUSE outcome publication regression");
            Path root = Files.createTempDirectory("house-outcome-regression");
            String[] decks = new String[4];
            for (int i = 0; i < decks.length; i++) {
                Path deck = root.resolve(i + ".dck");
                Files.writeString(deck, "[metadata]\nName=Outcome " + i
                        + "\n[Commander]\n1 Isamaru, Hound of Konda\n[Main]\n99 Plains\n");
                decks[i] = deck.toString();
            }
            Game aborted = game(decks);
            Object recorder = recorder(aborted, root.resolve("aborted.log"));
            SpectatorPlayback.reset(LiveGameState.starting());
            publish(recorder, "GAME_READY", false);
            // This is the actual cleanup path used after an unresponsive worker.
            Method forceDraw = ForgeBridge.class.getDeclaredMethod("forceDrawIfNeeded", Game.class);
            forceDraw.setAccessible(true);
            forceDraw.invoke(null, aborted);
            publish(recorder, "GameEventGameOutcome", false);
            if (SpectatorPlayback.latestState().gameOver()
                    || !SpectatorPlayback.latestState().winner().isEmpty()
                    || FriendlyGameLog.renderFeed(Long.MAX_VALUE, 100).contains("wins the game")
                    || FriendlyGameLog.renderGameSummary(Long.MAX_VALUE).contains("Winner:")) {
                throw new AssertionError("Watchdog cleanup published an unvalidated winner");
            }
            System.out.println("ABORTED_GAME_NO_WINNER_PASS");

            Method validate = null;
            for (Method method : ForgeBridge.class.getDeclaredMethods()) {
                if (method.getName().equals("validateOutcomeAndLog")) validate = method;
            }
            validate.setAccessible(true);
            Class<?> heartbeatType = Class.forName("com.housecommander.forgebridge.ForgeBridge$ProgressHeartbeat");
            Constructor<?> heartbeatCtor = heartbeatType.getDeclaredConstructor(long.class);
            heartbeatCtor.setAccessible(true);
            Object heartbeat = heartbeatCtor.newInstance(System.nanoTime());
            try {
                validate.invoke(null, aborted, root.resolve("draw.log").toString(), 0L, 60, 30, heartbeat);
                throw new AssertionError("Forced draw was accepted as a winner");
            } catch (InvocationTargetException expected) {
                if (!(expected.getCause() instanceof IllegalStateException)
                        || !expected.getCause().getMessage().contains("draw")) throw expected;
            }
            System.out.println("FORCED_DRAW_REJECTED_PASS");

            Game finished = game(decks);
            recorder = recorder(finished, root.resolve("finished.log"));
            SpectatorPlayback.reset(LiveGameState.starting());
            publish(recorder, "GAME_READY", false);
            for (int i = 1; i < finished.getRegisteredPlayers().size(); i++) {
                finished.getRegisteredPlayers().get(i).getStats().setOutcome(PlayerOutcome.concede());
            }
            finished.setGameOver(GameEndReason.AllOpposingTeamsLost);
            publish(recorder, "GameEventGameOutcome", false);
            if (!SpectatorPlayback.latestState().winner().isEmpty()) {
                throw new AssertionError("Winner published before validation");
            }
            validate.invoke(null, finished, root.resolve("finished.log").toString(), 0L, 60, 30, heartbeat);
            publish(recorder, "GAME_COMPLETE", true);
            if (!SpectatorPlayback.latestState().gameOver()
                    || !SpectatorPlayback.latestState().winner().equals("Outcome 0")
                    || !FriendlyGameLog.renderFeed(Long.MAX_VALUE, 100).contains("Outcome 0 wins the game")) {
                throw new AssertionError("Validated winner was not published");
            }
            System.out.println("VALIDATED_WINNER_PUBLISHED_PASS");
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    @SuppressWarnings("unchecked")
    private static Game game(String[] decks) throws Exception {
        Method load = ForgeBridge.class.getDeclaredMethod("loadPlayers", String[].class, int.class);
        load.setAccessible(true);
        List<RegisteredPlayer> players = (List<RegisteredPlayer>) load.invoke(null, decks, -1);
        return new Match(new GameRules(GameType.Commander), players, "HOUSE outcome regression").createGame();
    }

    private static Object recorder(Game game, Path log) throws Exception {
        Class<?> type = Class.forName("com.housecommander.forgebridge.ForgeBridge$LiveStateRecorder");
        Constructor<?> ctor = type.getDeclaredConstructor(Game.class, String.class);
        ctor.setAccessible(true);
        Object recorder = ctor.newInstance(game, log.toString());
        game.subscribeToEvents(recorder);
        return recorder;
    }

    private static void publish(Object recorder, String event, boolean force) throws Exception {
        Method method = recorder.getClass().getDeclaredMethod("publish", String.class, boolean.class);
        method.setAccessible(true);
        method.invoke(recorder, event, force);
    }
}
