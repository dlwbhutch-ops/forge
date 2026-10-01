package com.housecommander.forgebridge;

import forge.StaticData;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.item.PaperCard;
import forge.util.FileSection;
import forge.util.FileUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Translates display/face names to Forge's existing card identities without swapping cards. */
public final class ForgeDeckLoader {
    private static final Pattern CARD_LINE = Pattern.compile("^(\\d+)\\s+(.+)$");
    private ForgeDeckLoader() { }

    public static Deck load(File file) {
        Map<String, List<String>> sections = FileSection.parseSections(FileUtil.readFile(file));
        for (Map.Entry<String, List<String>> section : sections.entrySet()) {
            if (DeckSection.smartValueOf(section.getKey()) == null) continue;
            List<String> normalized = new ArrayList<>();
            for (String line : section.getValue()) {
                Matcher match = CARD_LINE.matcher(line);
                normalized.add(match.matches()
                        ? match.group(1) + " " + resolveRequest(match.group(2))
                        : line);
            }
            section.setValue(normalized);
        }
        return DeckSerializer.fromSections(sections);
    }

    private static String resolveRequest(String request) {
        int separator = request.indexOf('|');
        String name = (separator < 0 ? request : request.substring(0, separator)).trim();
        String suffix = separator < 0 ? "" : request.substring(separator);
        PaperCard card = StaticData.instance().getCommonCards().getCard(request);
        if (card != null && !card.getRules().isUnsupported()) return request;
        card = lookup(name);
        if (card == null && name.contains("/")) {
            String faces = name.replaceAll("\\s*/+\\s*", " // ");
            card = lookup(faces);
            if (card == null) card = lookup(faces.split(" // ", 2)[0]);
        }
        if (card == null && name.endsWith(")") && name.lastIndexOf(" (") > 0) {
            card = lookup(name.substring(0, name.lastIndexOf(" (")));
        }
        // Unresolved names remain unresolved and are rejected by the strict bridge gate.
        return card == null ? request : card.getName() + suffix;
    }

    private static PaperCard lookup(String name) {
        PaperCard card = StaticData.instance().getCommonCards().getUniqueByName(name.trim());
        return card != null && !card.getRules().isUnsupported() ? card : null;
    }
}
