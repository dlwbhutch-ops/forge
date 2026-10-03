package com.housecommander.lab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.housecommander.core.HousePackage;
import com.housecommander.forgebridge.ForgeBridge;
import com.housecommander.forgebridge.LiveGameState;
import com.housecommander.lab.engine.ForgeDatabaseBootstrap;
import com.housecommander.lab.engine.ForgeEngineAdapter;
import com.housecommander.lab.service.TournamentService;
import com.housecommander.lab.state.ResultsWriter;
import com.housecommander.lab.state.RunState;
import com.housecommander.lab.state.StateStore;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private static final int DEFAULT_POD_COUNT = 95;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView packageStatus;
    private TextView engineStatus;
    private TextView runStatus;
    private TextView details;
    private ProgressBar progress;
    private Button testButton;
    private Button runOneButton;
    private Button run500Button;
    private Button pauseButton;
    private Button resetButton;
    private TextView libraryStatus;
    private TextView libraryDetails;
    private Button importButton;
    private Button manageLibraryButton;
    private Button rosterButton;
    private Button defaultRosterButton;
    private Button watchButton;
    private Button playButton;
    private TextView watchStatus;
    private LinearLayout watchBoard;
    private TextView watchStack;
    private TextView watchDetails;
    private DeckLibraryController libraryController;

    private boolean preflightPass;
    private boolean engineAvailable;
    private String forgeStartupError;
    private int podCount = DEFAULT_POD_COUNT;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshEngineStatus();
            updateRunState();
            refreshWatchView();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }

        libraryController = new DeckLibraryController(this, new DeckLibraryController.Callback() {
            @Override
            public void onLibraryChanged() {
                runPreflight();
            }
        });

        setContentView(buildUi());
        runPreflight();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresh);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (libraryController != null
                && libraryController.handleActivityResult(requestCode, resultCode, data)) {
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(36));
        scroll.addView(root);

        TextView title = text("HOUSE Commander Lab", 28, true);
        root.addView(title);

        String installedVersion;
        try {
            installedVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            installedVersion = "unknown build";
        }
        TextView version = text(
                "Bridge 0.13 • Live Battlefield • Unified HOUSE Lab\n"
                        + installedVersion,
                14,
                false
        );
        version.setAlpha(0.75f);
        root.addView(version);

        String device = Build.MANUFACTURER
                + " "
                + Build.MODEL
                + " • Android "
                + Build.VERSION.RELEASE
                + " • API "
                + Build.VERSION.SDK_INT;
        TextView deviceView = text(device, 13, false);
        deviceView.setPadding(0, dp(8), 0, dp(20));
        root.addView(deviceView);

        TextView unified = text(
                "One app • one Forge engine • one deck library • one tournament/results store. "
                        + "Decks, Tournament, Watch, and Play all live here.",
                14,
                true
        );
        unified.setPadding(0, 0, 0, dp(8));
        root.addView(unified);

        root.addView(section("STRICT PREFLIGHT"));

        packageStatus = text("Checking HOUSE package…", 16, false);
        root.addView(packageStatus);

        engineStatus = text("Checking Forge bridge…", 16, false);
        engineStatus.setPadding(0, dp(6), 0, dp(12));
        root.addView(engineStatus);

        Button preflight = button("Run preflight again");
        preflight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runPreflight();
            }
        });
        root.addView(preflight);

        root.addView(section("DECK LIBRARY"));

        libraryStatus = text("Loading deck library…", 16, true);
        root.addView(libraryStatus);

        importButton = button("Import .dck / ManaBox .txt deck");
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                libraryController.openImporter();
            }
        });
        root.addView(importButton);

        manageLibraryButton = button("Manage Deck Library");
        manageLibraryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                libraryController.showLibraryManager();
            }
        });
        root.addView(manageLibraryButton);

        rosterButton = button("Select tournament roster");
        rosterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                libraryController.showRosterPicker();
            }
        });
        root.addView(rosterButton);

        defaultRosterButton = button("Restore bundled HOUSE 19");
        defaultRosterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                libraryController.confirmRestoreDefaultRoster();
            }
        });
        root.addView(defaultRosterButton);

        libraryDetails = text("", 13, false);
        libraryDetails.setPadding(0, dp(8), 0, 0);
        libraryDetails.setAlpha(0.82f);
        root.addView(libraryDetails);

        root.addView(section("TOURNAMENT"));

        testButton = button("Run 1 literal Forge test game");
        testButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startTest();
            }
        });
        root.addView(testButton);

        runOneButton = button("Run / resume 1 gauntlet");
        runOneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRun(1);
            }
        });
        root.addView(runOneButton);

        run500Button = button("Run / resume 500 gauntlets");
        run500Button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRun(500);
            }
        });
        root.addView(run500Button);

        pauseButton = button("Pause after current game");
        pauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pauseRun();
            }
        });
        root.addView(pauseButton);

        resetButton = button("Reset tournament checkpoint");
        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });
        root.addView(resetButton);

        root.addView(section("WATCH GAME"));

        watchButton = button("Run & watch 1 literal Forge game");
        watchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startTest();
            }
        });
        root.addView(watchButton);

        watchStatus = text("Spectator board ready", 16, true);
        watchStatus.setPadding(0, dp(8), 0, dp(6));
        root.addView(watchStatus);

        watchBoard = new LinearLayout(this);
        watchBoard.setOrientation(LinearLayout.VERTICAL);
        watchBoard.setPadding(0, dp(4), 0, dp(8));
        root.addView(watchBoard);

        watchStack = text("Stack empty", 12, false);
        watchStack.setTypeface(Typeface.MONOSPACE);
        watchStack.setTextIsSelectable(true);
        watchStack.setPadding(0, dp(6), 0, dp(8));
        root.addView(watchStack);

        TextView logLabel = text("Forge event log", 12, true);
        root.addView(logLabel);

        watchDetails = text(
                "Run & watch a literal Forge game. The battlefield, public zones, "
                        + "turn/phase, and stack will render above.",
                11,
                false
        );
        watchDetails.setTypeface(Typeface.MONOSPACE);
        watchDetails.setTextIsSelectable(true);
        root.addView(watchDetails);

        root.addView(section("PLAY VS AI"));

        playButton = button("Pilot a deck vs AI");
        playButton.setEnabled(false);
        root.addView(playButton);

        TextView playNote = text(
                "Human-seat decision controls are the next engine bridge milestone. "
                        + "They will unlock here inside this same HOUSE app.",
                13,
                false
        );
        playNote.setAlpha(0.8f);
        root.addView(playNote);

        root.addView(section("LIVE STATUS"));

        runStatus = text("Ready", 18, true);
        root.addView(runStatus);

        progress = new ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
        );
        progress.setMax(podCount);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(14)
        );
        pp.setMargins(0, dp(10), 0, dp(10));
        root.addView(progress, pp);

        details = text("", 14, false);
        root.addView(details);

        TextView strict = text(
                "Integrity rule: HOUSE never substitutes power scores, matchup weights, "
                        + "or guessed winners for Forge. A stalled game is diagnosed and "
                        + "the run stops at that checkpoint; completed games are preserved.",
                13,
                false
        );
        strict.setPadding(0, dp(24), 0, 0);
        strict.setAlpha(0.8f);
        root.addView(strict);

        return scroll;
    }

    private void runPreflight() {
        try {
            HousePackage pack = HouseRuntime.loadActivePackage(this);

            if (pack.schedule() == null || pack.schedule().isEmpty()) {
                throw new IllegalStateException("HOUSE schedule contains no pods");
            }

            podCount = pack.schedule().size();
            progress.setMax(podCount);
            packageStatus.setText(
                    "PASS • "
                            + pack.validation().summary()
                            + " • "
                            + podCount
                            + " pods/gauntlet"
            );
            preflightPass = true;
        } catch (Throwable t) {
            packageStatus.setText("BLOCKED • " + safeMessage(t));
            preflightPass = false;
        }

        forgeStartupError = null;
        refreshEngineStatus();
        if (preflightPass && !engineAvailable) {
            try {
                // Initialize the real rules database in private storage.
                // Progress and startup failures are polled by refreshEngineStatus().
                ForgeDatabaseBootstrap.ensureReady(this);
            } catch (Throwable t) {
                forgeStartupError = "Forge startup failed • " + safeMessage(t);
            }
            refreshEngineStatus();
        }
        refreshLibraryViews();
        updateRunState();
    }

    private void refreshLibraryViews() {
        if (libraryController == null || libraryStatus == null || libraryDetails == null) {
            return;
        }
        try {
            libraryStatus.setText(libraryController.summary());
            libraryDetails.setText(libraryController.details());
        } catch (Throwable t) {
            libraryStatus.setText("Deck Library blocked • " + safeMessage(t));
            libraryDetails.setText("");
        }
    }

    private void refreshEngineStatus() {
        ForgeEngineAdapter engine = new ForgeEngineAdapter(this);
        engineAvailable = engine.isAvailable();
        if (engineAvailable) {
            forgeStartupError = null;
        }
        engineStatus.setText(forgeStartupError == null ? engine.status() : forgeStartupError);
    }

    private void updateButtons(RunState state) {
        boolean running = state != null
                && ("RUNNING".equals(state.status) || "TESTING".equals(state.status));
        boolean enabled = preflightPass && engineAvailable && !running;

        testButton.setEnabled(enabled);
        runOneButton.setEnabled(enabled);
        run500Button.setEnabled(enabled);
        pauseButton.setEnabled(running);
        resetButton.setEnabled(!running);
        importButton.setEnabled(!running);
        manageLibraryButton.setEnabled(!running);
        rosterButton.setEnabled(!running);
        defaultRosterButton.setEnabled(!running);
        watchButton.setEnabled(enabled);
        playButton.setEnabled(false);

        long games500 = (long) podCount * 500L;

        if (!engineAvailable) {
            testButton.setText("Run 1 test game — Forge bridge pending");
            runOneButton.setText("Run 1 gauntlet — Forge bridge pending");
            run500Button.setText("Run 500 gauntlets — Forge bridge pending");
        } else {
            testButton.setText("Run 1 literal Forge test game");
            runOneButton.setText(
                    "Run / resume 1 gauntlet (" + podCount + " games)"
            );
            run500Button.setText(
                    "Run / resume 500 gauntlets (" + games500 + " games)"
            );
        }
    }

    private void startTest() {
        Intent intent = new Intent(this, TournamentService.class)
                .setAction(TournamentService.ACTION_TEST);
        startServiceCompat(intent, true);
    }

    private void startRun(int gauntlets) {
        Intent intent = new Intent(this, TournamentService.class)
                .setAction(TournamentService.ACTION_RUN)
                .putExtra(TournamentService.EXTRA_GAUNTLETS, gauntlets);
        startServiceCompat(intent, true);
    }

    private void pauseRun() {
        Intent intent = new Intent(this, TournamentService.class)
                .setAction(TournamentService.ACTION_PAUSE);
        startService(intent);
    }

    private void startServiceCompat(Intent intent, boolean foreground) {
        if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void updateRunState() {
        RunState state = new StateStore(this).load();

        runStatus.setText(state.status + " • " + state.lastMessage);
        progress.setMax(Math.max(1, podCount));
        progress.setProgress(Math.min(podCount, Math.max(0, state.nextPodIndex)));

        File results = ResultsWriter.resultsFile(this);

        String nextPodText;
        if (state.nextPodIndex >= podCount) {
            nextPodText = "gauntlet summary pending";
        } else if ("COMPLETE".equals(state.status)) {
            nextPodText = "complete";
        } else {
            nextPodText = (state.nextPodIndex + 1) + "/" + podCount;
        }

        details.setText(
                "Target gauntlets: "
                        + state.targetGauntlets
                        + "\nCompleted gauntlets: "
                        + state.completedGauntlets()
                        + "\nCurrent gauntlet: "
                        + state.currentGauntlet
                        + "\nNext pod: "
                        + nextPodText
                        + "\nLiteral games checkpointed: "
                        + state.totalGames
                        + "\nResults: "
                        + results.getAbsolutePath()
        );

        updateButtons(state);
    }

    private void refreshWatchView() {
        if (watchStatus == null || watchDetails == null || watchBoard == null) {
            return;
        }

        RunState run = new StateStore(this).load();
        LiveGameState live = ForgeBridge.liveGameState();

        if (live.sequence() > 1L) {
            String winner = live.winner().isEmpty() ? "" : " • winner " + live.winner();
            watchStatus.setText(
                    "Turn " + live.turn()
                            + " • " + live.phase()
                            + (live.activePlayer().isEmpty()
                            ? ""
                            : " • active " + live.activePlayer())
                            + " • " + live.lastEvent()
                            + winner
            );
        } else if ("TESTING".equals(run.status)) {
            watchStatus.setText("LIVE • Forge is starting the literal game");
        } else {
            watchStatus.setText("Spectator board ready");
        }

        watchBoard.removeAllViews();
        if (live.players().isEmpty()) {
            TextView waiting = text(
                    "Run & watch a literal Forge game. The four-player battlefield "
                            + "will appear here.",
                    13,
                    false
            );
            waiting.setPadding(0, dp(8), 0, dp(12));
            watchBoard.addView(waiting);
        } else {
            for (LiveGameState.PlayerState player : live.players()) {
                watchBoard.addView(buildPlayerBoard(player, live.activePlayer()));
            }
        }

        if (live.stack().isEmpty()) {
            watchStack.setText("STACK • empty");
        } else {
            StringBuilder stackText = new StringBuilder("STACK\n");
            int index = 1;
            for (String item : live.stack()) {
                stackText.append(index++).append(". ").append(item).append("\n");
            }
            watchStack.setText(stackText.toString());
        }

        File testDir = new File(getFilesDir(), "logs/test");
        File log = newestLog(testDir);
        if (log == null) {
            return;
        }
        try {
            int max = 16000;
            long length = log.length();
            long start = Math.max(0L, length - max);
            byte[] bytes = new byte[(int) (length - start)];
            try (RandomAccessFile input = new RandomAccessFile(log, "r")) {
                input.seek(start);
                input.readFully(bytes);
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (start > 0L) {
                text = "… earlier log omitted …\n" + text;
            }
            watchDetails.setText(text);
        } catch (Throwable ignored) {
            // Forge can be writing this file during the refresh; retry next tick.
        }
    }

    private View buildPlayerBoard(
            LiveGameState.PlayerState player,
            String activePlayer
    ) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(5), 0, dp(5));
        panel.setLayoutParams(params);

        boolean active = player.name().equals(activePlayer);
        String status = player.lost() ? " • OUT" : (active ? " • ACTIVE" : "");
        TextView header = text(
                player.name()
                        + status
                        + "   ♥ "
                        + player.life()
                        + "   ☠ "
                        + player.poison(),
                15,
                true
        );
        panel.addView(header);

        TextView counts = text(
                "Hand " + player.handCount()
                        + " • Library " + player.libraryCount()
                        + " • Battlefield " + player.battlefield().size(),
                12,
                false
        );
        counts.setAlpha(0.8f);
        panel.addView(counts);

        TextView battlefield = text(
                formatBattlefield(player.battlefield()),
                12,
                false
        );
        battlefield.setTypeface(Typeface.MONOSPACE);
        battlefield.setPadding(0, dp(5), 0, dp(5));
        panel.addView(battlefield);

        TextView zones = text(
                "Command: " + zoneSummary(player.command(), 4)
                        + "\nGraveyard (" + player.graveyard().size() + "): "
                        + zoneSummary(player.graveyard(), 5)
                        + "\nExile (" + player.exile().size() + "): "
                        + zoneSummary(player.exile(), 5),
                11,
                false
        );
        zones.setTypeface(Typeface.MONOSPACE);
        zones.setAlpha(0.85f);
        panel.addView(zones);

        return panel;
    }

    private static String formatBattlefield(List<LiveGameState.CardState> cards) {
        if (cards == null || cards.isEmpty()) {
            return "BATTLEFIELD • empty";
        }

        StringBuilder out = new StringBuilder("BATTLEFIELD\n");
        for (LiveGameState.CardState card : cards) {
            out.append(card.tapped() ? "↷ " : "• ")
                    .append(card.name());
            if (card.creature()) {
                out.append("  ").append(card.power()).append("/")
                        .append(card.toughness());
            }
            if (card.token()) {
                out.append(" [token]");
            }
            out.append("\n");
        }
        return out.toString();
    }

    private static String zoneSummary(List<String> cards, int limit) {
        if (cards == null || cards.isEmpty()) {
            return "—";
        }
        StringBuilder out = new StringBuilder();
        int start = Math.max(0, cards.size() - Math.max(1, limit));
        for (int i = start; i < cards.size(); i++) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(cards.get(i));
        }
        if (start > 0) {
            out.insert(0, "… ");
        }
        return out.toString();
    }

    private static File newestLog(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return null;
        }
        File newest = null;
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".log")) {
                continue;
            }
            if (newest == null || file.lastModified() > newest.lastModified()) {
                newest = file;
            }
        }
        return newest;
    }

    private void confirmReset() {
        final RunState state = new StateStore(this).load();
        boolean running = "RUNNING".equals(state.status) || "TESTING".equals(state.status);
        if (running) {
            runStatus.setText("BLOCKED • Pause the current run before resetting");
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Reset HOUSE tournament?")
                .setMessage(
                        "This erases the tournament checkpoint, consolidated results, "
                                + "and literal game logs. It cannot be undone."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        resetTournamentData();
                    }
                })
                .show();
    }

    private void resetTournamentData() {
        try {
            new StateStore(this).clear();
            ResultsWriter.reset(this);
            deleteRecursively(new File(getFilesDir(), "logs"));
            runStatus.setText("IDLE • Tournament checkpoint and run artifacts reset");
        } catch (Throwable t) {
            runStatus.setText("RESET FAILED • " + safeMessage(t));
        }
        updateRunState();
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("Could not list directory: " + file.getAbsolutePath());
            }
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            throw new IOException("Could not delete: " + file.getAbsolutePath());
        }
    }

    private TextView section(String label) {
        TextView t = text(label, 13, true);
        t.setAllCaps(true);
        t.setPadding(0, dp(26), 0, dp(8));
        return t;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        p.setMargins(0, dp(5), 0, dp(5));
        b.setLayoutParams(p);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        String safe = message == null || message.trim().isEmpty()
                ? x.getClass().getSimpleName()
                : message;
        return safe.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
