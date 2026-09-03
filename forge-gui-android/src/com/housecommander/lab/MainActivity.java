package com.housecommander.lab;

import android.Manifest;
import android.app.Activity;
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
import com.housecommander.core.HousePackageLoader;
import com.housecommander.lab.engine.ForgeEngineAdapter;
import com.housecommander.lab.service.TournamentService;
import com.housecommander.lab.state.ResultsWriter;
import com.housecommander.lab.state.RunState;
import com.housecommander.lab.state.StateStore;

import java.io.File;

public final class MainActivity extends Activity {
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
    private boolean preflightPass;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateRunState();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
        setContentView(buildUi());
        runPreflight();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(36));
        scroll.addView(root);

        TextView title = text("HOUSE Commander Lab", 28, true);
        root.addView(title);
        TextView version = text("Native Android MVP 0.4.0", 14, false);
        version.setAlpha(0.75f);
        root.addView(version);

        String device = Build.MANUFACTURER + " " + Build.MODEL + " • Android " + Build.VERSION.RELEASE + " • API " + Build.VERSION.SDK_INT;
        TextView deviceView = text(device, 13, false);
        deviceView.setPadding(0, dp(8), 0, dp(20));
        root.addView(deviceView);

        root.addView(section("STRICT PREFLIGHT"));
        packageStatus = text("Checking HOUSE-19 package…", 16, false);
        root.addView(packageStatus);
        engineStatus = text("Checking Forge bridge…", 16, false);
        engineStatus.setPadding(0, dp(6), 0, dp(12));
        root.addView(engineStatus);

        Button preflight = button("Run preflight again");
        preflight.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runPreflight(); } });
        root.addView(preflight);

        root.addView(section("TOURNAMENT"));
        testButton = button("Run 1 literal Forge test game");
        testButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startTest(); } });
        root.addView(testButton);

        runOneButton = button("Run / resume 1 gauntlet (95 games)");
        runOneButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startRun(1); } });
        root.addView(runOneButton);

        run500Button = button("Run / resume 500 gauntlets (47,500 games)");
        run500Button.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startRun(500); } });
        root.addView(run500Button);

        pauseButton = button("Pause after current game");
        pauseButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pauseRun(); } });
        root.addView(pauseButton);

        resetButton = button("Reset tournament checkpoint");
        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new StateStore(MainActivity.this).clear();
                updateRunState();
            }
        });
        root.addView(resetButton);

        root.addView(section("LIVE STATUS"));
        runStatus = text("Ready", 18, true);
        root.addView(runStatus);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(95);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14));
        pp.setMargins(0, dp(10), 0, dp(10));
        root.addView(progress, pp);
        details = text("", 14, false);
        root.addView(details);

        TextView strict = text("Integrity rule: this app never substitutes power scores, matchup weights, or guessed winners for Forge. If the engine is unavailable or a winner cannot be verified, the run stops.", 13, false);
        strict.setPadding(0, dp(24), 0, 0);
        strict.setAlpha(0.8f);
        root.addView(strict);
        return scroll;
    }

    private void runPreflight() {
        try {
            HousePackage pack = HousePackageLoader.load(AndroidAssets.from(this), "house19");
            HouseInstall.ensureDeckFiles(this, pack);
            packageStatus.setText("PASS • " + pack.validation().summary());
            preflightPass = true;
        } catch (Throwable t) {
            packageStatus.setText("BLOCKED • " + safeMessage(t));
            preflightPass = false;
        }
        ForgeEngineAdapter engine = new ForgeEngineAdapter(this);
        engineStatus.setText(engine.status());
        updateButtons(engine.isAvailable());
    }

    private void updateButtons(boolean engineAvailable) {
        boolean enabled = preflightPass && engineAvailable;
        testButton.setEnabled(enabled);
        runOneButton.setEnabled(enabled);
        run500Button.setEnabled(enabled);
        if (!engineAvailable) {
            testButton.setText("Run 1 test game — Forge bridge pending");
            runOneButton.setText("Run 1 gauntlet — Forge bridge pending");
            run500Button.setText("Run 500 gauntlets — Forge bridge pending");
        } else {
            testButton.setText("Run 1 literal Forge test game");
            runOneButton.setText("Run / resume 1 gauntlet (95 games)");
            run500Button.setText("Run / resume 500 gauntlets (47,500 games)");
        }
    }

    private void startTest() {
        Intent intent = new Intent(this, TournamentService.class).setAction(TournamentService.ACTION_TEST);
        startForegroundService(intent);
    }

    private void startRun(int gauntlets) {
        Intent intent = new Intent(this, TournamentService.class)
                .setAction(TournamentService.ACTION_RUN)
                .putExtra(TournamentService.EXTRA_GAUNTLETS, gauntlets);
        startForegroundService(intent);
    }

    private void pauseRun() {
        Intent intent = new Intent(this, TournamentService.class).setAction(TournamentService.ACTION_PAUSE);
        startService(intent);
    }

    private void updateRunState() {
        RunState state = new StateStore(this).load();
        runStatus.setText(state.status + " • " + state.lastMessage);
        progress.setProgress(Math.min(95, state.nextPodIndex));
        File results = ResultsWriter.resultsFile(this);
        details.setText("Target gauntlets: " + state.targetGauntlets
                + "\nCurrent gauntlet: " + state.currentGauntlet
                + "\nNext pod: " + (state.nextPodIndex + 1) + "/95"
                + "\nLiteral games checkpointed: " + state.totalGames
                + "\nResults: " + results.getAbsolutePath());
        boolean running = "RUNNING".equals(state.status) || "TESTING".equals(state.status);
        pauseButton.setEnabled(running);
        resetButton.setEnabled(!running);
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
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(5), 0, dp(5));
        b.setLayoutParams(p);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        return x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage();
    }
}
