package com.housecommander.lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.ScrollView;
import android.widget.TextView;

import com.housecommander.core.DeckFileSnapshot;
import com.housecommander.core.DeckSpec;
import com.housecommander.core.DeckVersion;
import com.housecommander.core.HousePackage;
import com.housecommander.core.Names;
import com.housecommander.core.RosterBuilder;
import com.housecommander.lab.state.ResultsWriter;
import com.housecommander.lab.state.RunState;
import com.housecommander.lab.state.StateStore;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** UI adapter for cross-platform HOUSE Deck Library and roster management. */
public final class DeckLibraryController {
    public static final int REQUEST_IMPORT_DCK = 2201;
    public static final int REQUEST_REPLACE_DCK = 2202;

    public interface Callback {
        void onLibraryChanged();
    }

    private final Activity activity;
    private final Callback callback;
    private String pendingReplaceDeckName;

    public DeckLibraryController(Activity activity, Callback callback) {
        if (activity == null) {
            throw new IllegalArgumentException("Activity must not be null");
        }
        this.activity = activity;
        this.callback = callback;
    }

    public void openImporter() {
        openDocument(REQUEST_IMPORT_DCK);
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_IMPORT_DCK && requestCode != REQUEST_REPLACE_DCK) {
            return false;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQUEST_REPLACE_DCK) {
                pendingReplaceDeckName = null;
            }
            return true;
        }

        Uri uri = data.getData();
        String displayName = queryDisplayName(uri);
        if (requestCode == REQUEST_IMPORT_DCK) {
            importFromUri(uri, displayName);
        } else {
            replaceFromUri(uri, displayName);
        }
        return true;
    }

    public void showLibraryManager() {
        try {
            final HousePackage template = HouseRuntime.loadTemplatePackage(activity);
            final DeckLibraryStore store = new DeckLibraryStore(activity);
            final List<DeckSpec> library = store.allDecks(template);
            final CharSequence[] labels = new CharSequence[library.size()];
            for (int i = 0; i < library.size(); i++) {
                DeckSpec deck = library.get(i);
                labels[i] = deck.deck()
                        + (store.isImported(deck) ? "  • imported" : "  • bundled")
                        + commanderSuffix(deck);
            }

            new AlertDialog.Builder(activity)
                    .setTitle("Manage Deck Library")
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            showDeckActions(template, library.get(which));
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
        } catch (Throwable t) {
            showError("Deck Library unavailable", safeMessage(t));
        }
    }

    public void showRosterPicker() {
        if (hasTournamentArtifacts()) {
            showError(
                    "Reset tournament first",
                    "The active roster is locked to the current checkpoint/results. "
                            + "Use Reset tournament checkpoint before changing the active HOUSE roster."
            );
            return;
        }

        try {
            final HousePackage template = HouseRuntime.loadTemplatePackage(activity);
            final DeckLibraryStore store = new DeckLibraryStore(activity);
            final List<DeckSpec> library = store.allDecks(template);
            final List<DeckSpec> active = store.loadRoster(template);
            final Set<String> activeNames = new HashSet<String>();
            for (DeckSpec deck : active) {
                activeNames.add(Names.canonical(deck.deck()));
            }

            final CharSequence[] labels = new CharSequence[library.size()];
            final boolean[] checked = new boolean[library.size()];
            for (int i = 0; i < library.size(); i++) {
                DeckSpec d = library.get(i);
                String suffix = store.isImported(d) ? "  • imported" : "  • bundled";
                labels[i] = d.deck() + suffix + commanderSuffix(d);
                checked[i] = activeNames.contains(Names.canonical(d.deck()));
            }

            new AlertDialog.Builder(activity)
                    .setTitle("Select Tournament Roster • 4 or more decks")
                    .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                            checked[which] = isChecked;
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save roster", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            List<DeckSpec> selected = new ArrayList<DeckSpec>();
                            for (int i = 0; i < checked.length; i++) {
                                if (checked[i]) {
                                    selected.add(library.get(i));
                                }
                            }
                            if (selected.size() < RosterBuilder.MIN_ROSTER_SIZE) {
                                showError(
                                        "Roster not saved",
                                        "You selected "
                                                + selected.size()
                                                + " decks. HOUSE requires at least "
                                                + RosterBuilder.MIN_ROSTER_SIZE
                                                + ". There is no fixed maximum."
                                );
                                return;
                            }
                            try {
                                store.saveRoster(template, selected);
                                notifyChanged();
                            } catch (Throwable t) {
                                showError("Roster not saved", safeMessage(t));
                            }
                        }
                    })
                    .show();
        } catch (Throwable t) {
            showError("Roster unavailable", safeMessage(t));
        }
    }

    public void confirmRestoreDefaultRoster() {
        if (hasTournamentArtifacts()) {
            showError(
                    "Reset tournament first",
                    "The active roster is locked to the current checkpoint/results. "
                            + "Reset the tournament before restoring the bundled HOUSE 19."
            );
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Restore bundled HOUSE 19?")
                .setMessage("Imported decks stay in the Deck Library; only the active tournament roster changes.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            new DeckLibraryStore(activity).restoreDefaultRoster();
                            notifyChanged();
                        } catch (Throwable t) {
                            showError("Roster restore failed", safeMessage(t));
                        }
                    }
                })
                .show();
    }

    public String summary() throws Exception {
        HousePackage template = HouseRuntime.loadTemplatePackage(activity);
        DeckLibraryStore store = new DeckLibraryStore(activity);
        int librarySize = store.allDecks(template).size();
        int rosterSize = store.loadRoster(template).size();
        return librarySize + " decks in library • " + rosterSize + " active • no fixed maximum";
    }

    public String details() throws Exception {
        HousePackage template = HouseRuntime.loadTemplatePackage(activity);
        DeckLibraryStore store = new DeckLibraryStore(activity);
        List<DeckSpec> active = store.loadRoster(template);
        StringBuilder out = new StringBuilder("Active HOUSE roster:\n");
        for (int i = 0; i < active.size(); i++) {
            DeckSpec d = active.get(i);
            out.append(i + 1).append(". ").append(d.deck());
            if (store.isImported(d)) {
                out.append("  [imported]");
            }
            if (i + 1 < active.size()) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private void importFromUri(Uri uri, String displayName) {
        try {
            HousePackage template = HouseRuntime.loadTemplatePackage(activity);
            DeckSpec imported;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IllegalStateException("Could not open the selected deck file");
                }
                imported = new DeckLibraryStore(activity).importDeck(template, in, displayName);
            }
            String commander = imported.commanders() == null || imported.commanders().trim().isEmpty()
                    ? "Commander metadata not found"
                    : imported.commanders();
            new AlertDialog.Builder(activity)
                    .setTitle("Deck imported")
                    .setMessage(imported.deck() + "\n" + commander
                            + "\n\nValidated as a 100-card Forge Commander deck.")
                    .setPositiveButton("OK", null)
                    .show();
            notifyChanged();
        } catch (Throwable t) {
            showError("Import blocked", safeMessage(t));
        }
    }

    private void replaceFromUri(Uri uri, String displayName) {
        String deckName = pendingReplaceDeckName;
        pendingReplaceDeckName = null;
        if (deckName == null || deckName.trim().isEmpty()) {
            showError("Deck update blocked", "The selected library deck was lost. Open Manage Deck Library and try again.");
            return;
        }

        try {
            HousePackage template = HouseRuntime.loadTemplatePackage(activity);
            DeckLibraryStore store = new DeckLibraryStore(activity);
            DeckSpec deck = findDeck(store.allDecks(template), deckName);
            if (deck == null) {
                throw new IllegalStateException("Deck is no longer in the library: " + deckName);
            }
            if (hasTournamentArtifacts() && store.isActive(template, deck)) {
                throw new IllegalStateException(
                        "Reset the tournament before replacing an active roster deck."
                );
            }

            DeckSpec updated;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IllegalStateException("Could not open the selected deck file");
                }
                updated = store.replaceImportedDeck(template, deck, in, displayName);
            }
            new AlertDialog.Builder(activity)
                    .setTitle("Deck updated")
                    .setMessage(updated.deck()
                            + "\n\nThe prior .dck was saved in version history.")
                    .setPositiveButton("OK", null)
                    .show();
            notifyChanged();
        } catch (Throwable t) {
            showError("Deck update blocked", safeMessage(t));
        }
    }

    private void showDeckActions(final HousePackage template, final DeckSpec deck) {
        final DeckLibraryStore store = new DeckLibraryStore(activity);
        final String[] actions = {
                "View exact 100-card deck",
                "Replace / Update",
                "Version history",
                "Remove imported deck"
        };
        new AlertDialog.Builder(activity)
                .setTitle(deck.deck())
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            showDeckDetails(deck);
                        } else if (which == 1) {
                            beginReplace(template, store, deck);
                        } else if (which == 2) {
                            showVersionHistory(template, store, deck);
                        } else if (which == 3) {
                            confirmRemove(template, store, deck);
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showDeckDetails(DeckSpec deck) {
        try {
            DeckLibraryStore store = new DeckLibraryStore(activity);
            DeckFileSnapshot snapshot = store.snapshot(deck);
            List<DeckVersion> history = store.history(deck);

            TextView text = new TextView(activity);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextIsSelectable(true);
            int pad = dp(18);
            text.setPadding(pad, pad, pad, pad);
            text.setText(
                    deck.deck() + "\n"
                            + "Source: " + deck.source() + "\n"
                            + "Forge name: " + deck.engineName() + "\n"
                            + "Commander(s): "
                            + (snapshot.commanderText().isEmpty()
                            ? "(metadata not found)"
                            : snapshot.commanderText())
                            + "\nCards: " + snapshot.cardCount()
                            + "\nSaved prior versions: " + history.size()
                            + "\n\n"
                            + snapshot.formattedDeckList()
            );

            ScrollView scroll = new ScrollView(activity);
            scroll.addView(text);
            new AlertDialog.Builder(activity)
                    .setTitle(deck.deck() + " • Exact Deck")
                    .setView(scroll)
                    .setPositiveButton("Close", null)
                    .show();
        } catch (Throwable t) {
            showError("Deck details unavailable", safeMessage(t));
        }
    }

    private void beginReplace(
            HousePackage template,
            DeckLibraryStore store,
            DeckSpec deck
    ) {
        if (!store.isImported(deck)) {
            showError(
                    "Bundled deck",
                    "Bundled HOUSE decks are read-only. Import a modified copy to manage versions."
            );
            return;
        }
        try {
            if (hasTournamentArtifacts() && store.isActive(template, deck)) {
                showError(
                        "Reset tournament first",
                        "This deck is active in the tournament roster. Reset the tournament before replacing it."
                );
                return;
            }
        } catch (Throwable t) {
            showError("Could not verify roster", safeMessage(t));
            return;
        }

        pendingReplaceDeckName = deck.deck();
        openDocument(REQUEST_REPLACE_DCK);
    }

    private void showVersionHistory(
            final HousePackage template,
            final DeckLibraryStore store,
            final DeckSpec deck
    ) {
        if (!store.isImported(deck)) {
            showError("No managed history", "Bundled HOUSE decks do not have Deck Library version history.");
            return;
        }

        try {
            final List<DeckVersion> versions = store.history(deck);
            if (versions.isEmpty()) {
                showError(
                        "Version history",
                        "No archived versions yet. The first Replace / Update operation will create one."
                );
                return;
            }

            final SimpleDateFormat format = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
            );
            CharSequence[] labels = new CharSequence[versions.size()];
            for (int i = 0; i < versions.size(); i++) {
                DeckVersion version = versions.get(i);
                labels[i] = format.format(new Date(version.savedAtMillis()))
                        + "\n" + version.engineName()
                        + "\n" + version.source();
            }

            new AlertDialog.Builder(activity)
                    .setTitle("Restore version • " + deck.deck())
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            confirmRestoreVersion(
                                    template,
                                    store,
                                    deck,
                                    versions.get(which),
                                    format
                            );
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
        } catch (Throwable t) {
            showError("Version history unavailable", safeMessage(t));
        }
    }

    private void confirmRestoreVersion(
            final HousePackage template,
            final DeckLibraryStore store,
            final DeckSpec deck,
            final DeckVersion version,
            final SimpleDateFormat format
    ) {
        try {
            if (hasTournamentArtifacts() && store.isActive(template, deck)) {
                showError(
                        "Reset tournament first",
                        "Reset the current tournament before restoring a version of an active roster deck."
                );
                return;
            }
        } catch (Throwable t) {
            showError("Could not verify roster", safeMessage(t));
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle("Restore archived version?")
                .setMessage(
                        format.format(new Date(version.savedAtMillis()))
                                + "\n\nThe current deck will be archived first."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            store.restoreVersion(template, deck, version);
                            notifyChanged();
                            showError(
                                    "Version restored",
                                    "The archived version is current again, and the pre-restore deck was saved to history."
                            );
                        } catch (Throwable t) {
                            showError("Version restore blocked", safeMessage(t));
                        }
                    }
                })
                .show();
    }

    private void confirmRemove(
            final HousePackage template,
            final DeckLibraryStore store,
            final DeckSpec deck
    ) {
        if (!store.isImported(deck)) {
            showError("Bundled deck", "Bundled HOUSE decks cannot be removed from the Deck Library.");
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle("Remove " + deck.deck() + "?")
                .setMessage(
                        "The current .dck and its archived versions will be deleted. "
                                + "A deck occupying an active HOUSE seat cannot be removed."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            store.removeImportedDeck(template, deck);
                            notifyChanged();
                        } catch (Throwable t) {
                            showError("Deck removal blocked", safeMessage(t));
                        }
                    }
                })
                .show();
    }

    private boolean hasTournamentArtifacts() {
        RunState state = new StateStore(activity).load();
        boolean active = "RUNNING".equals(state.status) || "TESTING".equals(state.status);
        boolean progress = state.totalGames > 0L
                || state.completedGauntlets() > 0
                || state.nextPodIndex > 0;
        boolean results = ResultsWriter.resultsFile(activity).isFile()
                && ResultsWriter.resultsFile(activity).length() > 0L;
        return active || progress || results;
    }

    private void openDocument(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain",
                "application/octet-stream",
                "application/x-forge-deck"
        });
        activity.startActivityForResult(intent, requestCode);
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) {
                        return value.trim();
                    }
                }
            }
        } catch (Throwable ignored) {
            // The import parser can fall back to the deck's internal metadata name.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "imported_deck.dck" : last;
    }

    private static DeckSpec findDeck(List<DeckSpec> decks, String name) {
        String key = Names.canonical(name);
        for (DeckSpec deck : decks) {
            if (Names.canonical(deck.deck()).equals(key)) {
                return deck;
            }
        }
        return null;
    }

    private static String commanderSuffix(DeckSpec deck) {
        return deck.commanders() == null || deck.commanders().trim().isEmpty()
                ? ""
                : "\n   " + deck.commanders();
    }

    private void notifyChanged() {
        if (callback != null) {
            callback.onLibraryChanged();
        }
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        Throwable x = t;
        while (x.getCause() != null && x.getCause() != x) {
            x = x.getCause();
        }
        String message = x.getMessage();
        return message == null || message.trim().isEmpty()
                ? x.getClass().getSimpleName()
                : message.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
