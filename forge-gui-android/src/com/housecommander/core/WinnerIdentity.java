package com.housecommander.core;

import java.io.File;
import java.util.List;

/**
 * Resolves a Forge winner to exactly one participant of the current Commander pod.
 *
 * Forge sometimes renders the two faces of a double-faced commander with an em
 * dash instead of the " // " separator stored in the .dck metadata. Only the
 * winner-matching path permits this alias; roster and checkpoint keys remain
 * unchanged. Ambiguous aliases fail closed rather than award the wrong deck.
 */
public final class WinnerIdentity {
    private WinnerIdentity() {}

    /**
     * Strict bridge result produced from Forge's winning LobbyPlayer object,
     * matched to the exact registered seat. Unlike names, these values never
     * depend on "//", em dashes, localized names, or deck metadata.
     */
    public static String resolveVerifiedSeat(List<DeckSpec> decks, String result) {
        final String prefix = "HOUSE-VERIFIED-SEAT:";
        if (result == null || !result.startsWith(prefix)) {
            throw new IllegalStateException("Forge did not return a verified winning seat: " + result);
        }
        if (decks == null || decks.size() < 2) {
            throw new IllegalStateException("Cannot resolve a verified winner without a valid pod");
        }
        String seatText = result.substring(prefix.length());
        if (!seatText.matches("0|[1-9][0-9]*") || seatText.length() > 2) {
            throw new IllegalStateException("Malformed Forge winning seat: " + result);
        }
        int seat = Integer.parseInt(seatText);
        if (seat >= decks.size() || decks.get(seat) == null
                || Names.canonical(decks.get(seat).deck()).isEmpty()) {
            throw new IllegalStateException("Forge winning seat does not belong to this pod: " + result);
        }
        return decks.get(seat).deck();
    }

    public static String resolve(List<DeckSpec> decks, String winner) {
        if (winner == null || winner.trim().isEmpty()) {
            throw new IllegalStateException("Forge returned an empty winner");
        }
        if (decks == null || decks.isEmpty()) {
            throw new IllegalStateException("Forge winner cannot be matched against an empty pod");
        }

        final String wanted = Names.canonical(winner);
        final String wantedFaceAlias = canonicalFaceAlias(winner);
        DeckSpec match = null;

        for (DeckSpec candidate : decks) {
            if (candidate == null) continue;
            final String deckName = candidate.deck();
            final String engineName = candidate.engineName();
            final String fileName = candidate.dck() == null ? "" : new File(candidate.dck()).getName();
            final int dot = fileName.lastIndexOf('.');
            final String stem = dot > 0 ? fileName.substring(0, dot) : fileName;

            boolean exact = wanted.equals(Names.canonical(deckName))
                    || wanted.equals(Names.canonical(engineName))
                    || (!fileName.isEmpty() && wanted.equals(Names.canonical(fileName)))
                    || (!stem.isEmpty() && wanted.equals(Names.canonical(stem)));
            boolean twoFaceAlias = wantedFaceAlias.equals(canonicalFaceAlias(deckName))
                    || wantedFaceAlias.equals(canonicalFaceAlias(engineName));

            if (exact || twoFaceAlias) {
                if (match != null && match != candidate) {
                    throw new IllegalStateException(
                            "Ambiguous Forge winner maps to multiple decks in this pod: " + winner);
                }
                match = candidate;
            }
        }
        if (match != null) return match.deck();
        throw new IllegalStateException("Winner returned by Forge is not a member of this pod: " + winner);
    }

    private static String canonicalFaceAlias(String value) {
        return Names.canonical(value)
                .replace(" // ", " — ")
                .replace(" – ", " — ");
    }
}
