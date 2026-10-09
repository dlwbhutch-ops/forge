package com.housecommander.lab.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.housecommander.core.DeckSpec;
import com.housecommander.core.HousePackage;
import com.housecommander.core.RosterBuilder;
import com.housecommander.core.Names;
import com.housecommander.core.PodSpec;
import com.housecommander.lab.HouseRuntime;
import com.housecommander.lab.MainActivity;
import com.housecommander.lab.engine.ForgeEngineAdapter;
import com.housecommander.lab.engine.GameOutcome;
import com.housecommander.lab.state.ResultsWriter;
import com.housecommander.lab.state.RunState;
import com.housecommander.lab.state.StateStore;
import com.housecommander.lab.state.GameCancellation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground runner for literal Forge HOUSE Commander games.
 *
 * Checkpoint policy:
 *  - checkpoint synchronously after every verified literal game;
 *  - never destroy an existing run from ACTION_RUN;
 *  - an explicitly larger target extends a completed run in place;
 *  - only the UI's explicit reset action should erase tournament state.
 */
public final class TournamentService extends Service {
    public static final String ACTION_RUN = "com.housecommander.lab.RUN";
    public static final String ACTION_TEST = "com.housecommander.lab.TEST";
    public static final String ACTION_PAUSE = "com.housecommander.lab.PAUSE";
    public static final String ACTION_STOP = "com.housecommander.lab.STOP";
    public static final String ACTION_PILOT = "com.housecommander.lab.PILOT";
    public static final String EXTRA_GAUNTLETS = "gauntlets";
    public static final String EXTRA_PILOT_DECK = "pilot_deck";

    private static final int NOTIFICATION_ID = 1901;
    private static final String CHANNEL_ID = "house_tournament";
    private static final Object RUNNER_LOCK = new Object();
    private static TournamentService activeWorker;

    /* Bridge 0.7 watchdog policy. */
    private static final int HARD_TIMEOUT_SECONDS = 60 * 60;
    private static final int STALL_TIMEOUT_SECONDS = 3 * 60;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean workerActive = false;
    private volatile GameCancellation cancellation;
    private PowerManager.WakeLock wakeLock;

    public static boolean hasActiveWorker() {
        synchronized (RUNNER_LOCK) {
            return activeWorker != null;
        }
    }

    public static boolean isStopRequested() {
        synchronized (RUNNER_LOCK) {
            return activeWorker != null && activeWorker.cancellation.isStopRequested();
        }
    }

    public static boolean stopHasTimedOut() {
        synchronized (RUNNER_LOCK) {
            return activeWorker != null && activeWorker.cancellation.stopHasTimedOut();
        }
    }

    /** Activity and Service share a process; process loss clears this lease, but not the checkpoint. */
    public static RunState displayState(Context context) {
        synchronized (RUNNER_LOCK) {
            StateStore store = new StateStore(context);
            RunState state = store.load();
            if (state.recoverInterruptedRun(activeWorker != null)) store.save(state);
            if (activeWorker != null && activeWorker.cancellation.isStopRequested()) {
                state.status = "STOPPING";
                state.lastMessage = "Stopping current game • completed results are saved";
            }
            return state;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            synchronized (RUNNER_LOCK) {
                if (activeWorker != null) {
                    activeWorker.updateNotification("Stopping current game — completed results saved");
                    activeWorker.cancellation.requestStop();
                } else {
                    displayState(this);
                    stopSelf(startId);
                }
            }
            return START_NOT_STICKY;
        }

        if (ACTION_PAUSE.equals(action)) {
            try {
                new StateStore(this).requestPause();
                if (workerActive) {
                    updateNotification("Pause requested — finishing current game");
                }
            } catch (Throwable t) {
                if (workerActive) {
                    updateNotification("Pause request failed: " + safeMessage(t));
                }
            }
            if (!workerActive) {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }

        if (!ACTION_TEST.equals(action)
                && !ACTION_RUN.equals(action)
                && !ACTION_PILOT.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        final GameCancellation control;
        synchronized (RUNNER_LOCK) {
            startForegroundCompat("Preparing HOUSE Commander Lab");
            if (activeWorker != null) {
                updateNotification("HOUSE runner is already active");
                if (activeWorker != this) stopSelf(startId);
                return START_NOT_STICKY;
            }
            workerActive = true;
            control = new GameCancellation();
            cancellation = control;
            activeWorker = this;
        }

        try {
            acquireWakeLock();

            final int workerStartId = startId;
            if (ACTION_TEST.equals(action)) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        runEngineTest(workerStartId, control);
                    }
                });
            } else if (ACTION_PILOT.equals(action)) {
                final String pilotDeck = intent.getStringExtra(EXTRA_PILOT_DECK);
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        runPilotGame(workerStartId, pilotDeck, control);
                    }
                });
            } else {
                final int gauntlets = Math.max(
                        1,
                        intent.getIntExtra(EXTRA_GAUNTLETS, 1)
                );
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        runTournament(workerStartId, gauntlets, control);
                    }
                });
            }

        } catch (RuntimeException error) {
            finishWorker(startId);
            throw error;
        }
        return START_NOT_STICKY;
    }

    private void runEngineTest(int startId, GameCancellation control) {
        StateStore store = new StateStore(this);
        RunState state = store.load();

        try {
            control.bindToCurrentThread();
            control.check();
            state.status = "TESTING";
            state.lastMessage = "Running one literal Forge test game";
            store.save(state);

            HousePackage pack = HouseRuntime.loadActivePackage(this);

            if (pack.schedule() == null || pack.schedule().isEmpty()) {
                throw new IllegalStateException("HOUSE schedule contains no pods");
            }

            ForgeEngineAdapter engine = new ForgeEngineAdapter(this);
            if (!engine.isAvailable()) {
                throw new IllegalStateException(engine.status());
            }

            PodSpec pod = pack.schedule().get(0);
            List<DeckSpec> decks = resolvePod(pack, pod);
            File log = logFile("test", 1, pod);
            state.lastLogPath = relativeLogPath(log);
            store.save(state);

            GameOutcome outcome = engine.runCommanderGame(
                    decks,
                    log,
                    HARD_TIMEOUT_SECONDS,
                    STALL_TIMEOUT_SECONDS
            );
            String winner = validateWinner(decks, outcome.winner());

            control.commitVerifiedGame(() -> {
                RunState completed = store.load();
                completed.status = "TEST_COMPLETE";
                completed.lastMessage = "Test winner: " + winner + " • " + outcome.engineVersion();
                store.save(completed);
                updateNotification(completed.lastMessage);
            });
        } catch (Throwable t) {
            handleFailure(store, control, t);
        } finally {
            control.releaseWorker();
            finishWorker(startId);
        }
    }

    private void runPilotGame(int startId, String pilotDeckName, GameCancellation control) {
        StateStore store = new StateStore(this);
        RunState state = store.load();

        try {
            control.bindToCurrentThread();
            control.check();
            HousePackage pack = HouseRuntime.loadActivePackage(this);
            List<DeckSpec> decks = pilotPod(pack, pilotDeckName);
            DeckSpec pilot = decks.get(0);

            ForgeEngineAdapter engine = new ForgeEngineAdapter(this);
            if (!engine.isAvailable()) {
                throw new IllegalStateException(engine.status());
            }

            state.status = "PILOTING";
            state.lastMessage = "Piloting "
                    + pilot.deck()
                    + " vs 3 Forge AI";
            store.save(state);
            updateNotification(state.lastMessage);

            File log = new File(
                    getFilesDir(),
                    "logs/pilot/pilot-game.log"
            );
            state.lastLogPath = relativeLogPath(log);
            store.save(state);
            GameOutcome outcome = engine.runCommanderGameWithPilot(
                    decks,
                    0,
                    log,
                    HARD_TIMEOUT_SECONDS,
                    STALL_TIMEOUT_SECONDS
            );
            String winner = validateWinner(decks, outcome.winner());

            control.commitVerifiedGame(() -> {
                RunState completed = store.load();
                completed.status = "PILOT_COMPLETE";
                completed.lastMessage = "Pilot game winner: " + winner + " • " + outcome.engineVersion();
                store.save(completed);
                updateNotification(completed.lastMessage);
            });
        } catch (Throwable t) {
            handleFailure(store, control, t);
        } finally {
            control.releaseWorker();
            finishWorker(startId);
        }
    }

    private void runTournament(int startId, int requestedGauntlets, GameCancellation control) {
        StateStore store = new StateStore(this);
        RunState state = store.load();

        try {
            control.bindToCurrentThread();
            control.check();
            HousePackage pack = HouseRuntime.loadActivePackage(this);

            if (pack.schedule() == null || pack.schedule().isEmpty()) {
                throw new IllegalStateException("HOUSE schedule contains no pods");
            }
            final int podCount = pack.schedule().size();
            final String rosterKey = RosterBuilder.fingerprint(pack.decks());

            ForgeEngineAdapter engine = new ForgeEngineAdapter(this);
            if (!engine.isAvailable()) {
                throw new IllegalStateException(engine.status());
            }

            state = prepareRunState(store, state, requestedGauntlets, podCount, rosterKey);

            /*
             * A completed run is never silently reset by pressing Run again.
             * To start over, the user must use the explicit Reset control.
             */
            if ("COMPLETE".equals(state.status)
                    && requestedGauntlets <= state.completedGauntlets()) {
                state.lastMessage = "Already completed "
                        + state.completedGauntlets()
                        + " gauntlets — reset explicitly to start over";
                store.save(state);
                updateNotification(state.lastMessage);
                return;
            }

            store.clearPauseRequest();
            state.status = "RUNNING";
            state.lastMessage = "Resuming HOUSE run at gauntlet " + state.currentGauntlet;
            store.save(state);

            for (int g = state.currentGauntlet; g <= state.targetGauntlets; g++) {
                state = store.load();
                state.currentGauntlet = g;
                validateCheckpoint(state, podCount);
                store.save(state);

                for (int i = state.nextPodIndex; i < podCount; i++) {
                    control.check();
                    if (store.consumePauseRequest()) {
                        state = store.load();
                        state.status = "PAUSED";
                        state.lastMessage = "Paused at gauntlet "
                                + g
                                + ", next pod "
                                + (i + 1)
                                + "/"
                                + podCount;
                        store.save(state);
                        updateNotification(state.lastMessage);
                        return;
                    }

                    PodSpec pod = pack.schedule().get(i);
                    List<DeckSpec> decks = resolvePod(pack, pod);
                    File log = logFile(
                            "g" + String.format(Locale.US, "%04d", g),
                            i + 1,
                            pod
                    );

                    state = store.load();
                    state.status = "RUNNING";
                    state.currentGauntlet = g;
                    state.nextPodIndex = i;
                    state.lastLogPath = relativeLogPath(log);
                    state.lastMessage = "G"
                            + g
                            + "/"
                            + state.targetGauntlets
                            + " • R"
                            + pod.round()
                            + " P"
                            + pod.pod()
                            + " • game "
                            + (i + 1)
                            + "/"
                            + podCount;
                    store.save(state);
                    updateNotification(state.lastMessage);

                    GameOutcome outcome = engine.runCommanderGame(
                            decks,
                            log,
                            HARD_TIMEOUT_SECONDS,
                            STALL_TIMEOUT_SECONDS
                    );
                    String winner = validateWinner(decks, outcome.winner());

                    /*
                     * Commit the verified game before starting another one.
                     * Bridge 0.7 already writes HOUSE_ENGINE and HOUSE_WINNER
                     * into the literal log, so no duplicate marker is appended
                     * here.
                     */
                    checkpointGame(store, control, g, i + 1, podCount, decks, winner);
                }

                /*
                 * Idempotent result write: if Android dies after this write but
                 * before the checkpoint advances, writeGauntlet() replaces the
                 * same gauntlet rows on resume rather than duplicating them.
                 */
                state = store.load();
                validateCheckpoint(state, podCount);
                if (state.nextPodIndex != podCount) {
                    throw new IllegalStateException(
                            "Cannot finalize gauntlet "
                                    + g
                                    + ": checkpoint says nextPodIndex="
                                    + state.nextPodIndex
                                    + " but podCount="
                                    + podCount
                    );
                }

                ResultsWriter.writeGauntlet(
                        this,
                        pack,
                        g,
                        state.wins,
                        state.games
                );

                state.wins.clear();
                state.games.clear();
                state.currentGauntlet = g + 1;
                state.nextPodIndex = 0;
                state.lastMessage = "Completed gauntlet "
                        + g
                        + "/"
                        + state.targetGauntlets;
                store.save(state);
            }

            state = store.load();
            state.status = "COMPLETE";
            state.lastMessage = "Completed "
                    + state.completedGauntlets()
                    + " gauntlets • "
                    + state.totalGames
                    + " literal games";
            store.save(state);
            updateNotification(state.lastMessage);
        } catch (Throwable t) {
            handleFailure(store, control, t);
        } finally {
            control.releaseWorker();
            finishWorker(startId);
        }
    }

    private void checkpointGame(StateStore store, GameCancellation control, int gauntlet,
            int nextPod, int podCount, List<DeckSpec> decks, String winner) {
        control.commitVerifiedGame(() -> {
            RunState completed = store.load();
            completed.currentGauntlet = gauntlet;
            for (DeckSpec deck : decks) increment(completed.games, deck.deck());
            increment(completed.wins, winner);
            completed.totalGames++;
            completed.nextPodIndex = nextPod;
            completed.lastMessage = "Winner: " + winner + " • G" + gauntlet + " game " + nextPod + "/" + podCount;
            store.save(completed);
        });
    }

    private void handleFailure(StateStore store, GameCancellation control, Throwable error) {
        // Forge restores the interrupt flag after cleanup. Clear it while committing
        // recovery state: SharedPreferences.commit() waits on an interruptible latch.
        boolean interrupted = Thread.interrupted();
        try {
            RunState saved = store.load();
            if (control.isStopRequested()) {
                saved.markStoppedGame();
                store.clearPauseRequest();
            } else {
                saved.status = "BLOCKED";
                saved.lastMessage = safeMessage(error);
            }
            store.save(saved);
            updateNotification(saved.lastMessage);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Never discards progress. A larger request extends the current run. A
     * same/smaller request resumes what exists. Starting over requires reset.
     */
    private RunState prepareRunState(
            StateStore store,
            RunState state,
            int requestedGauntlets,
            int podCount,
            String rosterKey
    ) {
        validateCheckpoint(state, podCount);

        boolean hasProgress = state.totalGames > 0L
                || state.completedGauntlets() > 0
                || state.nextPodIndex > 0;

        String savedRosterKey = state.rosterKey == null ? "" : state.rosterKey.trim();
        if (hasProgress && !savedRosterKey.isEmpty() && !savedRosterKey.equals(rosterKey)) {
            throw new IllegalStateException(
                    "Active tournament roster differs from the saved checkpoint. "
                            + "Restore the prior roster or explicitly reset the tournament before continuing."
            );
        }
        if (hasProgress && savedRosterKey.isEmpty()) {
            state.rosterKey = rosterKey;
            store.save(state);
        }

        if (!hasProgress || state.targetGauntlets <= 0) {
            File existingResults = ResultsWriter.resultsFile(this);
            if (existingResults.isFile() && existingResults.length() > 0L) {
                throw new IllegalStateException(
                        "Checkpoint is empty but a prior HOUSE results file still exists. "
                                + "Use the explicit Reset control before starting a new run; "
                                + "results were not deleted automatically."
                );
            }

            RunState fresh = freshRun(requestedGauntlets, rosterKey);
            store.clearPauseRequest();
            return fresh;
        }

        int completed = state.completedGauntlets();

        if ("COMPLETE".equals(state.status)) {
            if (requestedGauntlets > completed) {
                state.targetGauntlets = Math.max(state.targetGauntlets, requestedGauntlets);
                state.currentGauntlet = completed + 1;
                state.nextPodIndex = 0;
                state.status = "RUNNING";
                state.lastMessage = "Extending completed run from "
                        + completed
                        + " to "
                        + state.targetGauntlets
                        + " gauntlets";
            }
            return state;
        }

        state.targetGauntlets = Math.max(
                Math.max(1, state.targetGauntlets),
                requestedGauntlets
        );
        state.status = "RUNNING";
        state.lastMessage = "Resuming existing checkpoint";
        return state;
    }

    private static void validateCheckpoint(RunState state, int podCount) {
        if (state == null) {
            throw new IllegalStateException("Tournament checkpoint is null");
        }
        if (state.currentGauntlet < 1) {
            throw new IllegalStateException("Invalid currentGauntlet: " + state.currentGauntlet);
        }
        if (state.nextPodIndex < 0 || state.nextPodIndex > podCount) {
            throw new IllegalStateException(
                    "Invalid nextPodIndex "
                            + state.nextPodIndex
                            + " for schedule size "
                            + podCount
            );
        }
        if (state.totalGames < 0L) {
            throw new IllegalStateException("Invalid totalGames: " + state.totalGames);
        }
    }

    private static RunState freshRun(int target, String rosterKey) {
        RunState state = new RunState();
        state.status = "RUNNING";
        state.rosterKey = rosterKey == null ? "" : rosterKey;
        state.targetGauntlets = Math.max(1, target);
        state.currentGauntlet = 1;
        state.nextPodIndex = 0;
        state.totalGames = 0L;
        state.lastMessage = "Starting HOUSE Commander Lab";
        return state;
    }

    private List<DeckSpec> pilotPod(
            HousePackage pack,
            String pilotDeckName
    ) {
        if (pilotDeckName == null || pilotDeckName.trim().isEmpty()) {
            throw new IllegalArgumentException("Pilot deck was not selected");
        }

        DeckSpec pilot = null;
        String wanted = Names.canonical(pilotDeckName);
        for (DeckSpec deck : pack.decks()) {
            if (deck != null
                    && Names.canonical(deck.deck()).equals(wanted)) {
                pilot = deck;
                break;
            }
        }
        if (pilot == null) {
            throw new IllegalStateException(
                    "Pilot deck is not in the active HOUSE roster: "
                            + pilotDeckName
            );
        }

        List<DeckSpec> out = new ArrayList<>();
        out.add(pilot);
        for (DeckSpec deck : pack.decks()) {
            if (out.size() >= 4) {
                break;
            }
            if (deck == null
                    || Names.canonical(deck.deck()).equals(wanted)) {
                continue;
            }
            out.add(deck);
        }

        if (out.size() != 4) {
            throw new IllegalStateException(
                    "Pilot mode needs four distinct Commander decks; resolved "
                            + out.size()
            );
        }
        return out;
    }

    private List<DeckSpec> resolvePod(HousePackage pack, PodSpec pod) {
        if (pod == null) {
            throw new IllegalStateException("HOUSE schedule contains a null pod");
        }

        List<DeckSpec> out = new ArrayList<>();
        for (String name : pod.members()) {
            DeckSpec d = pack.deckNamed(name);
            if (d == null) {
                throw new IllegalStateException("Deck not resolved: " + name);
            }
            out.add(d);
        }

        if (out.size() < 2) {
            throw new IllegalStateException(
                    "Commander pod has fewer than two resolved decks: " + out.size()
            );
        }
        return out;
    }

    private String validateWinner(List<DeckSpec> decks, String winner) {
        return com.housecommander.core.WinnerIdentity.resolve(decks, winner);
    }

    private File logFile(String group, int sequence, PodSpec pod) throws IOException {
        File dir = new File(getFilesDir(), "logs/" + group);
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Could not create log directory: " + dir.getAbsolutePath());
        }
        return new File(
                dir,
                String.format(
                        Locale.US,
                        "%03d_r%02d_p%02d.log",
                        sequence,
                        pod.round(),
                        pod.pod()
                )
        );
    }

    private static String relativeLogPath(File log) {
        return "logs/" + log.getParentFile().getName() + "/" + log.getName();
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) {
            throw new IllegalStateException("PowerManager is unavailable");
        }

        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "HouseCommanderLab:Tournament"
            );
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void finishWorker(int startId) {
        synchronized (RUNNER_LOCK) {
            try {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                stopForeground(false);
                // Pause/stop commands can have newer start IDs than the worker.
                // This lease still owns the runner, so all of its commands are done.
                stopSelf();
            } finally {
                workerActive = false;
                if (activeWorker == this) activeWorker = null;
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "HOUSE tournament",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Progress for local Forge Commander simulations");
        nm.createNotificationChannel(channel);
    }

    private void startForegroundCompat(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("HOUSE Commander Lab")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(workerActive);
        if (workerActive && cancellation != null && !cancellation.isStopRequested()) {
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, TournamentService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(android.R.drawable.ic_media_pause, "Stop game", stop);
        }
        return builder.build();
    }

    private static void increment(Map<String, Integer> values, String key) {
        Integer current = values.get(key);
        values.put(key, current == null ? 1 : current + 1);
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        Throwable x = t;
        while (x.getCause() != null && x.getCause() != x) {
            x = x.getCause();
        }
        String message = x.getMessage();
        String safe = (message == null || message.trim().isEmpty())
                ? x.getClass().getSimpleName()
                : message;
        return safe.replace('\n', ' ').replace('\r', ' ').trim();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        synchronized (RUNNER_LOCK) {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            List<Runnable> notStarted = executor.shutdownNow();
            if (!notStarted.isEmpty()) {
                workerActive = false;
                if (activeWorker == this) activeWorker = null;
            }
        }
        // A worker that already started releases its lease in finishWorker().
        // Until then it may still be unwinding Forge; do not admit a second game.
        super.onDestroy();
    }
}
