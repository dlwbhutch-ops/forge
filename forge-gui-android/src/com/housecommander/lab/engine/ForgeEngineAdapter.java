package com.housecommander.lab.engine;

import android.content.Context;

import com.housecommander.core.DeckSpec;
import com.housecommander.lab.HouseInstall;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Reflection boundary for the upcoming Forge integration module.
 * No fake results are generated when the bridge is absent.
 */
public final class ForgeEngineAdapter {
    private static final String BRIDGE_CLASS = "com.housecommander.forgebridge.ForgeBridge";

    private final Context context;

    public ForgeEngineAdapter(Context context) {
        this.context = context.getApplicationContext();
    }
private Class<?> getBridgeClass() throws Exception {
    Context forgeContext = context.createPackageContext(
            "forge.app",
            Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
    );

    return Class.forName(
            BRIDGE_CLASS,
            true,
            forgeContext.getClassLoader()
    );
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
public String status() {
    try {
        Class<?> bridge = getBridgeClass();
        Method version = bridge.getMethod("version");
        return "Forge bridge linked: " + version.invoke(null);
        } catch (Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return "Forge bridge load failed: "
                + root.getClass().getName()
                + ": "
                + String.valueOf(root.getMessage());
    }
    }

    public GameOutcome runCommanderGame(List<DeckSpec> pod, File logFile, int clockSeconds) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("STRICT GATE: Forge engine bridge is not linked. No game was simulated.");
        }
        String[] deckPaths = new String[pod.size()];
        for (int i = 0; i < pod.size(); i++) {
            deckPaths[i] = HouseInstall.deckFile(context, pod.get(i)).getAbsolutePath();
        }
        Class<?> bridge = getBridgeClass();
        Method run = bridge.getMethod("runCommanderGame", String[].class, String.class, int.class);
        Object winner = run.invoke(null, deckPaths, logFile.getAbsolutePath(), clockSeconds);
        Method version = bridge.getMethod("version");
        String winnerName = winner == null ? "" : winner.toString().trim();
        if (winnerName.isEmpty()) throw new IllegalStateException("Forge bridge returned no winner");
        return new GameOutcome(winnerName, String.valueOf(version.invoke(null)), "");
    }
}
