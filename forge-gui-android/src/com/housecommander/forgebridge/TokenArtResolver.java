/*
 * HOUSE Commander Lab procedural token art metadata.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import java.util.Locale;

/**
 * Resolves every Forge token to a deterministic visual style.
 *
 * <p>Known token families get themed art direction. Unknown or newly-added
 * Magic token types still receive deterministic procedural art, so HOUSE never
 * falls back to a blank rectangle merely because a token was not pre-listed.
 */
public final class TokenArtResolver {
    private static final StyleRule[] RULES = new StyleRule[]{
            rule("treasure", "Treasure", "◆", 0xF7C948, 0x7A431D,
                    "treasure", "gold token"),
            rule("food", "Food", "✿", 0xEAAE6A, 0x6D8F45,
                    "food"),
            rule("clue", "Clue", "?", 0xD6E6F2, 0x3E6A8C,
                    "clue"),
            rule("blood", "Blood", "♥", 0xD94B4B, 0x551B2C,
                    "blood"),
            rule("map", "Map", "✦", 0xD8B36A, 0x6A5537,
                    "map"),
            rule("powerstone", "Powerstone", "◇", 0x82C4D8, 0x394E75,
                    "powerstone"),
            rule("incubator", "Incubator", "◉", 0xB4C97A, 0x465A37,
                    "incubator"),
            rule("copy", "Copy", "◇", 0xA98AD8, 0x355C9A,
                    "copy token", "token copy"),
            rule("role", "Role", "✦", 0xC78AD8, 0x5E3B7A,
                    " role", "role token"),
            rule("squirrel", "Squirrel", "S", 0xA87B4F, 0x4D7A42,
                    "squirrel"),
            rule("saproling", "Saproling", "✿", 0x86B84C, 0x315C38,
                    "saproling"),
            rule("plant", "Plant", "♣", 0x8CCB62, 0x2F6D3C,
                    "plant"),
            rule("zombie", "Zombie", "☠", 0x7B8E86, 0x333D4E,
                    "zombie"),
            rule("spirit", "Spirit", "✧", 0xD7E8FF, 0x7484B8,
                    "spirit"),
            rule("goblin", "Goblin", "!", 0xD95D39, 0x6A2B2B,
                    "goblin"),
            rule("elf", "Elf", "♠", 0x66A65C, 0x2D5B45,
                    "elf"),
            rule("soldier", "Soldier", "♜", 0xC7C8C4, 0x596579,
                    "soldier"),
            rule("warrior", "Warrior", "⚔", 0xD89048, 0x6A3E32,
                    "warrior"),
            rule("knight", "Knight", "♞", 0xD8D5C8, 0x4F5266,
                    "knight"),
            rule("angel", "Angel", "✦", 0xFFF0B5, 0x9A78C2,
                    "angel"),
            rule("dragon", "Dragon", "△", 0xE04F3E, 0x6A2535,
                    "dragon"),
            rule("beast", "Beast", "B", 0x8B6A48, 0x47643C,
                    "beast"),
            rule("bird", "Bird", "V", 0x9FD7E8, 0x4D6E9A,
                    "bird"),
            rule("human", "Human", "H", 0xD8B08A, 0x67546C,
                    "human"),
            rule("citizen", "Citizen", "C", 0xE3C998, 0x6B6675,
                    "citizen"),
            rule("cat", "Cat", "C", 0xE3B76F, 0x786046,
                    "cat"),
            rule("dog", "Dog", "D", 0xC89C72, 0x5E5147,
                    "dog"),
            rule("wolf", "Wolf", "W", 0x9EA8B0, 0x45515F,
                    "wolf"),
            rule("vampire", "Vampire", "V", 0xA74256, 0x3C2038,
                    "vampire"),
            rule("demon", "Demon", "D", 0x7E4A78, 0x261E35,
                    "demon"),
            rule("devil", "Devil", "!", 0xE45D3D, 0x6F2836,
                    "devil"),
            rule("elemental", "Elemental", "✦", 0x55A9A6, 0x514C91,
                    "elemental"),
            rule("insect", "Insect", "I", 0xA5B34E, 0x4D6130,
                    "insect"),
            rule("rat", "Rat", "R", 0x82776F, 0x3D3542,
                    "rat"),
            rule("snake", "Snake", "S", 0x72A35D, 0x345B42,
                    "snake"),
            rule("spider", "Spider", "✣", 0x6D815C, 0x372E42,
                    "spider"),
            rule("faerie", "Faerie", "✧", 0x9F82D8, 0x3D5C9D,
                    "faerie"),
            rule("merfolk", "Merfolk", "M", 0x58A9C7, 0x2E557A,
                    "merfolk"),
            rule("dinosaur", "Dinosaur", "D", 0xC57645, 0x47613B,
                    "dinosaur"),
            rule("horror", "Horror", "H", 0x695A88, 0x26263D,
                    "horror"),
            rule("thopter", "Thopter", "⚙", 0x9FB7C4, 0x465A66,
                    "thopter"),
            rule("servo", "Servo", "⚙", 0xB7B1A6, 0x55585F,
                    "servo"),
            rule("construct", "Construct", "⚙", 0x9A9D9F, 0x4B5358,
                    "construct"),
            rule("golem", "Golem", "◆", 0x9A9183, 0x4A4E50,
                    "golem"),
            rule("myr", "Myr", "◇", 0xAAB9C1, 0x4D5E68,
                    "myr"),
            rule("eldrazi", "Eldrazi", "◈", 0xA59BB0, 0x4E465C,
                    "eldrazi", "scion", "spawn"),
            rule("phyrexian", "Phyrexian", "Φ", 0xB4B5A6, 0x4F394D,
                    "phyrexian"),
            rule("germ", "Germ", "•", 0x8B8F7A, 0x41493E,
                    "germ"),
            rule("pest", "Pest", "•", 0x73965A, 0x3F4D3D,
                    "pest"),
            rule("fractal", "Fractal", "◇", 0x61B59C, 0x3E4D91,
                    "fractal"),
            rule("army", "Army", "♟", 0x7B786A, 0x3D3A48,
                    "army"),
            rule("shard", "Shard", "◆", 0x9ED5E2, 0x665CA3,
                    "shard")
    };

    private TokenArtResolver() {
    }

    public static TokenArtSpec resolve(LiveGameState.CardState card) {
        if (card == null || !card.token()) {
            return null;
        }

        String name = safe(card.name());
        String typeLine = safe(card.typeLine());
        String colorKey = safe(card.colorKey()).toUpperCase(Locale.ROOT);
        String haystack = (name + " " + typeLine).toLowerCase(Locale.ROOT);

        StyleRule matched = null;
        for (StyleRule rule : RULES) {
            if (rule.matches(haystack)) {
                matched = rule;
                break;
            }
        }

        boolean fallback = matched == null;
        int primary;
        int secondary;
        String family;
        String glyph;
        String familyTitle;

        if (matched != null) {
            primary = matched.primaryRgb;
            secondary = matched.secondaryRgb;
            family = matched.key;
            glyph = matched.glyph;
            familyTitle = matched.title;
        } else {
            int[] palette = paletteFor(colorKey, signature(name, typeLine, colorKey,
                    card.power(), card.toughness()).hashCode());
            primary = palette[0];
            secondary = palette[1];
            family = "procedural";
            glyph = firstGlyph(name);
            familyTitle = genericFamilyTitle(typeLine, name);
        }

        return new TokenArtSpec(
                family,
                trimTokenWord(name),
                familyTitle,
                typeLine,
                card.creature() ? card.power() + "/" + card.toughness() : "",
                colorKey,
                glyph,
                primary,
                secondary,
                signature(name, typeLine, colorKey, card.power(), card.toughness()),
                fallback
        );
    }

    public static String signature(LiveGameState.CardState card) {
        if (card == null) {
            return "";
        }
        return signature(
                card.name(),
                card.typeLine(),
                card.colorKey(),
                card.power(),
                card.toughness()
        );
    }

    public static int knownFamilyCount() {
        return RULES.length;
    }

    private static String signature(
            String name,
            String typeLine,
            String colorKey,
            int power,
            int toughness
    ) {
        return normalize(name)
                + "|"
                + normalize(typeLine)
                + "|"
                + normalize(colorKey)
                + "|"
                + power
                + "/"
                + toughness;
    }

    private static String normalize(String value) {
        String clean = safe(value).toLowerCase(Locale.ROOT).trim();
        clean = clean.replaceAll("[^a-z0-9]+", "-");
        while (clean.startsWith("-")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("-")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private static String trimTokenWord(String value) {
        String clean = safe(value).trim();
        if (clean.toLowerCase(Locale.ROOT).endsWith(" token")) {
            clean = clean.substring(0, clean.length() - 6).trim();
        }
        return clean.isEmpty() ? "Token" : clean;
    }

    private static String genericFamilyTitle(String typeLine, String name) {
        String clean = safe(typeLine).replace("Token", "").trim();
        int dash = clean.indexOf('-');
        if (dash >= 0 && dash + 1 < clean.length()) {
            clean = clean.substring(dash + 1).trim();
        }
        if (!clean.isEmpty()) {
            return clean;
        }
        return trimTokenWord(name);
    }

    private static String firstGlyph(String name) {
        String clean = trimTokenWord(name);
        if (clean.isEmpty()) {
            return "✦";
        }
        return clean.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static int[] paletteFor(String colorKey, int seed) {
        if (colorKey.contains("WUBRG") || colorKey.length() >= 4) {
            return new int[]{0xD3A84B, 0x6D4A8D};
        }
        if (colorKey.contains("W")) {
            return new int[]{0xF0E4C6, 0xA78A54};
        }
        if (colorKey.contains("U")) {
            return new int[]{0x5BA6D6, 0x31558A};
        }
        if (colorKey.contains("B")) {
            return new int[]{0x66516D, 0x282536};
        }
        if (colorKey.contains("R")) {
            return new int[]{0xD86645, 0x762D35};
        }
        if (colorKey.contains("G")) {
            return new int[]{0x69A85B, 0x345D3E};
        }

        int selector = Math.abs(seed % 5);
        if (selector == 0) {
            return new int[]{0x6B9FB8, 0x3D526B};
        } else if (selector == 1) {
            return new int[]{0xA488C2, 0x4F456F};
        } else if (selector == 2) {
            return new int[]{0xC18A5B, 0x654836};
        } else if (selector == 3) {
            return new int[]{0x7EA26C, 0x3E5B48};
        }
        return new int[]{0xA2A5A8, 0x4C555B};
    }

    private static StyleRule rule(
            String key,
            String title,
            String glyph,
            int primaryRgb,
            int secondaryRgb,
            String... terms
    ) {
        return new StyleRule(key, title, glyph, primaryRgb, secondaryRgb, terms);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class StyleRule {
        private final String key;
        private final String title;
        private final String glyph;
        private final int primaryRgb;
        private final int secondaryRgb;
        private final String[] terms;

        private StyleRule(
                String key,
                String title,
                String glyph,
                int primaryRgb,
                int secondaryRgb,
                String[] terms
        ) {
            this.key = key;
            this.title = title;
            this.glyph = glyph;
            this.primaryRgb = primaryRgb;
            this.secondaryRgb = secondaryRgb;
            this.terms = terms == null ? new String[0] : terms.clone();
        }

        private boolean matches(String haystack) {
            for (String term : terms) {
                if (haystack.contains(term)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final class TokenArtSpec {
        private final String familyKey;
        private final String displayName;
        private final String familyTitle;
        private final String typeLine;
        private final String powerToughness;
        private final String colorKey;
        private final String glyph;
        private final int primaryRgb;
        private final int secondaryRgb;
        private final String signature;
        private final boolean proceduralFallback;

        private TokenArtSpec(
                String familyKey,
                String displayName,
                String familyTitle,
                String typeLine,
                String powerToughness,
                String colorKey,
                String glyph,
                int primaryRgb,
                int secondaryRgb,
                String signature,
                boolean proceduralFallback
        ) {
            this.familyKey = familyKey;
            this.displayName = displayName;
            this.familyTitle = familyTitle;
            this.typeLine = typeLine;
            this.powerToughness = powerToughness;
            this.colorKey = colorKey;
            this.glyph = glyph;
            this.primaryRgb = primaryRgb;
            this.secondaryRgb = secondaryRgb;
            this.signature = signature;
            this.proceduralFallback = proceduralFallback;
        }

        public String familyKey() {
            return familyKey;
        }

        public String displayName() {
            return displayName;
        }

        public String familyTitle() {
            return familyTitle;
        }

        public String typeLine() {
            return typeLine;
        }

        public String powerToughness() {
            return powerToughness;
        }

        public String colorKey() {
            return colorKey;
        }

        public String glyph() {
            return glyph;
        }

        public int primaryRgb() {
            return primaryRgb;
        }

        public int secondaryRgb() {
            return secondaryRgb;
        }

        public String signature() {
            return signature;
        }

        public boolean proceduralFallback() {
            return proceduralFallback;
        }
    }
}
