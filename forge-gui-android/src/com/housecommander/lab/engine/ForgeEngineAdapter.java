package com.housecommander.lab.engine;

import android.content.Context;

import com.housecommander.core.DeckSpec;
import com.housecommander.lab.HouseInstall;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Strict in-APK bridge between HOUSE Commander Lab and the real Forge engine.
 *
 * IMPORTANT:
 * ForgeBridge is compiled into the SAME APK as HOUSE Commander Lab.
 * Therefore this class must NEVER try to load a separate Android package
 * named "forge.app".
 *
 * The runtime Android application package may be:
 *
 *     com.housecommander.labapp
 *
 * while Forge's Java classes still live under packages such as:
 *
 *     forge.app.Main
 *     forge.model.FModel
 *     com.housecommander.forgebridge.ForgeBridge
 *
 * Android application package names and Java package names are not the
 * same thing. The bridge must use this APK's own ClassLoader.
 *
 * Integrity rule:
 * This adapter never invents game results. If Forge is unavailable,
 * uninitialized, throws, times out, or returns no winner, the caller gets
 * a real error.
 */
public final class ForgeEngineAdapter {

    private static final String BRIDGE_CLASS =
            "com.housecommander.forgebridge.ForgeBridge";

    private final Context context;

    public ForgeEngineAdapter(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }

        Context appContext = context.getApplicationContext();
        this.context = appContext != null ? appContext : context;
    }

    /**
     * Load ForgeBridge directly from THIS APK.
     *
     * Do not use createPackageContext("forge.app", ...).
     * There is no separate installed application with that package name in
     * the HOUSE build.
     */
    private Class<?> getBridgeClass() throws ClassNotFoundException {
        ClassLoader loader = context.getClassLoader();

        if (loader == null) {
            loader = ForgeEngineAdapter.class.getClassLoader();
        }

        return Class.forName(
                BRIDGE_CLASS,
                true,
                loader
        );
    }

    /**
     * Returns true only when the real Forge card database has completed
     * initialization.
     */
    public boolean isAvailable() {
        try {
            Class<?> bridge = getBridgeClass();

            Method available =
                    bridge.getMethod("isAvailable");

            Object result =
                    available.invoke(null);

            return Boolean.TRUE.equals(result);

        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Human-readable strict preflight status.
     *
     * Distinguishes:
     *   1. Bridge class missing
     *   2. Bridge present but Forge DB still loading
     *   3. Bridge + Forge DB ready
     */
    public String status() {
        final String runtimePackage = context.getPackageName();

        try {
            Class<?> bridge = getBridgeClass();

            Method versionMethod =
                    bridge.getMethod("version");

            Method availableMethod =
                    bridge.getMethod("isAvailable");

            Object version =
                    versionMethod.invoke(null);

            Object available =
                    availableMethod.invoke(null);

            if (Boolean.TRUE.equals(available)) {
                return "Forge bridge READY"
                        + " • "
                        + String.valueOf(version)
                        + " • package="
                        + runtimePackage;
            }

            return "Forge bridge linked"
                    + " • Forge card database loading"
                    + " • "
                    + String.valueOf(version)
                    + " • package="
                    + runtimePackage;

        } catch (Throwable t) {
            Throwable root = rootCause(t);

            return "Forge bridge load failed"
                    + " • package="
                    + runtimePackage
                    + " • "
                    + root.getClass().getName()
                    + ": "
                    + safeMessage(root);
        }
    }

    /**
     * Execute exactly one real Forge multiplayer Commander game.
     *
     * @param pod          physical HOUSE deck specifications
     * @param logFile      destination for Forge's literal game log
     * @param clockSeconds hard timeout for the game
     */
    public GameOutcome runCommanderGame(
            List<DeckSpec> pod,
            File logFile,
            int clockSeconds
    ) throws Exception {

        if (pod == null || pod.size() < 2) {
            throw new IllegalArgumentException(
                    "At least two Commander decks are required"
            );
        }

        if (logFile == null) {
            throw new IllegalArgumentException(
                    "Forge log file must not be null"
            );
        }

        if (clockSeconds < 1) {
            throw new IllegalArgumentException(
                    "Forge game timeout must be at least 1 second"
            );
        }

        /*
         * Strict gate.
         *
         * ForgeBridge.isAvailable() ultimately verifies FModel's real
         * Magic database. If it is false, absolutely no substitute
         * simulation is allowed.
         */
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "STRICT GATE: Forge engine/card database is not ready. "
                            + "No game was simulated. "
                            + status()
            );
        }

        String[] deckPaths =
                new String[pod.size()];

        for (int i = 0; i < pod.size(); i++) {
            DeckSpec spec = pod.get(i);

            if (spec == null) {
                throw new IllegalArgumentException(
                        "Pod contains a null deck at index " + i
                );
            }

            File deckFile =
                    HouseInstall.deckFile(context, spec);

            if (deckFile == null || !deckFile.isFile()) {
                throw new IllegalStateException(
                        "Physical Forge deck file is missing for pod index "
                                + i
                                + ": "
                                + (deckFile == null
                                ? "<null>"
                                : deckFile.getAbsolutePath())
                );
            }

            deckPaths[i] =
                    deckFile.getAbsolutePath();
        }

        Class<?> bridge =
                getBridgeClass();

        Method run =
                bridge.getMethod(
                        "runCommanderGame",
                        String[].class,
                        String.class,
                        int.class
                );

        Object winner;

        try {
            /*
             * String[].class is an Object when passed through reflection.
             * The cast prevents Java from treating the deck array as
             * multiple reflection arguments.
             */
            winner = run.invoke(
                    null,
                    (Object) deckPaths,
                    logFile.getAbsolutePath(),
                    clockSeconds
            );

        } catch (InvocationTargetException e) {
            /*
             * Preserve the REAL Forge failure instead of hiding it inside
             * reflection's InvocationTargetException.
             */
            Throwable cause =
                    e.getCause() != null
                            ? e.getCause()
                            : e;

            if (cause instanceof Exception) {
                throw (Exception) cause;
            }

            if (cause instanceof Error) {
                throw (Error) cause;
            }

            throw new RuntimeException(cause);
        }

        String winnerName =
                winner == null
                        ? ""
                        : winner.toString().trim();

        if (winnerName.isEmpty()) {
            throw new IllegalStateException(
                    "STRICT GATE: Forge returned no verified winner. "
                            + "HOUSE will not guess a result."
            );
        }

        Method version =
                bridge.getMethod("version");

        Object forgeVersion =
                version.invoke(null);

        return new GameOutcome(
                winnerName,
                String.valueOf(forgeVersion),
                ""
        );
    }

    private static Throwable rootCause(Throwable t) {
        if (t == null) {
            return new IllegalStateException(
                    "Unknown Forge bridge error"
            );
        }

        Throwable root = t;

        while (root.getCause() != null
                && root.getCause() != root) {
            root = root.getCause();
        }

        return root;
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "unknown error";
        }

        String message =
                t.getMessage();

        if (message == null
                || message.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }

        return message
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
    }
} 