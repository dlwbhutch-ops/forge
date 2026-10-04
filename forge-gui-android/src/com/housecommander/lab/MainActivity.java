package com.housecommander.lab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.housecommander.core.DeckSpec;
import com.housecommander.core.HousePackage;
import com.housecommander.forgebridge.LiveGameState;
import com.housecommander.forgebridge.PilotDecision;
import com.housecommander.forgebridge.PilotDecisionBridge;
import com.housecommander.forgebridge.SpectatorCardGroup;
import com.housecommander.forgebridge.SpectatorPlayback;
import com.housecommander.forgebridge.SpectatorTransition;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private FrameLayout watchStage;
    private LinearLayout watchBoard;
    private TargetOverlayView targetOverlay;
    private TextView watchStack;
    private TextView watchEvents;
    private TextView watchDetails;
    private final List<String> recentActionFeed = new ArrayList<String>();
    private final Set<String> currentStackTargets = new HashSet<String>();
    private final Set<String> currentStackSources = new HashSet<String>();
    private final Map<String, String> currentTargetSources =
            new HashMap<String, String>();
    private final Map<String, String> currentSourceTargets =
            new HashMap<String, String>();
    private List<LiveGameState.StackState> currentStackStates =
            new ArrayList<LiveGameState.StackState>();
    private List<LiveGameState.CombatLinkState> currentCombatLinks =
            new ArrayList<LiveGameState.CombatLinkState>();
    private LiveGameState transitionCursor = LiveGameState.idle();
    private DeckLibraryController libraryController;
    private AndroidCardArtCache cardArtCache;
    private final Set<String> seenVisualPiles = new HashSet<String>();
    private long shownPilotDecisionId = -1L;
    private boolean pilotDialogOpen;

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
            refreshPilotDecision();
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

        cardArtCache = new AndroidCardArtCache(this);

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
        if (cardArtCache != null) {
            cardArtCache.shutdown();
        }
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
                "Bridge 0.17 • Unified Distribution • Play + Watch + Decks + Tournaments\n"
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

        defaultRosterButton = button("Restore default HOUSE roster");
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

        HorizontalScrollView playbackScroll = new HorizontalScrollView(this);
        playbackScroll.setHorizontalScrollBarEnabled(true);
        LinearLayout playbackRow = new LinearLayout(this);
        playbackRow.setOrientation(LinearLayout.HORIZONTAL);
        playbackRow.setPadding(0, 0, 0, dp(6));
        playbackScroll.addView(playbackRow);

        playbackRow.addView(playbackButton("Pause view", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpectatorPlayback.pause();
                refreshWatchView();
            }
        }));
        playbackRow.addView(playbackButton("1x", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpectatorPlayback.setSpeed(SpectatorPlayback.Speed.X1);
                refreshWatchView();
            }
        }));
        playbackRow.addView(playbackButton("2x", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpectatorPlayback.setSpeed(SpectatorPlayback.Speed.X2);
                refreshWatchView();
            }
        }));
        playbackRow.addView(playbackButton("4x", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpectatorPlayback.setSpeed(SpectatorPlayback.Speed.X4);
                refreshWatchView();
            }
        }));
        playbackRow.addView(playbackButton("8x", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpectatorPlayback.setSpeed(SpectatorPlayback.Speed.X8);
                refreshWatchView();
            }
        }));
        playbackRow.addView(playbackButton("Max / live", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpectatorPlayback.goLive();
                refreshWatchView();
            }
        }));
        playbackRow.addView(playbackButton("Step action", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpectatorPlayback.nextAction();
                refreshWatchView();
            }
        }));
        playbackRow.addView(playbackButton("Step phase", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpectatorPlayback.nextPhase();
                refreshWatchView();
            }
        }));
        playbackRow.addView(playbackButton("Step turn", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpectatorPlayback.nextTurn();
                refreshWatchView();
            }
        }));
        root.addView(playbackScroll);

        watchStage = new FrameLayout(this);
        watchBoard = new LinearLayout(this);
        watchBoard.setOrientation(LinearLayout.VERTICAL);
        watchBoard.setPadding(0, dp(4), 0, dp(8));
        watchStage.addView(
                watchBoard,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                )
        );
        targetOverlay = new TargetOverlayView();
        targetOverlay.setClickable(false);
        targetOverlay.setFocusable(false);
        watchStage.addView(
                targetOverlay,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );
        root.addView(watchStage);

        watchStack = text("Stack empty", 12, false);
        watchStack.setTypeface(Typeface.MONOSPACE);
        watchStack.setTextIsSelectable(true);
        watchStack.setPadding(0, dp(6), 0, dp(8));
        root.addView(watchStack);

        TextView actionLabel = text("Action feed", 12, true);
        root.addView(actionLabel);

        watchEvents = text("No visual transitions yet.", 11, false);
        watchEvents.setTypeface(Typeface.MONOSPACE);
        watchEvents.setTextIsSelectable(true);
        watchEvents.setPadding(0, dp(4), 0, dp(8));
        root.addView(watchEvents);

        TextView logLabel = text("Detailed Forge log", 12, true);
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

        playButton = button("Start pilot game vs 3 AI");
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choosePilotDeck();
            }
        });
        root.addView(playButton);

        TextView playNote = text(
                "0.15 Assisted Pilot: choose the legal card/ability to cast, play, "
                        + "or activate—or pass priority. HOUSE also asks your yes/no "
                        + "decisions. Forge handles detailed mana and target plumbing "
                        + "while the live battlefield updates above.",
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
                && ("RUNNING".equals(state.status)
                || "TESTING".equals(state.status)
                || "PILOTING".equals(state.status));
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
        playButton.setEnabled(enabled);

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

    private void choosePilotDeck() {
        try {
            final HousePackage pack = HouseRuntime.loadActivePackage(this);
            final List<DeckSpec> decks = new ArrayList<DeckSpec>(pack.decks());
            if (decks.isEmpty()) {
                throw new IllegalStateException("Active HOUSE roster is empty");
            }

            String[] labels = new String[decks.size()];
            for (int i = 0; i < decks.size(); i++) {
                DeckSpec deck = decks.get(i);
                labels[i] = deck.deck()
                        + (deck.commanders() == null
                        || deck.commanders().trim().isEmpty()
                        ? ""
                        : "\n" + deck.commanders());
            }

            new AlertDialog.Builder(this)
                    .setTitle("Choose your Commander deck")
                    .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(
                                android.content.DialogInterface dialog,
                                int which
                        ) {
                            if (which >= 0 && which < decks.size()) {
                                startPilot(decks.get(which).deck());
                            }
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Throwable t) {
            runStatus.setText("PILOT BLOCKED • " + safeMessage(t));
        }
    }

    private void startPilot(String deckName) {
        shownPilotDecisionId = -1L;
        Intent intent = new Intent(this, TournamentService.class)
                .setAction(TournamentService.ACTION_PILOT)
                .putExtra(TournamentService.EXTRA_PILOT_DECK, deckName);
        startServiceCompat(intent, true);
    }

    private void refreshPilotDecision() {
        if (pilotDialogOpen) {
            return;
        }

        final PilotDecision decision = PilotDecisionBridge.current();
        if (!decision.pending()
                || decision.id() == shownPilotDecisionId
                || isFinishing()) {
            return;
        }

        pilotDialogOpen = true;
        shownPilotDecisionId = decision.id();

        final String[] options = decision.options().toArray(new String[0]);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("HOUSE Pilot • " + decision.player())
                .setMessage(decision.prompt())
                .setItems(
                        options,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(
                                    android.content.DialogInterface d,
                                    int which
                            ) {
                                PilotDecisionBridge.submit(
                                        decision.id(),
                                        which
                                );
                                pilotDialogOpen = false;
                            }
                        }
                )
                .create();

        dialog.setCancelable(false);
        dialog.setOnDismissListener(
                new android.content.DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(
                            android.content.DialogInterface d
                    ) {
                        pilotDialogOpen = false;
                        PilotDecision stillPending =
                                PilotDecisionBridge.current();
                        if (stillPending.pending()
                                && stillPending.id() == decision.id()) {
                            shownPilotDecisionId = -1L;
                        }
                    }
                }
        );
        dialog.show();
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
        LiveGameState live = SpectatorPlayback.visibleState();

        updateSpectatorTransitions(live);
        currentStackStates = live.stackStates();
        currentCombatLinks = live.combatLinks();
        currentStackTargets.clear();
        currentStackSources.clear();
        currentTargetSources.clear();
        currentSourceTargets.clear();
        for (LiveGameState.StackState stackItem : live.stackStates()) {
            String source = stackItem.source().isEmpty()
                    ? stackItem.description()
                    : stackItem.source();
            if (!source.isEmpty()) {
                currentStackSources.add(source);
            }
            for (String target : stackItem.targets()) {
                currentStackTargets.add(target);
                mergeLink(currentTargetSources, target, source);
                mergeLink(currentSourceTargets, source, target);
            }
        }

        if (live.sequence() <= 1L) {
            seenVisualPiles.clear();
        }

        if (live.sequence() > 1L) {
            String winner = live.winner().isEmpty() ? "" : " • winner " + live.winner();
            watchStatus.setText(
                    "Turn " + live.turn()
                            + " • " + live.phase()
                            + (live.activePlayer().isEmpty()
                            ? ""
                            : " • active " + live.activePlayer())
                            + " • " + live.lastEvent()
                            + " • viewer " + SpectatorPlayback.speed().label()
                            + (SpectatorPlayback.framesBehind() > 0
                            ? " • " + SpectatorPlayback.framesBehind() + " frames behind"
                            : " • live")
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
        if (targetOverlay != null) {
            targetOverlay.invalidate();
        }

        if (live.stackStates().isEmpty()) {
            watchStack.setText("STACK • empty");
        } else {
            StringBuilder stackText = new StringBuilder("STACK + TARGETS\n");
            int index = 1;
            for (LiveGameState.StackState item : live.stackStates()) {
                stackText.append(index++).append(". ");
                if (!item.activatingPlayer().isEmpty()) {
                    stackText.append(item.activatingPlayer()).append(" • ");
                }
                if (!item.source().isEmpty()) {
                    stackText.append(item.source());
                } else {
                    stackText.append(item.description());
                }
                if (!item.targets().isEmpty()) {
                    stackText.append("  →  ").append(join(item.targets()));
                }
                stackText.append("\n");
            }
            watchStack.setText(stackText.toString());
        }

        StringBuilder eventText = new StringBuilder();
        for (String event : recentActionFeed) {
            eventText.append(event).append("\n");
        }
        watchEvents.setText(
                eventText.length() == 0
                        ? "No visual transitions yet."
                        : eventText.toString()
        );

        File log;
        if ("PILOTING".equals(run.status)
                || "PILOT_COMPLETE".equals(run.status)) {
            log = new File(
                    getFilesDir(),
                    "logs/pilot/pilot-game.log"
            );
        } else {
            File testDir = new File(getFilesDir(), "logs/test");
            log = newestLog(testDir);
        }
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
        panel.setBackgroundResource(android.R.drawable.editbox_background);
        panel.setTag("house-player:" + player.name());

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

        List<SpectatorCardGroup> groups =
                SpectatorCardGroup.group(player.battlefield());

        TextView counts = text(
                "Hand " + player.handCount()
                        + " • Library " + player.libraryCount()
                        + " • Battlefield " + player.battlefield().size()
                        + " • Piles " + groups.size(),
                12,
                false
        );
        counts.setAlpha(0.8f);
        panel.addView(counts);

        panel.addView(buildCombatRow(groups));
        panel.addView(buildPermanentRow("CREATURES", groups, 0));
        panel.addView(buildPermanentRow("LANDS", groups, 1));
        panel.addView(buildPermanentRow("OTHER", groups, 2));

        TextView zones = text(
                "Commander: " + zoneSummary(player.commanders(), 3)
                        + "\nCommander damage: " + commanderDamageSummary(player.commanderDamage())
                        + "\nCommand zone: " + zoneSummary(player.command(), 4)
                        + "\nGraveyard (" + player.graveyard().size() + "): "
                        + zoneSummary(player.graveyard(), 5)
                        + "\nExile (" + player.exile().size() + "): "
                        + zoneSummary(player.exile(), 5),
                11,
                false
        );
        zones.setTypeface(Typeface.MONOSPACE);
        zones.setAlpha(0.85f);
        zones.setPadding(0, dp(6), 0, 0);
        panel.addView(zones);

        return panel;
    }

    private View buildCombatRow(List<SpectatorCardGroup> groups) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(7), 0, dp(2));

        TextView label = text("COMBAT", 11, true);
        label.setAlpha(0.75f);
        section.addView(label);
        section.addView(buildCombatLane("Attackers", groups, true));
        section.addView(buildCombatLane("Blockers", groups, false));
        return section;
    }

    private View buildCombatLane(
            String title,
            List<SpectatorCardGroup> groups,
            boolean attackers
    ) {
        LinearLayout lane = new LinearLayout(this);
        lane.setOrientation(LinearLayout.VERTICAL);

        TextView label = text(title, 10, true);
        label.setAlpha(0.7f);
        lane.addView(label);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));
        scroll.addView(row);

        int added = 0;
        for (SpectatorCardGroup group : groups) {
            LiveGameState.CardState card = group.card();
            if ((attackers && card.attacking())
                    || (!attackers && card.blocking())) {
                row.addView(buildCardTile(group));
                added++;
            }
        }
        if (added == 0) {
            TextView empty = text("—", 12, false);
            empty.setPadding(dp(8), dp(6), dp(8), dp(6));
            row.addView(empty);
        }
        lane.addView(scroll);
        return lane;
    }

    private View buildPermanentRow(
            String title,
            List<SpectatorCardGroup> groups,
            int category
    ) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(7), 0, dp(2));

        TextView label = text(title, 11, true);
        label.setAlpha(0.75f);
        section.addView(label);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(true);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));
        scroll.addView(row);

        int added = 0;
        for (SpectatorCardGroup group : groups) {
            if (!matchesCategory(group.card(), category)) {
                continue;
            }
            if (category == 0
                    && (group.card().attacking() || group.card().blocking())) {
                continue;
            }
            row.addView(buildCardTile(group));
            added++;
        }

        if (added == 0) {
            TextView empty = text("—", 12, false);
            empty.setPadding(dp(8), dp(10), dp(8), dp(10));
            row.addView(empty);
        }

        section.addView(scroll);
        return section;
    }

    private View buildCardTile(SpectatorCardGroup group) {
        LiveGameState.CardState card = group.card();

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(4), dp(4), dp(4), dp(4));
        tile.setBackgroundResource(android.R.drawable.editbox_background);

        int tileWidth = card.tapped() ? dp(158) : dp(116);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                tileWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, dp(6), 0);
        tile.setLayoutParams(params);
        tile.setTag("house-card:" + card.name());

        if (group.count() > 1) {
            TextView badge = text("×" + group.count(), 11, true);
            badge.setGravity(Gravity.CENTER);
            tile.addView(badge);
        }

        ImageView face = new ImageView(this);
        face.setAdjustViewBounds(true);
        face.setScaleType(ImageView.ScaleType.FIT_CENTER);
        face.setContentDescription(card.name());

        Bitmap art = cardArtCache.cardBitmap(
                card,
                card.tapped() ? dp(146) : dp(104),
                card.tapped() ? dp(100) : dp(146),
                this::refreshWatchView
        );
        if (art != null) {
            face.setImageBitmap(art);
            tile.addView(
                    face,
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            card.tapped() ? dp(104) : dp(150)
                    )
            );
        } else {
            TextView placeholder = text(
                    card.name()
                            + "\n"
                            + (card.imageUrl().isEmpty()
                            ? "art unavailable"
                            : "loading art…"),
                    11,
                    true
            );
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setMinHeight(card.tapped() ? dp(90) : dp(134));
            tile.addView(placeholder);
        }

        String details = cardDetail(card);
        boolean targeted = currentStackTargets.contains(card.name());
        boolean stackSource = currentStackSources.contains(card.name());
        if (stackSource) {
            String targets = currentSourceTargets.get(card.name());
            details = details
                    + " • STACK"
                    + (targets == null || targets.isEmpty() ? "" : " → " + targets);
        }
        if (targeted) {
            String sources = currentTargetSources.get(card.name());
            details = details
                    + " • TARGET"
                    + (sources == null || sources.isEmpty() ? "" : " ← " + sources);
            tile.setScaleX(1.04f);
            tile.setScaleY(1.04f);
        }
        TextView state = text(details, 10, false);
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, dp(3), 0, 0);
        tile.addView(state);

        tile.setContentDescription(card.name() + " • " + details);
        tile.setOnClickListener(v -> showCardZoom(card));

        String visualKey = card.imageKey()
                + "|"
                + card.name()
                + "|"
                + card.tapped()
                + "|"
                + details
                + "|"
                + group.count();
        if (seenVisualPiles.add(visualKey)) {
            tile.setAlpha(0f);
            if (card.attacking()) {
                tile.setTranslationY(-dp(18));
            } else if (card.blocking()) {
                tile.setTranslationX(dp(18));
            } else {
                tile.setTranslationY(dp(12));
            }
            tile.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(card.attacking() || card.blocking() ? 240L : 180L)
                    .start();
        }
        return tile;
    }

    private final class TargetOverlayView extends View {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint combatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint floatingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<FlightVisual> flights = new ArrayList<FlightVisual>();
        private final List<FloatingVisual> floatingVisuals =
                new ArrayList<FloatingVisual>();

        TargetOverlayView() {
            super(MainActivity.this);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(dp(2));
            combatPaint.setStyle(Paint.Style.STROKE);
            combatPaint.setStrokeWidth(dp(2));
            flightPaint.setStyle(Paint.Style.STROKE);
            flightPaint.setStrokeWidth(dp(2));
            flightPaint.setTextSize(dp(12));
            floatingPaint.setStyle(Paint.Style.FILL);
            floatingPaint.setTextSize(dp(14));
            floatingPaint.setTypeface(
                    Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            );
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (((currentStackStates == null || currentStackStates.isEmpty())
                    && (currentCombatLinks == null || currentCombatLinks.isEmpty())
                    && flights.isEmpty()
                    && floatingVisuals.isEmpty())
                    || watchBoard == null) {
                return;
            }

            int visualColor = watchStatus == null
                    ? 0xFF777777
                    : watchStatus.getCurrentTextColor();
            linePaint.setColor(visualColor);
            combatPaint.setColor(visualColor);
            combatPaint.setPathEffect(
                    new android.graphics.DashPathEffect(
                            new float[]{dp(8), dp(6)},
                            0f
                    )
            );
            flightPaint.setColor(visualColor);
            floatingPaint.setColor(visualColor);

            if (currentStackStates != null) {
                for (LiveGameState.StackState stackItem : currentStackStates) {
                View source = findTaggedView(
                        watchBoard,
                        "house-card:" + stackItem.source()
                );
                if (source == null) {
                    source = findTaggedView(
                            watchBoard,
                            "house-player:" + stackItem.activatingPlayer()
                    );
                }
                if (source == null) {
                    continue;
                }

                for (String targetName : stackItem.targets()) {
                    View target = findTaggedView(
                            watchBoard,
                            "house-card:" + targetName
                    );
                    if (target == null) {
                        target = findTaggedView(
                                watchBoard,
                                "house-player:" + targetName
                        );
                    }
                    if (target == null || target == source) {
                        continue;
                    }

                    float[] from = centerInOverlay(source);
                    float[] to = centerInOverlay(target);
                        drawArrow(
                                canvas,
                                from[0],
                                from[1],
                                to[0],
                                to[1],
                                linePaint
                        );
                    }
                }
            }

            drawCombatLinks(canvas);
            drawFlights(canvas);
            drawFloatingVisuals(canvas);
        }

        private void drawCombatLinks(Canvas canvas) {
            if (currentCombatLinks == null || currentCombatLinks.isEmpty()) {
                return;
            }

            for (LiveGameState.CombatLinkState combatLink : currentCombatLinks) {
                View attacker = findTaggedView(
                        watchBoard,
                        "house-card:" + combatLink.attacker()
                );
                if (attacker == null) {
                    continue;
                }
                float[] from = centerInOverlay(attacker);

                if (!combatLink.blockers().isEmpty()) {
                    for (String blockerName : combatLink.blockers()) {
                        View blocker = findTaggedView(
                                watchBoard,
                                "house-card:" + blockerName
                        );
                        if (blocker == null || blocker == attacker) {
                            continue;
                        }
                        float[] to = centerInOverlay(blocker);
                        drawArrow(
                                canvas,
                                from[0],
                                from[1],
                                to[0],
                                to[1],
                                combatPaint
                        );
                    }
                } else if (!combatLink.defender().isEmpty()) {
                    View defender = findTaggedView(
                            watchBoard,
                            "house-player:" + combatLink.defender()
                    );
                    if (defender != null && defender != attacker) {
                        float[] to = centerInOverlay(defender);
                        to[1] -= Math.max(0f, defender.getHeight() / 2f - dp(18));
                        drawArrow(
                                canvas,
                                from[0],
                                from[1],
                                to[0],
                                to[1],
                                combatPaint
                        );
                    }
                }
            }
        }

        private void queueZoneFlight(SpectatorTransition.Transition transition) {
            String zone = transitionZone(transition.kind());
            if (zone.isEmpty()
                    || transition.subject().isEmpty()
                    || watchBoard == null) {
                return;
            }
            View source = findTaggedView(
                    watchBoard,
                    "house-card:" + transition.subject()
            );
            if (source == null) {
                return;
            }
            float[] start = centerInOverlay(source);
            flights.add(
                    new FlightVisual(
                            start[0],
                            start[1],
                            transition.player(),
                            transition.subject(),
                            zone,
                            System.currentTimeMillis()
                    )
            );
            postInvalidateOnAnimation();
        }

        private void clearFlights() {
            flights.clear();
            floatingVisuals.clear();
            invalidate();
        }

        private void drawFlights(Canvas canvas) {
            if (flights.isEmpty()) {
                return;
            }

            long now = System.currentTimeMillis();
            Iterator<FlightVisual> iterator = flights.iterator();
            while (iterator.hasNext()) {
                FlightVisual flight = iterator.next();
                long age = now - flight.startedAt;
                if (age > 850L) {
                    iterator.remove();
                    continue;
                }

                View player = findTaggedView(
                        watchBoard,
                        "house-player:" + flight.player
                );
                if (player == null) {
                    continue;
                }

                float[] destination = centerInOverlay(player);
                destination[1] += Math.max(0f, player.getHeight() / 2f - dp(24));
                float progress = Math.min(1f, age / 700f);
                float x = flight.startX
                        + (destination[0] - flight.startX) * progress;
                float y = flight.startY
                        + (destination[1] - flight.startY) * progress;

                canvas.drawLine(
                        flight.startX,
                        flight.startY,
                        x,
                        y,
                        flightPaint
                );
                canvas.drawRect(
                        x - dp(20),
                        y - dp(12),
                        x + dp(20),
                        y + dp(12),
                        flightPaint
                );
                canvas.drawText(
                        flight.card + " → " + flight.zone,
                        x + dp(24),
                        y + dp(4),
                        flightPaint
                );
            }

            if (!flights.isEmpty()) {
                postInvalidateOnAnimation();
            }
        }

        private void queueFloatingVisual(
                SpectatorTransition.Transition transition
        ) {
            if (transition == null || transition.player().isEmpty()) {
                return;
            }

            String label = "";
            if (transition.kind() == SpectatorTransition.Kind.LIFE) {
                label = "♥ " + transition.detail();
            } else if (transition.kind() == SpectatorTransition.Kind.POISON) {
                label = "☠ " + transition.detail();
            } else if (transition.kind()
                    == SpectatorTransition.Kind.COMMANDER_DAMAGE) {
                label = transition.subject()
                        + " CMD "
                        + transition.detail();
            }
            if (label.isEmpty()) {
                return;
            }

            floatingVisuals.add(
                    new FloatingVisual(
                            transition.player(),
                            label,
                            System.currentTimeMillis()
                    )
            );
            while (floatingVisuals.size() > 24) {
                floatingVisuals.remove(0);
            }
            postInvalidateOnAnimation();
        }

        private void drawFloatingVisuals(Canvas canvas) {
            if (floatingVisuals.isEmpty()) {
                return;
            }

            long now = System.currentTimeMillis();
            Iterator<FloatingVisual> iterator = floatingVisuals.iterator();
            while (iterator.hasNext()) {
                FloatingVisual visual = iterator.next();
                long age = now - visual.startedAt;
                if (age > 1050L) {
                    iterator.remove();
                    continue;
                }

                View player = findTaggedView(
                        watchBoard,
                        "house-player:" + visual.player
                );
                if (player == null) {
                    continue;
                }

                float[] anchor = centerInOverlay(player);
                anchor[1] -= Math.max(0f, player.getHeight() / 2f - dp(30));
                float progress = Math.min(1f, age / 900f);
                float y = anchor[1] - dp(40) * progress;
                canvas.drawText(
                        visual.text,
                        anchor[0] - floatingPaint.measureText(visual.text) / 2f,
                        y,
                        floatingPaint
                );
            }

            if (!floatingVisuals.isEmpty()) {
                postInvalidateOnAnimation();
            }
        }

        private String transitionZone(SpectatorTransition.Kind kind) {
            if (kind == SpectatorTransition.Kind.GRAVEYARD_ADD) {
                return "graveyard";
            }
            if (kind == SpectatorTransition.Kind.EXILE_ADD) {
                return "exile";
            }
            if (kind == SpectatorTransition.Kind.COMMAND_ADD) {
                return "command";
            }
            return "";
        }

        private final class FloatingVisual {
            private final String player;
            private final String text;
            private final long startedAt;

            private FloatingVisual(
                    String player,
                    String text,
                    long startedAt
            ) {
                this.player = player == null ? "" : player;
                this.text = text == null ? "" : text;
                this.startedAt = startedAt;
            }
        }

        private final class FlightVisual {
            private final float startX;
            private final float startY;
            private final String player;
            private final String card;
            private final String zone;
            private final long startedAt;

            private FlightVisual(
                    float startX,
                    float startY,
                    String player,
                    String card,
                    String zone,
                    long startedAt
            ) {
                this.startX = startX;
                this.startY = startY;
                this.player = player == null ? "" : player;
                this.card = card == null ? "" : card;
                this.zone = zone == null ? "" : zone;
                this.startedAt = startedAt;
            }
        }

        private View findTaggedView(View root, String tag) {
            if (root == null || tag == null || tag.isEmpty()) {
                return null;
            }
            if (tag.equals(root.getTag())) {
                return root;
            }
            if (!(root instanceof ViewGroup)) {
                return null;
            }
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findTaggedView(group.getChildAt(i), tag);
                if (match != null) {
                    return match;
                }
            }
            return null;
        }

        private float[] centerInOverlay(View view) {
            int[] viewLocation = new int[2];
            int[] overlayLocation = new int[2];
            view.getLocationOnScreen(viewLocation);
            getLocationOnScreen(overlayLocation);
            return new float[]{
                    viewLocation[0] - overlayLocation[0] + view.getWidth() / 2f,
                    viewLocation[1] - overlayLocation[1] + view.getHeight() / 2f
            };
        }

        private void drawArrow(
                Canvas canvas,
                float x1,
                float y1,
                float x2,
                float y2,
                Paint paint
        ) {
            canvas.drawLine(x1, y1, x2, y2, paint);
            double angle = Math.atan2(y2 - y1, x2 - x1);
            float size = dp(10);
            float ax1 = x2 - (float) (size * Math.cos(angle - Math.PI / 6.0));
            float ay1 = y2 - (float) (size * Math.sin(angle - Math.PI / 6.0));
            float ax2 = x2 - (float) (size * Math.cos(angle + Math.PI / 6.0));
            float ay2 = y2 - (float) (size * Math.sin(angle + Math.PI / 6.0));
            canvas.drawLine(x2, y2, ax1, ay1, paint);
            canvas.drawLine(x2, y2, ax2, ay2, paint);
        }
    }

    private static void mergeLink(
            Map<String, String> links,
            String key,
            String value
    ) {
        if (links == null
                || key == null
                || key.isEmpty()
                || value == null
                || value.isEmpty()) {
            return;
        }
        String prior = links.get(key);
        if (prior == null || prior.isEmpty()) {
            links.put(key, value);
        } else if (!prior.contains(value)) {
            links.put(key, prior + ", " + value);
        }
    }

    private void updateSpectatorTransitions(LiveGameState visible) {
        if (visible == null) {
            return;
        }
        if (visible.sequence() <= 1L) {
            transitionCursor = visible;
            recentActionFeed.clear();
            if (targetOverlay != null) {
                targetOverlay.clearFlights();
            }
            if (watchEvents != null) {
                watchEvents.setText("No visual transitions yet.");
            }
            return;
        }

        List<LiveGameState> frames =
                SpectatorPlayback.framesAfter(transitionCursor.sequence(), 160);
        for (LiveGameState frame : frames) {
            if (frame.sequence() > visible.sequence()) {
                break;
            }
            if (transitionCursor.sequence() > 1L
                    && frame.sequence() <= transitionCursor.sequence()) {
                transitionCursor = frame;
                recentActionFeed.clear();
                continue;
            }

            List<SpectatorTransition.Transition> transitions =
                    SpectatorTransition.diff(transitionCursor, frame);
            for (SpectatorTransition.Transition transition : transitions) {
                if (targetOverlay != null) {
                    targetOverlay.queueZoneFlight(transition);
                    targetOverlay.queueFloatingVisual(transition);
                }
                String line = "T" + frame.turn()
                        + " " + frame.phase()
                        + " • " + transition.displayText();
                recentActionFeed.add(0, line);
            }
            while (recentActionFeed.size() > 80) {
                recentActionFeed.remove(recentActionFeed.size() - 1);
            }
            transitionCursor = frame;
        }
    }

    private void showCardZoom(LiveGameState.CardState card) {
        Bitmap art = cardArtCache.zoomBitmap(
                card,
                dp(420),
                dp(586),
                () -> showCardZoom(card)
        );
        if (art == null || isFinishing()) {
            return;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(8), dp(8), dp(8));

        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(art);
        content.addView(image);

        TextView detail = text(cardDetail(card), 12, false);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(6), 0, 0);
        content.addView(detail);

        new AlertDialog.Builder(this)
                .setTitle(card.name())
                .setView(content)
                .setPositiveButton("Close", null)
                .show();
    }

    private static String cardDetail(LiveGameState.CardState card) {
        StringBuilder out = new StringBuilder();
        if (card.creature()) {
            out.append(card.power()).append("/").append(card.toughness());
        }
        if (card.tapped()) {
            appendDetail(out, "TAPPED");
        }
        if (card.attacking()) {
            appendDetail(out, "ATTACK");
        }
        if (card.blocking()) {
            appendDetail(out, "BLOCK");
        }
        if (card.token()) {
            appendDetail(out, "TOKEN");
        }
        if (!card.counters().isEmpty()) {
            appendDetail(out, join(card.counters()));
        }
        return out.length() == 0 ? "ready" : out.toString();
    }

    private static boolean matchesCategory(
            LiveGameState.CardState card,
            int category
    ) {
        if (category == 0) {
            return card.creature();
        }
        if (category == 1) {
            return !card.creature() && card.land();
        }
        return !card.creature() && !card.land();
    }

    private static void appendDetail(StringBuilder out, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append(" • ");
        }
        out.append(value);
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(value);
        }
        return out.toString();
    }

    private static String commanderDamageSummary(
            List<LiveGameState.CommanderDamageState> values
    ) {
        if (values == null || values.isEmpty()) {
            return "—";
        }
        StringBuilder out = new StringBuilder();
        for (LiveGameState.CommanderDamageState value : values) {
            if (value == null || value.damage() <= 0) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(value.commander()).append(" ").append(value.damage()).append("/21");
        }
        return out.length() == 0 ? "—" : out.toString();
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

    private Button playbackButton(String value, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        p.setMargins(0, dp(3), dp(4), dp(3));
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
