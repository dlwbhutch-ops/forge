package com.housecommander.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HousePackageLoader {
    private HousePackageLoader() {}

    public static HousePackage load(AssetSource assets, String root) throws IOException {
        String prefix = root.endsWith("/") ? root : root + "/";
        List<DeckSpec> decks = loadManifest(assets, prefix + "house19_manifest.csv");
        List<PodSpec> pods = loadSchedule(assets, prefix + "HOUSE_Triple_Round_Robin_Pairing_Schedule_2026-08-29.csv");

        Map<String, DeckSpec> byName = new LinkedHashMap<>();
        boolean exact = true;
        boolean allDck = true;
        boolean all100 = true;
        for (DeckSpec d : decks) {
            String key = Names.canonical(d.deck());
            if (byName.put(key, d) != null) {
                throw new IOException("Duplicate canonical deck name in manifest: " + d.deck());
            }
            exact &= "EXACT".equals(d.status());
            try (InputStream in = assets.open(prefix + d.dck())) {
                all100 &= ForgeDeckCounter.countCards(in) == 100;
            } catch (IOException e) {
                allDck = false;
                all100 = false;
            }
        }

        Map<String, Integer> games = new HashMap<>();
        Map<String, Integer> pairs = new HashMap<>();
        for (PodSpec p : pods) {
            if (p.members().size() != 3 && p.members().size() != 4) {
                throw new IOException("Pod must contain 3 or 4 decks: round " + p.round() + " pod " + p.pod());
            }
            List<String> canonicalMembers = new ArrayList<>();
            for (String member : p.members()) {
                String key = Names.canonical(member);
                if (!byName.containsKey(key)) {
                    throw new IOException("Schedule deck not found in manifest: " + member);
                }
                canonicalMembers.add(key);
                increment(games, key);
            }
            for (int i = 0; i < canonicalMembers.size(); i++) {
                for (int j = i + 1; j < canonicalMembers.size(); j++) {
                    String a = canonicalMembers.get(i);
                    String b = canonicalMembers.get(j);
                    String pairKey = a.compareTo(b) <= 0 ? a + "\u0000" + b : b + "\u0000" + a;
                    increment(pairs, pairKey);
                }
            }
        }

        int gamesPerDeck = uniformValue(games, byName.size(), "games per deck");
        int meetingsPerPair = uniformValue(pairs, 171, "pair meetings");

        ValidationReport report = new ValidationReport(
                decks.size(), pods.size(), pairs.size(), meetingsPerPair, gamesPerDeck,
                exact, allDck, all100
        );
        if (!report.passesStrictGate()) {
            throw new IOException("STRICT GATE failed: " + report.summary());
        }
        return new HousePackage(decks, byName, pods, report);
    }

    private static void increment(Map<String, Integer> values, String key) {
        Integer current = values.get(key);
        values.put(key, current == null ? 1 : current + 1);
    }

    private static int uniformValue(Map<String, Integer> values, int expectedEntries, String label) throws IOException {
        if (values.size() != expectedEntries) {
            throw new IOException(label + " entry count=" + values.size() + ", expected=" + expectedEntries);
        }
        Integer common = null;
        for (int v : values.values()) {
            if (common == null) common = v;
            else if (common != v) throw new IOException(label + " are not uniform");
        }
        return common == null ? 0 : common;
    }

    private static List<DeckSpec> loadManifest(AssetSource assets, String path) throws IOException {
        List<List<String>> rows;
        try (InputStream in = assets.open(path)) {
            rows = Csv.readAll(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        if (rows.isEmpty()) throw new IOException("Empty manifest");
        Map<String, Integer> header = headerMap(rows.get(0));
        List<DeckSpec> out = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            out.add(new DeckSpec(
                    get(r, header, "deck"), get(r, header, "source"), get(r, header, "commanders"),
                    get(r, header, "dck"), get(r, header, "status"), get(r, header, "detail")
            ));
        }
        if (out.size() != 19) throw new IOException("Manifest has " + out.size() + " decks; expected 19");
        return out;
    }

    private static List<PodSpec> loadSchedule(AssetSource assets, String path) throws IOException {
        List<List<String>> rows;
        try (InputStream in = assets.open(path)) {
            rows = Csv.readAll(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        if (rows.isEmpty()) throw new IOException("Empty schedule");
        Map<String, Integer> header = headerMap(rows.get(0));
        List<PodSpec> out = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            int round = Integer.parseInt(get(r, header, "Round"));
            int pod = Integer.parseInt(get(r, header, "Pod"));
            int declaredSize = Integer.parseInt(get(r, header, "Size"));
            String[] names = get(r, header, "Decks").split("\\|");
            List<String> members = new ArrayList<>();
            for (String n : names) members.add(n.trim());
            if (members.size() != declaredSize) {
                throw new IOException("Declared pod size mismatch at round " + round + " pod " + pod);
            }
            out.add(new PodSpec(round, pod, members));
        }
        if (out.size() != 95) throw new IOException("Schedule has " + out.size() + " pods; expected 95");
        return out;
    }

    private static Map<String, Integer> headerMap(List<String> headers) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) map.put(headers.get(i), i);
        return map;
    }

    private static String get(List<String> row, Map<String, Integer> header, String column) throws IOException {
        Integer idx = header.get(column);
        if (idx == null || idx >= row.size()) throw new IOException("Missing CSV column: " + column);
        return row.get(idx);
    }
}
