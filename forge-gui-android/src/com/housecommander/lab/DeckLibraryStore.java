package com.housecommander.lab;

import android.content.Context;

import com.housecommander.core.DeckFileParser;
import com.housecommander.core.DeckFileSnapshot;
import com.housecommander.core.DeckSpec;
import com.housecommander.core.DeckVersion;
import com.housecommander.core.HousePackage;
import com.housecommander.core.Names;
import com.housecommander.core.RosterBuilder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Android persistence adapter for the cross-platform HOUSE deck library model. */
public final class DeckLibraryStore {
    private static final String LIBRARY_FILE = "deck_library.tsv";
    private static final String ROSTER_FILE = "active_roster.txt";
    private static final String HISTORY_FILE = "deck_history.tsv";
    private static final String IMPORT_DIR = "imported_decks";
    private static final String HISTORY_DIR = "deck_history";

    private final Context context;

    public DeckLibraryStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        Context app = context.getApplicationContext();
        this.context = app != null ? app : context;
    }

    public List<DeckSpec> allDecks(HousePackage template) throws IOException {
        if (template == null) {
            throw new IllegalArgumentException("Template package must not be null");
        }
        List<DeckSpec> out = new ArrayList<DeckSpec>();
        out.addAll(template.decks());
        out.addAll(readImportedDecks());
        return out;
    }

    public List<DeckSpec> loadRoster(HousePackage template) throws IOException {
        File file = rosterFile();
        if (!file.isFile() || file.length() == 0L) {
            return new ArrayList<DeckSpec>(template.decks());
        }

        List<DeckSpec> library = allDecks(template);
        Map<String, DeckSpec> byName = new LinkedHashMap<String, DeckSpec>();
        for (DeckSpec d : library) {
            byName.put(Names.canonical(d.deck()), d);
        }

        List<DeckSpec> selected = new ArrayList<DeckSpec>();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String name = unescape(line.trim());
                if (name.isEmpty()) {
                    continue;
                }
                DeckSpec deck = byName.get(Names.canonical(name));
                if (deck == null) {
                    throw new IOException("Active roster deck is missing from the library: " + name);
                }
                selected.add(deck);
            }
        }

        if (selected.size() != RosterBuilder.HOUSE_ROSTER_SIZE) {
            throw new IOException(
                    "Active HOUSE roster has " + selected.size() + " decks; expected "
                            + RosterBuilder.HOUSE_ROSTER_SIZE
            );
        }
        return selected;
    }

    public HousePackage activePackage(HousePackage template) throws IOException {
        List<DeckSpec> selected = loadRoster(template);
        validateDeckFiles(selected);
        return RosterBuilder.build(template, selected);
    }

    public void saveRoster(HousePackage template, List<DeckSpec> selected) throws IOException {
        HousePackage remapped = RosterBuilder.build(template, selected);
        validateDeckFiles(remapped.decks());

        File file = rosterFile();
        File temp = new File(file.getParentFile(), ROSTER_FILE + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(temp, false);
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
            for (DeckSpec deck : remapped.decks()) {
                out.write(escape(deck.deck()));
                out.newLine();
            }
            out.flush();
            fos.getFD().sync();
        }
        replace(temp, file);
    }

    public void restoreDefaultRoster() throws IOException {
        File file = rosterFile();
        File temp = new File(file.getParentFile(), ROSTER_FILE + ".tmp");
        if (file.exists() && !file.delete()) {
            throw new IOException("Could not delete active roster file: " + file.getAbsolutePath());
        }
        if (temp.exists() && !temp.delete()) {
            throw new IOException("Could not delete temporary roster file: " + temp.getAbsolutePath());
        }
    }

    public DeckSpec importDeck(HousePackage template, InputStream input, String originalName)
            throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Deck input stream must not be null");
        }
        File root = HouseInstall.installRoot(context);
        File dir = new File(root, IMPORT_DIR);
        ensureDirectory(dir);

        long stamp = System.currentTimeMillis();
        File incoming = new File(dir, ".incoming_" + stamp + ".dck");
        copyInput(input, incoming);

        DeckFileSnapshot snapshot;
        try {
            snapshot = inspectFile(incoming, fileStem(originalName));
        } catch (IOException error) {
            incoming.delete();
            throw error;
        }
        requireOneHundred(snapshot);

        List<DeckSpec> current = allDecks(template);
        String displayName = uniqueDisplayName(snapshot.engineName(), current);
        String relative = currentDeckPath(displayName, stamp);
        File destination = new File(root, relative);
        replace(incoming, destination);

        String sourceName = sourceName(originalName, destination.getName());
        DeckSpec imported = new DeckSpec(
                displayName,
                "import:" + sourceName,
                snapshot.commanderText(),
                relative,
                "EXACT",
                "Imported by HOUSE Commander Lab • validated 100-card Forge deck",
                snapshot.engineName()
        );

        List<DeckSpec> importedDecks = readImportedDecks();
        importedDecks.add(imported);
        try {
            writeImportedDecks(importedDecks);
        } catch (IOException error) {
            destination.delete();
            throw error;
        }
        return imported;
    }

    public DeckFileSnapshot snapshot(DeckSpec deck) throws IOException {
        File file = deckFile(deck);
        if (!file.isFile() || !file.canRead()) {
            throw new IOException("Deck file is missing or unreadable: " + deck.deck());
        }
        return inspectFile(file, deck.engineName());
    }

    public List<DeckVersion> history(DeckSpec deck) throws IOException {
        List<DeckVersion> matches = new ArrayList<DeckVersion>();
        String key = Names.canonical(deck.deck());
        for (DeckVersion version : readHistory()) {
            if (Names.canonical(version.deck()).equals(key)) {
                matches.add(version);
            }
        }
        Collections.sort(matches, new Comparator<DeckVersion>() {
            @Override
            public int compare(DeckVersion left, DeckVersion right) {
                return Long.compare(right.savedAtMillis(), left.savedAtMillis());
            }
        });
        return matches;
    }

    public DeckSpec replaceImportedDeck(
            HousePackage template,
            DeckSpec deck,
            InputStream input,
            String originalName
    ) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Deck input stream must not be null");
        }
        DeckSpec current = requireImported(deck);
        File root = HouseInstall.installRoot(context);
        File dir = new File(root, IMPORT_DIR);
        ensureDirectory(dir);

        long stamp = System.currentTimeMillis();
        File incoming = new File(dir, ".replacement_" + stamp + ".dck");
        copyInput(input, incoming);

        DeckFileSnapshot replacement;
        try {
            replacement = inspectFile(incoming, fileStem(originalName));
        } catch (IOException error) {
            incoming.delete();
            throw error;
        }
        requireOneHundred(replacement);

        List<DeckVersion> oldHistory = readHistory();
        List<DeckVersion> updatedHistory = new ArrayList<DeckVersion>(oldHistory);
        File archive = archiveCurrent(current, stamp);
        updatedHistory.add(versionOf(current, stamp, relativeToRoot(archive)));

        String relative = currentDeckPath(current.deck(), stamp);
        File destination = new File(root, relative);
        replace(incoming, destination);

        DeckSpec updated = new DeckSpec(
                current.deck(),
                "import:" + sourceName(originalName, destination.getName()),
                replacement.commanderText(),
                relative,
                "EXACT",
                "Updated by HOUSE Commander Lab • previous version archived",
                replacement.engineName()
        );

        List<DeckSpec> imported = readImportedDecks();
        replaceLibraryEntry(imported, current, updated);
        try {
            writeHistory(updatedHistory);
            writeImportedDecks(imported);
        } catch (IOException error) {
            destination.delete();
            archive.delete();
            writeHistory(oldHistory);
            throw error;
        }

        File oldCurrent = deckFile(current);
        if (!oldCurrent.equals(destination)) {
            oldCurrent.delete();
        }
        return updated;
    }

    public DeckSpec restoreVersion(
            HousePackage template,
            DeckSpec deck,
            DeckVersion version
    ) throws IOException {
        DeckSpec current = requireImported(deck);
        if (version == null || !Names.canonical(version.deck()).equals(Names.canonical(current.deck()))) {
            throw new IOException("Selected version does not belong to " + current.deck());
        }

        File root = HouseInstall.installRoot(context);
        File archivedVersion = new File(root, version.dck());
        if (!archivedVersion.isFile() || !archivedVersion.canRead()) {
            throw new IOException("Archived deck version is missing: " + version.dck());
        }

        DeckFileSnapshot restoredSnapshot;
        try (InputStream in = new FileInputStream(archivedVersion)) {
            restoredSnapshot = DeckFileParser.parse(in, version.engineName());
        }
        requireOneHundred(restoredSnapshot);

        long stamp = System.currentTimeMillis();
        List<DeckVersion> oldHistory = readHistory();
        List<DeckVersion> updatedHistory = new ArrayList<DeckVersion>(oldHistory);
        File archive = archiveCurrent(current, stamp);
        updatedHistory.add(versionOf(current, stamp, relativeToRoot(archive)));

        String relative = currentDeckPath(current.deck(), stamp);
        File destination = new File(root, relative);
        copyFile(archivedVersion, destination);

        DeckSpec restored = new DeckSpec(
                current.deck(),
                version.source(),
                version.commanders(),
                relative,
                "EXACT",
                "Restored HOUSE version from " + version.savedAtMillis(),
                version.engineName()
        );

        List<DeckSpec> imported = readImportedDecks();
        replaceLibraryEntry(imported, current, restored);
        try {
            writeHistory(updatedHistory);
            writeImportedDecks(imported);
        } catch (IOException error) {
            destination.delete();
            archive.delete();
            writeHistory(oldHistory);
            throw error;
        }

        File oldCurrent = deckFile(current);
        if (!oldCurrent.equals(destination)) {
            oldCurrent.delete();
        }
        return restored;
    }

    public void removeImportedDeck(HousePackage template, DeckSpec deck) throws IOException {
        DeckSpec current = requireImported(deck);
        if (isActive(template, current)) {
            throw new IOException(
                    current.deck() + " is in the active tournament roster. "
                            + "Select a different 19-deck roster before removing it."
            );
        }

        List<DeckSpec> imported = readImportedDecks();
        if (!removeLibraryEntry(imported, current)) {
            throw new IOException("Imported deck is no longer in the library: " + current.deck());
        }
        writeImportedDecks(imported);

        File currentFile = deckFile(current);
        if (currentFile.exists() && !currentFile.delete()) {
            throw new IOException("Deck metadata was removed but the current .dck could not be deleted");
        }

        List<DeckVersion> retained = new ArrayList<DeckVersion>();
        for (DeckVersion version : readHistory()) {
            if (Names.canonical(version.deck()).equals(Names.canonical(current.deck()))) {
                File historical = new File(HouseInstall.installRoot(context), version.dck());
                if (historical.exists()) {
                    historical.delete();
                }
            } else {
                retained.add(version);
            }
        }
        writeHistory(retained);
    }

    public boolean isImported(DeckSpec deck) {
        return deck != null && deck.source() != null && deck.source().startsWith("import:");
    }

    public boolean isActive(HousePackage template, DeckSpec deck) throws IOException {
        String key = Names.canonical(deck.deck());
        for (DeckSpec active : loadRoster(template)) {
            if (Names.canonical(active.deck()).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private DeckSpec requireImported(DeckSpec deck) throws IOException {
        if (!isImported(deck)) {
            throw new IOException("Bundled HOUSE decks are read-only in Deck Library management");
        }
        String key = Names.canonical(deck.deck());
        for (DeckSpec imported : readImportedDecks()) {
            if (Names.canonical(imported.deck()).equals(key)) {
                return imported;
            }
        }
        throw new IOException("Imported deck is no longer in the library: " + deck.deck());
    }

    private void validateDeckFiles(List<DeckSpec> decks) throws IOException {
        for (DeckSpec deck : decks) {
            DeckFileSnapshot snapshot = snapshot(deck);
            if (snapshot.cardCount() != 100) {
                throw new IOException(deck.deck() + " contains "
                        + snapshot.cardCount() + " cards; expected 100");
            }
        }
    }

    private File archiveCurrent(DeckSpec current, long stamp) throws IOException {
        File existing = deckFile(current);
        if (!existing.isFile() || !existing.canRead()) {
            throw new IOException("Current deck file is missing: " + current.deck());
        }
        File dir = new File(HouseInstall.installRoot(context), HISTORY_DIR);
        ensureDirectory(dir);
        File archive = new File(
                dir,
                safeFileStem(current.deck()) + "_" + stamp + ".dck"
        );
        copyFile(existing, archive);
        return archive;
    }

    private DeckVersion versionOf(DeckSpec deck, long savedAt, String relativePath) {
        return new DeckVersion(
                deck.deck(),
                savedAt,
                deck.source(),
                deck.commanders(),
                relativePath,
                deck.engineName(),
                deck.detail()
        );
    }

    private File deckFile(DeckSpec deck) throws IOException {
        return new File(HouseInstall.installRoot(context), deck.dck());
    }

    private String relativeToRoot(File file) throws IOException {
        File root = HouseInstall.installRoot(context).getCanonicalFile();
        File target = file.getCanonicalFile();
        String rootPath = root.getPath() + File.separator;
        if (!target.getPath().startsWith(rootPath)) {
            throw new IOException("Deck path is outside HOUSE storage");
        }
        return target.getPath().substring(rootPath.length()).replace(File.separatorChar, '/');
    }

    private List<DeckSpec> readImportedDecks() throws IOException {
        List<DeckSpec> out = new ArrayList<DeckSpec>();
        File file = libraryFile();
        if (!file.isFile() || file.length() == 0L) {
            return out;
        }
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                List<String> fields = splitEscaped(line);
                if (fields.size() != 7) {
                    throw new IOException("Malformed imported deck library row");
                }
                out.add(new DeckSpec(
                        fields.get(0), fields.get(1), fields.get(2), fields.get(3),
                        fields.get(4), fields.get(5), fields.get(6)
                ));
            }
        }
        return out;
    }

    private void writeImportedDecks(List<DeckSpec> decks) throws IOException {
        File file = libraryFile();
        File temp = new File(file.getParentFile(), LIBRARY_FILE + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(temp, false);
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
            for (DeckSpec d : decks) {
                out.write(joinEscaped(
                        d.deck(), d.source(), d.commanders(), d.dck(),
                        d.status(), d.detail(), d.engineName()
                ));
                out.newLine();
            }
            out.flush();
            fos.getFD().sync();
        }
        replace(temp, file);
    }

    private List<DeckVersion> readHistory() throws IOException {
        List<DeckVersion> out = new ArrayList<DeckVersion>();
        File file = historyFile();
        if (!file.isFile() || file.length() == 0L) {
            return out;
        }
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                List<String> fields = splitEscaped(line);
                if (fields.size() != 7) {
                    throw new IOException("Malformed deck history row");
                }
                long savedAt;
                try {
                    savedAt = Long.parseLong(fields.get(1));
                } catch (NumberFormatException error) {
                    throw new IOException("Malformed deck history timestamp", error);
                }
                out.add(new DeckVersion(
                        fields.get(0),
                        savedAt,
                        fields.get(2),
                        fields.get(3),
                        fields.get(4),
                        fields.get(5),
                        fields.get(6)
                ));
            }
        }
        return out;
    }

    private void writeHistory(List<DeckVersion> versions) throws IOException {
        File file = historyFile();
        File temp = new File(file.getParentFile(), HISTORY_FILE + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(temp, false);
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
            for (DeckVersion version : versions) {
                out.write(joinEscaped(
                        version.deck(),
                        Long.toString(version.savedAtMillis()),
                        version.source(),
                        version.commanders(),
                        version.dck(),
                        version.engineName(),
                        version.detail()
                ));
                out.newLine();
            }
            out.flush();
            fos.getFD().sync();
        }
        replace(temp, file);
    }

    private File libraryFile() throws IOException {
        return new File(HouseInstall.installRoot(context), LIBRARY_FILE);
    }

    private File rosterFile() throws IOException {
        return new File(HouseInstall.installRoot(context), ROSTER_FILE);
    }

    private File historyFile() throws IOException {
        return new File(HouseInstall.installRoot(context), HISTORY_FILE);
    }

    private static DeckFileSnapshot inspectFile(File file, String fallback) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return DeckFileParser.parse(in, fallback);
        }
    }

    private static void requireOneHundred(DeckFileSnapshot snapshot) throws IOException {
        if (snapshot.cardCount() != 100) {
            throw new IOException(
                    "Commander deck contains " + snapshot.cardCount()
                            + " cards; expected exactly 100"
            );
        }
    }

    private static void replaceLibraryEntry(
            List<DeckSpec> library,
            DeckSpec oldDeck,
            DeckSpec newDeck
    ) throws IOException {
        String key = Names.canonical(oldDeck.deck());
        for (int i = 0; i < library.size(); i++) {
            if (Names.canonical(library.get(i).deck()).equals(key)) {
                library.set(i, newDeck);
                return;
            }
        }
        throw new IOException("Imported deck is no longer in the library: " + oldDeck.deck());
    }

    private static boolean removeLibraryEntry(List<DeckSpec> library, DeckSpec deck) {
        String key = Names.canonical(deck.deck());
        for (int i = 0; i < library.size(); i++) {
            if (Names.canonical(library.get(i).deck()).equals(key)) {
                library.remove(i);
                return true;
            }
        }
        return false;
    }

    private static String uniqueDisplayName(String base, List<DeckSpec> current) {
        Map<String, Boolean> names = new LinkedHashMap<String, Boolean>();
        for (DeckSpec d : current) {
            names.put(Names.canonical(d.deck()), Boolean.TRUE);
        }
        if (!names.containsKey(Names.canonical(base))) {
            return base;
        }
        String candidate = base + " (Imported)";
        if (!names.containsKey(Names.canonical(candidate))) {
            return candidate;
        }
        int version = 2;
        while (names.containsKey(Names.canonical(base + " (Imported " + version + ")"))) {
            version++;
        }
        return base + " (Imported " + version + ")";
    }

    private static String currentDeckPath(String displayName, long stamp) {
        return IMPORT_DIR + "/" + safeFileStem(displayName) + "_" + stamp + ".dck";
    }

    private static String safeFileStem(String value) {
        String safe = value == null ? "deck"
                : value.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        safe = safe.replaceAll("^_+|_+$", "");
        return safe.isEmpty() ? "deck" : safe;
    }

    private static String fileStem(String value) {
        if (value == null) {
            return "";
        }
        String name = new File(value).getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.replace('_', ' ').trim();
    }

    private static String sourceName(String originalName, String fallback) {
        return originalName == null || originalName.trim().isEmpty()
                ? fallback
                : originalName.trim();
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Could not create directory: " + dir.getAbsolutePath());
        }
    }

    private static void copyInput(InputStream input, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null) {
            ensureDirectory(parent);
        }
        try (FileOutputStream out = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = input.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
            out.getFD().sync();
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (InputStream in = new FileInputStream(source)) {
            copyInput(in, destination);
        }
    }

    private static void replace(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null) {
            ensureDirectory(parent);
        }
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Could not replace file: " + destination.getAbsolutePath());
        }
        if (source.renameTo(destination)) {
            return;
        }
        copyFile(source, destination);
        source.delete();
    }

    private static String joinEscaped(String... values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append('\t');
            }
            out.append(escape(values[i]));
        }
        return out.toString();
    }

    private static List<String> splitEscaped(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                switch (c) {
                    case 't': field.append('\t'); break;
                    case 'n': field.append('\n'); break;
                    case 'r': field.append('\r'); break;
                    case '\\': field.append('\\'); break;
                    default: field.append('\\').append(c); break;
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '\t') {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        if (escaped) {
            field.append('\\');
        }
        out.add(field.toString());
        return out;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String unescape(String value) {
        List<String> one = splitEscaped(value);
        return one.isEmpty() ? "" : one.get(0);
    }
}
