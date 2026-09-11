package com.housecommander.lab.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.housecommander.core.DeckSpec;
import com.housecommander.core.HousePackage;
import com.housecommander.core.HousePackageLoader;
import com.housecommander.core.Names;
import com.housecommander.core.PodSpec;
import com.housecommander.lab.AndroidAssets;
import com.housecommander.lab.HouseInstall;
import com.housecommander.lab.MainActivity;
import com.housecommander.lab.engine.ForgeEngineAdapter;
import com.housecommander.lab.engine.GameOutcome;
import com.housecommander.lab.state.ResultsWriter;
import com.housecommander.lab.state.RunState;
import com.housecommander.lab.state.StateStore;

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
    public static final String EXTRA_GAUNTLETS = "gauntlets";

    private static final int NOTIFICATION_ID = 1901;
    private static final String CHANNEL_ID = "house_tournament";

    /* Bridge 0.7 watchdog policy. */
    private static final int HARD_TIMEOUT_SECONDS = 60 * 60;
    private static final int STALL_TIMEOUT_SECONDS = 3 * 60;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean workerActive = false;
    private PowerManager.WakeLock wakeLock;

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

        if (!ACTION_TEST.equals(action) && !ACTION_RUN.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForegroundCompat("Preparing HOUSE Commander Lab");

        if (workerActive) {
            updateNotification("HOUSE runner is already active");
            return START_NOT_STICKY;
        }

        workerActive = true;
        acquireWakeLock();

        final int workerStartId = startId;
        if (ACTION_TEST.equals(action)) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    runEngineTest(workerStartId);
                }
            });
        } else {
            final int gauntlets = Math.max(1, intent.getIntExtra(EXTRA_GAUNTLETS, 1));
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    runTournament(workerStartId, gauntlets);
                }
            });
        }

        return START_NOT_STICKY;
    }

    private void runEngineTest(int startId) {
        StateStore store = new StateStore(this);
        RunState state = store.load();

        try {
            state.status = "TESTING";
            state.lastMessage = "Running one literal Forge test game";
            store.save(state);

            HousePackage pack = HousePackageLoader.load(AndroidAssets.from(this), "house19");
            HouseInstall.ensureDeckFiles(this, pack);

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

            GameOutcome outcome = engine.runCommanderGame(
                    decks,
                    log,
                    HARD_TIMEOUT_SECONDS,
                    STALL_TIMEOUT_SECONDS
            );
            String winner = validateWinner(decks, outcome.winner());

            state = store.load();
            state.status = "TEST_COMPLETE";
            state.lastMessage = "Test winner: " + winner + " • " + outcome.engineVersion();
            store.save(state);
            updateNotification(state.lastMessage);
        } catch (Throwable t) {
            state = store.load();
            state.status = "BLOCKED";
            state.lastMessage = safeMessage(t);
            store.save(state);
            updateNotification("Blocked: " + state.lastMessage);
        } finally {
            finishWorker(startId);
        }
    }

    private void runTournament(int startId, int requestedGauntlets) {
        StateStore store = new StateStore(this);
        RunState state = store.load();

        try {
            HousePackage pack = HousePackageLoader.load(AndroidAssets.from(this), "house19");
            HouseInstall.ensureDeckFiles(this, pack);

            if (pack.schedule() == null || pack.schedule().isEmpty()) {
                throw new IllegalStateException("HOUSE schedule contains no pods");
            }
            final int podCount = pack.schedule().size();

            ForgeEngineAdapter engine = new ForgeEngineAdapter(this);
            if (!engine.isAvailable()) {
                throw new IllegalStateException(engine.status());
            }

            state = prepareRunState(store, state, requestedGauntlets, podCount);

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

                    state = store.load();
                    state.status = "RUNNING";
                    state.currentGauntlet = g;
                    state.nextPodIndex = i;
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

                    File log = logFile(
                            "g" + String.format(Locale.US, "%04d", g),
                            i + 1,
                            pod
                    );

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
                    state = store.load();
                    state.currentGauntlet = g;
                    for (DeckSpec d : decks) {
                        increment(state.games, d.deck());
                    }
                    increment(state.wins, winner);
                    state.totalGames++;
                    state.nextPodIndex = i + 1;
                    state.lastMessage = "Winner: "
                            + winner
                            + " • G"
                            + g
                            + " game "
                            + (i + 1)
                            + "/"
                            + podCount;
                    store.save(state);
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
            state = store.load();
            state.status = "BLOCKED";
            state.lastMessage = safeMessage(t);
            store.save(state);
            updateNotification("Blocked: " + state.lastMessage);
        } finally {
            finishWorker(startId);
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
            int podCount
    ) {
        validateCheckpoint(state, podCount);

        boolean hasProgress = state.totalGames > 0L
                || state.completedGauntlets() > 0
                || state.nextPodIndex > 0;

        if (!hasProgress || state.targetGauntlets <= 0) {
            File existingResults = ResultsWriter.resultsFile(this);
            if (existingResults.isFile() && existingResults.length() > 0L) {
                throw new IllegalStateException(
                        "Checkpoint is empty but a prior HOUSE results file still exists. "
                                + "Use the explicit Reset control before starting a new run; "
                                + "results were not deleted automatically."
                );
            }

            RunState fresh = freshRun(requestedGauntlets);
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

    private static RunState freshRun(int target) {
        RunState state = new RunState();
        state.status = "RUNNING";
        state.targetGauntlets = Math.max(1, target);
        state.currentGauntlet = 1;
        state.nextPodIndex = 0;
        state.totalGames = 0L;
        state.lastMessage = "Starting HOUSE Commander Lab";
        return state;
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
        if (winner == null || winner.trim().isEmpty()) {
            throw new IllegalStateException("Forge returned an empty winner");
        }

        String canonicalWinner = Names.canonical(winner);
        for (DeckSpec d : decks) {
            if (Names.canonical(d.deck()).equals(canonicalWinner)) {
                return d.deck();
            }

            String fileName = new File(d.dck()).getName();
            if (Names.canonical(fileName).equals(canonicalWinner)) {
                return d.deck();
            }

            int dot = fileName.lastIndexOf('.');
            String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
            if (Names.canonical(stem).equals(canonicalWinner)) {
                return d.deck();
            }
        }

        throw new IllegalStateException(
                "Winner returned by Forge is not a member of this pod: " + winner
        );
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
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        workerActive = false;
        stopForeground(false);
        stopSelf(startId);
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

        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("HOUSE Commander Lab")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(workerActive)
                .build();
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
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        executor.shutdownNow();
        workerActive = false;
        super.onDestroy();
    }
}
