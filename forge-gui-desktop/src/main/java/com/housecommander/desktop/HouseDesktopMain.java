package com.housecommander.desktop;

import com.housecommander.core.DeckFileSnapshot;
import com.housecommander.core.DeckSpec;
import com.housecommander.core.DeckVersion;
import com.housecommander.core.HousePackage;
import com.housecommander.core.RosterBuilder;
import com.housecommander.forgebridge.ForgeBridge;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HouseDesktopMain extends JFrame implements DesktopTournamentRunner.Listener {
    private static final int POD_COUNT = 95;

    private final DesktopDeckLibraryStore libraryStore = new DesktopDeckLibraryStore();
    private final DesktopTournamentRunner runner = new DesktopTournamentRunner(this);

    private final JLabel librarySummary = new JLabel("Loading Deck Library…");
    private final JLabel engineStatus = new JLabel("Forge engine: starting…");
    private final JLabel runStatus = new JLabel("Ready");
    private final JTextArea rosterText = new JTextArea();
    private final JProgressBar progress = new JProgressBar(0, POD_COUNT);
    private final DefaultTableModel resultsModel = new DefaultTableModel(
            new Object[]{"Rank", "Deck", "Wins", "Games", "Win Rate"},
            0
    ) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };

    private final JButton importButton = new JButton("Import .dck Deck");
    private final JButton manageButton = new JButton("Manage Deck Library");
    private final JButton rosterButton = new JButton("Select Tournament Roster");
    private final JButton restoreButton = new JButton("Restore Bundled HOUSE 19");
    private final JButton testButton = new JButton("Run 1 Literal Test Game");
    private final JButton oneButton = new JButton("Run / Resume 1 Gauntlet");
    private final JButton fiveHundredButton = new JButton("Run / Resume 500 Gauntlets");
    private final JButton pauseButton = new JButton("Pause After Current Game");
    private final JButton resetButton = new JButton("Reset Tournament");
    private final JButton folderButton = new JButton("Open HOUSE Data Folder");

    private final Timer refreshTimer;

    public HouseDesktopMain() {
        super("HOUSE Commander Lab 0.10");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 720));
        setPreferredSize(new Dimension(1180, 820));
        setLocationByPlatform(true);

        setContentPane(buildUi());
        wireActions();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                runner.shutdown();
                dispose();
            }
        });

        refreshTimer = new Timer(1000, event -> refreshAll());
        refreshTimer.start();

        pack();
        refreshAll();
        runner.bootstrapEngine();
    }

    private JPanel buildUi() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JPanel header = new JPanel(new BorderLayout(10, 6));
        JLabel title = new JLabel("HOUSE Commander Lab");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));
        JLabel version = new JLabel(
                "Desktop 0.10 • Deck Details + Version Management • Mac + Windows"
        );
        header.add(title, BorderLayout.NORTH);
        header.add(version, BorderLayout.CENTER);
        header.add(engineStatus, BorderLayout.SOUTH);
        root.add(header, BorderLayout.NORTH);

        JPanel libraryPanel = new JPanel(new BorderLayout(8, 8));
        libraryPanel.setBorder(BorderFactory.createTitledBorder("Deck Library → Tournament Roster"));
        librarySummary.setFont(librarySummary.getFont().deriveFont(Font.BOLD));
        libraryPanel.add(librarySummary, BorderLayout.NORTH);

        rosterText.setEditable(false);
        rosterText.setLineWrap(false);
        rosterText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        libraryPanel.add(new JScrollPane(rosterText), BorderLayout.CENTER);

        JPanel libraryButtons = new JPanel(new GridLayout(0, 1, 6, 6));
        libraryButtons.add(importButton);
        libraryButtons.add(manageButton);
        libraryButtons.add(rosterButton);
        libraryButtons.add(restoreButton);
        libraryButtons.add(folderButton);
        libraryPanel.add(libraryButtons, BorderLayout.EAST);

        JPanel tournamentPanel = new JPanel(new BorderLayout(8, 8));
        tournamentPanel.setBorder(BorderFactory.createTitledBorder("Literal Forge Tournament"));

        JPanel runButtons = new JPanel(new GridLayout(0, 2, 6, 6));
        runButtons.add(testButton);
        runButtons.add(oneButton);
        runButtons.add(fiveHundredButton);
        runButtons.add(pauseButton);
        runButtons.add(resetButton);
        tournamentPanel.add(runButtons, BorderLayout.NORTH);

        JPanel live = new JPanel(new BorderLayout(6, 6));
        runStatus.setFont(runStatus.getFont().deriveFont(Font.BOLD));
        live.add(runStatus, BorderLayout.NORTH);
        progress.setStringPainted(true);
        live.add(progress, BorderLayout.SOUTH);
        tournamentPanel.add(live, BorderLayout.CENTER);

        JTable resultsTable = new JTable(resultsModel);
        resultsTable.setAutoCreateRowSorter(true);
        resultsTable.getColumnModel().getColumn(0).setPreferredWidth(45);
        resultsTable.getColumnModel().getColumn(1).setPreferredWidth(280);
        resultsTable.getColumnModel().getColumn(2).setPreferredWidth(65);
        resultsTable.getColumnModel().getColumn(3).setPreferredWidth(65);
        resultsTable.getColumnModel().getColumn(4).setPreferredWidth(85);

        JPanel resultsPanel = new JPanel(new BorderLayout());
        resultsPanel.setBorder(BorderFactory.createTitledBorder("Results"));
        resultsPanel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);

        JSplitPane vertical = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                libraryPanel,
                tournamentPanel
        );
        vertical.setResizeWeight(0.66d);

        JSplitPane main = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                vertical,
                resultsPanel
        );
        main.setResizeWeight(0.62d);
        root.add(main, BorderLayout.CENTER);

        JLabel footer = new JLabel(
                "HOUSE data is stored in ~/.house-commander-lab • roster stays 19 decks; library may grow"
        );
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private void wireActions() {
        importButton.addActionListener(event -> importDeck());
        manageButton.addActionListener(event -> manageLibrary());
        rosterButton.addActionListener(event -> selectRoster());
        restoreButton.addActionListener(event -> restoreDefaultRoster());
        folderButton.addActionListener(event -> openDataFolder());
        testButton.addActionListener(event -> runner.runOneLiteralGame());
        oneButton.addActionListener(event -> runner.runTournament(1));
        fiveHundredButton.addActionListener(event -> runner.runTournament(500));
        pauseButton.addActionListener(event -> runner.requestPause());
        resetButton.addActionListener(event -> resetTournament());
    }

    private void importDeck() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import Forge Commander Deck");
        chooser.setFileFilter(new FileNameExtensionFilter("Forge deck (*.dck)", "dck"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        try {
            HousePackage template = HouseDesktopRuntime.loadTemplatePackage();
            DeckSpec imported = libraryStore.importDeck(template, chooser.getSelectedFile());
            JOptionPane.showMessageDialog(
                    this,
                    imported.deck() + "\n"
                            + (imported.commanders().isEmpty()
                            ? "Commander metadata not found"
                            : imported.commanders())
                            + "\n\nValidated as a 100-card Forge Commander deck.",
                    "Deck Imported",
                    JOptionPane.INFORMATION_MESSAGE
            );
            refreshAll();
        } catch (Throwable error) {
            showError("Import blocked", error);
        }
    }

    private void manageLibrary() {
        while (true) {
            try {
                HousePackage template = HouseDesktopRuntime.loadTemplatePackage();
                List<DeckSpec> library = libraryStore.allDecks(template);
                JList<DeckSpec> list = new JList<DeckSpec>(library.toArray(new DeckSpec[0]));
                list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
                list.setCellRenderer(new DeckRenderer(libraryStore));
                if (!library.isEmpty()) {
                    list.setSelectedIndex(0);
                }

                JScrollPane pane = new JScrollPane(list);
                pane.setPreferredSize(new Dimension(700, 500));
                Object[] options = {"View Details", "Replace / Update", "Version History", "Remove", "Close"};
                int choice = JOptionPane.showOptionDialog(
                        this,
                        pane,
                        "Manage Deck Library",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.PLAIN_MESSAGE,
                        null,
                        options,
                        options[0]
                );
                if (choice < 0 || choice == 4) {
                    return;
                }

                DeckSpec selected = list.getSelectedValue();
                if (selected == null) {
                    JOptionPane.showMessageDialog(
                            this,
                            "Select a deck first.",
                            "No Deck Selected",
                            JOptionPane.WARNING_MESSAGE
                    );
                    continue;
                }

                if (choice == 0) {
                    showDeckDetails(selected);
                } else if (choice == 1) {
                    replaceDeck(template, selected);
                } else if (choice == 2) {
                    showVersionHistory(template, selected);
                } else if (choice == 3) {
                    removeDeck(template, selected);
                }
                refreshAll();
            } catch (Throwable error) {
                showError("Deck Library unavailable", error);
                return;
            }
        }
    }

    private void showDeckDetails(DeckSpec deck) {
        try {
            DeckFileSnapshot snapshot = libraryStore.snapshot(deck);
            List<DeckVersion> versions = libraryStore.history(deck);
            JTextArea text = new JTextArea();
            text.setEditable(false);
            text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            text.setText(
                    deck.deck() + "\n"
                            + "Source: " + deck.source() + "\n"
                            + "Forge name: " + deck.engineName() + "\n"
                            + "Commander(s): "
                            + (snapshot.commanderText().isEmpty()
                            ? "(metadata not found)"
                            : snapshot.commanderText())
                            + "\nCards: " + snapshot.cardCount()
                            + "\nSaved prior versions: " + versions.size()
                            + "\n\n"
                            + snapshot.formattedDeckList()
            );
            text.setCaretPosition(0);
            JScrollPane pane = new JScrollPane(text);
            pane.setPreferredSize(new Dimension(760, 620));
            JOptionPane.showMessageDialog(
                    this,
                    pane,
                    deck.deck() + " • Exact Deck",
                    JOptionPane.PLAIN_MESSAGE
            );
        } catch (Throwable error) {
            showError("Deck details unavailable", error);
        }
    }

    private void replaceDeck(HousePackage template, DeckSpec deck) {
        if (!libraryStore.isImported(deck)) {
            JOptionPane.showMessageDialog(
                    this,
                    "Bundled HOUSE decks are read-only. Import a modified copy if you want a managed version.",
                    "Bundled Deck",
                    JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }
        try {
            if (runner.hasTournamentArtifacts() && libraryStore.isActive(template, deck)) {
                JOptionPane.showMessageDialog(
                        this,
                        "This deck is in the active tournament roster. Reset the tournament before replacing it, "
                                + "so the saved roster fingerprint still matches the literal results.",
                        "Tournament Roster Locked",
                        JOptionPane.WARNING_MESSAGE
                );
                return;
            }
        } catch (Throwable error) {
            showError("Could not verify roster", error);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Replace " + deck.deck());
        chooser.setFileFilter(new FileNameExtensionFilter("Forge deck (*.dck)", "dck"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            DeckSpec updated = libraryStore.replaceImportedDeck(
                    template,
                    deck,
                    chooser.getSelectedFile()
            );
            JOptionPane.showMessageDialog(
                    this,
                    updated.deck() + " updated.\nThe prior .dck was saved in version history.",
                    "Deck Updated",
                    JOptionPane.INFORMATION_MESSAGE
            );
        } catch (Throwable error) {
            showError("Deck update blocked", error);
        }
    }

    private void showVersionHistory(HousePackage template, DeckSpec deck) {
        if (!libraryStore.isImported(deck)) {
            JOptionPane.showMessageDialog(
                    this,
                    "Bundled HOUSE decks do not have managed version history.",
                    "No Managed History",
                    JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        try {
            List<DeckVersion> versions = libraryStore.history(deck);
            if (versions.isEmpty()) {
                JOptionPane.showMessageDialog(
                        this,
                        "No archived versions yet. The first Replace / Update operation will create one.",
                        "Version History",
                        JOptionPane.INFORMATION_MESSAGE
                );
                return;
            }

            String[] labels = new String[versions.size()];
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            for (int i = 0; i < versions.size(); i++) {
                DeckVersion version = versions.get(i);
                labels[i] = format.format(new Date(version.savedAtMillis()))
                        + " • "
                        + version.engineName()
                        + " • "
                        + version.source();
            }

            JList<String> list = new JList<String>(labels);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setSelectedIndex(0);
            JScrollPane pane = new JScrollPane(list);
            pane.setPreferredSize(new Dimension(700, 340));
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    pane,
                    "Restore an archived version of " + deck.deck() + "?",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE
            );
            if (choice != JOptionPane.OK_OPTION || list.getSelectedIndex() < 0) {
                return;
            }
            if (runner.hasTournamentArtifacts() && libraryStore.isActive(template, deck)) {
                JOptionPane.showMessageDialog(
                        this,
                        "Reset the current tournament before restoring a version of an active roster deck.",
                        "Tournament Roster Locked",
                        JOptionPane.WARNING_MESSAGE
                );
                return;
            }

            DeckVersion version = versions.get(list.getSelectedIndex());
            int confirm = JOptionPane.showConfirmDialog(
                    this,
                    "Restore the version saved "
                            + format.format(new Date(version.savedAtMillis()))
                            + "?\nThe current deck will be archived first.",
                    "Confirm Version Restore",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (confirm != JOptionPane.OK_OPTION) {
                return;
            }
            libraryStore.restoreVersion(template, deck, version);
            JOptionPane.showMessageDialog(
                    this,
                    "Version restored. The deck's current pre-restore state is also preserved in history.",
                    "Version Restored",
                    JOptionPane.INFORMATION_MESSAGE
            );
        } catch (Throwable error) {
            showError("Version history unavailable", error);
        }
    }

    private void removeDeck(HousePackage template, DeckSpec deck) {
        if (!libraryStore.isImported(deck)) {
            JOptionPane.showMessageDialog(
                    this,
                    "Bundled HOUSE decks cannot be removed from the library.",
                    "Bundled Deck",
                    JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }
        int choice = JOptionPane.showConfirmDialog(
                this,
                "Remove " + deck.deck() + " from the Deck Library?\n"
                        + "Its current .dck and archived versions will be deleted.\n"
                        + "An active-roster deck cannot be removed.",
                "Remove Imported Deck",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            libraryStore.removeImportedDeck(template, deck);
            JOptionPane.showMessageDialog(
                    this,
                    deck.deck() + " was removed from the Deck Library.",
                    "Deck Removed",
                    JOptionPane.INFORMATION_MESSAGE
            );
        } catch (Throwable error) {
            showError("Deck removal blocked", error);
        }
    }

    private void selectRoster() {
        if (runner.hasTournamentArtifacts()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Reset the current tournament before changing the active roster.\n"
                            + "Imported decks remain safely in the Deck Library.",
                    "Roster Locked",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        try {
            HousePackage template = HouseDesktopRuntime.loadTemplatePackage();
            List<DeckSpec> library = libraryStore.allDecks(template);
            List<DeckSpec> active = libraryStore.loadRoster(template);
            Set<String> activeNames = new HashSet<String>();
            for (DeckSpec deck : active) {
                activeNames.add(deck.deck());
            }

            JList<DeckSpec> list = new JList<DeckSpec>(
                    library.toArray(new DeckSpec[0])
            );
            list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            list.setCellRenderer(new DeckRenderer(libraryStore));

            List<Integer> selected = new ArrayList<Integer>();
            for (int i = 0; i < library.size(); i++) {
                if (activeNames.contains(library.get(i).deck())) {
                    selected.add(i);
                }
            }
            int[] indices = new int[selected.size()];
            for (int i = 0; i < selected.size(); i++) {
                indices[i] = selected.get(i);
            }
            list.setSelectedIndices(indices);

            JScrollPane pane = new JScrollPane(list);
            pane.setPreferredSize(new Dimension(620, 520));
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    pane,
                    "Select exactly 19 tournament decks",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE
            );
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }

            List<DeckSpec> roster = list.getSelectedValuesList();
            if (roster.size() != RosterBuilder.HOUSE_ROSTER_SIZE) {
                JOptionPane.showMessageDialog(
                        this,
                        "You selected " + roster.size()
                                + " decks. HOUSE requires exactly "
                                + RosterBuilder.HOUSE_ROSTER_SIZE + ".",
                        "Roster Not Saved",
                        JOptionPane.WARNING_MESSAGE
                );
                return;
            }
            libraryStore.saveRoster(template, roster);
            refreshAll();
        } catch (Throwable error) {
            showError("Roster unavailable", error);
        }
    }

    private void restoreDefaultRoster() {
        if (runner.hasTournamentArtifacts()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Reset the current tournament before restoring the bundled roster.",
                    "Roster Locked",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }
        int choice = JOptionPane.showConfirmDialog(
                this,
                "Restore the bundled HOUSE 19?\n"
                        + "Imported decks will remain in the Deck Library.",
                "Restore Bundled HOUSE 19",
                JOptionPane.OK_CANCEL_OPTION
        );
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            libraryStore.restoreDefaultRoster();
            refreshAll();
        } catch (Throwable error) {
            showError("Roster restore failed", error);
        }
    }

    private void resetTournament() {
        if (runner.isActive()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Pause the active run before resetting.",
                    "Reset Blocked",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }
        int choice = JOptionPane.showConfirmDialog(
                this,
                "Reset the tournament checkpoint, results CSV, and literal game logs?\n"
                        + "The Deck Library and active roster are preserved.",
                "Reset HOUSE Tournament",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            runner.reset();
            refreshAll();
        } catch (Throwable error) {
            showError("Reset failed", error);
        }
    }

    private void openDataFolder() {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IllegalStateException("Desktop file browser integration is unavailable");
            }
            Desktop.getDesktop().open(HouseDesktopPaths.home());
        } catch (Throwable error) {
            showError("Could not open HOUSE data folder", error);
        }
    }

    private void refreshAll() {
        refreshLibrary();
        refreshState();
        refreshResults();
    }

    private void refreshLibrary() {
        try {
            HousePackage template = HouseDesktopRuntime.loadTemplatePackage();
            List<DeckSpec> library = libraryStore.allDecks(template);
            List<DeckSpec> roster = libraryStore.loadRoster(template);
            librarySummary.setText(
                    library.size() + " decks in library • "
                            + roster.size() + "/19 active"
            );

            StringBuilder text = new StringBuilder();
            text.append("ACTIVE HOUSE ROSTER\n\n");
            for (int i = 0; i < roster.size(); i++) {
                DeckSpec deck = roster.get(i);
                text.append(String.format("%2d. %s", i + 1, deck.deck()));
                if (libraryStore.isImported(deck)) {
                    text.append("  [imported]");
                }
                if (deck.commanders() != null && !deck.commanders().trim().isEmpty()) {
                    text.append("\n    ").append(deck.commanders());
                }
                text.append("\n");
            }
            rosterText.setText(text.toString());
            rosterText.setCaretPosition(0);
        } catch (Throwable error) {
            librarySummary.setText("Deck Library blocked • " + safeMessage(error));
        }
    }

    private void refreshState() {
        DesktopStateStore.State state = runner.state();
        runStatus.setText(state.status + " • " + state.lastMessage);
        progress.setValue(Math.min(POD_COUNT, Math.max(0, state.nextPodIndex)));
        progress.setString(
                "Gauntlet " + state.currentGauntlet
                        + " • next pod "
                        + (state.nextPodIndex >= POD_COUNT
                        ? "summary"
                        : (state.nextPodIndex + 1) + "/" + POD_COUNT)
                        + " • literal games " + state.totalGames
        );

        boolean running = runner.isActive()
                || "RUNNING".equals(state.status)
                || "TESTING".equals(state.status);
        importButton.setEnabled(!running);
        manageButton.setEnabled(!running);
        rosterButton.setEnabled(!running);
        restoreButton.setEnabled(!running);
        testButton.setEnabled(!running && ForgeBridge.isAvailable());
        oneButton.setEnabled(!running && ForgeBridge.isAvailable());
        fiveHundredButton.setEnabled(!running && ForgeBridge.isAvailable());
        pauseButton.setEnabled(running);
        resetButton.setEnabled(!running);
    }

    private void refreshResults() {
        try {
            List<DesktopResultsWriter.Standing> standings = DesktopResultsWriter.aggregate();
            resultsModel.setRowCount(0);
            int rank = 1;
            for (DesktopResultsWriter.Standing standing : standings) {
                resultsModel.addRow(new Object[]{
                        rank++,
                        standing.deck,
                        standing.wins,
                        standing.games,
                        String.format("%.2f%%", standing.rate * 100.0d)
                });
            }
        } catch (Throwable ignored) {
            // Live tournament state remains more important than a transient table refresh.
        }
    }

    @Override
    public void onState(DesktopStateStore.State state) {
        SwingUtilities.invokeLater(this::refreshAll);
    }

    @Override
    public void onEngineStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            engineStatus.setText("Forge engine: " + status);
            refreshState();
        });
    }

    private void showError(String title, Throwable error) {
        JOptionPane.showMessageDialog(
                this,
                safeMessage(error),
                title,
                JOptionPane.ERROR_MESSAGE
        );
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
        return message == null || message.trim().isEmpty()
                ? root.getClass().getSimpleName()
                : message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static final class DeckRenderer extends DefaultListCellRenderer {
        private final DesktopDeckLibraryStore store;

        DeckRenderer(DesktopDeckLibraryStore store) {
            this.store = store;
        }

        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean selected,
                boolean focus
        ) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof DeckSpec) {
                DeckSpec deck = (DeckSpec) value;
                setText(deck.deck()
                        + (store.isImported(deck) ? "  • imported" : "  • bundled")
                        + (deck.commanders() == null || deck.commanders().trim().isEmpty()
                        ? ""
                        : "  — " + deck.commanders()));
            }
            return this;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                System.setProperty("apple.laf.useScreenMenuBar", "true");
                HouseDesktopMain frame = new HouseDesktopMain();
                frame.setVisible(true);
            } catch (Throwable error) {
                JOptionPane.showMessageDialog(
                        null,
                        safeMessage(error),
                        "HOUSE Commander Lab failed to start",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        });
    }
}
