package com.housecommander.mac;

import com.housecommander.forgebridge.ForgeBridge;
import com.housecommander.forgebridge.HouseForgeRuntime;
import com.housecommander.forgebridge.HouseSpectatorState;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One-process-per-game Forge worker.
 *
 * Keeping each literal Commander game in its own JVM means a pathological
 * board state cannot poison the tournament process. When this JVM exits, all
 * tokens, triggers, AI search state, and Forge globals for that game are
 * reclaimed by the OS.
 */
public final class HouseGameWorker {
    private static final String APP_VERSION = "0.9.2-endurance-worker";
    private static final int NO_PRACTICAL_HARD_TIMEOUT_SECONDS = Integer.MAX_VALUE;

    private HouseGameWorker() {
    }

    public static void main(String[] args) {
        int code = 0;
        ScheduledExecutorService snapshots = null;
        Path resultPath = null;
        Path snapshotPath = null;

        try {
            if (args.length < 7) {
                throw new IllegalArgumentException(
                        "Usage: HouseGameWorker <runtimeRoot> <resultFile> <snapshotFile> "
                                + "<logFile> <stallSeconds> <deck1> <deck2> [deck3] [deck4]"
                );
            }

            Path runtimeRoot = Path.of(args[0]).toAbsolutePath().normalize();
            resultPath = Path.of(args[1]).toAbsolutePath().normalize();
            snapshotPath = Path.of(args[2]).toAbsolutePath().normalize();
            Path logPath = Path.of(args[3]).toAbsolutePath().normalize();
            int stallSeconds = Integer.parseInt(args[4]);

            if (stallSeconds < 30) {
                throw new IllegalArgumentException("stallSeconds must be at least 30");
            }

            String[] deckPaths = new String[args.length - 5];
            System.arraycopy(args, 5, deckPaths, 0, deckPaths.length);

            Files.createDirectories(resultPath.getParent());
            Files.createDirectories(snapshotPath.getParent());
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }

            HouseForgeRuntime.initialize(runtimeRoot.toFile(), APP_VERSION);
            if (!ForgeBridge.isAvailable()) {
                throw new IllegalStateException("Forge unavailable: " + ForgeBridge.status());
            }

            final Path liveSnapshot = snapshotPath;
            snapshots = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "HOUSE-Snapshot-Writer");
                t.setDaemon(true);
                return t;
            });
            snapshots.scheduleAtFixedRate(() -> {
                try {
                    writeAtomic(liveSnapshot, HouseSpectatorState.snapshot());
                } catch (Throwable ignored) {
                    // Visualization must never interrupt a literal Forge game.
                }
            }, 0L, 1500L, TimeUnit.MILLISECONDS);

            long started = System.currentTimeMillis();
            String winner = ForgeBridge.runCommanderGame(
                    deckPaths,
                    logPath.toString(),
                    NO_PRACTICAL_HARD_TIMEOUT_SECONDS,
                    stallSeconds
            );
            long elapsed = System.currentTimeMillis() - started;

            writeAtomic(snapshotPath, HouseSpectatorState.snapshot());
            Properties result = new Properties();
            result.setProperty("status", "WINNER");
            result.setProperty("winner", winner == null ? "" : winner);
            result.setProperty("elapsedMs", String.valueOf(elapsed));
            result.setProperty("bridge", ForgeBridge.version());
            storeAtomic(resultPath, result);
        } catch (Throwable t) {
            code = 2;
            try {
                if (snapshotPath != null) {
                    writeAtomic(
                            snapshotPath,
                            HouseSpectatorState.snapshot()
                                    + "\n\nWORKER FAILED • "
                                    + safeMessage(t)
                    );
                }
                if (resultPath != null) {
                    Properties result = new Properties();
                    result.setProperty("status", "ERROR");
                    result.setProperty("errorClass", rootCause(t).getClass().getName());
                    result.setProperty("message", safeMessage(t));
                    storeAtomic(resultPath, result);
                }
            } catch (Throwable ignored) {
                // Last-resort failure path; stderr still records the original error.
            }
            t.printStackTrace(System.err);
        } finally {
            if (snapshots != null) {
                snapshots.shutdownNow();
            }
        }

        System.exit(code);
    }

    private static void writeAtomic(Path target, String text) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, text == null ? "" : text, StandardCharsets.UTF_8);
        moveAtomicBestEffort(temp, target);
    }

    private static void storeAtomic(Path target, Properties properties) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            properties.store(out, "HOUSE Commander Lab isolated Forge worker");
        }
        moveAtomicBestEffort(temp, target);
    }

    private static void moveAtomicBestEffort(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (IOException atomicFailed) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Throwable rootCause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private static String safeMessage(Throwable t) {
        Throwable root = rootCause(t);
        String message = root.getMessage();
        String value = message == null || message.trim().isEmpty()
                ? root.getClass().getSimpleName()
                : message;
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
