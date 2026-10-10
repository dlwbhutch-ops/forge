package com.housecommander.desktop;

import com.housecommander.forgebridge.LiveGameState;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Headless, cross-platform smoke gate for the complete painted-art ZIP flow. */
public final class DesktopPaintedTokenSmoke {
    private DesktopPaintedTokenSmoke() {}

    private static void add(ZipOutputStream archive, String filename, byte[] bytes) throws Exception {
        archive.putNextEntry(new ZipEntry(filename));
        archive.write(bytes);
        archive.closeEntry();
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff3355cc);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new AssertionError("PNG encoder missing");
        return out.toByteArray();
    }

    public static void verify() throws Exception {
        String original = System.getProperty("user.home");
        Path fakeHome = Files.createTempDirectory("house-mac-art-smoke-");
        System.setProperty("user.home", fakeHome.toString());
        try {
            File zip = fakeHome.resolve("art.zip").toFile();
            String manifest = "{\"schema\":1,\"artworks\":["
                    + "{\"id\":\"soldier\",\"name\":\"Soldier\",\"tokenId\":\"w_1_1_soldier\","
                    + "\"filename\":\"art/soldier.png\",\"type\":\"creatures\",\"power\":1,\"toughness\":1},"
                    + "{\"id\":\"eldrazi-variant\",\"name\":\"Eldrazi Horror\","
                    + "\"filename\":\"art/eldrazi_horror.png\",\"galleryOnly\":true,\"type\":\"creatures\"}"
                    + "]}";
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
                add(out, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
                add(out, "art/soldier.png", png());
                add(out, "art/eldrazi_horror.png", png());
            }
            DesktopPaintedTokenPack pack = new DesktopPaintedTokenPack();
            if (pack.install(zip) != 2 || pack.gallery().size() != 2) {
                throw new AssertionError("Imported token gallery is missing art");
            }
            LiveGameState.CardState soldier = new LiveGameState.CardState(
                    "Soldier Token", "t:w_1_1_soldier", "",
                    false, true, false, true, false, false, false, 1, 1,
                    Collections.emptyList(), 0, 0, false,
                    "Token Creature — Soldier", "white", Collections.emptyList());
            if (pack.find(soldier) == null) throw new AssertionError("Soldier token art was not matched");
            LiveGameState.CardState eldrazi = new LiveGameState.CardState(
                    "Eldrazi Horror", "t:unrecognized_token", "", false, true,
                    false, true, false, false, false, 3, 2, Collections.emptyList());
            if (pack.find(eldrazi) != null) {
                throw new AssertionError("Concept art incorrectly received playable token identity");
            }
            DesktopCardArtCache cache = new DesktopCardArtCache();
            if (cache.cardIcon(soldier, 200, 280, null) == null) {
                throw new AssertionError("Painted token cannot render on the desktop");
            }
            cache.shutdown();

            File bad = fakeHome.resolve("malformed.zip").toFile();
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(bad))) {
                add(out, "../unsafe.png", png());
            }
            try {
                pack.install(bad);
                throw new AssertionError("ZIP path traversal should be rejected");
            } catch (IOException expected) {
                // Previous art survives even if a replacement cannot be validated.
            }
            if (pack.size() != 2 || pack.find(soldier) == null) {
                throw new AssertionError("Rejected token art package deleted existing artwork");
            }
            System.out.println("DESKTOP_PAINTED_TOKEN_IMPORT_PASS playable=1 gallery=2 legacyRules=untouched");
        } finally {
            if (original == null) System.clearProperty("user.home");
            else System.setProperty("user.home", original);
        }
    }
}
