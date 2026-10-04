package com.housecommander.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a HOUSE tournament package from an active Commander roster.
 *
 * <p>The bundled 19-deck roster keeps its proven 95-pod exact schedule. Any
 * other roster size at or above four decks gets a deterministic generated
 * schedule with no artificial maximum roster size. Generated schedules ensure
 * every unordered pair meets at least three times and then add only enough
 * balancing pods to keep games-per-deck within one game.
 */
public final class RosterBuilder {
    public static final int MIN_ROSTER_SIZE = 4;
    public static final int LEGACY_ROSTER_SIZE = 19;
    private static final int TARGET_PAIR_MEETINGS = 3;
    private static final int GENERATED_POD_SIZE = 4;

    private RosterBuilder() {}

    public static HousePackage build(HousePackage template, List<DeckSpec> selected) {
        if (template == null) {
            throw new IllegalArgumentException(
                    "Template HOUSE package must not be null"
            );
        }
        if (selected == null || selected.size() < MIN_ROSTER_SIZE) {
            throw new IllegalArgumentException(
                    "HOUSE roster must contain at least "
                            + MIN_ROSTER_SIZE
                            + " decks"
            );
        }

        Set<String> displayNames = new HashSet<String>();
        Set<String> engineNames = new HashSet<String>();
        List<DeckSpec> roster = new ArrayList<DeckSpec>(selected.size());
        Map<String, DeckSpec> byName = new LinkedHashMap<String, DeckSpec>();

        for (DeckSpec deck : selected) {
            if (deck == null) {
                throw new IllegalArgumentException(
                        "HOUSE roster contains a null deck"
                );
            }

            String displayKey = Names.canonical(deck.deck());
            if (displayKey.isEmpty() || !displayNames.add(displayKey)) {
                throw new IllegalArgumentException(
                        "Duplicate HOUSE roster deck name: " + deck.deck()
                );
            }

            String engineKey = Names.canonical(deck.engineName());
            if (engineKey.isEmpty() || !engineNames.add(engineKey)) {
                throw new IllegalArgumentException(
                        "Two selected decks resolve to the same Forge deck name: "
                                + deck.engineName()
                );
            }

            roster.add(deck);
            byName.put(displayKey, deck);
        }

        List<PodSpec> schedule;
        if (canUseLegacyTemplate(template, roster)) {
            schedule = remapLegacyTemplate(template, roster);
        } else {
            schedule = generateSchedule(roster);
        }

        ValidationReport report = analyze(
                roster,
                schedule,
                true,
                true,
                true
        );
        if (!report.passesStrictGate()) {
            throw new IllegalStateException(
                    "Generated HOUSE roster failed strict schedule validation: "
                            + report.summary()
            );
        }

        return new HousePackage(roster, byName, schedule, report);
    }

    private static boolean canUseLegacyTemplate(
            HousePackage template,
            List<DeckSpec> roster
    ) {
        return roster.size() == LEGACY_ROSTER_SIZE
                && template.decks().size() == LEGACY_ROSTER_SIZE
                && template.schedule().size() == 95;
    }

    private static List<PodSpec> remapLegacyTemplate(
            HousePackage template,
            List<DeckSpec> roster
    ) {
        Map<String, Integer> templateSlot = new HashMap<String, Integer>();
        for (int i = 0; i < template.decks().size(); i++) {
            templateSlot.put(
                    Names.canonical(template.decks().get(i).deck()),
                    Integer.valueOf(i)
            );
        }

        List<PodSpec> remapped =
                new ArrayList<PodSpec>(template.schedule().size());
        for (PodSpec pod : template.schedule()) {
            List<String> members =
                    new ArrayList<String>(pod.members().size());
            for (String templateName : pod.members()) {
                Integer slot = templateSlot.get(
                        Names.canonical(templateName)
                );
                if (slot == null) {
                    throw new IllegalArgumentException(
                            "Template schedule references an unknown deck: "
                                    + templateName
                    );
                }
                members.add(roster.get(slot.intValue()).deck());
            }
            remapped.add(
                    new PodSpec(pod.round(), pod.pod(), members)
            );
        }
        return remapped;
    }

    private static List<PodSpec> generateSchedule(List<DeckSpec> roster) {
        int count = roster.size();
        int[][] meetings = new int[count][count];
        int[] games = new int[count];
        int[] remaining = new int[count];
        Arrays.fill(remaining, TARGET_PAIR_MEETINGS * (count - 1));

        long unresolved = (long) TARGET_PAIR_MEETINGS
                * count
                * (count - 1L)
                / 2L;

        List<PodSpec> pods = new ArrayList<PodSpec>();
        while (unresolved > 0L) {
            int[] seats = new int[GENERATED_POD_SIZE];
            boolean[] used = new boolean[count];

            int seed = chooseSeed(remaining, games);
            seats[0] = seed;
            used[seed] = true;

            for (int seat = 1; seat < GENERATED_POD_SIZE; seat++) {
                int candidate = chooseCandidate(
                        meetings,
                        remaining,
                        games,
                        used,
                        seats,
                        seat
                );
                seats[seat] = candidate;
                used[candidate] = true;
            }

            List<String> members =
                    new ArrayList<String>(GENERATED_POD_SIZE);
            for (int index : seats) {
                games[index]++;
                members.add(roster.get(index).deck());
            }

            for (int a = 0; a < seats.length; a++) {
                for (int b = a + 1; b < seats.length; b++) {
                    int left = seats[a];
                    int right = seats[b];
                    int previous = meetings[left][right];

                    meetings[left][right] = previous + 1;
                    meetings[right][left] = previous + 1;

                    if (previous < TARGET_PAIR_MEETINGS) {
                        remaining[left]--;
                        remaining[right]--;
                        unresolved--;
                    }
                }
            }

            pods.add(
                    new PodSpec(
                            pods.size() + 1,
                            1,
                            members
                    )
            );
        }

        while (gameSpread(games) > 1) {
            int[] seats = lowestGameSeats(games, GENERATED_POD_SIZE);
            List<String> members =
                    new ArrayList<String>(GENERATED_POD_SIZE);

            for (int index : seats) {
                games[index]++;
                members.add(roster.get(index).deck());
            }
            for (int a = 0; a < seats.length; a++) {
                for (int b = a + 1; b < seats.length; b++) {
                    int left = seats[a];
                    int right = seats[b];
                    meetings[left][right]++;
                    meetings[right][left]++;
                }
            }

            pods.add(
                    new PodSpec(
                            pods.size() + 1,
                            1,
                            members
                    )
            );
        }

        return pods;
    }

    private static int chooseSeed(int[] remaining, int[] games) {
        int best = 0;
        for (int i = 1; i < remaining.length; i++) {
            if (remaining[i] > remaining[best]
                    || (remaining[i] == remaining[best]
                    && games[i] < games[best])) {
                best = i;
            }
        }
        return best;
    }

    private static int chooseCandidate(
            int[][] meetings,
            int[] remaining,
            int[] games,
            boolean[] used,
            int[] seats,
            int seatCount
    ) {
        int best = -1;
        long bestScore = Long.MIN_VALUE;

        for (int candidate = 0; candidate < used.length; candidate++) {
            if (used[candidate]) {
                continue;
            }

            int directDeficit = 0;
            int existingMeetings = 0;
            for (int i = 0; i < seatCount; i++) {
                int other = seats[i];
                directDeficit += Math.max(
                        0,
                        TARGET_PAIR_MEETINGS
                                - meetings[candidate][other]
                );
                existingMeetings += meetings[candidate][other];
            }

            long score = directDeficit * 1_000_000L
                    + remaining[candidate] * 1_000L
                    - games[candidate] * 100L
                    - existingMeetings;

            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }

        if (best < 0) {
            throw new IllegalStateException(
                    "Could not fill generated Commander pod"
            );
        }
        return best;
    }

    private static int[] lowestGameSeats(int[] games, int seatCount) {
        Integer[] order = new Integer[games.length];
        for (int i = 0; i < games.length; i++) {
            order[i] = Integer.valueOf(i);
        }

        Arrays.sort(
                order,
                (left, right) -> {
                    int byGames = Integer.compare(
                            games[left.intValue()],
                            games[right.intValue()]
                    );
                    return byGames != 0
                            ? byGames
                            : Integer.compare(
                                    left.intValue(),
                                    right.intValue()
                            );
                }
        );

        int[] seats = new int[seatCount];
        for (int i = 0; i < seatCount; i++) {
            seats[i] = order[i].intValue();
        }
        return seats;
    }

    private static int gameSpread(int[] games) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int gamesPlayed : games) {
            min = Math.min(min, gamesPlayed);
            max = Math.max(max, gamesPlayed);
        }
        return max - min;
    }

    private static ValidationReport analyze(
            List<DeckSpec> roster,
            List<PodSpec> schedule,
            boolean exactDecks,
            boolean allDckFilesPresent,
            boolean allDckFilesCountTo100
    ) {
        int count = roster.size();
        Map<String, Integer> index = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < count; i++) {
            index.put(
                    Names.canonical(roster.get(i).deck()),
                    Integer.valueOf(i)
            );
        }

        int[] games = new int[count];
        int[][] meetings = new int[count][count];

        for (PodSpec pod : schedule) {
            if (pod.members().size() < 3 || pod.members().size() > 4) {
                throw new IllegalStateException(
                        "Commander pod must contain 3 or 4 decks"
                );
            }

            int[] members = new int[pod.members().size()];
            for (int i = 0; i < pod.members().size(); i++) {
                Integer resolved = index.get(
                        Names.canonical(pod.members().get(i))
                );
                if (resolved == null) {
                    throw new IllegalStateException(
                            "Schedule references missing deck: "
                                    + pod.members().get(i)
                    );
                }
                members[i] = resolved.intValue();
                games[members[i]]++;
            }

            for (int a = 0; a < members.length; a++) {
                for (int b = a + 1; b < members.length; b++) {
                    meetings[members[a]][members[b]]++;
                    meetings[members[b]][members[a]]++;
                }
            }
        }

        int uniquePairs = 0;
        int minMeetings = Integer.MAX_VALUE;
        int maxMeetings = 0;
        for (int left = 0; left < count; left++) {
            for (int right = left + 1; right < count; right++) {
                int value = meetings[left][right];
                if (value > 0) {
                    uniquePairs++;
                }
                minMeetings = Math.min(minMeetings, value);
                maxMeetings = Math.max(maxMeetings, value);
            }
        }

        int minGames = Integer.MAX_VALUE;
        int maxGames = 0;
        for (int value : games) {
            minGames = Math.min(minGames, value);
            maxGames = Math.max(maxGames, value);
        }

        if (minMeetings == Integer.MAX_VALUE) {
            minMeetings = 0;
        }
        if (minGames == Integer.MAX_VALUE) {
            minGames = 0;
        }

        return new ValidationReport(
                count,
                schedule.size(),
                uniquePairs,
                minMeetings,
                maxMeetings,
                minGames,
                maxGames,
                exactDecks,
                allDckFilesPresent,
                allDckFilesCountTo100
        );
    }

    public static String fingerprint(List<DeckSpec> roster) {
        if (roster == null) {
            return "";
        }

        StringBuilder raw = new StringBuilder();
        for (DeckSpec d : roster) {
            if (d == null) {
                raw.append("<null>");
            } else {
                raw.append(d.deck()).append('\u001f')
                        .append(d.engineName()).append('\u001f')
                        .append(d.source()).append('\u001f')
                        .append(d.dck()).append('\u001f')
                        .append(d.status()).append('\u001f')
                        .append(d.detail());
            }
            raw.append('\u001e');
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    raw.toString().getBytes(StandardCharsets.UTF_8)
            );
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 unavailable",
                    impossible
            );
        }
    }
}
