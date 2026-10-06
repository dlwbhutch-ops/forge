package com.housecommander.lab.state;

import java.io.File;
import java.io.IOException;

/** Locates the actual game log, including tournaments and checkpoints from earlier builds. */
public final class GameLogFiles {
    private GameLogFiles() { }

    public static File find(File filesDir, RunState state) {
        File logs = new File(filesDir, "logs");
        String saved = state.lastLogPath == null ? "" : state.lastLogPath;
        if (!saved.isEmpty()) {
            File active = new File(filesDir, saved);
            try {
                if (active.getCanonicalPath().startsWith(logs.getCanonicalPath() + File.separator)
                        && active.getName().endsWith(".log") && !active.getName().endsWith(".viewer.log")) {
                    return active; // The audit log is written when Forge finishes; its sidecar may exist first.
                }
            } catch (IOException ignored) { }
        }
        File newest = null;
        File[] groups = logs.listFiles();
        if (groups == null) return null;
        for (File group : groups) {
            File[] files = group.isDirectory() ? group.listFiles() : null;
            if (files == null) continue;
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".log")
                        && !file.getName().endsWith(".viewer.log")
                        && (newest == null || file.lastModified() > newest.lastModified())) newest = file;
            }
        }
        return newest;
    }
}
