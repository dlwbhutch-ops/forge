package com.housecommander.core;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts ManaBox/plain-text Commander exports into Forge .dck text.
 *
 * Supported examples:
 *   Commander
 *   1 Shorikai, Genesis Engine
 *
 *   Deck
 *   1 Sol Ring
 *
 * Or a simple 100-card list with no headings; the first card line is treated
 * as the commander and the remaining cards as the main deck.
 */
public final class TextDeckImport {
    private static final Pattern CARD_LINE = Pattern.compile("^\\s*(\\d+)\\s*[xX]?\\s+(.+?)\\s*$");
    private static final Pattern MANABOX_PRINTING = Pattern.compile(
            "^(.*)\\s+\\(([A-Za-z0-9]{2,8})\\)\\s+[A-Za-z0-9-]+(?:\\s+.*)?$"
    );

    private TextDeckImport() {}

    public static byte[] toForgeDck(InputStream input, String fallbackName) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Deck input must not be null");
        }

        List<Line> commanders = new ArrayList<Line>();
        List<Line> main = new ArrayList<Line>();
        List<Line> unsectioned = new ArrayList<Line>();
        Section section = Section.NONE;
        boolean sawSection = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                String line = stripBom(raw).trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                    continue;
                }

                Section heading = heading(line);
                if (heading != null) {
                    section = heading;
                    sawSection = true;
                    continue;
                }

                Matcher matcher = CARD_LINE.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                int quantity;
                try {
                    quantity = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException error) {
                    throw new IOException("Invalid card quantity: " + line, error);
                }
                if (quantity <= 0) {
                    throw new IOException("Card quantity must be positive: " + line);
                }

                String card = cleanCardRequest(matcher.group(2));
                if (card.isEmpty()) {
                    throw new IOException("Missing card name: " + line);
                }

                Line parsed = new Line(quantity, card);
                if (!sawSection) {
                    unsectioned.add(parsed);
                } else if (section == Section.COMMANDER) {
                    commanders.add(parsed);
                } else if (section == Section.MAIN) {
                    main.add(parsed);
                } else if (section == Section.IGNORE) {
                    // Sideboards, maybeboard, considering, tokens, etc. are not part
                    // of the 100-card Commander deck.
                } else {
                    throw new IOException("Card appears before Commander/Deck section: " + line);
                }
            }
        }

        if (!sawSection) {
            if (unsectioned.isEmpty()) {
                throw new IOException("No card lines found in text deck");
            }
            Line first = unsectioned.get(0);
            if (first.quantity != 1) {
                throw new IOException(
                        "Plain-text decks without headings must list the commander first with quantity 1"
                );
            }
            commanders.add(first);
            for (int i = 1; i < unsectioned.size(); i++) {
                main.add(unsectioned.get(i));
            }
        }

        int commanderCards = quantity(commanders);
        int total = commanderCards + quantity(main);
        if (commanderCards < 1) {
            throw new IOException(
                    "No commander found. Add a 'Commander' heading, or put the commander first."
            );
        }
        if (total != 100) {
            throw new IOException(
                    "Text Commander deck contains " + total + " cards; expected exactly 100"
            );
        }

        String name = cleanName(fallbackName);
        if (name.isEmpty()) {
            name = commanders.get(0).card;
        }

        StringBuilder out = new StringBuilder();
        out.append("[metadata]\n");
        out.append("Name=").append(name).append('\n');
        out.append("[general]\n");
        out.append("Commander\n");
        out.append("[commander]\n");
        appendCards(out, commanders);
        out.append("[main]\n");
        appendCards(out, main);

        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        try (ByteArrayInputStream verify = new ByteArrayInputStream(bytes)) {
            DeckFileSnapshot snapshot = DeckFileParser.parse(verify, name);
            if (snapshot.cardCount() != 100) {
                throw new IOException("Converted Forge deck failed 100-card validation");
            }
        }
        return bytes;
    }

    public static DeckFileSnapshot snapshot(byte[] forgeDck, String fallbackName) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(forgeDck)) {
            return DeckFileParser.parse(input, fallbackName);
        }
    }

    private static void appendCards(StringBuilder out, List<Line> cards) {
        for (Line card : cards) {
            out.append(card.quantity).append(' ').append(card.card).append('\n');
        }
    }

    private static int quantity(List<Line> cards) {
        int total = 0;
        for (Line card : cards) {
            total += card.quantity;
        }
        return total;
    }

    private static Section heading(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        if ("commander".equals(normalized) || "commanders".equals(normalized)) {
            return Section.COMMANDER;
        }
        if ("deck".equals(normalized)
                || "main".equals(normalized)
                || "mainboard".equals(normalized)
                || "main deck".equals(normalized)) {
            return Section.MAIN;
        }
        if ("sideboard".equals(normalized)
                || "maybeboard".equals(normalized)
                || "maybe board".equals(normalized)
                || "considering".equals(normalized)
                || "tokens".equals(normalized)) {
            return Section.IGNORE;
        }
        return null;
    }

    private static String cleanCardRequest(String value) {
        String card = value.trim();
        Matcher printing = MANABOX_PRINTING.matcher(card);
        if (printing.matches()) {
            card = printing.group(1).trim();
        }
        return card;
    }

    private static String cleanName(String value) {
        if (value == null) {
            return "";
        }
        String name = value.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replace('_', ' ').replace('-', ' ').trim();
        return name;
    }

    private static String stripBom(String value) {
        return value != null && !value.isEmpty() && value.charAt(0) == '\ufeff'
                ? value.substring(1)
                : value;
    }

    private enum Section {
        NONE,
        COMMANDER,
        MAIN,
        IGNORE
    }

    private static final class Line {
        final int quantity;
        final String card;

        Line(int quantity, String card) {
            this.quantity = quantity;
            this.card = card;
        }
    }
}
