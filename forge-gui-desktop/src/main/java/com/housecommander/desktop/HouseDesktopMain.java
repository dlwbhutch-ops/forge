package com.housecommander.desktop;

import com.housecommander.core.DeckFileSnapshot;
import com.housecommander.core.DeckSpec;
import com.housecommander.core.DeckVersion;
import com.housecommander.core.HousePackage;
import com.housecommander.core.RosterBuilder;
import com.housecommander.forgebridge.ForgeBridge;
import com.housecommander.forgebridge.LiveGameState;
import com.housecommander.forgebridge.PilotDecision;
import com.housecommander.forgebridge.PilotDecisionBridge;
import com.housecommander.forgebridge.SpectatorCardGroup;

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
import javax.swing.JTable;
import javax.swing.JTabbedPane;
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
import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HouseDesktopMain extends JFrame implements DesktopTournamentRunner.Listener {
    private int podCount = 95;
    private String podRosterFingerprint = "";

    private final DesktopDeckLibraryStore libraryStore = new DesktopDeckLibraryStore();
    private final DesktopTournamentRunner runner = new DesktopTournamentRunner(this);

    private final JLabel librarySummary = new JLabel("Loading Deck Library…");
    private final JLabel engineStatus = new JLabel("Forge engine: starting…");
    private final JLabel runStatus = new JLabel("Ready");
    private final JTextArea rosterText = new JTextArea();
    private final JProgressBar progress = new JProgressBar(0, 95);
    private final DefaultTableModel resultsModel = new DefaultTableModel(
            new Object[]{"Rank", "Deck", "Wins", "Games", "Win Rate"},
            0
    ) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };

    private final JButton importButton = new JButton("Import .dck / ManaBox .txt");
    private final JButton manageButton = new JButton("Manage Deck Library");
    private final JButton rosterButton = new JButton("Select Tournament Roster");
    private final JButton restoreButton = new JButton("Restore Default HOUSE Roster");
    private final JButton testButton = new JButton("Run 1 Literal Test Game");
    private final JButton oneButton = new JButton("Run / Resume 1 Gauntlet");
    private final JButton fiveHundredButton = new JButton("Run / Resume 500 Gauntlets");
    private final JButton pauseButton = new JButton("Pause After Current Game");
    private final JButton resetButton = new JButton("Reset Tournament");
    private final JButton folderButton = new JButton("Open HOUSE Data Folder");
    private final JButton watchButton = new JButton("Run & Watch 1 Literal Game");
    private final JButton playButton = new JButton("Start Pilot Game vs 3 AI");
    private final JLabel playStatus = new JLabel("Pilot mode ready");
    private long shownPilotDecisionId = -1L;
    private boolean pilotDialogOpen;
    private final JLabel watchStatus = new JLabel("Spectator board ready");
    private final JPanel watchBoard = new JPanel(new GridLayout(2, 2, 8, 8));
    private final JTextArea watchStack = new JTextArea();
    private final JTextArea watchLog = new JTextArea();

    private final Timer refreshTimer;

    public HouseDesktopMain() {
        super("HOUSE Commander Lab 0.15");
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
                "Desktop 0.15 • Play vs AI • Unified HOUSE Lab"
        );
        header.add(title, BorderLayout.NORTH);
        header.add(version, BorderLayout.CENTER);
        header.add(engineStatus, BorderLayout.SOUTH);
        root.add(header, BorderLayout.NORTH);

        JTabbedPane modes = new JTabbedPane();
        modes.addTab("Decks", buildDecksPanel());
        modes.addTab("Tournament", buildTournamentPanel());
        modes.addTab("Results", buildResultsPanel());
        modes.addTab("Watch", buildWatchPanel());
        modes.addTab("Play", buildPlayPanel());
        root.add(modes, BorderLayout.CENTER);

        JLabel footer = new JLabel(
                "One app • one Forge engine • one deck library • one results/checkpoint store"
        );
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildDecksPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        librarySummary.setFont(librarySummary.getFont().deriveFont(Font.BOLD));
        panel.add(librarySummary, BorderLayout.NORTH);

        rosterText.setEditable(false);
        rosterText.setLineWrap(false);
        rosterText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(rosterText), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(0, 1, 6, 6));
        buttons.add(importButton);
        buttons.add(manageButton);
        buttons.add(rosterButton);
        buttons.add(restoreButton);
        buttons.add(folderButton);
        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    private JPanel buildTournamentPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel buttons = new JPanel(new GridLayout(0, 2, 6, 6));
        buttons.add(testButton);
        buttons.add(oneButton);
        buttons.add(fiveHundredButton);
        buttons.add(pauseButton);
        buttons.add(resetButton);
        panel.add(buttons, BorderLayout.NORTH);

        JPanel live = new JPanel(new BorderLayout(6, 6));
        runStatus.setFont(runStatus.getFont().deriveFont(Font.BOLD));
        live.add(runStatus, BorderLayout.NORTH);
        progress.setStringPainted(true);
        live.add(progress, BorderLayout.CENTER);
        panel.add(live, BorderLayout.CENTER);

        JTextArea note = new JTextArea(
                "Literal Forge only. HOUSE preserves checkpoints after every game and "
                        + "never substitutes matchup scores or guessed winners."
        );
        note.setEditable(false);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setOpaque(false);
        panel.add(note, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildResultsPanel() {
        JTable resultsTable = new JTable(resultsModel);
        resultsTable.setAutoCreateRowSorter(true);
        resultsTable.getColumnModel().getColumn(0).setPreferredWidth(45);
        resultsTable.getColumnModel().getColumn(1).setPreferredWidth(280);
        resultsTable.getColumnModel().getColumn(2).setPreferredWidth(65);
        resultsTable.getColumnModel().getColumn(3).setPreferredWidth(65);
        resultsTable.getColumnModel().getColumn(4).setPreferredWidth(85);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildWatchPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel controls = new JPanel(new BorderLayout(8, 8));
        watchStatus.setFont(watchStatus.getFont().deriveFont(Font.BOLD));
        controls.add(watchStatus, BorderLayout.CENTER);
        controls.add(watchButton, BorderLayout.EAST);
        panel.add(controls, BorderLayout.NORTH);

        watchBoard.setBorder(BorderFactory.createTitledBorder("Literal Forge Battlefield"));
        panel.add(watchBoard, BorderLayout.CENTER);

        watchStack.setEditable(false);
        watchStack.setLineWrap(true);
        watchStack.setWrapStyleWord(true);
        watchStack.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        watchLog.setEditable(false);
        watchLog.setLineWrap(false);
        watchLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JTabbedPane diagnostics = new JTabbedPane();
        diagnostics.addTab("Stack", new JScrollPane(watchStack));
        diagnostics.addTab("Forge Log", new JScrollPane(watchLog));
        diagnostics.setPreferredSize(new Dimension(900, 210));
        panel.add(diagnostics, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildPlayPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));

        JTextArea description = new JTextArea(
                "Choose one deck from the active HOUSE roster and pilot it against "
                        + "three Forge AI opponents. HOUSE asks you to choose legal "
                        + "priority actions and yes/no decisions; Forge still handles "
                        + "mana sequencing and detailed target plumbing. Keep the Watch "
                        + "tab open whenever you want the live battlefield."
        );
        description.setEditable(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setOpaque(false);
        panel.add(description, BorderLayout.NORTH);

        JPanel controls = new JPanel(new BorderLayout(8, 8));
        playStatus.setFont(playStatus.getFont().deriveFont(Font.BOLD));
        controls.add(playStatus, BorderLayout.NORTH);
        controls.add(playButton, BorderLayout.CENTER);
        panel.add(controls, BorderLayout.CENTER);

        JTextArea note = new JTextArea(
                "0.15 Assisted Pilot: you choose what to cast/play/activate or when "
                        + "to pass priority. Complex targeting and payment sub-decisions "
                        + "remain Forge-assisted in this release."
        );
        note.setEditable(false);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setOpaque(false);
        panel.add(note, BorderLayout.SOUTH);
        return panel;
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
        watchButton.addActionListener(event -> runner.runOneLiteralGame());
        playButton.addActionListener(event -> startPilotGame());
    }

    private void startPilotGame() {
        try {
            HousePackage template = HouseDesktopRuntime.loadTemplatePackage();
            List<DeckSpec> roster = libraryStore.loadRoster(template);
            JList<DeckSpec> list = new JList<DeckSpec>(
                    roster.toArray(new DeckSpec[0])
            );
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new DeckRenderer(libraryStore));
            if (!roster.isEmpty()) {
                list.setSelectedIndex(0);
            }

            JScrollPane pane = new JScrollPane(list);
            pane.setPreferredSize(new Dimension(620, 420));
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    pane,
                    "Choose Your Commander Deck",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE
            );
            if (choice != JOptionPane.OK_OPTION || list.getSelectedValue() == null) {
                return;
            }

            DeckSpec pilot = list.getSelectedValue();
            shownPilotDecisionId = -1L;
            playStatus.setText("Starting " + pilot.deck() + " vs 3 Forge AI…");
            runner.runPilotGame(pilot);
        } catch (Throwable error) {
            showError("Pilot game could not start", error);
        }
    }

    private void importDeck() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import Forge or ManaBox Commander Deck");
        chooser.setFileFilter(new FileNameExtensionFilter("Commander deck (*.dck, *.txt)", "dck", "txt"));
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
        chooser.setDialogTitle("Replace " + deck.deck() + " from .dck or ManaBox .txt");
        chooser.setFileFilter(new FileNameExtensionFilter("Commander deck (*.dck, *.txt)", "dck", "txt"));
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
                    "Select Tournament Roster • 4 or more decks",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE
            );
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }

            List<DeckSpec> roster = list.getSelectedValuesList();
            if (roster.size() < RosterBuilder.MIN_ROSTER_SIZE) {
                JOptionPane.showMessageDialog(
                        this,
                        "You selected "
                                + roster.size()
                                + " decks. HOUSE requires at least "
                                + RosterBuilder.MIN_ROSTER_SIZE
                                + ". There is no fixed maximum.",
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
                "Restore the bundled default 19-deck roster?\n"
                        + "Imported decks will remain in the Deck Library.",
                "Restore Default HOUSE Roster",
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
        refreshWatch();
        refreshPilotDecision();
    }

    private void refreshLibrary() {
        try {
            HousePackage template = HouseDesktopRuntime.loadTemplatePackage();
            List<DeckSpec> library = libraryStore.allDecks(template);
            List<DeckSpec> roster = libraryStore.loadRoster(template);
            librarySummary.setText(
                    library.size()
                            + " decks in library • "
                            + roster.size()
                            + " active • no fixed maximum"
            );

            String fingerprint = RosterBuilder.fingerprint(roster);
            if (!fingerprint.equals(podRosterFingerprint)) {
                HousePackage activePackage =
                        RosterBuilder.build(template, roster);
                podCount = activePackage.schedule().size();
                podRosterFingerprint = fingerprint;
                progress.setMaximum(Math.max(1, podCount));
            }

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
        progress.setMaximum(Math.max(1, podCount));
        progress.setValue(Math.min(podCount, Math.max(0, state.nextPodIndex)));
        progress.setString(
                "Gauntlet " + state.currentGauntlet
                        + " • next pod "
                        + (state.nextPodIndex >= podCount
                        ? "summary"
                        : (state.nextPodIndex + 1) + "/" + podCount)
                        + " • literal games " + state.totalGames
        );

        boolean running = runner.isActive()
                || "RUNNING".equals(state.status)
                || "TESTING".equals(state.status)
                || "PILOTING".equals(state.status);
        importButton.setEnabled(!running);
        manageButton.setEnabled(!running);
        rosterButton.setEnabled(!running);
        restoreButton.setEnabled(!running);
        testButton.setEnabled(!running && ForgeBridge.isAvailable());
        oneButton.setEnabled(!running && ForgeBridge.isAvailable());
        fiveHundredButton.setEnabled(!running && ForgeBridge.isAvailable());
        oneButton.setText(
                "Run / Resume 1 Gauntlet (" + podCount + " games)"
        );
        fiveHundredButton.setText(
                "Run / Resume 500 Gauntlets ("
                        + ((long) podCount * 500L)
                        + " games)"
        );
        pauseButton.setEnabled(running);
        resetButton.setEnabled(!running);
        watchButton.setEnabled(!running && ForgeBridge.isAvailable());
        playButton.setEnabled(!running && ForgeBridge.isAvailable());
        if ("PILOTING".equals(state.status) || "PILOT_COMPLETE".equals(state.status)) {
            playStatus.setText(state.lastMessage);
        } else if (!running) {
            playStatus.setText("Pilot mode ready");
        }
    }

    private void refreshPilotDecision() {
        PilotDecision decision = PilotDecisionBridge.current();
        if (!decision.pending()) {
            return;
        }
        if (pilotDialogOpen || decision.id() == shownPilotDecisionId) {
            return;
        }

        pilotDialogOpen = true;
        shownPilotDecisionId = decision.id();
        playStatus.setText(
                decision.player() + " • " + decision.prompt()
        );

        try {
            Object[] options = decision.options().toArray(new Object[0]);
            int selected = JOptionPane.showOptionDialog(
                    this,
                    decision.prompt(),
                    "HOUSE Pilot • " + decision.player(),
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    options,
                    options.length == 0 ? null : options[0]
            );
            if (selected < 0) {
                selected = decision.kind() == PilotDecision.Kind.ACTION
                        ? Math.max(0, options.length - 1)
                        : Math.min(1, Math.max(0, options.length - 1));
            }
            PilotDecisionBridge.submit(decision.id(), selected);
        } finally {
            pilotDialogOpen = false;
        }
    }

    private void refreshWatch() {
        DesktopStateStore.State run = runner.state();
        LiveGameState live = ForgeBridge.liveGameState();

        if (live.sequence() > 1L) {
            String winner = live.winner().isEmpty() ? "" : " • winner " + live.winner();
            watchStatus.setText(
                    "Turn " + live.turn()
                            + " • " + live.phase()
                            + (live.activePlayer().isEmpty()
                            ? ""
                            : " • active " + live.activePlayer())
                            + " • " + live.lastEvent()
                            + winner
            );
        } else if (runner.isActive() && "TESTING".equals(run.status)) {
            watchStatus.setText("LIVE • Forge is starting the literal game");
        } else {
            watchStatus.setText("Spectator board ready");
        }

        watchBoard.removeAll();
        if (live.players().isEmpty()) {
            JPanel waiting = new JPanel(new BorderLayout());
            waiting.add(
                    new JLabel(
                            "<html><center>Run & Watch a literal Forge game.<br>"
                                    + "The four-player battlefield will appear here.</center></html>",
                            JLabel.CENTER
                    ),
                    BorderLayout.CENTER
            );
            watchBoard.add(waiting);
        } else {
            for (LiveGameState.PlayerState player : live.players()) {
                watchBoard.add(buildPlayerBoard(player, live.activePlayer()));
            }
        }
        watchBoard.revalidate();
        watchBoard.repaint();

        if (live.stack().isEmpty()) {
            watchStack.setText("Stack empty");
        } else {
            StringBuilder stackText = new StringBuilder();
            int i = 1;
            for (String item : live.stack()) {
                stackText.append(i++).append(". ").append(item).append("\n");
            }
            watchStack.setText(stackText.toString());
            watchStack.setCaretPosition(0);
        }

        try {
            String logName = "PILOTING".equals(run.status)
                    || "PILOT_COMPLETE".equals(run.status)
                    ? "desktop-pilot-game.log"
                    : "desktop-test-game.log";
            File log = new File(
                    HouseDesktopPaths.logsDir(),
                    logName
            );
            if (!log.isFile()) {
                return;
            }

            String text = Files.readString(log.toPath());
            int max = 24000;
            if (text.length() > max) {
                text = "… earlier log omitted …\n" + text.substring(text.length() - max);
            }
            watchLog.setText(text);
            watchLog.setCaretPosition(watchLog.getDocument().getLength());
        } catch (Throwable ignored) {
            // Forge may be writing this diagnostics file; retry next refresh.
        }
    }

    private JPanel buildPlayerBoard(
            LiveGameState.PlayerState player,
            String activePlayer
    ) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        boolean active = player.name().equals(activePlayer);
        String status = player.lost() ? " • OUT" : (active ? " • ACTIVE" : "");
        JLabel header = new JLabel(
                player.name()
                        + status
                        + "   ♥ "
                        + player.life()
                        + "   ☠ "
                        + player.poison()
                        + "   Hand "
                        + player.handCount()
                        + "   Library "
                        + player.libraryCount()
        );
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        panel.add(header, BorderLayout.NORTH);

        List<SpectatorCardGroup> groups =
                SpectatorCardGroup.group(player.battlefield());

        JPanel battlefield = new JPanel(new GridLayout(3, 1, 4, 4));
        battlefield.add(buildPermanentSection("Creatures", groups, 0));
        battlefield.add(buildPermanentSection("Lands", groups, 1));
        battlefield.add(buildPermanentSection("Other permanents", groups, 2));

        JScrollPane battlefieldScroll = new JScrollPane(battlefield);
        battlefieldScroll.setBorder(BorderFactory.createEmptyBorder());
        panel.add(battlefieldScroll, BorderLayout.CENTER);

        JTextArea zones = new JTextArea();
        zones.setEditable(false);
        zones.setLineWrap(true);
        zones.setWrapStyleWord(true);
        zones.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        zones.setRows(5);
        zones.setText(
                "Commander: " + zoneSummary(player.commanders(), 3)
                        + "\nCommand zone: " + zoneSummary(player.command(), 4)
                        + "\nGraveyard (" + player.graveyard().size() + "): "
                        + zoneSummary(player.graveyard(), 5)
                        + "\nExile (" + player.exile().size() + "): "
                        + zoneSummary(player.exile(), 5)
                        + "\nBattlefield: "
                        + player.battlefield().size()
                        + " permanents • "
                        + groups.size()
                        + " rendered piles"
        );
        panel.add(zones, BorderLayout.SOUTH);

        String borderTitle = player.lost()
                ? "ELIMINATED"
                : (active ? "ACTIVE TURN" : "PLAYER");
        panel.setBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createEtchedBorder(),
                        borderTitle
                )
        );
        return panel;
    }

    private JPanel buildPermanentSection(
            String title,
            List<SpectatorCardGroup> groups,
            int category
    ) {
        JPanel section = new JPanel(new BorderLayout(3, 3));
        section.setBorder(BorderFactory.createTitledBorder(title));

        JPanel tiles = new JPanel(new GridLayout(0, 4, 4, 4));
        int added = 0;
        for (SpectatorCardGroup group : groups) {
            if (!matchesCategory(group.card(), category)) {
                continue;
            }
            tiles.add(buildCardTile(group));
            added++;
        }
        if (added == 0) {
            tiles.add(new JLabel("—", JLabel.CENTER));
        }
        section.add(tiles, BorderLayout.CENTER);
        return section;
    }

    private JPanel buildCardTile(SpectatorCardGroup group) {
        LiveGameState.CardState card = group.card();
        JPanel tile = new JPanel(new BorderLayout(3, 3));
        tile.setBorder(BorderFactory.createEtchedBorder());
        tile.setPreferredSize(new Dimension(130, 74));

        String count = group.count() > 1 ? " ×" + group.count() : "";
        JLabel name = new JLabel(
                "<html><center>"
                        + escapeHtml(card.name())
                        + count
                        + "</center></html>",
                JLabel.CENTER
        );
        name.setFont(name.getFont().deriveFont(Font.BOLD, 11f));
        tile.add(name, BorderLayout.CENTER);

        String details = cardDetail(card);
        JLabel state = new JLabel(
                "<html><center>" + escapeHtml(details) + "</center></html>",
                JLabel.CENTER
        );
        state.setFont(state.getFont().deriveFont(10f));
        tile.add(state, BorderLayout.SOUTH);
        tile.setToolTipText(card.name() + " — " + details);
        return tile;
    }

    private static boolean matchesCategory(
            LiveGameState.CardState card,
            int category
    ) {
        if (category == 0) {
            return card.creature();
        }
        if (category == 1) {
            return !card.creature() && card.land();
        }
        return !card.creature() && !card.land();
    }

    private static String cardDetail(LiveGameState.CardState card) {
        StringBuilder out = new StringBuilder();
        if (card.creature()) {
            out.append(card.power()).append("/").append(card.toughness());
        }
        if (card.tapped()) {
            appendDetail(out, "TAPPED");
        }
        if (card.attacking()) {
            appendDetail(out, "ATTACK");
        }
        if (card.blocking()) {
            appendDetail(out, "BLOCK");
        }
        if (card.token()) {
            appendDetail(out, "TOKEN");
        }
        if (!card.counters().isEmpty()) {
            appendDetail(out, String.join(", ", card.counters()));
        }
        return out.length() == 0 ? "ready" : out.toString();
    }

    private static void appendDetail(StringBuilder out, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            out.append(" • ");
        }
        out.append(value);
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String zoneSummary(List<String> cards, int limit) {
        if (cards == null || cards.isEmpty()) {
            return "—";
        }
        StringBuilder out = new StringBuilder();
        int start = Math.max(0, cards.size() - Math.max(1, limit));
        for (int i = start; i < cards.size(); i++) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(cards.get(i));
        }
        if (start > 0) {
            out.insert(0, "… ");
        }
        return out.toString();
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
