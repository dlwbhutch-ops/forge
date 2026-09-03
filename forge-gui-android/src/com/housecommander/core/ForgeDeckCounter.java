package com.housecommander.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class ForgeDeckCounter {
    private ForgeDeckCounter() {}

    /** Counts commander + main-deck quantities. Sideboards and metadata are ignored. */
    public static int countCards(InputStream input) throws IOException {
        int total = 0;
        String section = "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim().toLowerCase();
                    continue;
                }
                if (!(section.equals("commander") || section.equals("main"))) continue;
                int space = line.indexOf(' ');
                if (space <= 0) continue;
                try {
                    total += Integer.parseInt(line.substring(0, space));
                } catch (NumberFormatException ignored) {
                    // Non-card metadata in a card section is skipped; strict validation will fail if total != 100.
                }
            }
        }
        return total;
    }
}
