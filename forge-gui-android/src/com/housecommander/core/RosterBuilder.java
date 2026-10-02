package com.housecommander.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a HOUSE tournament package from a 19-deck active roster while keeping
 * the proven 95-pod triple-round-robin template intact.
 *
 * This class has no Android dependencies so the exact same roster logic can be
 * reused by Android, macOS, and Windows shells.
 */
public final class RosterBuilder {
    public static final int HOUSE_ROSTER_SIZE = 19;

    private RosterBuilder() {}

    public static HousePackage build(HousePackage template, List<DeckSpec> selected) {
        if (template == null) {
            throw new IllegalArgumentException("Template HOUSE package must not be null");
        }
        if (selected == null || selected.size() != HOUSE_ROSTER_SIZE) {
            throw new IllegalArgumentException(
                    "HOUSE roster must contain exactly " + HOUSE_ROSTER_SIZE + " decks"
            );
        }
        if (template.decks().size() != HOUSE_ROSTER_SIZE) {
            throw new IllegalArgumentException(
                    "HOUSE template must contain exactly " + HOUSE_ROSTER_SIZE + " deck slots"
            );
        }

        Set<String> displayNames = new HashSet<String>();
        Set<String> engineNames = new HashSet<String>();
        List<DeckSpec> roster = new ArrayList<DeckSpec>(selected.size());
        Map<String, DeckSpec> byName = new LinkedHashMap<String, DeckSpec>();
        for (DeckSpec deck : selected) {
            if (deck == null) {
                throw new IllegalArgumentException("HOUSE roster contains a null deck");
            }
            String displayKey = Names.canonical(deck.deck());
            if (displayKey.isEmpty() || !displayNames.add(displayKey)) {
                throw new IllegalArgumentException("Duplicate HOUSE roster deck name: " + deck.deck());
            }
            String engineKey = Names.canonical(deck.engineName());
            if (engineKey.isEmpty() || !engineNames.add(engineKey)) {
                throw new IllegalArgumentException(
                        "Two selected decks resolve to the same Forge deck name: " + deck.engineName()
                );
            }
            roster.add(deck);
            byName.put(displayKey, deck);
        }

        Map<String, Integer> templateSlot = new HashMap<String, Integer>();
        for (int i = 0; i < template.decks().size(); i++) {
            templateSlot.put(Names.canonical(template.decks().get(i).deck()), Integer.valueOf(i));
        }

        List<PodSpec> remapped = new ArrayList<PodSpec>(template.schedule().size());
        for (PodSpec pod : template.schedule()) {
            List<String> members = new ArrayList<String>(pod.members().size());
            for (String templateName : pod.members()) {
                Integer slot = templateSlot.get(Names.canonical(templateName));
                if (slot == null) {
                    throw new IllegalArgumentException(
                            "Template schedule references an unknown deck: " + templateName
                    );
                }
                members.add(roster.get(slot.intValue()).deck());
            }
            remapped.add(new PodSpec(pod.round(), pod.pod(), members));
        }

        ValidationReport report = new ValidationReport(
                HOUSE_ROSTER_SIZE,
                remapped.size(),
                171,
                3,
                19,
                true,
                true,
                true
        );
        if (!report.passesStrictGate()) {
            throw new IllegalStateException("Remapped HOUSE roster failed strict schedule validation");
        }
        return new HousePackage(roster, byName, remapped, report);
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
            byte[] bytes = digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
