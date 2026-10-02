package com.housecommander.desktop;

import com.housecommander.core.DeckSpec;
import com.housecommander.core.ForgeDeckCounter;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desktop persistence adapter for the same library/roster files used by the
 * Android HOUSE layer: deck_library.tsv and active_roster.txt.
 */
public final class DesktopDeckLibraryStore {
    private static final String IMPORT_DIR = "imported_decks";

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
        if (sourceFile == null || !sourceFile.isFile() || !sourceFile.canRead()) {
            throw new IOException("Selected deck file is missing or unreadable");
        }

        File dir = new File(HouseDesktopPaths.deckRoot(), IMPORT_DIR);
        HouseDesktopPaths.ensureDirectory(dir);

        DeckFileInfo info = inspect(sourceFile);
        if (info.cardCount != 100) {
            throw new IOException(
                    "Imported Commander deck contains " + info.cardCount + " cards; expected exactly 100"
            );
        }

        List<DeckSpec> current = allDecks(template);
        String displayName = uniqueDisplayName(info.engineName, current);
        long stamp = System.currentTimeMillis();
        String relative = IMPORT_DIR + "/" + safeFileStem(info.engineName) + "_" + stamp + ".dck";
        File destination = new File(HouseDesktopPaths.deckRoot(), relative);
        Files.copy(
                sourceFile.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
        );

        DeckSpec imported = new DeckSpec(
                displayName,
                "import:" + sourceFile.getName(),
                info.commanders,
                relative,
                "EXACT",
                "Imported by HOUSE Commander Lab Desktop • validated 100-card Forge deck",
                info.engineName
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

    public boolean isImported(DeckSpec deck) {
        return deck != null && deck.source() != null && deck.source().startsWith("import:");
    }

    private void validateDeckFiles(List<DeckSpec> decks) throws IOException {
        for (DeckSpec deck : decks) {
            File file = HouseDesktopRuntime.deckFile(deck);
            if (!file.isFile() || !file.canRead()) {
                throw new IOException("Deck file is missing or unreadable: " + deck.deck());
            }
            int count;
            try (InputStream in = new FileInputStream(file)) {
                count = ForgeDeckCounter.countCards(in);
            }
            if (count != 100) {
                throw new IOException(deck.deck() + " contains " + count + " cards; expected 100");
            }
        }
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

    private static DeckFileInfo inspect(File file) throws IOException {
        int count;
        try (InputStream in = new FileInputStream(file)) {
            count = ForgeDeckCounter.countCards(in);
        }

        String metadataName = "";
        List<String> commanders = new ArrayList<String>();
        String section = "";
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    section = trimmed.substring(1, trimmed.length() - 1).trim().toLowerCase();
                    continue;
                }
                if ("metadata".equals(section) && trimmed.startsWith("Name=")) {
                    metadataName = trimmed.substring("Name=".length()).trim();
                } else if ("commander".equals(section)) {
                    int space = trimmed.indexOf(' ');
                    if (space > 0 && space < trimmed.length() - 1) {
                        try {
                            int quantity = Integer.parseInt(trimmed.substring(0, space));
                            String card = trimmed.substring(space + 1).trim();
                            for (int i = 0; i < quantity; i++) {
                                commanders.add(card);
                            }
                        } catch (NumberFormatException ignored) {
                            // Strict card count validation still applies.
                        }
                    }
                }
            }
        }

        String fallback = file.getName();
        int dot = fallback.lastIndexOf('.');
        if (dot > 0) {
            fallback = fallback.substring(0, dot);
        }
        fallback = fallback.replace('_', ' ').trim();

        String engineName = metadataName.isEmpty() ? fallback : metadataName;
        if (engineName.isEmpty()) {
            engineName = "Imported Commander Deck";
        }

        StringBuilder commanderText = new StringBuilder();
        for (String commander : commanders) {
            if (commanderText.length() > 0) {
                commanderText.append(" | ");
            }
            commanderText.append(commander);
        }
        return new DeckFileInfo(engineName, commanderText.toString(), count);
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

    private static String safeFileStem(String value) {
        String safe = value == null ? "deck" : value.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        safe = safe.replaceAll("^_+|_+$", "");
        return safe.isEmpty() ? "deck" : safe;
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

    private static final class DeckFileInfo {
        final String engineName;
        final String commanders;
        final int cardCount;

        DeckFileInfo(String engineName, String commanders, int cardCount) {
            this.engineName = engineName;
            this.commanders = commanders;
            this.cardCount = cardCount;
        }
    }
}
