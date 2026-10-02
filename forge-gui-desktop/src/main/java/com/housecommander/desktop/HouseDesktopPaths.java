package com.housecommander.desktop;

import java.io.File;
import java.io.IOException;

public final class HouseDesktopPaths {
    private HouseDesktopPaths() {}

    public static File home() throws IOException {
        File root = new File(System.getProperty("user.home"), ".house-commander-lab");
        ensureDirectory(root);
        return root;
    }

    public static File deckRoot() throws IOException {
        File dir = new File(home(), "decks");
        ensureDirectory(dir);
        return dir;
    }

    public static File runtimeRoot() throws IOException {
        File dir = new File(home(), "runtime");
        ensureDirectory(dir);
        return dir;
    }

    public static File resultsDir() throws IOException {
        File dir = new File(home(), "results");
        ensureDirectory(dir);
        return dir;
    }

    public static File logsDir() throws IOException {
        File dir = new File(home(), "logs");
        ensureDirectory(dir);
        return dir;
    }

    public static File stateFile() throws IOException {
        return new File(home(), "tournament_state.properties");
    }

    public static File libraryFile() throws IOException {
        return new File(home(), "deck_library.tsv");
    }

    public static File rosterFile() throws IOException {
        return new File(home(), "active_roster.txt");
    }

    public static void ensureDirectory(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Could not create directory: " + dir.getAbsolutePath());
        }
    }
}
