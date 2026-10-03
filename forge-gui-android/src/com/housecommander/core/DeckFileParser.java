package com.housecommander.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DeckFileParser {
    private DeckFileParser() {}

    public static DeckFileSnapshot parse(InputStream input, String fallbackName) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Deck input must not be null");
        }

        String metadataName = "";
        String section = "";
        int total = 0;
        List<String> commanders = new ArrayList<String>();
        List<DeckFileSnapshot.CardLine> cards = new ArrayList<DeckFileSnapshot.CardLine>();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    section = trimmed.substring(1, trimmed.length() - 1)
                            .trim()
                            .toLowerCase(Locale.ROOT);
                    continue;
                }

                if ("metadata".equals(section)
                        && trimmed.regionMatches(true, 0, "Name=", 0, "Name=".length())) {
                    metadataName = trimmed.substring("Name=".length()).trim();
                    continue;
                }

                if (!("commander".equals(section) || "main".equals(section))) {
                    continue;
                }

                int space = trimmed.indexOf(' ');
                if (space <= 0 || space >= trimmed.length() - 1) {
                    continue;
                }

                final int quantity;
                try {
                    quantity = Integer.parseInt(trimmed.substring(0, space));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (quantity <= 0) {
                    continue;
                }

                String request = trimmed.substring(space + 1).trim();
                if (request.isEmpty()) {
                    continue;
                }

                cards.add(new DeckFileSnapshot.CardLine(section, quantity, request));
                total += quantity;
                if ("commander".equals(section)) {
                    for (int i = 0; i < quantity; i++) {
                        commanders.add(cardName(request));
                    }
                }
            }
        }

        String engineName = metadataName.trim();
        if (engineName.isEmpty()) {
            engineName = fallbackName == null ? "" : fallbackName.trim();
        }
        if (engineName.isEmpty()) {
            engineName = "Imported Commander Deck";
        }

        return new DeckFileSnapshot(engineName, commanders, cards, total);
    }

    private static String cardName(String request) {
        int separator = request.indexOf('|');
        return (separator < 0 ? request : request.substring(0, separator)).trim();
    }
}
