import com.housecommander.forgebridge.ForgeBridge;
import com.housecommander.forgebridge.HouseForgeRuntime;
import com.housecommander.forgebridge.PilotDecisionBridge;
import com.housecommander.forgebridge.SpectatorPlayback;
import com.housecommander.lab.state.GameCancellation;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real Forge cancellation, then fault injection for an engine holding its Game monitor. */
public final class HouseCancellationSmoke {
    public static void main(String[] args) throws Exception {
        try { run(args); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        System.exit(0);
    }

    private static void run(String[] args) throws Exception {
        HouseForgeRuntime.initialize(Path.of(args[0]).toFile(), "HOUSE-stop-smoke");
        if (!ForgeBridge.isAvailable()) throw new AssertionError(ForgeBridge.status());
        Path logs = Path.of(args[1]); Files.createDirectories(logs);
        Path decks = Files.createTempDirectory("house-stop-decks");
        String[] paths = new String[4];
        for (int i = 0; i < paths.length; i++) {
            Path file = decks.resolve("stop-" + i + ".dck");
            Files.writeString(file, "[metadata]\nName=Stop Smoke " + i
                    + "\n[Commander]\n1 Isamaru, Hound of Konda\n[Main]\n99 Plains\n");
            paths[i] = file.toString();
        }
        switch (args[2]) {
            case "pilot": cancelRealGame(paths, logs.resolve("pilot-stop.log"), true); break;
            case "ai": cancelRealGame(paths, logs.resolve("ai-stop.log"), false); break;
            case "locked": lockedGameAbort(paths, logs.resolve("blocked-engine.log")); break;
            default: throw new IllegalArgumentException("Unknown cancellation scenario");
        }
    }

    private static void cancelRealGame(String[] decks, Path log, boolean pilot) throws Exception {
        GameCancellation control = new GameCancellation();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean returnedWinner = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            control.bindToCurrentThread(); entered.countDown();
            try {
                if (pilot) ForgeBridge.runCommanderGameWithPilot(decks, 0, log.toString(), 60, 30);
                else ForgeBridge.runCommanderGame(decks, log.toString(), 60, 30);
                returnedWinner.set(true);
            } catch (Throwable error) { failure.set(error); }
            finally { control.releaseWorker(); }
        }, "HOUSE-Real-Stop-Smoke");
        worker.setDaemon(true); worker.start();
        if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("Runner did not start");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        boolean ready = false;
        while (worker.isAlive() && System.nanoTime() < deadline) {
            ready = pilot ? PilotDecisionBridge.hasPending()
                    : SpectatorPlayback.latestState().turn() > 0
                    && !SpectatorPlayback.latestState().gameOver()
                    && SpectatorPlayback.latestState().players().size() == 4;
            if (ready) break;
            Thread.sleep(10L);
        }
        if (!ready) throw new AssertionError("No live " + (pilot ? "pilot decision" : "four-player game"));
        long started = System.nanoTime();
        control.requestStop(); worker.join(10000L);
        if (worker.isAlive() || returnedWinner.get() || !(failure.get() instanceof InterruptedException)) {
            throw new AssertionError("Cancelled game did not finish with interruption: " + failure.get());
        }
        String audit = Files.readString(log);
        if (!audit.contains("HOUSE_ERROR=INTERRUPTED") || audit.contains("HOUSE_WINNER=")
                || PilotDecisionBridge.hasPending()
                || (!ForgeBridge.isAvailable() && !ForgeBridge.requiresProcessRestart())) {
            throw new AssertionError("Stop must leave no result or pending pilot decision: " + ForgeBridge.status());
        }
        System.out.println("LITERAL_" + (pilot ? "PILOT" : "AI") + "_STOP_PASS elapsed-ms="
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " no-winner "
                + (ForgeBridge.requiresProcessRestart() ? "restart-required" : "engine-reusable"));
    }

    @SuppressWarnings("unchecked")
    private static void lockedGameAbort(String[] decks, Path log) throws Exception {
        Method load = ForgeBridge.class.getDeclaredMethod("loadPlayers", String[].class, int.class);
        load.setAccessible(true);
        List<RegisteredPlayer> players = (List<RegisteredPlayer>) load.invoke(null, decks, -1);
        Game game = new Match(new GameRules(GameType.Commander), players, "HOUSE locked-engine fault").createGame();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService stuck = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "HOUSE-Stuck-Engine-Fault"); thread.setDaemon(true); return thread;
        });
        Future<?> future = stuck.submit(() -> {
            synchronized (game) {
                locked.countDown();
                while (release.getCount() > 0) {
                    try { release.await(); }
                    catch (InterruptedException ignored) { /* Deliberately ignore cancellation. */ }
                }
            }
        });
        if (!locked.await(2, TimeUnit.SECONDS)) throw new AssertionError("Engine fault did not hold Game lock");
        Method abort = ForgeBridge.class.getDeclaredMethod("abortGame", Future.class, ExecutorService.class,
                Game.class, Throwable.class, String.class);
        abort.setAccessible(true);
        long started = System.nanoTime();
        Throwable original = new InterruptedException("Fault injection");
        try {
            abort.invoke(null, future, stuck, game, original, "locked engine fault");
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (elapsed > 7000 || !ForgeBridge.requiresProcessRestart() || ForgeBridge.isAvailable()) {
                throw new AssertionError("An unresponsive Game lock must finish cleanup within its bounded grace");
            }
            Method snapshot = ForgeBridge.class.getDeclaredMethod("trySnapshot", Game.class);
            snapshot.setAccessible(true);
            if (snapshot.invoke(null, game) != null) throw new AssertionError("Poisoned engine must not be read");
            Method logFailure = ForgeBridge.class.getDeclaredMethod("writeFailureLog", Game.class, String.class,
                    long.class, Throwable.class, String[].class);
            logFailure.setAccessible(true);
            logFailure.invoke(null, game, log.toString(), elapsed, original, new String[]{"HOUSE_ERROR=INTERRUPTED"});
            String audit = Files.readString(log);
            if (!audit.contains("HOUSE_ERROR=INTERRUPTED") || audit.contains("HOUSE_WINNER=")) {
                throw new AssertionError("Blocked cleanup must leave diagnostic evidence without a winner");
            }
            System.out.println("LOCKED_ENGINE_ABORT_PASS elapsed-ms=" + elapsed + " restart-required no-winner");
        } finally { release.countDown(); stuck.shutdownNow(); }
    }
}
