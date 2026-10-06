import com.housecommander.lab.state.GameLogFiles;
import com.housecommander.lab.state.GameCancellation;
import com.housecommander.lab.state.RunState;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Regression for a stopped Android process with a durable, partly completed tournament. */
public final class HouseAndroidStateSmoke {
    public static void main(String[] args) throws Exception {
        for (String status : List.of("RUNNING", "TESTING", "PILOTING")) {
            RunState state = checkpoint(status);
            check(!state.recoverInterruptedRun(true), "A live service must keep its run state");
            check(status.equals(state.status), "A live game must not be interrupted by Activity recreation");
            check(state.recoverInterruptedRun(false), "Process loss must enable recovery");
            check("INTERRUPTED".equals(state.status) && !state.inProgress(), "Resume must be available");
            check(state.totalGames == 15 && state.nextPodIndex == 15 && state.currentGauntlet == 1,
                    "Completed games and next pod must survive");
            check(state.targetGauntlets == 500 && state.rosterKey.equals("21-deck-custom-roster"),
                    "Target and imported roster must survive");
            check(state.wins.get("Jace") == 3 && state.games.get("Jace") == 6,
                    "Partial gauntlet standings must survive");
            check(state.pauseRequested && state.lastLogPath.equals("logs/g0001/016_r16_p01.log"),
                    "Pause command and diagnostic identity must survive");
            check(!state.recoverInterruptedRun(false), "Recovery must be idempotent");
        }
        for (String status : List.of("IDLE", "PAUSED", "COMPLETE", "BLOCKED", "TEST_COMPLETE", "PILOT_COMPLETE")) {
            RunState state = checkpoint(status);
            check(!state.recoverInterruptedRun(false) && status.equals(state.status), "Terminal state must survive");
        }
        System.out.println("ANDROID_PROCESS_RECOVERY_PASS completed=15 next-pod=16 roster=21 preserved");

        Path dir = Files.createTempDirectory("house-game-log-smoke");
        try {
            Path test = dir.resolve("logs/test/test.log");
            Path tournament = dir.resolve("logs/g0001/015_r15_p01.log");
            Path viewer = dir.resolve("logs/g0001/015_r15_p01.log.viewer.log");
            Files.createDirectories(test.getParent()); Files.createDirectories(tournament.getParent());
            Files.writeString(test, "test"); Files.writeString(tournament, "tournament"); Files.writeString(viewer, "viewer error");
            test.toFile().setLastModified(1000); tournament.toFile().setLastModified(2000); viewer.toFile().setLastModified(3000);
            RunState legacy = new RunState();
            check(GameLogFiles.find(dir.toFile(), legacy).equals(tournament.toFile()),
                    "Old checkpoints must find gauntlet logs and exclude viewer sidecars");
            RunState active = checkpoint("RUNNING");
            File expected = dir.resolve(active.lastLogPath).toFile();
            check(GameLogFiles.find(dir.toFile(), active).equals(expected),
                    "Current game's path must win even before Forge writes the final audit log");
            active.lastLogPath = "../outside.log";
            check(GameLogFiles.find(dir.toFile(), active).equals(tournament.toFile()), "Log paths must stay inside logs");
            active.lastLogPath = "logs/test/test.log";
            check(GameLogFiles.find(dir.toFile(), active).equals(test.toFile()), "Explicit test path must beat older tournament state");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("ANDROID_GAME_LOG_SELECTION_PASS active + legacy + sidecars");
        cancellationChecks();
    }

    private static void cancellationChecks() throws Exception {
        RunState state = checkpoint("RUNNING");
        GameCancellation queued = new GameCancellation();
        check(!queued.stopHasTimedOut(), "An idle controller must not offer forced close");
        check(queued.requestStop() && !queued.requestStop(), "Stop must be idempotent");
        check(!queued.stopHasTimedOut(), "Cooperative cleanup gets a grace period");
        queued.bindToCurrentThread();
        check(Thread.interrupted(), "A queued stop must interrupt when the worker starts");
        try {
            queued.commitVerifiedGame(() -> { state.totalGames++; state.nextPodIndex++; state.wins.put("Jace", 4); });
            throw new AssertionError("Cancelled game was checkpointed");
        } catch (CancellationException expected) {
            state.markStoppedGame();
        } finally { queued.releaseWorker(); }
        check("PAUSED".equals(state.status) && state.totalGames == 15 && state.nextPodIndex == 15,
                "Stopping must retain the unfinished pod and all completed results");
        check(state.wins.get("Jace") == 3 && state.games.get("Jace") == 6
                && state.targetGauntlets == 500 && state.rosterKey.equals("21-deck-custom-roster"),
                "Stopping must retain standings, target, and custom roster");

        GameCancellation live = new GameCancellation();
        CountDownLatch bound = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            live.bindToCurrentThread(); bound.countDown();
            try {
                new CountDownLatch(1).await();
                failure.set(new AssertionError("Worker unexpectedly resumed"));
            } catch (InterruptedException expected) {
                check(live.isStopRequested(), "Interrupt must correspond to Stop");
                cancelled.countDown();
            } finally { live.releaseWorker(); }
        }, "HOUSE-Cancellation-State-Smoke");
        worker.setDaemon(true); worker.start();
        check(bound.await(2, TimeUnit.SECONDS), "Worker did not bind");
        live.requestStop();
        check(cancelled.await(2, TimeUnit.SECONDS), "Stop did not interrupt a live worker");
        worker.join(2000);
        check(!worker.isAlive() && failure.get() == null, "Cancelled worker did not finish");

        GameCancellation released = new GameCancellation();
        released.bindToCurrentThread(); released.releaseWorker(); released.requestStop();
        check(!Thread.interrupted(), "A released controller must not interrupt later work");

        GameCancellation race = new GameCancellation();
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch finishCommit = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean stopped = new AtomicBoolean();
        Thread checkpoint = new Thread(() -> race.commitVerifiedGame(() -> {
            committing.countDown();
            try { check(finishCommit.await(2, TimeUnit.SECONDS), "Commit test timed out"); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            completed.set(true);
        }));
        checkpoint.setDaemon(true); checkpoint.start();
        check(committing.await(2, TimeUnit.SECONDS), "Checkpoint did not start");
        Thread stopper = new Thread(() -> { race.requestStop(); stopped.set(completed.get()); });
        stopper.setDaemon(true); stopper.start(); finishCommit.countDown();
        checkpoint.join(2000); stopper.join(2000);
        check(!checkpoint.isAlive() && !stopper.isAlive() && stopped.get(),
                "Stop must wait for a verified checkpoint already committing");
        try { race.commitVerifiedGame(() -> { throw new AssertionError("A later result was accepted"); }); }
        catch (CancellationException expected) { }
        System.out.println("ANDROID_STOP_CHECKPOINT_PASS interrupted + queued + race + completed=15 next-pod=16 preserved");
    }
    private static RunState checkpoint(String status) {
        RunState state = new RunState(); state.status = status; state.targetGauntlets = 500;
        state.totalGames = 15; state.nextPodIndex = 15; state.currentGauntlet = 1;
        state.rosterKey = "21-deck-custom-roster"; state.pauseRequested = true;
        state.lastLogPath = "logs/g0001/016_r16_p01.log";
        state.wins.put("Jace", 3); state.games.put("Jace", 6);
        return state;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
