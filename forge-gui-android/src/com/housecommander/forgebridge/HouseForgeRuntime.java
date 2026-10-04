package com.housecommander.forgebridge;

import forge.gui.GuiBase;
import forge.gui.interfaces.IProgressBar;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.io.File;
import java.util.function.Consumer;

/** Owns publication of a completely initialized rules database. */
public final class HouseForgeRuntime {
    public static final String VERSION = "0.16.0";
    private static volatile boolean ready;
    private static volatile boolean failed;
    private static volatile String status = "Waiting to initialize bundled Forge database";
    private static volatile String forgeVersion = "bundled";

    private HouseForgeRuntime() { }

    public static boolean isReady() { return ready; }
    public static boolean hasFailed() { return failed; }
    public static String status() { return status; }
    public static String forgeVersion() { return forgeVersion; }
    public static void report(String message) { status = message; }

    public static void fail(Throwable error) {
        ready = false;
        failed = true;
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        status = "STARTUP FAILED • " + root.getClass().getSimpleName()
                + (message == null ? "" : ": " + message.replace('\n', ' '))
                + " • Restart the app after correcting this error";
    }

    public static synchronized void initialize(File root, String version) {
        if (ready) return;
        if (failed) throw new IllegalStateException(status);
        try {
            report("Initializing bundled Forge rules database");
            // Set the platform boundary BEFORE ForgeConstants or FModel is touched.
            GuiBase.setInterface(new HouseHeadlessGui(root, version));
            GuiBase.setIsAndroid(true);
            GuiBase.setUsingAppDirectory(true);
            forgeVersion = version;
            FModel.initialize(new DatabaseProgress(HouseForgeRuntime::report), prefs -> {
                prefs.setPref(FPref.DECKGEN_CARDBASED, false);
                prefs.setPref(FPref.UI_ENABLE_SOUNDS, false);
                prefs.setPref(FPref.UI_ENABLE_MUSIC, false);
                prefs.setPref(FPref.UI_LANGUAGE, "en-US");
                prefs.setPref(FPref.UI_LOAD_UNKNOWN_CARDS, false);
                return null;
            });
            int cards = FModel.getMagicDb().getCommonCards().getAllCards().size();
            if (cards < 1000) throw new IllegalStateException("Incomplete Forge card database: " + cards + " printings");
            report("READY • " + cards + " card printings loaded locally");
            ready = true;
        } catch (Throwable error) {
            fail(error);
            throw new IllegalStateException(status, error);
        }
    }

    private static final class DatabaseProgress implements IProgressBar {
        private final Consumer<String> reporter;
        private String description = "Loading cards";
        private int maximum;
        DatabaseProgress(Consumer<String> reporter) { this.reporter = reporter; }
        @Override public void setDescription(String value) { description = value; reporter.accept(value); }
        @Override public void setValue(int value) { reporter.accept(description + " • " + value + "/" + maximum); }
        @Override public void reset() { maximum = 0; }
        @Override public void setShowETA(boolean value) { }
        @Override public void setShowCount(boolean value) { }
        @Override public void setPercentMode(boolean value) { }
        @Override public int getMaximum() { return maximum; }
        @Override public void setMaximum(int value) { maximum = value; }
    }
}
