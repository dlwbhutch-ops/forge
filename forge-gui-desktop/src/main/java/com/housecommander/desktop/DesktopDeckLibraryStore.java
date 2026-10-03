package com.housecommander.desktop;

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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desktop persistence adapter for the same library/roster/history files used
 * by the Android HOUSE layer.
 */
public final class DesktopDeckLibraryStore {
    private static final String HISTORY_FILE = "deck_history.tsv";
    private static final String IMPORT_DIR = "imported_decks";
    private static final String HISTORY_DIR = "deck_history";

    public List<DeckSpec> allDecks(HousePackage template) throws IOException {
        List<DeckSpec> out = new ArrayList<DeckSpec>();
        out.addAll(template.decks());
        out.addAll(readImportedDecks());
        return out;
    }

    public List<DeckSpec> loadRoster(HousePackage template) throws IOException {
        File file = HouseDesktopPaths.rosterFile();
        if (!file.isFile() || file.length() == 0L) {
            return new ArrayList<DeckSpec>(template.decks());
        }

        Map<String, DeckSpec> byName = new LinkedHashMap<String, DeckSpec>();
        for (DeckSpec d : allDecks(template)) {
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

        File file = HouseDesktopPaths.rosterFile();
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
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
        Files.deleteIfExists(HouseDesktopPaths.rosterFile().toPath());
    }

    public DeckSpec importDeck(HousePackage template, File sourceFile) throws IOException {
        requireReadable(sourceFile);
        DeckFileSnapshot snapshot = inspectFile(sourceFile, fileStem(sourceFile.getName()));
        requireOneHundred(snapshot);

        List<DeckSpec> current = allDecks(template);
        String displayName = uniqueDisplayName(snapshot.engineName(), current);
        long stamp = System.currentTimeMillis();
        String relative = currentDeckPath(displayName, stamp);
        File destination = new File(HouseDesktopPaths.deckRoot(), relative);
        copyFile(sourceFile, destination);

        DeckSpec imported = new DeckSpec(
                displayName,
                "import:" + sourceFile.getName(),
                snapshot.commanderText(),
                relative,
                "EXACT",
                "Imported by HOUSE Commander Lab Desktop • validated 100-card Forge deck",
                snapshot.engineName()
        );

        List<DeckSpec> importedDecks = readImportedDecks();
        importedDecks.add(imported);
        try {
            writeImportedDecks(importedDecks);
        } catch (IOException error) {
            Files.deleteIfExists(destination.toPath());
            throw error;
        }
        return imported;
    }

    public DeckFileSnapshot snapshot(DeckSpec deck) throws IOException {
        File file = HouseDesktopRuntime.deckFile(deck);
        requireReadable(file);
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
            File sourceFile
    ) throws IOException {
        requireReadable(sourceFile);
        DeckSpec current = requireImported(deck);
        DeckFileSnapshot replacement = inspectFile(sourceFile, fileStem(sourceFile.getName()));
        requireOneHundred(replacement);

        long stamp = System.currentTimeMillis();
        List<DeckVersion> oldHistory = readHistory();
        List<DeckVersion> updatedHistory = new ArrayList<DeckVersion>(oldHistory);
        File archive = archiveCurrent(current, stamp);
        updatedHistory.add(versionOf(current, stamp, relativeToRoot(archive)));

        String relative = currentDeckPath(current.deck(), stamp);
        File destination = new File(HouseDesktopPaths.deckRoot(), relative);
        copyFile(sourceFile, destination);

        DeckSpec updated = new DeckSpec(
                current.deck(),
                "import:" + sourceFile.getName(),
                replacement.commanderText(),
                relative,
                "EXACT",
                "Updated by HOUSE Commander Lab Desktop • previous version archived",
                replacement.engineName()
        );

        List<DeckSpec> imported = readImportedDecks();
        replaceLibraryEntry(imported, current, updated);
        try {
            writeHistory(updatedHistory);
            writeImportedDecks(imported);
        } catch (IOException error) {
            Files.deleteIfExists(destination.toPath());
            Files.deleteIfExists(archive.toPath());
            writeHistory(oldHistory);
            throw error;
        }

        File oldCurrent = HouseDesktopRuntime.deckFile(current);
        if (!oldCurrent.equals(destination)) {
            Files.deleteIfExists(oldCurrent.toPath());
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

        File archivedVersion = new File(HouseDesktopPaths.deckRoot(), version.dck());
        requireReadable(archivedVersion);
        DeckFileSnapshot restoredSnapshot = inspectFile(archivedVersion, version.engineName());
        requireOneHundred(restoredSnapshot);

        long stamp = System.currentTimeMillis();
        List<DeckVersion> oldHistory = readHistory();
        List<DeckVersion> updatedHistory = new ArrayList<DeckVersion>(oldHistory);
        File archive = archiveCurrent(current, stamp);
        updatedHistory.add(versionOf(current, stamp, relativeToRoot(archive)));

        String relative = currentDeckPath(current.deck(), stamp);
        File destination = new File(HouseDesktopPaths.deckRoot(), relative);
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
            Files.deleteIfExists(destination.toPath());
            Files.deleteIfExists(archive.toPath());
            writeHistory(oldHistory);
            throw error;
        }

        File oldCurrent = HouseDesktopRuntime.deckFile(current);
        if (!oldCurrent.equals(destination)) {
            Files.deleteIfExists(oldCurrent.toPath());
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
        Files.deleteIfExists(HouseDesktopRuntime.deckFile(current).toPath());

        List<DeckVersion> retained = new ArrayList<DeckVersion>();
        for (DeckVersion version : readHistory()) {
            if (Names.canonical(version.deck()).equals(Names.canonical(current.deck()))) {
                Files.deleteIfExists(
                        new File(HouseDesktopPaths.deckRoot(), version.dck()).toPath()
                );
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
        File existing = HouseDesktopRuntime.deckFile(current);
        requireReadable(existing);
        File dir = new File(HouseDesktopPaths.deckRoot(), HISTORY_DIR);
        HouseDesktopPaths.ensureDirectory(dir);
        File archive = new File(dir, safeFileStem(current.deck()) + "_" + stamp + ".dck");
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

    private String relativeToRoot(File file) throws IOException {
        File root = HouseDesktopPaths.deckRoot().getCanonicalFile();
        File target = file.getCanonicalFile();
        String rootPath = root.getPath() + File.separator;
        if (!target.getPath().startsWith(rootPath)) {
            throw new IOException("Deck path is outside HOUSE storage");
        }
        return target.getPath().substring(rootPath.length()).replace(File.separatorChar, '/');
    }

    private List<DeckSpec> readImportedDecks() throws IOException {
        List<DeckSpec> out = new ArrayList<DeckSpec>();
        File file = HouseDesktopPaths.libraryFile();
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
        File file = HouseDesktopPaths.libraryFile();
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
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

    private File historyFile() throws IOException {
        return new File(HouseDesktopPaths.home(), HISTORY_FILE);
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

    private static void requireReadable(File file) throws IOException {
        if (file == null || !file.isFile() || !file.canRead()) {
            throw new IOException("Selected deck file is missing or unreadable");
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null) {
            HouseDesktopPaths.ensureDirectory(parent);
        }
        Files.copy(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
        );
    }

    private static void replace(File source, File destination) throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException atomicFailed) {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
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
