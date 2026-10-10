package com.housecommander.desktop;

import com.google.gson.Gson;
import com.housecommander.forgebridge.LiveGameState;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Offline, optional painted-art overrides for desktop HOUSE. Does not alter
 * Forge card definitions or tournament data. Supports PNG art packs, which
 * macOS/Windows Java ImageIO can decode without platform-dependent plugins.
 */
public final class DesktopPaintedTokenPack {
    private static final int MAX_ENTRIES = 90;
    private static final long MAX_EXTRACTED_BYTES = 72L * 1024L * 1024L;
    private final File root;
    private final Map<String, Artwork> byScriptId = new HashMap<>();
    private final Map<String, List<Artwork>> byName = new HashMap<>();
    private final List<GalleryEntry> gallery = new ArrayList<>();

    public static final class GalleryEntry {
        public final String name;
        public final String releaseSet;
        public final String scriptId;
        public final String type;
        public final boolean galleryOnly;
        public final File file;
        public final Integer power;
        public final Integer toughness;
        public final Integer baseLoyalty;

        GalleryEntry(Artwork entry, File location) {
            name = entry.name;
            releaseSet = entry.releaseSet;
            scriptId = entry.tokenId;
            type = entry.type;
            galleryOnly = entry.galleryOnly;
            file = new File(location, entry.filename);
            power = entry.power;
            toughness = entry.toughness;
            baseLoyalty = entry.baseLoyalty;
        }
    }

    private static final class Manifest {
        int schema;
        List<Artwork> artworks;
    }

    private static final class Artwork {
        String id;
        String name;
        String tokenId;
        String filename;
        String type;
        String releaseSet;
        boolean galleryOnly;
        Integer power;
        Integer toughness;
        Integer baseLoyalty;
    }

    public DesktopPaintedTokenPack() {
        root = new File(new File(System.getProperty("user.home", "."), ".house-commander-lab"),
                "painted-token-art");
        reload();
    }

    public synchronized int size() { return gallery.size(); }

    public synchronized List<GalleryEntry> gallery() {
        return Collections.unmodifiableList(new ArrayList<>(gallery));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+token$", "").trim();
    }

    private static boolean isPlayableFile(String filename) {
        return filename != null && filename.matches("art/[a-z0-9_]+\\.png");
    }

    private static boolean matchesType(Artwork artwork, LiveGameState.CardState card) {
        String kind = normalize(artwork.type);
        String types = normalize(card.typeLine());
        if (kind.equals("planeswalker")) return types.contains("planeswalker");
        if (types.contains("planeswalker")) return false;
        if (kind.equals("creature") || kind.equals("creatures")) return types.contains("creature");
        if (kind.equals("artifact")) return types.contains("artifact");
        if (kind.equals("emblem")) return types.contains("emblem");
        return true;
    }

    public synchronized File find(LiveGameState.CardState card) {
        if (card == null || !card.token()) return null;
        String key = card.imageKey() == null ? "" : card.imageKey().replaceFirst("^t:", "");
        Artwork exact = byScriptId.get(key);
        if (exact != null) {
            File f = new File(root, exact.filename);
            if (f.isFile()) return f;
        }

        List<Artwork> choices = byName.get(normalize(card.name()));
        if (choices == null) return null;
        Artwork winner = null;
        int matching = 0;
        for (Artwork candidate : choices) {
            if (!matchesType(candidate, card) || candidate.galleryOnly) continue;
            // Power/toughness may change through counters. Only trust fallback
            // when one compatible name/type exists; never guess between variants.
            winner = candidate;
            matching++;
        }
        if (matching != 1) return null;
        File f = new File(root, winner.filename);
        return f.isFile() ? f : null;
    }

    public synchronized void reload() {
        gallery.clear();
        byName.clear();
        byScriptId.clear();
        File manifest = new File(root, "manifest.json");
        if (!manifest.isFile()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(manifest), StandardCharsets.UTF_8)) {
            Manifest pack = new Gson().fromJson(reader, Manifest.class);
            if (pack == null || pack.schema != 1 || pack.artworks == null) return;
            for (Artwork a : pack.artworks) {
                if (a == null || a.name == null || !isPlayableFile(a.filename)) continue;
                File file = new File(root, a.filename);
                if (!file.isFile()) continue;
                gallery.add(new GalleryEntry(a, root));
                if (!a.galleryOnly) {
                    byName.computeIfAbsent(normalize(a.name), ignore -> new ArrayList<>()).add(a);
                    if (a.tokenId != null && !a.tokenId.isEmpty()) byScriptId.putIfAbsent(a.tokenId, a);
                }
            }
        } catch (Exception ignored) {
            gallery.clear();
            byName.clear();
            byScriptId.clear();
        }
    }

    public synchronized int install(File zipFile) throws IOException {
        if (zipFile == null || !zipFile.isFile()) throw new IOException("Select a token art ZIP file");
        File parent = root.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create HOUSE art directory");
        File stage = Files.createTempDirectory(parent.toPath(), "painted-stage-").toFile();
        File backup = new File(parent, "painted-token-art-backup");
        long total = 0L;
        int fileCount = 0;
        Set<String> paths = new HashSet<>();
        try {
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
                ZipEntry entry;
                byte[] buf = new byte[16384];
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) { zip.closeEntry(); continue; }
                    String name = entry.getName();
                    if (!"manifest.json".equals(name)
                            && !"README-MAC.txt".equals(name)
                            && !isPlayableFile(name)) {
                        throw new IOException("Unexpected file inside token pack: " + name);
                    }
                    if (!paths.add(name)) throw new IOException("Duplicate archive filename: " + name);
                    if (++fileCount > MAX_ENTRIES) throw new IOException("Too many files in token pack");
                    File target = new File(stage, name);
                    File folder = target.getParentFile();
                    if (folder == null || (!folder.isDirectory() && !folder.mkdirs())) {
                        throw new IOException("Could not extract token pack directory");
                    }
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                        int n;
                        while ((n = zip.read(buf)) != -1) {
                            total += n;
                            if (total > MAX_EXTRACTED_BYTES) throw new IOException("Token art exceeds safe size limit");
                            out.write(buf, 0, n);
                        }
                    }
                    zip.closeEntry();
                }
            }
            File metadata = new File(stage, "manifest.json");
            if (!metadata.isFile()) throw new IOException("Token pack has no manifest.json");
            Manifest data;
            try (Reader reader = new InputStreamReader(new FileInputStream(metadata), StandardCharsets.UTF_8)) {
                data = new Gson().fromJson(reader, Manifest.class);
            } catch (RuntimeException ex) {
                throw new IOException("Could not parse token art manifest", ex);
            }
            if (data == null || data.schema != 1 || data.artworks == null
                    || data.artworks.isEmpty() || data.artworks.size() > MAX_ENTRIES) {
                throw new IOException("Unsupported or empty token art manifest");
            }
            Set<String> ids = new HashSet<>();
            Set<String> scriptIds = new HashSet<>();
            for (Artwork art : data.artworks) {
                if (art == null || art.id == null || art.name == null
                        || art.name.trim().isEmpty() || !ids.add(art.id)
                        || !isPlayableFile(art.filename)) {
                    throw new IOException("Invalid or duplicate painted token identifier");
                }
                if (!art.galleryOnly && art.tokenId != null && !art.tokenId.isEmpty()
                        && !scriptIds.add(art.tokenId)) {
                    throw new IOException("Multiple images assigned to token: " + art.tokenId);
                }
                File artFile = new File(stage, art.filename);
                if (!artFile.isFile() || ImageIO.read(artFile) == null) {
                    throw new IOException("Missing or undecodable PNG token art: " + art.filename);
                }
            }

            deleteTree(backup);
            if (root.exists() && !root.renameTo(backup)) {
                throw new IOException("Could not back up existing token artwork");
            }
            if (!stage.renameTo(root)) {
                if (backup.isDirectory()) backup.renameTo(root);
                throw new IOException("Could not install new token artwork");
            }
            deleteTree(backup);
            reload();
            return size();
        } finally {
            if (stage.exists()) deleteTree(stage);
        }
    }

    private static void deleteTree(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
