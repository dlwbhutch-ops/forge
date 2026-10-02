package com.housecommander.mac;

import com.housecommander.core.AssetSource;
import com.housecommander.core.DeckSpec;
import com.housecommander.core.HousePackage;
import com.housecommander.core.HousePackageLoader;
import com.housecommander.core.Names;
import com.housecommander.core.PodSpec;
import com.housecommander.forgebridge.ForgeBridge;
import com.housecommander.forgebridge.HouseForgeRuntime;
import com.housecommander.forgebridge.HouseSpectatorState;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Native desktop shell for HOUSE Commander Lab on macOS.
 *
 * Forge remains the literal rules/AI engine. This class only provides a Swing
 * control surface and reads immutable spectator snapshots published by the
 * bridge. No winner is guessed when Forge fails or stalls.
 */
public final class HouseMacApp {
    private static final String APP_VERSION = "0.9.1-mac-selfcontained";
    private static final int HARD_TIMEOUT_SECONDS = 60 * 60;
    private static final int STALL_TIMEOUT_SECONDS = 3 * 60;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HOUSE-Mac-Worker");
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

    private volatile boolean ready;
    private volatile boolean running;
    private volatile boolean pauseRequested;

    private HousePackage pack;
    private Path resourcesRoot;
    private Path appSupportRoot;

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
                            "A Forge game is still running. Quit anyway?",
                            "HOUSE Commander Lab",
                            JOptionPane.YES_NO_OPTION
                    );
                    if (choice != JOptionPane.YES_OPTION) {
                        return;
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

        JLabel version = new JLabel(APP_VERSION + " • macOS native Java shell • literal Forge engine");
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

        gauntletButton = button("Run 1 full HOUSE gauntlet (95 literal games)");
        gauntletButton.addActionListener(e -> runGauntlet());
        root.add(gauntletButton);

        pauseButton = button("Pause after current game");
        pauseButton.addActionListener(e -> {
            pauseRequested = true;
            setRunStatus("Pause requested — current literal game will finish first");
        });
        root.add(pauseButton);

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
                "<html><body style='width:720px'>Integrity rule: HOUSE uses literal Forge outcomes only. "
                        + "A timeout, draw, unsupported card, or engine failure is reported as a failure; "
                        + "HOUSE does not invent a winner.</body></html>"
        );
        integrity.setAlignmentX(0f);
        root.add(Box.createVerticalStrut(10));
        root.add(integrity);

        frame.setLayout(new BorderLayout());
        frame.add(new JScrollPane(root), BorderLayout.CENTER);
        frame.setMinimumSize(new Dimension(780, 720));
        frame.setSize(900, 880);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        new Timer(500, e -> {
            spectatorArea.setText(HouseSpectatorState.snapshot());
            spectatorArea.setCaretPosition(0);
            refreshButtons();
        }).start();

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

        Path runtimeRoot = ensureRuntime(resourcesRoot, appSupportRoot);
        HouseForgeRuntime.initialize(runtimeRoot.toFile(), APP_VERSION);

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
        setRunStatus("Starting watched Forge game…");

        worker.submit(() -> {
            try {
                PodSpec pod = pack.schedule().get(choice.index);
                String winner = playPod(pod, "watched-" + String.format("%03d", choice.index + 1));
                SwingUtilities.invokeLater(() -> {
                    progress.setValue(1);
                    setRunStatus("Winner: " + winner);
                });
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> setRunStatus("BLOCKED • " + safeMessage(t)));
            } finally {
                running = false;
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
        progress.setValue(0);
        refreshButtons();
        setRunStatus("Starting 95-game literal HOUSE gauntlet…");

        worker.submit(() -> {
            Map<String, Integer> wins = new LinkedHashMap<>();
            Map<String, Integer> games = new LinkedHashMap<>();
            int completed = 0;
            try {
                for (int i = 0; i < pack.schedule().size(); i++) {
                    if (pauseRequested) {
                        final int done = completed;
                        SwingUtilities.invokeLater(() ->
                                setRunStatus("Paused after " + done + " verified literal games"));
                        return;
                    }

                    PodSpec pod = pack.schedule().get(i);
                    String winner = playPod(pod, "gauntlet-" + String.format("%03d", i + 1));
                    String canonical = canonicalWinner(winner);

                    for (String member : pod.members()) {
                        games.put(member, games.getOrDefault(member, 0) + 1);
                    }
                    wins.put(canonical, wins.getOrDefault(canonical, 0) + 1);
                    completed++;

                    final int done = completed;
                    final String displayWinner = canonical;
                    SwingUtilities.invokeLater(() -> {
                        progress.setValue(done);
                        setRunStatus("Game " + done + "/95 • winner: " + displayWinner);
                    });
                }

                Path results = writeGauntletResults(wins, games);
                SwingUtilities.invokeLater(() ->
                        setRunStatus("Gauntlet complete • results: " + results));
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> setRunStatus("BLOCKED • " + safeMessage(t)));
            } finally {
                running = false;
                SwingUtilities.invokeLater(this::refreshButtons);
            }
        });
    }

    private String playPod(PodSpec pod, String logStem) throws Exception {
        String[] deckPaths = new String[pod.members().size()];
        for (int i = 0; i < pod.members().size(); i++) {
            DeckSpec deck = pack.deckNamed(pod.members().get(i));
            if (deck == null) {
                throw new IOException("Deck not found in manifest: " + pod.members().get(i));
            }
            Path file = resourcesRoot.resolve("house19").resolve(deck.dck()).normalize();
            if (!file.startsWith(resourcesRoot.resolve("house19").normalize()) || !Files.isRegularFile(file)) {
                throw new IOException("Deck file missing: " + file);
            }
            deckPaths[i] = file.toAbsolutePath().toString();
        }

        Path logDir = appSupportRoot.resolve("logs");
        Files.createDirectories(logDir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path log = logDir.resolve(stamp + "-" + logStem + ".log");

        SwingUtilities.invokeLater(() ->
                setRunStatus("Forge playing R" + pod.round() + " P" + pod.pod() + "…"));

        return ForgeBridge.runCommanderGame(
                deckPaths,
                log.toAbsolutePath().toString(),
                HARD_TIMEOUT_SECONDS,
                STALL_TIMEOUT_SECONDS
        );
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

    private Path writeGauntletResults(Map<String, Integer> wins, Map<String, Integer> games) throws IOException {
        Path dir = appSupportRoot.resolve("results");
        Files.createDirectories(dir);
        Path file = dir.resolve(
                "house-gauntlet-"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        + ".csv"
        );

        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("deck,wins,games");
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

    private void refreshButtons() {
        boolean enabled = ready && !running;
        watchedButton.setEnabled(enabled);
        gauntletButton.setEnabled(enabled);
        podChoice.setEnabled(enabled);
        preflightButton.setEnabled(!running);
        pauseButton.setEnabled(running);
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

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
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
        } finally {
            deleteTree(temp);
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
