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
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TournamentService extends Service {
    public static final String ACTION_RUN = "com.housecommander.lab.RUN";
    public static final String ACTION_TEST = "com.housecommander.lab.TEST";
    public static final String ACTION_PAUSE = "com.housecommander.lab.PAUSE";
    public static final String EXTRA_GAUNTLETS = "gauntlets";

    private static final int NOTIFICATION_ID = 1901;
    private static final String CHANNEL_ID = "house_tournament";
    private static final int CLOCK_SECONDS = 600;

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
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            new StateStore(this).requestPause();
            updateNotification("Pause requested — finishing current game");
            if (!workerActive) stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForegroundCompat("Preparing HOUSE-19");
        if (workerActive) {
            updateNotification("HOUSE runner is already active");
            return START_NOT_STICKY;
        }
        workerActive = true;
        acquireWakeLock();

        if (ACTION_TEST.equals(action)) {
            final int workerStartId = startId;
            executor.execute(new Runnable() { @Override public void run() { runEngineTest(workerStartId); } });
        } else if (ACTION_RUN.equals(action)) {
            final int workerStartId = startId;
            final int gauntlets = Math.max(1, intent.getIntExtra(EXTRA_GAUNTLETS, 1));
            executor.execute(new Runnable() { @Override public void run() { runTournament(workerStartId, gauntlets); } });
        } else {
            finishWorker(startId);
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
            ForgeEngineAdapter engine = new ForgeEngineAdapter(this);
            if (!engine.isAvailable()) throw new IllegalStateException(engine.status());
            PodSpec pod = pack.schedule().get(0);
            List<DeckSpec> decks = resolvePod(pack, pod);
            File log = logFile("test", 1, pod);
            GameOutcome outcome = engine.runCommanderGame(decks, log, CLOCK_SECONDS);
            validateWinner(decks, outcome.winner());
            state.status = "TEST_COMPLETE";
            state.lastMessage = "Test winner: " + outcome.winner() + " • " + outcome.engineVersion();
            store.save(state);
            updateNotification(state.lastMessage);
        } catch (Throwable t) {
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
            ForgeEngineAdapter engine = new ForgeEngineAdapter(this);
            if (!engine.isAvailable()) throw new IllegalStateException(engine.status());

            if (state.status.equals("COMPLETE") || state.targetGauntlets == 0 || state.currentGauntlet > requestedGauntlets) {
                state = freshRun(requestedGauntlets);
            } else {
                state.targetGauntlets = Math.max(state.targetGauntlets, requestedGauntlets);
                state.pauseRequested = false;
                state.status = "RUNNING";
            }
            store.save(state);

            for (int g = state.currentGauntlet; g <= state.targetGauntlets; g++) {
                state.currentGauntlet = g;
                for (int i = state.nextPodIndex; i < pack.schedule().size(); i++) {
                    state = store.load();
                    if (state.pauseRequested) {
                        state.status = "PAUSED";
                        state.pauseRequested = false;
                        state.lastMessage = "Paused at gauntlet " + g + ", next pod " + (i + 1) + "/95";
                        store.save(state);
                        updateNotification(state.lastMessage);
                        return;
                    }

                    PodSpec pod = pack.schedule().get(i);
                    List<DeckSpec> decks = resolvePod(pack, pod);
                    state.status = "RUNNING";
                    state.lastMessage = "G" + g + "/" + state.targetGauntlets + " • R" + pod.round() + " P" + pod.pod();
                    store.save(state);
                    updateNotification(state.lastMessage);

                    File log = logFile("g" + String.format("%04d", g), i + 1, pod);
                    GameOutcome outcome = engine.runCommanderGame(decks, log, CLOCK_SECONDS);
                    String winner = validateWinner(decks, outcome.winner());
                    ensureLogMarker(log, winner, outcome.engineVersion());

                    state = store.load();
                    for (DeckSpec d : decks) increment(state.games, d.deck());
                    increment(state.wins, winner);
                    state.totalGames++;
                    state.nextPodIndex = i + 1;
                    state.lastMessage = "Winner: " + winner + " • G" + g + " pod " + (i + 1) + "/95";
                    store.save(state); // checkpoint after every literal game
                }

                state = store.load();
                ResultsWriter.appendGauntlet(this, pack, g, state.wins, state.games);
                state.wins.clear();
                state.games.clear();
                state.currentGauntlet = g + 1;
                state.nextPodIndex = 0;
                state.lastMessage = "Completed gauntlet " + g + "/" + state.targetGauntlets;
                store.save(state);
            }

            state = store.load();
            state.status = "COMPLETE";
            state.lastMessage = "Completed " + state.targetGauntlets + " gauntlets • " + state.totalGames + " literal games";
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

    private static RunState freshRun(int target) {
        RunState state = new RunState();
        state.status = "RUNNING";
        state.targetGauntlets = target;
        state.currentGauntlet = 1;
        state.nextPodIndex = 0;
        state.totalGames = 0;
        state.lastMessage = "Starting HOUSE-19";
        return state;
    }

    private List<DeckSpec> resolvePod(HousePackage pack, PodSpec pod) {
        List<DeckSpec> out = new ArrayList<>();
        for (String name : pod.members()) {
            DeckSpec d = pack.deckNamed(name);
            if (d == null) throw new IllegalStateException("Deck not resolved: " + name);
            out.add(d);
        }
        return out;
    }

    private String validateWinner(List<DeckSpec> decks, String winner) {
        String canonicalWinner = Names.canonical(winner);
        for (DeckSpec d : decks) {
            if (Names.canonical(d.deck()).equals(canonicalWinner)) return d.deck();
            String fileName = new File(d.dck()).getName();
            if (Names.canonical(fileName).equals(canonicalWinner)) return d.deck();
            int dot = fileName.lastIndexOf('.');
            String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
            if (Names.canonical(stem).equals(canonicalWinner)) return d.deck();
        }
        throw new IllegalStateException("Winner returned by Forge is not a member of this pod: " + winner);
    }

    private File logFile(String group, int sequence, PodSpec pod) throws IOException {
        File dir = new File(getFilesDir(), "logs/" + group);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create log directory");
        return new File(dir, String.format("%03d_r%02d_p%02d.log", sequence, pod.round(), pod.pod()));
    }

    private void ensureLogMarker(File log, String winner, String version) throws IOException {
        try (FileWriter w = new FileWriter(log, true)) {
            w.write("\nHOUSE_WINNER=" + winner + "\nHOUSE_ENGINE=" + version + "\n");
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HouseCommanderLab:Tournament");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void finishWorker(int startId) {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        workerActive = false;
        stopForeground(false);
        stopSelf(startId);
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "HOUSE tournament", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progress for local Forge Commander simulations");
        nm.createNotificationChannel(channel);
    }

    private void startForegroundCompat(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
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
        Throwable x = t;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        String m = x.getMessage();
        return (m == null || m.trim().isEmpty()) ? x.getClass().getSimpleName() : m;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        executor.shutdownNow();
        super.onDestroy();
    }
}
