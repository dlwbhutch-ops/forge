package com.housecommander.lab.engine;

import android.content.Context;

import com.housecommander.core.DeckSpec;
import com.housecommander.lab.HouseInstall;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Strict in-APK bridge between HOUSE Commander Lab and Forge.
 *
 * No substitute simulator and no guessed result is ever used. Any bridge,
 * database, deck, engine, timeout, or winner-validation failure is surfaced to
 * the tournament service as a real error.
 */
public final class ForgeEngineAdapter {
    private static final String BRIDGE_CLASS =
            "com.housecommander.forgebridge.ForgeBridge";

    private final Context context;

    public ForgeEngineAdapter(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        Context app = context.getApplicationContext();
        this.context = app != null ? app : context;
    }

    private Class<?> getBridgeClass() throws ClassNotFoundException {
        ClassLoader loader = context.getClassLoader();
        if (loader == null) {
            loader = ForgeEngineAdapter.class.getClassLoader();
        }
        return Class.forName(BRIDGE_CLASS, true, loader);
    }

    public boolean isAvailable() {
        try {
            Class<?> bridge = getBridgeClass();
            Method available = bridge.getMethod("isAvailable");
            return Boolean.TRUE.equals(available.invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Human-readable preflight/health status from the bridge itself.
     */
    public String status() {
        final String runtimePackage = context.getPackageName();
        try {
            Class<?> bridge = getBridgeClass();
            Method versionMethod = bridge.getMethod("version");
            Method availableMethod = bridge.getMethod("isAvailable");

            Object version = versionMethod.invoke(null);
            Object available = availableMethod.invoke(null);

            String bridgeStatus;
            try {
                Method statusMethod = bridge.getMethod("status");
                Object value = statusMethod.invoke(null);
                bridgeStatus = value == null ? "" : value.toString().trim();
            } catch (NoSuchMethodException legacyBridge) {
                bridgeStatus = Boolean.TRUE.equals(available)
                        ? "READY"
                        : "Forge card database loading";
            }

            return "Forge bridge "
                    + bridgeStatus
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
     * Execute one literal Forge multiplayer Commander game.
     *
     * @param pod                 HOUSE deck specifications for the pod
     * @param logFile             literal Forge audit log destination
     * @param hardTimeoutSeconds  absolute wall-clock ceiling for one game
     * @param stallTimeoutSeconds maximum time with no observable game progress
     */
    public GameOutcome runCommanderGame(
            List<DeckSpec> pod,
            File logFile,
            int hardTimeoutSeconds,
            int stallTimeoutSeconds
    ) throws Exception {
        validateArguments(pod, logFile, hardTimeoutSeconds, stallTimeoutSeconds);

        if (!isAvailable()) {
            throw new IllegalStateException(
                    "STRICT GATE: Forge engine/card database is not ready. "
                            + "No game was simulated. "
                            + status()
            );
        }

        String[] deckPaths = new String[pod.size()];
        for (int i = 0; i < pod.size(); i++) {
            DeckSpec spec = pod.get(i);
            if (spec == null) {
                throw new IllegalArgumentException("Pod contains a null deck at index " + i);
            }

            File deckFile = HouseInstall.deckFile(context, spec);
            if (deckFile == null || !deckFile.isFile()) {
                throw new IllegalStateException(
                        "Physical Forge deck file is missing for pod index "
                                + i
                                + ": "
                                + (deckFile == null ? "<null>" : deckFile.getAbsolutePath())
                );
            }
            if (!deckFile.canRead()) {
                throw new IllegalStateException(
                        "Physical Forge deck file is not readable: " + deckFile.getAbsolutePath()
                );
            }
            deckPaths[i] = deckFile.getAbsolutePath();
        }

        File parent = logFile.getParentFile();
        if (parent != null
                && !parent.exists()
                && !parent.mkdirs()
                && !parent.isDirectory()) {
            throw new IllegalStateException(
                    "Could not create Forge log directory: " + parent.getAbsolutePath()
            );
        }

        Class<?> bridge = getBridgeClass();
        Method run = bridge.getMethod(
                "runCommanderGame",
                String[].class,
                String.class,
                int.class,
                int.class
        );

        Object winner;
        try {
            winner = run.invoke(
                    null,
                    (Object) deckPaths,
                    logFile.getAbsolutePath(),
                    hardTimeoutSeconds,
                    stallTimeoutSeconds
            );
        } catch (InvocationTargetException e) {
            rethrowInvocationCause(e);
            throw new AssertionError("unreachable");
        }

        String winnerName = winner == null ? "" : winner.toString().trim();
        if (winnerName.isEmpty()) {
            throw new IllegalStateException(
                    "STRICT GATE: Forge returned no verified winner. HOUSE will not guess a result."
            );
        }

        Object forgeVersion;
        try {
            Method version = bridge.getMethod("version");
            forgeVersion = version.invoke(null);
        } catch (InvocationTargetException e) {
            rethrowInvocationCause(e);
            throw new AssertionError("unreachable");
        }

        return new GameOutcome(
                winnerName,
                String.valueOf(forgeVersion),
                ""
        );
    }

    /**
     * Compatibility shim for any caller still compiled against bridge 0.6.
     * New HOUSE code should always use the four-argument overload.
     */
    @Deprecated
    public GameOutcome runCommanderGame(
            List<DeckSpec> pod,
            File logFile,
            int clockSeconds
    ) throws Exception {
        int hard = Math.max(1, clockSeconds);
        int stall = Math.min(hard, 180);
        return runCommanderGame(pod, logFile, hard, stall);
    }

    private static void validateArguments(
            List<DeckSpec> pod,
            File logFile,
            int hardTimeoutSeconds,
            int stallTimeoutSeconds
    ) {
        if (pod == null || pod.size() < 2) {
            throw new IllegalArgumentException("At least two Commander decks are required");
        }
        if (logFile == null) {
            throw new IllegalArgumentException("Forge log file must not be null");
        }
        if (hardTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Hard game timeout must be at least 1 second");
        }
        if (stallTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Stall timeout must be at least 1 second");
        }
        if (stallTimeoutSeconds > hardTimeoutSeconds) {
            throw new IllegalArgumentException(
                    "Stall timeout must not exceed the hard game timeout"
            );
        }
    }

    private static void rethrowInvocationCause(InvocationTargetException e) throws Exception {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (cause instanceof Exception) {
            throw (Exception) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        throw new RuntimeException(cause);
    }

    private static Throwable rootCause(Throwable t) {
        if (t == null) {
            return new IllegalStateException("Unknown Forge bridge error");
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
