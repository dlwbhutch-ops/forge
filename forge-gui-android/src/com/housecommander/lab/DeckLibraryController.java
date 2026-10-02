package com.housecommander.lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.housecommander.core.DeckSpec;
import com.housecommander.core.HousePackage;
import com.housecommander.core.Names;
import com.housecommander.core.RosterBuilder;
import com.housecommander.lab.state.ResultsWriter;
import com.housecommander.lab.state.RunState;
import com.housecommander.lab.state.StateStore;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** UI adapter for Deck Library -> Import Deck -> Select Tournament Roster. */
public final class DeckLibraryController {
    public static final int REQUEST_IMPORT_DCK = 2201;

    public interface Callback {
        void onLibraryChanged();
    }

    private final Activity activity;
    private final Callback callback;

    public DeckLibraryController(Activity activity, Callback callback) {
        if (activity == null) {
            throw new IllegalArgumentException("Activity must not be null");
        }
        this.activity = activity;
        this.callback = callback;
    }

    public void openImporter() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        activity.startActivityForResult(intent, REQUEST_IMPORT_DCK);
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_IMPORT_DCK) {
            return false;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }

        Uri uri = data.getData();
        String displayName = queryDisplayName(uri);
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
        return true;
    }

    public void showRosterPicker() {
        if (hasTournamentArtifacts()) {
            showError(
                    "Reset tournament first",
                    "The active roster is locked to the current checkpoint/results. "
                            + "Use Reset tournament checkpoint before changing which decks occupy the 19 HOUSE seats."
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
                String commander = d.commanders() == null || d.commanders().trim().isEmpty()
                        ? ""
                        : "\n   " + d.commanders();
                labels[i] = d.deck() + suffix + commander;
                checked[i] = activeNames.contains(Names.canonical(d.deck()));
            }

            new AlertDialog.Builder(activity)
                    .setTitle("Select Tournament Roster • choose exactly 19")
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
                            if (selected.size() != RosterBuilder.HOUSE_ROSTER_SIZE) {
                                showError(
                                        "Roster not saved",
                                        "You selected " + selected.size() + " decks. HOUSE requires exactly "
                                                + RosterBuilder.HOUSE_ROSTER_SIZE + "."
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
        return librarySize + " decks in library • " + rosterSize + "/19 active";
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
