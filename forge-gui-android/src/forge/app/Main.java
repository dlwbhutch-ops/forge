package forge.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Version;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidAudio;
import com.badlogic.gdx.backends.android.AsynchronousAndroidAudio;
import com.getkeepsafe.relinker.ReLinker;
import de.cketti.fileprovider.PublicFileProvider;
import forge.util.HWInfo;
import forge.Forge;
import forge.interfaces.IDeviceAdapter;
import forge.util.FileUtil;
import forge.util.ThreadUtil;
import io.sentry.protocol.Device;
import io.sentry.protocol.OperatingSystem;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONObject;
import org.jupnp.DefaultUpnpServiceConfiguration;
import org.jupnp.android.AndroidUpnpServiceConfiguration;
import org.tinylog.Logger;
import org.tinylog.TaggedLogger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;

public class Main extends AndroidApplication {
    private static final TaggedLogger netLog = Logger.tag("NETWORK");
    private static final long HEAP_HEARTBEAT_MS = 30_000L;
    private static final long HOUSE_BOOTSTRAP_POLL_MS = 500L;

    // HOUSE Commander Lab bootstrap mode. When HOUSE launches this activity
    // only to initialize Forge, keep Forge alive long enough to load its real
    // card database, then return to the HOUSE activity automatically.
    private boolean houseBootstrapMode;
    private boolean houseBootstrapReturned;
    private Handler houseBootstrapHandler;

    private final Runnable houseBootstrapReturnWatch = new Runnable() {
        @Override public void run() {
            if (!houseBootstrapMode || houseBootstrapReturned) {
                return;
            }

            try {
                if (com.housecommander.forgebridge.ForgeBridge.isAvailable()) {
                    houseBootstrapReturned = true;
                    netLog.info("[house] Forge card database ready; returning to HOUSE Commander Lab");

                    Intent houseIntent = new Intent(
                            Main.this,
                            com.housecommander.lab.MainActivity.class);
                    houseIntent.addFlags(
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(houseIntent);
                    overridePendingTransition(0, 0);
                    return;
                }
            } catch (Throwable t) {
                netLog.debug(t, "[house] Forge bridge not ready yet");
            }

            if (houseBootstrapHandler != null) {
                houseBootstrapHandler.postDelayed(
                        this, HOUSE_BOOTSTRAP_POLL_MS);
            }
        }
    };

    private AndroidAdapter Gadapter;
    private ArrayList<String> gamepads;
    private AndroidClipboard androidClipboard;
    private boolean isMIUI;
    private String ASSETS_DIR = "";
    private SharedPreferences sharedPreferences;
    private int mShortAnimationDuration;
    private View forgeLogo = null, forgeView = null, activeView = null;
    private ApplicationListener forgeApp = null;
    private ProgressBar progressBar;
    private TextView progressText;
    private String versionString;
    private Handler heapHeartbeatHandler;
    private final Runnable heapHeartbeat = new Runnable() {
        @Override public void run() {
            Runtime r = Runtime.getRuntime();
            long total = r.totalMemory(), free = r.freeMemory(), max = r.maxMemory();
            netLog.info("[heap] used={}MB free={}MB total={}MB max={}MB",
                    (total - free) >> 20, free >> 20, total >> 20, max >> 20);
            if (heapHeartbeatHandler != null) {
                heapHeartbeatHandler.postDelayed(this, HEAP_HEARTBEAT_MS);
            }
        }
    };

    // The package name the resources are compiled under (stable across dev/prod).
    // If you ever change the base app package, update this constant once.
    private static final String RES_PKG_FALLBACK = "forge.app";

    private boolean isHouseBootstrapIntent(Intent intent) {
        return intent != null
                && intent.getBooleanExtra(
                        com.housecommander.lab.engine.ForgeDatabaseBootstrap.EXTRA_HOUSE_BOOTSTRAP,
                        false);
    }

    private void startHouseBootstrapReturnWatch() {
        if (!houseBootstrapMode) {
            return;
        }
        if (houseBootstrapHandler == null) {
            houseBootstrapHandler = new Handler(Looper.getMainLooper());
        }
        houseBootstrapReturned = false;
        houseBootstrapHandler.removeCallbacks(houseBootstrapReturnWatch);
        houseBootstrapHandler.post(houseBootstrapReturnWatch);
    }

    private int resId(String type, String name) {
        // 1) Try fully-qualified with *runtime* package
        int id = getResources().getIdentifier(name, type, getPackageName());
        if (id != 0) return id;

        // 2) Try fully-qualified with *fallback* resource package
        if (!RES_PKG_FALLBACK.equals(getPackageName())) {
            id = getResources().getIdentifier(name, type, RES_PKG_FALLBACK);
            if (id != 0) return id;
        }

        android.util.Log.e("ForgeRes", "Missing resource " + type + "/" + name +
                " for pkg=" + getPackageName() + " (also tried " + RES_PKG_FALLBACK + ")");
        return 0;
    }

    private AndroidClipboard getAndroidClipboard() {
        if (androidClipboard == null)
            androidClipboard = new AndroidClipboard();
        return androidClipboard;
    }

    public static boolean isMiUi() {
        return !TextUtils.isEmpty(getSystemProperty("ro.miui.ui.version.name"));
    }

    public boolean needExternalFileAccess() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager();
    }

    public static String getSystemProperty(String propName) {
        String line;
        BufferedReader input = null;
        try {
            java.lang.Process p = Runtime.getRuntime().exec("getprop " + propName);
            input = new BufferedReader(new InputStreamReader(p.getInputStream()), 1024);
            line = input.readLine();
            input.close();
        } catch (IOException ex) {
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return line;
    }

    @Override
    protected void onResume() {
        netLog.info("[lifecycle] onResume");
        try {
            super.onResume();
        } catch (Exception ignore) {}

        if (houseBootstrapMode && !houseBootstrapReturned) {
            startHouseBootstrapReturnWatch();
        }
    }

    @Override
    public AndroidAudio createAudio(Context context, AndroidApplicationConfiguration config) {
        return new AsynchronousAndroidAudio(context, config);
        //return super.createAudio(context, config);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Capture uncaught exceptions before the JVM dies — without this, the stack trace would only reach Android logcat, not the network log users share
        final Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                netLog.error(e, "[uncaught] thread={}", t.getName());
            } catch (Throwable ignore) {}
            if (prior != null) prior.uncaughtException(t, e);
        });

        netLog.info("[lifecycle] onCreate");
        heapHeartbeatHandler = new Handler(Looper.getMainLooper());
        heapHeartbeatHandler.postDelayed(heapHeartbeat, HEAP_HEARTBEAT_MS);

        super.onCreate(savedInstanceState);

        houseBootstrapMode = isHouseBootstrapIntent(getIntent());
        if (houseBootstrapMode) {
            netLog.info("[house] HOUSE requested native Forge/card-database bootstrap");
            houseBootstrapHandler = new Handler(Looper.getMainLooper());
        }

        try {
            PackageInfo pInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            versionString = pInfo.versionName;
        } catch (Exception e) {
            versionString = "0.0";
        }
        setContentView(resId("layout", "main"));
        mShortAnimationDuration = getResources().getInteger(android.R.integer.config_shortAnimTime);
        sharedPreferences = getPreferences(Context.MODE_PRIVATE);
        progressBar = findViewById(resId("id", "pBar"));
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        progressText = findViewById(resId("id", "pText"));
        progressText.setVisibility(View.GONE);

        isMIUI = isMiUi();
        if (isMIUI)
            preventSleep(true);

        gamepads = getGameControllers();

        //get total device RAM in mb
        ActivityManager actManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);

        boolean permissiongranted = checkPermission();
        Gadapter = new AndroidAdapter(getContext());
        String cpu = "";
        String soc = "";
        boolean getChipset = false;
        // database.json source: https://github.com/xTheEc0/Android-Device-Hardware-Specs-Database
        try {
            InputStream is = getAssets().open("database.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            JSONObject db = new JSONObject(new String(buffer, StandardCharsets.UTF_8));
            JSONObject board = db.getJSONObject(Build.BOARD);
            cpu = board.get("CPU").toString();
            soc = board.get("SoC").toString();
            getChipset = true;
        } catch (Exception e) {
            cpu = getCpuName();
            soc = Build.BOARD;
            getChipset = false;
        }
        // Device Info
        Device device = new Device();
        device.setId(Build.ID);
        device.setName(getDeviceName());
        device.setModel(Build.MODEL);
        device.setBrand(Build.BRAND);
        device.setManufacturer(Build.MANUFACTURER);
        device.setMemorySize(memInfo.totalMem);
        device.setCpuDescription(cpu);
        device.setChipset(soc);
        // OS Info
        OperatingSystem os = new OperatingSystem();
        os.setName("Android");
        os.setVersion(Build.VERSION.RELEASE);
        os.setBuild(Build.DISPLAY);
        os.setRawDescription(getAndroidOSName());

        initForge(Gadapter, new HWInfo(device, os, getChipset), permissiongranted, isTabletDevice(getContext()));

        if (houseBootstrapMode) {
            startHouseBootstrapReturnWatch();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        if (isHouseBootstrapIntent(intent)) {
            netLog.info("[house] Received HOUSE bootstrap intent");
            houseBootstrapMode = true;
            if (houseBootstrapHandler == null) {
                houseBootstrapHandler = new Handler(Looper.getMainLooper());
            }
            startHouseBootstrapReturnWatch();
        }
    }

    private void crossfade(View contentView, View previousView) {
         activeView = contentView;
        // Set the content view to 0% opacity but visible, so that it is visible
        // (but fully transparent) during the animation.
        contentView.setAlpha(0f);
        contentView.setVisibility(View.VISIBLE);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);
        addContentView(contentView, params);

        Animator ac = ObjectAnimator.ofFloat(contentView, "alpha", 0f, 1f).setDuration(mShortAnimationDuration);
        Animator ap = ObjectAnimator.ofFloat(previousView, "alpha", 1f, 0f).setDuration(mShortAnimationDuration);
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(ac, ap);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                previousView.setVisibility(View.GONE);
            }
        });
        animatorSet.start();
    }

    private static boolean isTabletDevice(Context activityContext) {
        Display display = ((Activity) activityContext).getWindowManager().getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);

        float widthInches = metrics.widthPixels / metrics.xdpi;
        float heightInches = metrics.heightPixels / metrics.ydpi;
        double diagonalInches = Math.sqrt(Math.pow(widthInches, 2) + Math.pow(heightInches, 2));
        return diagonalInches >= 7.0;
    }

    private void displayMessage(View previousView, AndroidAdapter adapter, boolean ex, String msg, boolean manageApp) {
        TableLayout TL = new TableLayout(this);
        TL.setBackgroundResource(android.R.color.black);
        TableRow row = new TableRow(this);
        TableRow row2 = new TableRow(this);
        TextView text = new TextView(this);
        text.setGravity(Gravity.LEFT);
        text.setTypeface(Typeface.SERIF);
        String SP = "Storage Permission";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SP = "Photos and Videos, Music and Audio Permissions";
        } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            SP = "Files & Media Permissions";
        }

        String title = "Forge needs " + SP + " to run properly...\n" +
                "Follow these simple steps:\n\n";
        String steps = " 1) Tap \"App Settings\" Button.\n" +
                " 2) Tap Permissions\n" +
                " 3) Enable the " + SP + ".\n\n" +
                "(You can tap anywhere to exit and restart the app)\n\n";
        if (ex) {
            title = manageApp ? "Forge AutoUpdater Permission...\n" : "Forge didn't initialize!\n";
            steps = manageApp ? " 1) Tap \"App Settings\" Button.\n" +
                    " 2) Enable \"Allow apps from this source\"\n" +
                    "(You can tap anywhere to exit and restart the app)\n\n" : msg + "\n\n";
        }

        SpannableString ss1 = new SpannableString(title);
        ss1.setSpan(new StyleSpan(Typeface.BOLD), 0, ss1.length(), 0);
        text.append(ss1);
        text.append(steps);
        row.addView(text);
        row.setGravity(Gravity.CENTER);

        int[] colors = {Color.TRANSPARENT, Color.TRANSPARENT};
        int[] pressed = {Color.GREEN, Color.GREEN};
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, colors);
        gd.setStroke(3, Color.DKGRAY);
        gd.setCornerRadius(100);

        GradientDrawable gd2 = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, pressed);
        gd2.setStroke(3, Color.DKGRAY);
        gd2.setCornerRadius(100);

        Button button = new Button(this);
        button.setText("App Settings");
        button.setTypeface(Typeface.DEFAULT_BOLD);

        StateListDrawable states = new StateListDrawable();

        states.addState(new int[]{android.R.attr.state_pressed}, gd2);
        states.addState(new int[]{}, gd);

        button.setBackground(states);

        button.setTextColor(Color.RED);
        button.setOnClickListener(v -> {
            if (manageApp) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse(String.format("package:%s", getPackageName())))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse(String.format("package:%s", getPackageName())))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        });

        row2.addView(button);
        row2.setGravity(Gravity.CENTER);

        TL.addView(row, new TableLayout.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, TableLayout.LayoutParams.WRAP_CONTENT));
        TL.addView(row2, new TableLayout.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, TableLayout.LayoutParams.WRAP_CONTENT));
        TL.setGravity(Gravity.CENTER);
        TL.setOnClickListener(v -> adapter.restart());
        crossfade(TL, previousView);
    }

    private void loadGame(final HWInfo hwInfo, final String title, final String steps, boolean isLandscape, AndroidAdapter adapter, boolean permissiongranted, boolean isTabletDevice, AndroidApplicationConfiguration config, boolean exception, String msg) {
        try {
            final Handler handler = new Handler();
            forgeLogo = findViewById(resId("id", "logo_id"));
            activeView = findViewById(resId("id", "mainview"));
            activeView.setBackgroundColor(Color.WHITE);
            forgeApp = Forge.getApp(hwInfo, getAndroidClipboard(), adapter, ASSETS_DIR, !isLandscape, isTabletDevice, Build.VERSION.SDK_INT);
            forgeView = initializeForView(forgeApp, config);

            getAnimator(ObjectAnimator.ofFloat(forgeLogo, "alpha", 1f, 1f).setDuration(800), ObjectAnimator.ofObject(activeView, "backgroundColor", new ArgbEvaluator(), Color.WHITE, Color.BLACK).setDuration(1600), new AnimatorListenerAdapter() {
                @Override
                public void onAn
