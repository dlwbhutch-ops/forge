package com.housecommander.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight structural parser for Forge .dck and common pasted deck lists.
 *
 * It intentionally does not duplicate Forge's card database. The shared layer
 * counts sections and preserves source text; each platform validates real cards
 * against the bundled Forge rules database before play.
 */
public final class DeckTextParser {
    private static final Pattern CARD_LINE =
            Pattern.compile("^(\\d+)x?\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SET_SUFFIX =
            Pattern.compile("\\s+\\([A-Za-z0-9]{2,8}\\)\\s+\\S+$");

    private enum Section {
        COMMANDER,
        MAIN,
        SIDEBOARD,
        OTHER
    }

    private DeckTextParser() {}

    public static DeckImportResult parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Deck text must not be blank");
        }

        Section section = Section.MAIN;
        boolean sawExplicitSection = false;
        boolean sawBracketSection = false;
        int commanders = 0;
        int main = 0;
        int sideboard = 0;
        List<String> commanderNames = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String sourceLine : lines) {
            String line = sourceLine.trim();
            if (line.isEmpty()
                    || line.startsWith("#")
                    || line.startsWith("//")
                    || line.startsWith(";")) {
                continue;
            }

            Section detected = sectionHeader(line);
            if (detected != null) {
                section = detected;
                sawExplicitSection = true;
                if (line.startsWith("[") && line.endsWith("]")) {
                    sawBracketSection = true;
                }
                continue;
            }

            Matcher matcher = CARD_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }

            int quantity = Integer.parseInt(matcher.group(1));
            if (quantity < 1) continue;
            String cardName = cleanCardName(matcher.group(2));

            switch (section) {
                case COMMANDER:
                    commanders += quantity;
                    if (!cardName.isEmpty()) commanderNames.add(cardName);
                    break;
                case SIDEBOARD:
                    sideboard += quantity;
                    break;
                case MAIN:
                case OTHER:
                default:
                    main += quantity;
                    break;
            }
        }

        int commanderDeckCards = commanders + main;
        if (!sawExplicitSection || commanders == 0) {
            warnings.add(
                    "Commander was not structurally identified; select the commander during import."
            );
        }
        if (commanderDeckCards != 100) {
            warnings.add(
                    "Commander deck contains "
                            + commanderDeckCards
                            + " cards; expected 100 including commander(s)."
            );
        }
        if (commanders > 2) {
            warnings.add(
                    "Detected " + commanders + " commander cards; verify partner/background rules."
            );
        }

        return new DeckImportResult(
                sawBracketSection ? "FORGE_DCK" : "TEXT",
                commanders,
                main,
                sideboard,
                commanderNames,
                warnings
        );
    }

    private static Section sectionHeader(String line) {
        String lower = line.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]")) {
            lower = lower.substring(1, lower.length() - 1).trim();
        }

        switch (lower) {
            case "commander":
            case "commanders":
            case "general":
                return Section.COMMANDER;
            case "main":
            case "mainboard":
            case "deck":
                return Section.MAIN;
            case "sideboard":
            case "maybeboard":
                return Section.SIDEBOARD;
            case "metadata":
            case "avatar":
            case "scheme":
            case "plane":
                return Section.OTHER;
            default:
                return null;
        }
    }

    private static String cleanCardName(String raw) {
        String value = raw.trim();
        int pipe = value.indexOf('|');
        if (pipe >= 0) {
            value = value.substring(0, pipe).trim();
        }
        value = SET_SUFFIX.matcher(value).replaceFirst("").trim();
        return value;
    }
}
