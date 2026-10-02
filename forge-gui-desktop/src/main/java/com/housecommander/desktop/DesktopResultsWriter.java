package com.housecommander.desktop;

import com.housecommander.core.DeckSpec;
import com.housecommander.core.HousePackage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DesktopResultsWriter {
    private static final String FILE_NAME = "literal_house19_gauntlets.csv";
    private static final String HEADER = "gauntlet,deck,pod_wins,games,win_rate";

    public static final class Standing {
        public final String deck;
        public final int wins;
        public final int games;
        public final double rate;

        Standing(String deck, int wins, int games) {
            this.deck = deck;
            this.wins = wins;
            this.games = games;
            this.rate = games == 0 ? 0.0d : (double) wins / (double) games;
        }
    }

    private DesktopResultsWriter() {}

    public static File resultsFile() throws IOException {
        return new File(HouseDesktopPaths.resultsDir(), FILE_NAME);
    }

    public static synchronized void writeGauntlet(
            HousePackage pack,
            int gauntlet,
            Map<String, Integer> wins,
            Map<String, Integer> games
    ) throws IOException {
        File file = resultsFile();
        List<String> retained = readRowsExcept(file, gauntlet);
        File temp = new File(file.getParentFile(), FILE_NAME + ".tmp");

        try (FileOutputStream fos = new FileOutputStream(temp, false);
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
            out.write(HEADER);
            out.newLine();
            for (String row : retained) {
                out.write(row);
                out.newLine();
            }
            for (DeckSpec d : pack.decks()) {
                int win = value(wins, d.deck());
                int game = value(games, d.deck());
                double rate = game == 0 ? 0.0d : (double) win / (double) game;
                out.write(Integer.toString(gauntlet));
                out.write(',');
                out.write(csv(d.deck()));
                out.write(',');
                out.write(Integer.toString(win));
                out.write(',');
                out.write(Integer.toString(game));
                out.write(',');
                out.write(Double.toString(rate));
                out.newLine();
            }
            out.flush();
            fos.getFD().sync();
        }
        replace(temp, file);
    }

    public static synchronized List<Standing> aggregate() throws IOException {
        File file = resultsFile();
        Map<String, int[]> totals = new LinkedHashMap<String, int[]>();
        if (!file.isFile() || file.length() == 0L) {
            return new ArrayList<Standing>();
        }

        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                if (first && HEADER.equals(line)) {
                    first = false;
                    continue;
                }
                first = false;
                List<String> fields = parseCsv(line);
                if (fields.size() < 5) {
                    continue;
                }
                String deck = fields.get(1);
                int[] values = totals.computeIfAbsent(deck, k -> new int[2]);
                values[0] += Integer.parseInt(fields.get(2));
                values[1] += Integer.parseInt(fields.get(3));
            }
        }

        List<Standing> out = new ArrayList<Standing>();
        for (Map.Entry<String, int[]> e : totals.entrySet()) {
            out.add(new Standing(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        out.sort((a, b) -> {
            int rate = Double.compare(b.rate, a.rate);
            if (rate != 0) {
                return rate;
            }
            int wins = Integer.compare(b.wins, a.wins);
            return wins != 0 ? wins : a.deck.compareToIgnoreCase(b.deck);
        });
        return out;
    }

    public static synchronized void reset() throws IOException {
        Files.deleteIfExists(resultsFile().toPath());
    }

    private static List<String> readRowsExcept(File file, int gauntlet) throws IOException {
        List<String> rows = new ArrayList<String>();
        if (!file.isFile() || file.length() == 0L) {
            return rows;
        }
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                if (first && HEADER.equals(line)) {
                    first = false;
                    continue;
                }
                first = false;
                List<String> fields = parseCsv(line);
                if (fields.isEmpty()) {
                    continue;
                }
                int rowGauntlet = Integer.parseInt(fields.get(0));
                if (rowGauntlet != gauntlet) {
                    rows.add(line);
                }
            }
        }
        return rows;
    }

    private static int value(Map<String, Integer> map, String key) {
        Integer value = map == null ? null : map.get(key);
        return value == null ? 0 : Math.max(0, value);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains(""") || safe.contains("\n") || safe.contains("\r")) {
            return """ + safe.replace(""", """") + """;
        }
        return safe;
    }

    private static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        out.add(field.toString());
        return out;
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
}
