package com.housecommander.token;

import com.housecommander.forgebridge.LiveGameState;
import java.util.Locale;

/** Original, resolution-independent token illustrations shared by both platforms. */
public final class TokenArtResolver {
    public interface Painter {
        void polygon(int color, float... xy);
        void circle(int color, float x, float y, float radius);
        void rect(int color, float x, float y, float width, float height);
        void line(int color, float width, float... xy);
        void text(int color, float size, float x, float y, String value);
    }
    private static volatile TokenRegistryLoader registry;
    private static volatile boolean preferIllustrations = true;
    private static final int INK = 0xff14232c;
    private static final int LIGHT = 0xfff0e8cf;
    private TokenArtResolver() {}
    public static void install(TokenRegistryLoader catalog) { registry = catalog; }
    public static int catalogSize() { return registry == null ? 0 : registry.size(); }
    public static void preferIllustrations(boolean prefer) { preferIllustrations = prefer; }
    public static boolean useIllustration(LiveGameState.CardState card) {
        return card != null && card.token() && !card.faceDown()
                && (preferIllustrations || card.imageUrl().isEmpty());
    }

    /** Uses the full runtime identity too: copied and dynamically created tokens are distinct. */
    public static String identity(LiveGameState.CardState c) {
        return c.name() + "|" + c.typeLine() + "|" + c.colors() + "|"
                + c.power() + "/" + c.toughness() + "|" + c.keywords() + "|" + c.oracle();
    }

    public static void paint(LiveGameState.CardState card, Painter p) {
        TokenDefinition d = registry == null ? null : registry.resolve(card);
        String type = card.typeLine().isEmpty() && d != null
                ? String.join(" ", d.types) + " " + String.join(" ", d.subtypes) : card.typeLine();
        String species = (card.name() + " " + type).toLowerCase(Locale.ROOT);
        int seed = identity(card).hashCode();
        int accent = 0xff000000 | (0x557777 ^ (seed & 0x3f3f3f));
        int metal = 0xffa6bcc4;
        int skin = 0xffb78862 + ((seed >>> 18) & 0x181818);
        p.rect(INK, 0, 0, 200, 280);
        p.rect(accent, 7, 7, 186, 266);
        p.rect(0xff203d45, 12, 34, 176, 195);
        // Identity-specific constellation and landscape, reproducible without RNG or downloads.
        for (int i = 0; i < 14; i++) {
            int v = Integer.rotateLeft(seed ^ (i * 0x45d9f3b), i);
            p.circle(0xff80979b, 18 + ((v >>> 1) % 165), 42 + ((v >>> 10) % 95), 1 + (i % 2));
        }
        p.polygon(0xff294952, 12, 209, 50, 164, 77, 190, 120, 143, 188, 191, 188, 229, 12, 229);
        p.circle(0xff435f66, 105, 126, 68);

        if (species.contains("treasure") || species.contains("gold")) {
            p.rect(INK, 42, 112, 116, 76); p.rect(0xffab6e3f, 47, 117, 106, 65);
            p.polygon(0xffd9b45c, 45, 114, 54, 90, 144, 90, 155, 114);
            p.rect(0xffead081, 68, 117, 9, 65); p.rect(0xffead081, 123, 117, 9, 65);
            for (int i = 0; i < 9; i++) p.circle(0xfff4d77b, 62 + i * 9, 110 - i % 3 * 6, 9);
            p.rect(INK, 92, 141, 18, 20); p.circle(LIGHT, 101, 149, 3);
        } else if (species.contains("food")) {
            p.circle(LIGHT, 100, 165, 52); p.circle(0xffa7633a, 92, 147, 34);
            p.circle(0xffc56e40, 123, 151, 21); p.polygon(0xff73a879, 57, 151, 80, 119, 104, 150);
            p.line(LIGHT, 4, 72, 106, 65, 91, 73, 76); p.line(LIGHT, 3, 114, 101, 123, 84, 119, 68);
        } else if (species.contains("clue") || species.contains("map")) {
            p.polygon(LIGHT, 40, 100, 88, 87, 110, 99, 157, 85, 162, 183, 114, 199, 90, 185, 45, 201);
            p.line(accent, 3, 64, 125, 126, 149, 89, 171, 139, 177);
            p.circle(INK, 132, 121, 29); p.circle(0xffaadbe0, 132, 121, 22);
            p.line(INK, 12, 113, 145, 90, 178);
        } else if (species.contains("blood")) {
            p.rect(metal, 75, 84, 50, 18); p.polygon(LIGHT, 78, 104, 122, 104, 148, 176, 135, 204, 65, 204, 52, 176);
            p.polygon(0xffa94559, 64, 163, 136, 163, 141, 178, 130, 195, 70, 195, 59, 178);
        } else if (species.contains("powerstone") || species.contains("shard")) {
            p.polygon(INK, 100, 61, 157, 136, 128, 206, 72, 206, 43, 136);
            p.polygon(0xff99e5e3, 100, 70, 147, 138, 100, 192, 53, 138);
            p.polygon(0xffd1faf1, 100, 70, 100, 192, 53, 138);
        } else if (species.contains("incubator") || species.contains("egg")) {
            p.circle(INK, 100, 147, 57); p.circle(LIGHT, 100, 147, 49);
            p.line(accent, 7, 99, 99, 85, 121, 117, 151, 96, 191);
            p.circle(accent, 117, 159, 15);
        } else if (species.contains("dragon") || species.contains("drake") || species.contains("demon")
                || species.contains("bat") || species.contains("gargoyle")) {
            p.polygon(accent, 91, 132, 24, 69, 28, 155, 58, 143, 74, 164,
                    100, 130, 172, 67, 170, 164, 142, 145, 119, 165);
            p.polygon(LIGHT, 87, 91, 125, 83, 149, 101, 130, 116, 111, 108,
                    111, 165, 143, 195, 97, 204, 64, 184, 79, 168);
            p.line(accent, 12, 88, 194, 49, 204, 25, 187);
            p.circle(INK, 128, 97, 4); p.polygon(0xffdfaa54, 145, 110, 179, 114, 156, 126);
        } else if (species.contains("bird") || species.contains("angel") || species.contains("pegasus")) {
            p.polygon(LIGHT, 91, 135, 22, 74, 39, 141, 68, 165, 99, 151, 131, 166, 163, 140, 179, 71, 109, 130);
            if (species.contains("angel")) {
                p.circle(0xffe7cb75, 100, 78, 22); p.circle(0xff435f66, 100, 78, 17);
                humanoid(p, accent, skin, metal, species);
            } else {
                p.circle(LIGHT, 100, 130, 22); p.polygon(0xffe7cb75, 108, 120, 143, 131, 111, 139);
                p.circle(INK, 104, 123, 4);
            }
        } else if (species.contains("squirrel") || species.contains("rat") || species.contains("cat")
                || species.contains("wolf") || species.contains("dog") || species.contains("beast")
                || species.contains("bear") || species.contains("boar") || species.contains("elephant")
                || species.contains("rabbit") || species.contains("fox") || species.contains("horse")
                || species.contains("elk") || species.contains("goat") || species.contains("sheep")
                || species.contains("ox") || species.contains("camel")) {
            p.circle(accent, 60, 142, 37); p.circle(LIGHT, 104, 162, 39);
            p.circle(accent, 128, 116, 29); p.polygon(accent, 107, 104, 107, 77, 124, 93, 144, 78, 150, 108);
            p.circle(INK, 137, 114, 4); p.circle(INK, 153, 124, 5);
            p.line(LIGHT, 12, 80, 181, 75, 211); p.line(LIGHT, 12, 120, 184, 142, 211);
            if (species.contains("squirrel")) p.line(LIGHT, 18, 59, 168, 36, 125, 47, 87, 76, 93);
            if (species.contains("elephant")) p.line(accent, 12, 151, 125, 167, 161, 153, 172);
            if (species.contains("rabbit")) { p.line(accent, 9, 120, 95, 113, 58); p.line(accent, 9, 138, 96, 140, 62); }
            if (species.contains("goat") || species.contains("elk") || species.contains("ox")) {
                p.line(LIGHT, 5, 114, 101, 95, 72, 108, 62); p.line(LIGHT, 5, 145, 102, 160, 77, 148, 66);
            }
        } else if (species.contains("fish") || species.contains("shark") || species.contains("whale")) {
            p.polygon(accent, 34, 146, 74, 113, 131, 110, 158, 135, 180, 115, 177, 171, 158, 153, 107, 178, 65, 173);
            p.polygon(LIGHT, 82, 125, 105, 86, 122, 124); p.circle(INK, 57, 143, 5);
            p.line(LIGHT, 3, 106, 131, 112, 151, 102, 166);
        } else if (species.contains("snake") || species.contains("serpent") || species.contains("worm")) {
            p.line(accent, 29, 44, 187, 66, 204, 110, 197, 134, 164, 88, 135, 97, 100, 139, 95);
            p.circle(LIGHT, 143, 94, 22); p.circle(INK, 150, 89, 4);
            p.line(RED(), 3, 163, 98, 180, 98, 185, 90);
        } else if (species.contains("turtle") || species.contains("crab")) {
            p.circle(accent, 100, 150, 43); p.circle(LIGHT, 149, 133, 18);
            p.line(LIGHT, 10, 72, 172, 55, 192); p.line(LIGHT, 10, 119, 175, 140, 194);
            p.polygon(INK, 100, 111, 130, 143, 113, 179, 80, 179, 65, 145);
            p.polygon(metal, 100, 120, 121, 143, 109, 170, 85, 170, 76, 144);
            p.circle(INK, 154, 128, 4);
        } else if (species.contains("spider") || species.contains("insect") || species.contains("scorpion")) {
            for (int i = 0; i < 4; i++) {
                p.line(LIGHT, 5, 91, 128 + i * 11, 42 - i * 4, 98 + i * 32, 24, 125 + i * 24);
                p.line(LIGHT, 5, 109, 128 + i * 11, 158 + i * 4, 98 + i * 32, 176, 125 + i * 24);
            }
            p.circle(accent, 100, 144, 30); p.circle(LIGHT, 100, 112, 18);
            p.circle(INK, 94, 108, 4); p.circle(INK, 107, 108, 4);
        } else if (species.contains("plant") || species.contains("saproling") || species.contains("fungus")
                || species.contains("treefolk")) {
            p.line(0xff88b18a, 16, 100, 209, 103, 139, 87, 102);
            p.polygon(0xffa4c89c, 102, 145, 136, 88, 161, 80, 149, 130);
            p.polygon(0xff7faa8a, 94, 167, 46, 135, 36, 105, 81, 125);
            p.circle(LIGHT, 89, 92, 31); p.polygon(accent, 47, 94, 68, 63, 111, 67, 134, 94);
            p.circle(INK, 81, 96, 4); p.circle(INK, 99, 96, 4);
        } else if (species.contains("spirit") || species.contains("illusion") || species.contains("elemental")) {
            p.polygon(0xffaadcd1, 43, 211, 66, 160, 63, 117, 83, 87, 117, 86, 139, 121,
                    133, 163, 157, 213, 123, 197, 100, 216, 79, 198);
            p.circle(INK, 87, 123, 6); p.circle(INK, 112, 123, 6);
            p.line(LIGHT, 3, 42, 172, 31, 149, 45, 135);
        } else if (species.contains("thopter") || species.contains("myr") || species.contains("construct")
                || species.contains("golem") || species.contains("servo")) {
            p.polygon(metal, 62, 108, 37, 82, 20, 119, 65, 148, 132, 148, 179, 119, 161, 81, 138, 109);
            p.rect(INK, 70, 102, 61, 79); p.rect(metal, 75, 107, 51, 69);
            p.circle(accent, 100, 139, 17); p.circle(LIGHT, 100, 139, 8);
            p.line(metal, 10, 82, 181, 66, 207); p.line(metal, 10, 117, 181, 134, 207);
        } else if (species.contains("eldrazi") || species.contains("tentacle") || species.contains("ooze")) {
            for (int i = 0; i < 6; i++) p.line(LIGHT, 8, 100, 144, 55 + i * 18, 179, 29 + i * 28, 210);
            p.circle(accent, 100, 115, 44); p.circle(LIGHT, 100, 116, 21); p.circle(INK, 100, 116, 11);
        } else if (!card.creature() && (species.contains("artifact") || species.contains("equipment"))) {
            p.circle(metal, 100, 143, 52); p.circle(INK, 100, 143, 39);
            for(int i=0;i<8;i++) {
                double angle=i*Math.PI/4;
                p.circle(metal,100+(float)Math.cos(angle)*49,143+(float)Math.sin(angle)*49,12);
            }
            p.polygon(LIGHT,100,105,130,143,100,181,70,143);p.circle(accent,100,143,16);
        } else if (!card.creature() && (species.contains("dungeon") || species.contains("land"))) {
            p.rect(metal,52,118,96,86);p.rect(LIGHT,42,93,24,112);p.rect(LIGHT,134,93,24,112);
            p.polygon(accent,38,94,54,70,70,94);p.polygon(accent,130,94,146,70,162,94);
            p.rect(INK,88,155,24,49);p.rect(INK,51,108,8,18);p.rect(INK,143,108,8,18);
        } else if (!card.creature()) {
            p.polygon(LIGHT,100,78,148,103,140,171,100,207,60,171,52,103);
            p.polygon(accent,100,92,135,111,127,163,100,187,73,163,65,111);
            p.polygon(LIGHT,100,110,109,134,135,139,112,151,116,177,100,161,81,176,87,151,64,138,91,134);
        } else {
            humanoid(p, accent, skin, metal, species);
        }
        p.rect(INK, 12, 9, 176, 25); p.text(LIGHT, 11, 100, 26, shorten(card.name(), 25));
        p.rect(INK, 12, 229, 176, 42); p.text(LIGHT, 9, 100, 246, shorten(type, 33));
        p.text(LIGHT, 12, 100, 263, card.creature() ? card.power() + "/" + card.toughness() : "TOKEN");
    }

    private static void humanoid(Painter p, int accent, int skin, int metal, String species) {
        if (species.contains("zombie") || species.contains("skeleton")) skin = 0xffadc3a3;
        if (species.contains("goblin") || species.contains("orc")) skin = 0xff8fb16b;
        p.polygon(INK, 72, 130, 128, 130, 149, 186, 118, 194, 100, 171, 81, 194, 50, 186);
        p.polygon(accent, 76, 134, 123, 134, 136, 183, 63, 183);
        p.circle(skin, 100, 105, 24); p.circle(INK, 91, 105, 3); p.circle(INK, 110, 105, 3);
        p.line(skin, 10, 70, 143, 54, 166); p.line(skin, 10, 128, 143, 151, 155);
        p.line(metal, 12, 85, 184, 79, 214); p.line(metal, 12, 116, 184, 122, 214);
        if (species.contains("wizard") || species.contains("warlock") || species.contains("shaman")
                || species.contains("druid") || species.contains("cleric")) {
            p.polygon(accent, 65, 89, 100, 45, 134, 89); p.line(LIGHT, 5, 151, 91, 151, 209);
            p.circle(0xffaeeae5, 151, 81, 13); p.circle(LIGHT, 151, 81, 6);
        } else if (species.contains("warrior") || species.contains("soldier") || species.contains("knight")) {
            p.polygon(metal, 76, 96, 81, 77, 116, 77, 126, 96, 107, 92, 107, 120, 95, 120, 95, 92);
            p.polygon(metal, 54, 132, 19, 121, 21, 165, 44, 184, 62, 157);
            p.polygon(LIGHT, 149, 143, 155, 66, 165, 91, 157, 143);
            p.line(0xffcda669, 6, 141, 142, 166, 142);
        } else if (species.contains("rogue") || species.contains("assassin") || species.contains("ninja")) {
            p.polygon(accent, 69, 113, 100, 65, 131, 113, 116, 126, 100, 101, 83, 127);
            p.polygon(LIGHT, 150, 156, 181, 137, 160, 168);
        } else {
            p.line(LIGHT, 5, 153, 88, 153, 204);
            p.polygon(metal, 153, 72, 140, 96, 165, 96);
        }
        if (species.contains("elf")) {
            p.polygon(skin, 78, 101, 60, 87, 80, 117); p.polygon(skin, 122, 101, 139, 87, 121, 117);
        }
        if (species.contains("human")) p.line(LIGHT, 2, 92, 114, 107, 114);
        if (species.contains("skeleton")) {
            p.line(INK, 3, 83, 147, 117, 147); p.line(INK, 3, 83, 157, 117, 157);
        }
    }
    private static String shorten(String value, int limit) {
        return value.length() > limit ? value.substring(0, limit - 1) + "…" : value;
    }
    private static int RED() { return 0xffdf7884; }
}
