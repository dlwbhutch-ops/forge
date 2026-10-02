package com.housecommander.mac;

import com.housecommander.core.AssetSource;
import com.housecommander.core.DeckSpec;
import com.housecommander.core.HousePackage;
import com.housecommander.core.HousePackageLoader;
import com.housecommander.core.Names;
import com.housecommander.core.PodSpec;
import com.housecommander.forgebridge.ForgeBridge;
import com.housecommander.forgebridge.HouseForgeRuntime;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * HOUSE Commander Lab 0.9.2 "Endurance Runner".
 *
 * Literal Forge games execute in isolated child JVMs. A giant token board is
 * allowed to keep playing as long as Forge emits progress. A genuinely stalled
 * game is terminated with its worker process, queued for retry, and cannot
 * poison the rest of the 95-game tournament.
 */
public final class HouseMacApp {
    private static final String APP_VERSION = "0.9.2-endurance";
    private static final int STALL_TIMEOUT_SECONDS = 5 * 60;
    private static final int MAX_AUTOMATIC_ATTEMPTS = 3;
    private static final int SPECTATOR_REFRESH_MS = 1500;
    private static final String CHECKPOINT_FILE = "house-gauntlet-checkpoint.properties";

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HOUSE-Mac-Orchestrator");
        t.setDaemon(true);
        return t;
    });

    private JFrame frame;
    private JLabel preflightStatus;
    private JLabel engineStatus;
    private JLabel runStatus;
    private JTextArea spectatorArea;
    private JProgressBar progress;
    private JComboBox<PodChoice> podChoice;
    private JButton watchedButton;
    private JButton gauntletButton;
    private JButton pauseButton;
    private JButton preflightButton;
    private JButton resetCheckpointButton;

    private volatile boolean ready;
    private volatile boolean running;
    private volatile boolean pauseRequested;
    private volatile Path liveSnapshotPath;
    private volatile Process activeGameProcess;

    private HousePackage pack;
    private Path resourcesRoot;
    private Path appSupportRoot;
    private Path forgeRuntimeRoot;

    public static void main(String[] args) {
        if (args.length > 0 && "--smoke".equals(args[0])) {
            try {
                smoke();
                return;
            } catch (Throwable t) {
                t.printStackTrace(System.err);
                System.exit(1);
                return;
            }
        }

        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "HOUSE Commander Lab");

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Throwable ignored) {
                // Default Swing look-and-feel is sufficient.
            }
            new HouseMacApp().open();
        });
    }

    private void open() {
        frame = new JFrame("HOUSE Commander Lab");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (running) {
                    int choice = JOptionPane.showConfirmDialog(
                            frame,
                            "A literal Forge game is still running. Quit and stop that isolated game?",
                            "HOUSE Commander Lab",
                            JOptionPane.YES_NO_OPTION
                    );
                    if (choice != JOptionPane.YES_OPTION) {
                        return;
                    }
                    Process process = activeGameProcess;
                    if (process != null && process.isAlive()) {
                        process.destroyForcibly();
                    }
                }
                worker.shutdownNow();
                frame.dispose();
            }
        });

        JPanel root = new JPanel();
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("HOUSE Commander Lab");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 27f));
        title.setAlignmentX(0f);
        root.add(title);

        JLabel version = new JLabel(
                APP_VERSION
                        + " • isolated Forge workers • checkpoint/resume • literal outcomes"
        );
        version.setAlignmentX(0f);
        root.add(version);
        root.add(Box.createVerticalStrut(16));

        root.add(section("STRICT PREFLIGHT"));
        preflightStatus = new JLabel("Waiting to check HOUSE package…");
        preflightStatus.setAlignmentX(0f);
        root.add(preflightStatus);
        engineStatus = new JLabel("Waiting to initialize Forge…");
        engineStatus.setAlignmentX(0f);
        root.add(engineStatus);
        root.add(Box.createVerticalStrut(8));

        preflightButton = button("Run preflight again");
        preflightButton.addActionListener(e -> runPreflight());
        root.add(preflightButton);
        root.add(Box.createVerticalStrut(16));

        root.add(section("PLAY / WATCH"));
        podChoice = new JComboBox<>();
        podChoice.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        podChoice.setAlignmentX(0f);
        root.add(podChoice);
        root.add(Box.createVerticalStrut(8));

        watchedButton = button("Run selected pod as watched Forge game");
        watchedButton.addActionListener(e -> runSelectedPod());
        root.add(watchedButton);

        gauntletButton = button("Run 1 full HOUSE gauntlet");
        gauntletButton.addActionListener(e -> runGauntlet());
        root.add(gauntletButton);

        pauseButton = button("Pause after current game");
        pauseButton.addActionListener(e -> {
            pauseRequested = true;
            setRunStatus("Pause requested — current literal game will finish first");
        });
        root.add(pauseButton);

        resetCheckpointButton = button("Reset tournament checkpoint");
        resetCheckpointButton.addActionListener(e -> resetCheckpoint());
        root.add(resetCheckpointButton);

        JButton pilot = button("Pilot a deck vs AI — next build");
        pilot.setEnabled(false);
        root.add(pilot);

        root.add(Box.createVerticalStrut(16));
        root.add(section("LIVE SPECTATOR"));

        spectatorArea = new JTextArea("No live match");
        spectatorArea.setEditable(false);
        spectatorArea.setLineWrap(true);
        spectatorArea.setWrapStyleWord(true);
        spectatorArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane spectatorScroll = new JScrollPane(spectatorArea);
        spectatorScroll.setPreferredSize(new Dimension(760, 360));
        spectatorScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 520));
        spectatorScroll.setAlignmentX(0f);
        root.add(spectatorScroll);

        root.add(Box.createVerticalStrut(16));
        root.add(section("RUN STATUS"));
        runStatus = new JLabel("Idle");
        runStatus.setAlignmentX(0f);
        root.add(runStatus);

        progress = new JProgressBar(0, 95);
        progress.setStringPainted(true);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        progress.setAlignmentX(0f);
        root.add(progress);

        JLabel integrity = new JLabel(
                "<html><body style='width:720px'>Endurance rule: HOUSE never caps tokens, "
                        + "turns, or card effects. A progressing game has no practical wall-clock "
                        + "ceiling. A true 5-minute no-progress stall is isolated, retried, and "
                        + "cannot stop the rest of the tournament. Winners always come from Forge.</body></html>"
        );
        integrity.setAlignmentX(0f);
        root.add(Box.createVerticalStrut(10));
        root.add(integrity);

        frame.setLayout(new BorderLayout());
        frame.add(new JScrollPane(root), BorderLayout.CENTER);
        frame.setMinimumSize(new Dimension(780, 720));
        frame.setSize(920, 900);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        new Timer(SPECTATOR_REFRESH_MS, e -> refreshSpectator()).start();

        refreshButtons();
        runPreflight();
    }

    private JLabel section(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setAlignmentX(0f);
        label.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));
        return label;
    }

    private JButton button(String text) {
        JButton button = new JButton(text);
        button.setAlignmentX(0f);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        return button;
    }

    private void refreshSpectator() {
        Path snapshot = liveSnapshotPath;
        if (snapshot == null || !Files.isRegularFile(snapshot)) {
            return;
        }
        try {
            String value = Files.readString(snapshot, StandardCharsets.UTF_8);
            if (!value.trim().isEmpty()) {
                spectatorArea.setText(value);
                spectatorArea.setCaretPosition(0);
            }
        } catch (IOException ignored) {
            // Snapshot writes are best-effort and must never affect the game.
        }
    }

    private void runPreflight() {
        if (running) {
            return;
        }
        ready = false;
        refreshButtons();
        preflightStatus.setText("Checking bundled HOUSE package…");
        engineStatus.setText("Preparing Forge rules database…");

        worker.submit(() -> {
            try {
                initialize();
                SwingUtilities.invokeLater(() -> {
                    preflightStatus.setText("PASS • " + pack.validation().summary());
                    engineStatus.setText("PASS • " + ForgeBridge.status() + " • " + ForgeBridge.version());
                    populatePods();
                    ready = true;
                    updateCheckpointUi();
                    setRunStatus("Ready");
                    refreshButtons();
                });
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> {
                    ready = false;
                    preflightStatus.setText("BLOCKED • " + safeMessage(t));
                    engineStatus.setText("Forge unavailable");
                    setRunStatus("Preflight failed");
                    refreshButtons();
                });
            }
        });
    }

    private void initialize() throws Exception {
        resourcesRoot = locateResources();
        Path packageRoot = resourcesRoot.resolve("house19");
        if (!Files.isDirectory(packageRoot)) {
            throw new IOException("Bundled HOUSE package not found: " + packageRoot);
        }

        appSupportRoot = Path.of(
                System.getProperty("user.home"),
                "Library",
                "Application Support",
                "HOUSE Commander Lab"
        );
        Files.createDirectories(appSupportRoot);

        forgeRuntimeRoot = ensureRuntime(resourcesRoot, appSupportRoot);
        HouseForgeRuntime.initialize(forgeRuntimeRoot.toFile(), APP_VERSION);

        AssetSource assets = path -> Files.newInputStream(resourcesRoot.resolve(path));
        pack = HousePackageLoader.load(assets, "house19");

        if (!ForgeBridge.isAvailable()) {
            throw new IllegalStateException("Forge bridge did not become ready: " + ForgeBridge.status());
        }
    }

    private void populatePods() {
        podChoice.removeAllItems();
        List<PodSpec> pods = pack.schedule();
        for (int i = 0; i < pods.size(); i++) {
            PodSpec pod = pods.get(i);
            StringBuilder name = new StringBuilder();
            name.append(String.format("%02d", i + 1))
                    .append(" • R")
                    .append(pod.round())
                    .append(" P")
                    .append(pod.pod())
                    .append(" • ");
            for (int j = 0; j < pod.members().size(); j++) {
                if (j > 0) {
                    name.append(" / ");
                }
                name.append(pod.members().get(j));
            }
            podChoice.addItem(new PodChoice(i, name.toString()));
        }
        if (podChoice.getItemCount() > 0) {
            podChoice.setSelectedIndex(0);
        }
    }

    private void runSelectedPod() {
        if (!ready || running) {
            return;
        }
        PodChoice choice = (PodChoice) podChoice.getSelectedItem();
        if (choice == null) {
            return;
        }

        running = true;
        pauseRequested = false;
        progress.setMaximum(1);
        progress.setValue(0);
        refreshButtons();
        setRunStatus("Starting watched Forge game in isolated worker…");

        worker.submit(() -> {
            try {
                PodSpec pod = pack.schedule().get(choice.index);
                GameResult result = runWithRetries(
                        pod,
                        "watched-" + String.format("%03d", choice.index + 1),
                        MAX_AUTOMATIC_ATTEMPTS
                );
                String winner = canonicalWinner(result.winner);
                SwingUtilities.invokeLater(() -> {
                    progress.setValue(1);
                    setRunStatus(
                            "Winner: "
                                    + winner
                                    + " • attempt "
                                    + result.attempt
                                    + " • "
                                    + formatDuration(result.elapsedMs)
                    );
                });
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() ->
                        setRunStatus("BLOCKED after retries • " + safeMessage(t)));
            } finally {
                running = false;
                activeGameProcess = null;
                SwingUtilities.invokeLater(this::refreshButtons);
            }
        });
    }

    private void runGauntlet() {
        if (!ready || running) {
            return;
        }

        running = true;
        pauseRequested = false;
        progress.setMaximum(pack.schedule().size());
        refreshButtons();

        worker.submit(() -> {
            Map<Integer, String> completed = new LinkedHashMap<>();
            try {
                completed.putAll(loadCheckpoint());
                final int initialCompleted = completed.size();
                SwingUtilities.invokeLater(() -> {
                    progress.setValue(initialCompleted);
                    setRunStatus(
                            initialCompleted == 0
                                    ? "Starting 95-game Endurance gauntlet…"
                                    : "Resuming at "
                                            + initialCompleted
                                            + "/95 completed games…"
                    );
                });

                ArrayDeque<Integer> queue = new ArrayDeque<>();
                for (int i = 0; i < pack.schedule().size(); i++) {
                    if (!completed.containsKey(i)) {
                        queue.addLast(i);
                    }
                }

                Map<Integer, Integer> attempts = new LinkedHashMap<>();
                List<Integer> unresolved = new ArrayList<>();

                while (!queue.isEmpty()) {
                    if (pauseRequested) {
                        saveCheckpoint(completed);
                        final int done = completed.size();
                        SwingUtilities.invokeLater(() -> {
                            setRunStatus(
                                    "Paused safely • "
                                            + done
                                            + "/95 completed • checkpoint saved"
                            );
                            updateCheckpointUi();
                        });
                        return;
                    }

                    int index = queue.removeFirst();
                    int attempt = attempts.getOrDefault(index, 0) + 1;
                    attempts.put(index, attempt);
                    PodSpec pod = pack.schedule().get(index);

                    final int displayIndex = index + 1;
                    final int displayAttempt = attempt;
                    final int doneBefore = completed.size();
                    SwingUtilities.invokeLater(() ->
                            setRunStatus(
                                    "Game "
                                            + displayIndex
                                            + "/95 • attempt "
                                            + displayAttempt
                                            + " • completed "
                                            + doneBefore
                            ));

                    try {
                        GameResult result = runIsolatedPod(
                                pod,
                                "gauntlet-" + String.format("%03d", displayIndex)
                                        + "-try" + attempt,
                                attempt
                        );
                        String winner = canonicalWinner(result.winner);
                        completed.put(index, winner);
                        saveCheckpoint(completed);

                        final int done = completed.size();
                        final String displayWinner = winner;
                        final long elapsed = result.elapsedMs;
                        SwingUtilities.invokeLater(() -> {
                            progress.setValue(done);
                            setRunStatus(
                                    "Completed "
                                            + done
                                            + "/95 • game "
                                            + displayIndex
                                            + " winner: "
                                            + displayWinner
                                            + " • "
                                            + formatDuration(elapsed)
                            );
                            updateCheckpointUi();
                        });
                    } catch (Throwable failure) {
                        if (attempt < MAX_AUTOMATIC_ATTEMPTS) {
                            queue.addLast(index);
                            final String reason = safeMessage(failure);
                            SwingUtilities.invokeLater(() ->
                                    setRunStatus(
                                            "Game "
                                                    + displayIndex
                                                    + " isolated failure • retry "
                                                    + (displayAttempt + 1)
                                                    + " queued • "
                                                    + reason
                                    ));
                        } else {
                            unresolved.add(index);
                            final String reason = safeMessage(failure);
                            SwingUtilities.invokeLater(() ->
                                    setRunStatus(
                                            "Game "
                                                    + displayIndex
                                                    + " deferred after "
                                                    + MAX_AUTOMATIC_ATTEMPTS
                                                    + " attempts; tournament continues • "
                                                    + reason
                                    ));
                        }
                    }
                }

                Path results = writeGauntletResults(completed);
                if (completed.size() == pack.schedule().size()) {
                    Files.deleteIfExists(checkpointPath());
                    SwingUtilities.invokeLater(() -> {
                        progress.setValue(pack.schedule().size());
                        setRunStatus("Gauntlet complete • all 95 literal games finished • " + results);
                        updateCheckpointUi();
                    });
                } else {
                    saveCheckpoint(completed);
                    Path unresolvedFile = writeUnresolved(unresolved);
                    SwingUtilities.invokeLater(() -> {
                        progress.setValue(completed.size());
                        setRunStatus(
                                "Tournament pass complete • "
                                        + completed.size()
                                        + "/95 resolved • "
                                        + unresolved.size()
                                        + " deferred for next resume • "
                                        + unresolvedFile
                        );
                        updateCheckpointUi();
                    });
                }
            } catch (Throwable t) {
                try {
                    saveCheckpoint(completed);
                } catch (Throwable ignored) {
                    // Preserve original failure.
                }
                SwingUtilities.invokeLater(() -> {
                    setRunStatus("TOURNAMENT BLOCKED • checkpoint preserved • " + safeMessage(t));
                    updateCheckpointUi();
                });
            } finally {
                running = false;
                activeGameProcess = null;
                SwingUtilities.invokeLater(this::refreshButtons);
            }
        });
    }

    private GameResult runWithRetries(PodSpec pod, String logStem, int maxAttempts) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return runIsolatedPod(pod, logStem + "-try" + attempt, attempt);
            } catch (Exception e) {
                last = e;
                final int next = attempt + 1;
                if (attempt < maxAttempts) {
                    SwingUtilities.invokeLater(() ->
                            setRunStatus("Isolated game failed; retry " + next + " starting…"));
                }
            }
        }
        throw last == null ? new IllegalStateException("Game failed without a result") : last;
    }

    private GameResult runIsolatedPod(PodSpec pod, String logStem, int attempt) throws Exception {
        String[] deckPaths = deckPaths(pod);

        Path runDir = appSupportRoot.resolve("runs").resolve(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        + "-"
                        + UUID.randomUUID().toString().substring(0, 8)
        );
        Files.createDirectories(runDir);

        Path resultFile = runDir.resolve("result.properties");
        Path snapshotFile = runDir.resolve("spectator.txt");
        Path workerLog = runDir.resolve("worker.log");
        Path gameLog = runDir.resolve(logStem + ".log");
        liveSnapshotPath = snapshotFile;

        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                "java"
        );
        if (!Files.isExecutable(javaExecutable)) {
            throw new IOException("Bundled Java executable not found: " + javaExecutable);
        }

        int heapMb = workerHeapMb();
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Xmx" + heapMb + "m");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dhouse.resources=" + resourcesRoot.toAbsolutePath());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("com.housecommander.mac.HouseGameWorker");
        command.add(forgeRuntimeRoot.toAbsolutePath().toString());
        command.add(resultFile.toString());
        command.add(snapshotFile.toString());
        command.add(gameLog.toString());
        command.add(String.valueOf(STALL_TIMEOUT_SECONDS));
        Collections.addAll(command, deckPaths);

        SwingUtilities.invokeLater(() ->
                setRunStatus(
                        "Forge worker running • "
                                + heapMb
                                + " MB heap • no practical hard timeout • attempt "
                                + attempt
                ));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(workerLog.toFile());

        long started = System.currentTimeMillis();
        Process process = builder.start();
        activeGameProcess = process;

        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            if (activeGameProcess == process) {
                activeGameProcess = null;
            }
        }

        long elapsed = System.currentTimeMillis() - started;
        if (!Files.isRegularFile(resultFile)) {
            throw new IOException(
                    "Forge worker exited "
                            + exit
                            + " without a result. Diagnostics: "
                            + workerLog
            );
        }

        Properties result = new Properties();
        try (InputStream in = Files.newInputStream(resultFile)) {
            result.load(in);
        }

        String status = result.getProperty("status", "");
        if ("WINNER".equals(status)) {
            String winner = result.getProperty("winner", "").trim();
            if (winner.isEmpty()) {
                throw new IOException("Forge worker returned an empty winner");
            }
            long workerElapsed = parseLong(result.getProperty("elapsedMs"), elapsed);
            return new GameResult(winner, workerElapsed, attempt);
        }

        String errorClass = result.getProperty("errorClass", "ForgeWorkerError");
        String message = result.getProperty("message", "Unknown isolated Forge failure");
        throw new IOException(
                errorClass
                        + ": "
                        + message
                        + " • worker diagnostics: "
                        + workerLog
        );
    }

    private String[] deckPaths(PodSpec pod) throws IOException {
        String[] deckPaths = new String[pod.members().size()];
        Path houseRoot = resourcesRoot.resolve("house19").normalize();

        for (int i = 0; i < pod.members().size(); i++) {
            DeckSpec deck = pack.deckNamed(pod.members().get(i));
            if (deck == null) {
                throw new IOException("Deck not found in manifest: " + pod.members().get(i));
            }
            Path file = houseRoot.resolve(deck.dck()).normalize();
            if (!file.startsWith(houseRoot) || !Files.isRegularFile(file)) {
                throw new IOException("Deck file missing: " + file);
            }
            deckPaths[i] = file.toAbsolutePath().toString();
        }
        return deckPaths;
    }

    private int workerHeapMb() {
        String configured = System.getProperty("house.worker.heap.mb", "4096");
        try {
            int value = Integer.parseInt(configured);
            return Math.max(2048, Math.min(12288, value));
        } catch (NumberFormatException ignored) {
            return 4096;
        }
    }

    private String canonicalWinner(String winner) {
        String key = Names.canonical(winner);
        for (DeckSpec deck : pack.decks()) {
            if (Names.canonical(deck.deck()).equals(key)) {
                return deck.deck();
            }
            Path file = Path.of(deck.dck()).getFileName();
            if (file != null) {
                String fileName = file.toString();
                if (Names.canonical(fileName).equals(key)) {
                    return deck.deck();
                }
                int dot = fileName.lastIndexOf('.');
                String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
                if (Names.canonical(stem).equals(key)) {
                    return deck.deck();
                }
            }
        }
        throw new IllegalStateException("Forge winner is not a member of HOUSE manifest: " + winner);
    }

    private Path checkpointPath() {
        return appSupportRoot.resolve("results").resolve(CHECKPOINT_FILE);
    }

    private Map<Integer, String> loadCheckpoint() throws IOException {
        Map<Integer, String> completed = new LinkedHashMap<>();
        Path file = checkpointPath();
        if (!Files.isRegularFile(file)) {
            return completed;
        }

        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }

        int podCount = Integer.parseInt(properties.getProperty("podCount", "-1"));
        int deckCount = Integer.parseInt(properties.getProperty("deckCount", "-1"));
        if (podCount != pack.schedule().size() || deckCount != pack.decks().size()) {
            throw new IOException(
                    "Checkpoint belongs to a different HOUSE package; reset checkpoint before running"
            );
        }

        for (int i = 0; i < pack.schedule().size(); i++) {
            String winner = properties.getProperty("completed." + i);
            if (winner != null && !winner.trim().isEmpty()) {
                completed.put(i, canonicalWinner(winner));
            }
        }
        return completed;
    }

    private void saveCheckpoint(Map<Integer, String> completed) throws IOException {
        if (appSupportRoot == null || pack == null) {
            return;
        }

        Path file = checkpointPath();
        Files.createDirectories(file.getParent());
        Properties properties = new Properties();
        properties.setProperty("format", "1");
        properties.setProperty("appVersion", APP_VERSION);
        properties.setProperty("deckCount", String.valueOf(pack.decks().size()));
        properties.setProperty("podCount", String.valueOf(pack.schedule().size()));
        properties.setProperty("completedCount", String.valueOf(completed.size()));

        for (Map.Entry<Integer, String> entry : completed.entrySet()) {
            properties.setProperty("completed." + entry.getKey(), entry.getValue());
        }

        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            properties.store(out, "HOUSE Commander Lab tournament checkpoint");
        }
        moveAtomicBestEffort(temp, file);
    }

    private void resetCheckpoint() {
        if (running || appSupportRoot == null) {
            return;
        }

        Path file = checkpointPath();
        if (!Files.exists(file)) {
            setRunStatus("No tournament checkpoint to reset");
            return;
        }

        int choice = JOptionPane.showConfirmDialog(
                frame,
                "Reset the saved HOUSE tournament checkpoint? Completed games in that checkpoint "
                        + "will no longer be resumable.",
                "Reset tournament checkpoint",
                JOptionPane.YES_NO_OPTION
        );
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            Files.deleteIfExists(file);
            progress.setValue(0);
            setRunStatus("Tournament checkpoint reset");
            updateCheckpointUi();
        } catch (IOException e) {
            setRunStatus("Could not reset checkpoint • " + safeMessage(e));
        }
    }

    private void updateCheckpointUi() {
        if (gauntletButton == null || resetCheckpointButton == null) {
            return;
        }

        int completed = 0;
        boolean exists = false;
        try {
            Path file = checkpointPath();
            exists = Files.isRegularFile(file);
            if (exists) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
                completed = Integer.parseInt(p.getProperty("completedCount", "0"));
            }
        } catch (Throwable ignored) {
            // UI label only; preflight/load will report actual checkpoint errors.
        }

        gauntletButton.setText(
                exists
                        ? "Resume HOUSE gauntlet (" + completed + "/95 completed)"
                        : "Run 1 full HOUSE gauntlet (95 literal games)"
        );
        resetCheckpointButton.setEnabled(exists && !running);
    }

    private Path writeGauntletResults(Map<Integer, String> completed) throws IOException {
        Map<String, Integer> wins = new LinkedHashMap<>();
        Map<String, Integer> games = new LinkedHashMap<>();

        for (Map.Entry<Integer, String> entry : completed.entrySet()) {
            PodSpec pod = pack.schedule().get(entry.getKey());
            for (String member : pod.members()) {
                String canonicalMember = pack.deckNamed(member).deck();
                games.put(canonicalMember, games.getOrDefault(canonicalMember, 0) + 1);
            }
            String winner = canonicalWinner(entry.getValue());
            wins.put(winner, wins.getOrDefault(winner, 0) + 1);
        }

        Path dir = appSupportRoot.resolve("results");
        Files.createDirectories(dir);
        Path file = dir.resolve(
                "house-gauntlet-"
                        + completed.size()
                        + "of"
                        + pack.schedule().size()
                        + "-"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        + ".csv"
        );

        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("deck,wins,completed_games");
            out.newLine();
            for (DeckSpec deck : pack.decks()) {
                String name = deck.deck();
                out.write(csv(name));
                out.write(",");
                out.write(String.valueOf(wins.getOrDefault(name, 0)));
                out.write(",");
                out.write(String.valueOf(games.getOrDefault(name, 0)));
                out.newLine();
            }
        }
        return file;
    }

    private Path writeUnresolved(List<Integer> unresolved) throws IOException {
        Path dir = appSupportRoot.resolve("results");
        Files.createDirectories(dir);
        Path file = dir.resolve(
                "house-gauntlet-deferred-"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        + ".txt"
        );

        List<String> lines = new ArrayList<>();
        lines.add("HOUSE Commander Lab " + APP_VERSION);
        lines.add("Games deferred after " + MAX_AUTOMATIC_ATTEMPTS + " automatic attempts:");
        for (Integer index : unresolved) {
            PodSpec pod = pack.schedule().get(index);
            lines.add(
                    String.format(
                            "%02d • Round %d Pod %d • %s",
                            index + 1,
                            pod.round(),
                            pod.pod(),
                            String.join(" / ", pod.members())
                    )
            );
        }
        lines.add("Resume the gauntlet to retry only unresolved games; completed games stay checkpointed.");
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    private void refreshButtons() {
        boolean enabled = ready && !running;
        watchedButton.setEnabled(enabled);
        gauntletButton.setEnabled(enabled);
        podChoice.setEnabled(enabled);
        preflightButton.setEnabled(!running);
        pauseButton.setEnabled(running);
        if (resetCheckpointButton != null) {
            resetCheckpointButton.setEnabled(enabled && Files.isRegularFile(checkpointPathSafe()));
        }
    }

    private Path checkpointPathSafe() {
        if (appSupportRoot == null) {
            return Path.of(System.getProperty("java.io.tmpdir"), "house-no-checkpoint");
        }
        return checkpointPath();
    }

    private void setRunStatus(String text) {
        runStatus.setText(text);
    }

    private static Path locateResources() throws IOException {
        String configured = System.getProperty("house.resources", "").trim();
        if (!configured.isEmpty()) {
            Path root = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(root)) {
                return root;
            }
        }

        Path cwd = Path.of(".").toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve("house19"))) {
            return cwd;
        }

        throw new IOException(
                "HOUSE resources directory not found. Launch the packaged HOUSE Commander Lab.app."
        );
    }

    private static Path ensureRuntime(Path resources, Path appSupport) throws IOException {
        Path zip = resources.resolve("house-forge-runtime.zip");
        Path idFile = resources.resolve("house-forge-runtime.id");
        if (!Files.isRegularFile(zip) || !Files.isRegularFile(idFile)) {
            throw new IOException("Bundled Forge runtime files are missing");
        }

        String id = Files.readString(idFile, StandardCharsets.UTF_8).trim();
        if (id.isEmpty()) {
            throw new IOException("Bundled Forge runtime ID is empty");
        }

        Path runtimeBase = appSupport.resolve("runtime");
        Path runtime = runtimeBase.resolve(id.substring(0, Math.min(id.length(), 24)));
        Path marker = runtime.resolve(".complete");

        if (Files.isRegularFile(marker)) {
            return runtime;
        }

        Files.createDirectories(runtimeBase);
        Path temp = runtimeBase.resolve("extract-" + System.nanoTime());
        Files.createDirectories(temp);

        try (ZipInputStream in = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(zip)),
                StandardCharsets.UTF_8
        )) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path dest = temp.resolve(entry.getName()).normalize();
                if (!dest.startsWith(temp)) {
                    throw new IOException("Unsafe path in HOUSE runtime archive: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Path parent = dest.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                in.closeEntry();
            }
        } catch (Throwable t) {
            deleteTree(temp);
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException("Could not extract Forge runtime", t);
        }

        Files.writeString(temp.resolve(".complete"), id + "\n", StandardCharsets.UTF_8);

        if (Files.exists(runtime)) {
            deleteTree(runtime);
        }
        Files.move(temp, runtime, StandardCopyOption.REPLACE_EXISTING);
        return runtime;
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            Path[] paths = stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .toArray(Path[]::new);
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
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

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String formatDuration(long elapsedMs) {
        long totalSeconds = Math.max(0L, elapsedMs / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0
                ? String.format("%dh %02dm %02ds", hours, minutes, seconds)
                : String.format("%dm %02ds", minutes, seconds);
    }

    private static Throwable rootCause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        Throwable root = rootCause(t);
        String message = root.getMessage();
        String value = message == null || message.trim().isEmpty()
                ? root.getClass().getSimpleName()
                : message;
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void smoke() throws Exception {
        Path resources = locateResources();
        Path temp = Files.createTempDirectory("house-mac-smoke");
        try {
            Path runtime = ensureRuntime(resources, temp);
            HouseForgeRuntime.initialize(runtime.toFile(), APP_VERSION + "-smoke");

            AssetSource assets = path -> Files.newInputStream(resources.resolve(path));
            HousePackage pack = HousePackageLoader.load(assets, "house19");
            if (!ForgeBridge.isAvailable()) {
                throw new IllegalStateException(ForgeBridge.status());
            }

            if (pack.decks().size() != 19 || pack.schedule().size() != 95) {
                throw new IllegalStateException(
                        "HOUSE package counts are wrong: decks="
                                + pack.decks().size()
                                + ", pods="
                                + pack.schedule().size()
                );
            }

            System.out.println("HOUSE_MAC_SMOKE=PASS");
            System.out.println("HOUSE_MAC_PREFLIGHT=" + pack.validation().summary());
            System.out.println("HOUSE_MAC_ENGINE=" + ForgeBridge.version());
            System.out.println("HOUSE_MAC_ENDURANCE=isolated-workers,checkpoint,retry,5m-stall");
        } finally {
            deleteTree(temp);
        }
    }

    private static final class GameResult {
        private final String winner;
        private final long elapsedMs;
        private final int attempt;

        private GameResult(String winner, long elapsedMs, int attempt) {
            this.winner = winner;
            this.elapsedMs = elapsedMs;
            this.attempt = attempt;
        }
    }

    private static final class PodChoice {
        private final int index;
        private final String label;

        private PodChoice(int index, String label) {
            this.index = index;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
