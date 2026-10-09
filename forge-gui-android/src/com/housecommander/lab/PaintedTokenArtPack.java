package com.housecommander.lab;

import android.content.Context;
import android.net.Uri;
import com.google.gson.Gson;
import com.housecommander.forgebridge.LiveGameState;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A user-imported, offline-only, original hand-drawn art pack. This overrides
 * token pictures, never token rules or stats. ZIP extraction is confined to
 * the app-private directory with quotas against malformed archives.
 */
public final class PaintedTokenArtPack {
    private static final int MAX_FILES = 120;
    private static final long MAX_BYTES = 20L * 1024L * 1024L;
    private final File root;
    private final Map<String, List<Artwork>> artworks = new HashMap<>();
    private int count;

    static final class Manifest {
        int schema;
        List<Artwork> artworks;
    }

    static final class Artwork {
        String id;
        String name;
        String filename;
        Integer power;
        Integer toughness;
        Integer baseLoyalty;
        String type;
    }

    public PaintedTokenArtPack(Context context) {
        root = new File(context.getFilesDir(), "painted-token-art");
        reload();
    }

    public synchronized int size() { return count; }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+token$", "").trim();
    }

    public synchronized File find(LiveGameState.CardState card) {
        if (card == null || !card.token()) return null;
        List<Artwork> candidates = artworks.get(normalize(card.name()));
        if (candidates == null || candidates.isEmpty()) return null;
        Artwork choice = null;
        int top = Integer.MIN_VALUE;
        for (Artwork a : candidates) {
            int score = 0;
            if (card.typeLine().toLowerCase(Locale.ROOT).contains("planeswalker")
                    && "planeswalker".equals(a.type)) score += 100;
            if ("planeswalker".equals(a.type)
                    && !card.typeLine().toLowerCase(Locale.ROOT).contains("planeswalker")) score -= 100;
            if (a.power != null && a.toughness != null) {
                if (a.power == card.power() && a.toughness == card.toughness()) score += 30;
            }
            if (score > top) { choice = a; top = score; }
        }
        if (choice == null || choice.filename == null) return null;
        File file = new File(root, choice.filename);
        return file.isFile() ? file : null;
    }

    public synchronized void reload() {
        artworks.clear();
        count = 0;
        File manifestFile = new File(root, "manifest.json");
        if (!manifestFile.isFile()) return;
        try (InputStreamReader reader = new InputStreamReader(
                new java.io.FileInputStream(manifestFile), StandardCharsets.UTF_8)) {
            Manifest manifest = new Gson().fromJson(reader, Manifest.class);
            if (manifest == null || manifest.schema != 1 || manifest.artworks == null) return;
            for (Artwork artwork : manifest.artworks) {
                if (artwork == null || artwork.filename == null || artwork.name == null
                        || !artwork.filename.matches("art/[a-z0-9_]+\\.webp")) continue;
                if (!new File(root, artwork.filename).isFile()) continue;
                artworks.computeIfAbsent(normalize(artwork.name), k -> new ArrayList<>()).add(artwork);
                count++;
            }
        } catch (Exception ignored) { artworks.clear(); count = 0; }
    }

    public synchronized int install(Context context, Uri uri) throws IOException {
        File stage = new File(context.getCacheDir(), "painted-token-art-stage");
        deleteRecursively(stage);
        if (!stage.mkdirs() && !stage.isDirectory()) throw new IOException("Could not prepare token pack");
        int files = 0;
        long total = 0L;
        try {
            try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
                if (raw == null) throw new IOException("Cannot open token art ZIP");
                try (ZipInputStream zip = new ZipInputStream(raw)) {
                    ZipEntry entry;
                    byte[] buffer = new byte[16384];
                    while ((entry = zip.getNextEntry()) != null) {
                        if (entry.isDirectory()) { zip.closeEntry(); continue; }
                        String name = entry.getName();
                        if (!"manifest.json".equals(name)
                                && !name.matches("art/[a-z0-9_]+\\.webp")) {
                            throw new IOException("Unexpected file in token pack: " + name);
                        }
                        if (++files > MAX_FILES) throw new IOException("Too many art files");
                        File file = new File(stage, name);
                        File dir = file.getParentFile();
                        if (dir == null || (!dir.exists() && !dir.mkdirs())) throw new IOException("Invalid file layout");
                        try (FileOutputStream dst = new FileOutputStream(file)) {
                            int n;
                            while ((n = zip.read(buffer)) > 0) {
                                total += n;
                                if (total > MAX_BYTES) throw new IOException("Token pack exceeds size limit");
                                dst.write(buffer, 0, n);
                            }
                        }
                        zip.closeEntry();
                    }
                }
            }
            File metadata = new File(stage, "manifest.json");
            if (!metadata.isFile()) throw new IOException("Token pack has no manifest");
            try (InputStreamReader reader = new InputStreamReader(
                    new java.io.FileInputStream(metadata), StandardCharsets.UTF_8)) {
                Manifest m = new Gson().fromJson(reader, Manifest.class);
                if (m == null || m.schema != 1 || m.artworks == null || m.artworks.isEmpty())
                    throw new IOException("Unsupported token art manifest");
                for (Artwork a : m.artworks) {
                    if (a == null || a.filename == null || !a.filename.matches("art/[a-z0-9_]+\\.webp")
                            || !new File(stage, a.filename).isFile())
                        throw new IOException("Missing token art: " + (a == null ? "null" : a.filename));
                }
            }
            File backup = new File(context.getFilesDir(), "painted-token-art-backup");
            deleteRecursively(backup);
            if (root.exists() && !root.renameTo(backup))
                throw new IOException("Cannot back up existing token art");
            if (!stage.renameTo(root)) {
                if (backup.isDirectory()) backup.renameTo(root);
                throw new IOException("Cannot install token art");
            }
            deleteRecursively(backup);
            reload();
            return count;
        } finally { if (stage.exists()) deleteRecursively(stage); }
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] contents = file.listFiles();
            if (contents != null) for (File child : contents) deleteRecursively(child);
        }
        file.delete();
    }
}
