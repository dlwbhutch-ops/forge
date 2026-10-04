/*
 * HOUSE Commander Lab exact-print card image service.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import forge.ImageKeys;
import forge.StaticData;
import forge.card.CardEdition;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.item.PaperToken;
import forge.localinstance.properties.ForgeConstants;
import forge.util.BuildInfo;
import forge.util.ImageUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Resolves exact Forge card-print identities through Forge's own cache and card
 * database. Missing images are fetched from Scryfall into the same cache
 * layout Forge already understands.
 */
public final class HouseCardImageService {
    private static final long MIN_REQUEST_GAP_MS = 125L;
    private static final long RATE_LIMIT_COOLDOWN_MS = 5L * 60L * 1000L;
    private static final Object PACE_LOCK = new Object();

    private static final Set<String> IN_FLIGHT =
            Collections.newSetFromMap(
                    new ConcurrentHashMap<String, Boolean>()
            );

    private static final ExecutorService DOWNLOADS =
            Executors.newFixedThreadPool(
                    2,
                    new ThreadFactory() {
                        private int index;

                        @Override
                        public Thread newThread(Runnable runnable) {
                            Thread thread = new Thread(
                                    runnable,
                                    "HOUSE-Card-Art-" + (++index)
                            );
                            thread.setDaemon(true);
                            return thread;
                        }
                    }
            );

    private static volatile long lastRequestMs;
    private static volatile long cooldownUntilMs;

    private HouseCardImageService() {
    }

    public static File localFile(String imageIdentity) {
        ResolvedImage resolved = resolve(imageIdentity);
        if (resolved == null) {
            return null;
        }

        if (resolved.target.isFile() && resolved.target.length() > 0L) {
            return resolved.target;
        }

        try {
            File cached = ImageKeys.getImageFile(resolved.lookupKey);
            if (cached != null && cached.isFile() && cached.length() > 0L) {
                return cached;
            }
        } catch (Throwable ignored) {
            // The UI falls back to a text tile if image resolution fails.
        }
        return null;
    }

    /**
     * Queue one missing image without blocking Forge or the UI.
     *
     * <p>The callback runs on a worker thread. Desktop/Android callers must
     * marshal it back to their UI thread.
     */
    public static boolean request(
            final String imageIdentity,
            final Runnable onReady
    ) {
        if (imageIdentity == null || imageIdentity.trim().isEmpty()) {
            return false;
        }
        if (localFile(imageIdentity) != null) {
            return false;
        }

        final ResolvedImage resolved = resolve(imageIdentity);
        if (resolved == null || resolved.url.isEmpty()) {
            return false;
        }

        final String requestKey = resolved.target.getAbsolutePath();
        if (!IN_FLIGHT.add(requestKey)) {
            return false;
        }

        DOWNLOADS.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    download(resolved);
                } catch (Throwable error) {
                    System.err.println(
                            "HOUSE card image fetch failed: "
                                    + safeMessage(error)
                    );
                } finally {
                    IN_FLIGHT.remove(requestKey);
                    if (localFile(imageIdentity) != null && onReady != null) {
                        try {
                            onReady.run();
                        } catch (Throwable ignored) {
                            // Rendering callbacks are best-effort only.
                        }
                    }
                }
            }
        });
        return true;
    }

    private static void download(ResolvedImage resolved) throws Exception {
        if (System.currentTimeMillis() < cooldownUntilMs) {
            return;
        }

        pace();

        HttpURLConnection connection =
                (HttpURLConnection) new URL(resolved.url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "image/*");
        connection.setRequestProperty("User-Agent", BuildInfo.getUserAgent());

        File temporary =
                new File(resolved.target.getAbsolutePath() + ".tmp");
        try {
            int response = connection.getResponseCode();
            if (response == 429) {
                cooldownUntilMs = System.currentTimeMillis()
                        + RATE_LIMIT_COOLDOWN_MS;
                return;
            }
            if (response != HttpURLConnection.HTTP_OK) {
                return;
            }

            String contentType = connection.getContentType();
            if (contentType != null && !contentType.startsWith("image/")) {
                return;
            }

            File parent = resolved.target.getParentFile();
            if (parent != null
                    && !parent.exists()
                    && !parent.mkdirs()
                    && !parent.isDirectory()) {
                return;
            }

            try (InputStream input = connection.getInputStream();
                    FileOutputStream output =
                            new FileOutputStream(temporary)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            }

            if (temporary.length() <= 0L) {
                temporary.delete();
                return;
            }

            if (resolved.target.exists()) {
                temporary.delete();
                return;
            }

            if (!temporary.renameTo(resolved.target)) {
                copy(temporary, resolved.target);
                temporary.delete();
            }
            ImageKeys.clearMissingCards();
        } finally {
            connection.disconnect();
            if (temporary.isFile()
                    && resolved.target.isFile()
                    && resolved.target.length() > 0L) {
                temporary.delete();
            }
        }
    }

    private static void copy(File source, File target) throws Exception {
        try (FileInputStream input = new FileInputStream(source);
                FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private static void pace() {
        synchronized (PACE_LOCK) {
            long now = System.currentTimeMillis();
            long wait = lastRequestMs + MIN_REQUEST_GAP_MS - now;
            if (wait > 0L) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestMs = System.currentTimeMillis();
        }
    }

    private static ResolvedImage resolve(String identity) {
        if (identity == null) {
            return null;
        }
        String key = identity.trim();
        if (key.isEmpty()) {
            return null;
        }

        try {
            if (key.startsWith(ImageKeys.CARD_PREFIX)) {
                return resolveCard(key);
            }
            if (key.startsWith(ImageKeys.TOKEN_PREFIX)) {
                return resolveToken(key);
            }

            File direct = ImageKeys.getImageFile(key);
            if (direct != null) {
                return new ResolvedImage(key, direct, "");
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private static ResolvedImage resolveCard(String identity) {
        String face = "";
        String base = identity;

        if (base.endsWith(ImageKeys.BACKFACE_POSTFIX)) {
            face = "back";
            base = base.substring(
                    0,
                    base.length() - ImageKeys.BACKFACE_POSTFIX.length()
            );
        } else if (base.endsWith(ImageKeys.SPECFACE_W)) {
            face = "white";
            base = stripSpecial(base);
        } else if (base.endsWith(ImageKeys.SPECFACE_U)) {
            face = "blue";
            base = stripSpecial(base);
        } else if (base.endsWith(ImageKeys.SPECFACE_B)) {
            face = "black";
            base = stripSpecial(base);
        } else if (base.endsWith(ImageKeys.SPECFACE_R)) {
            face = "red";
            base = stripSpecial(base);
        } else if (base.endsWith(ImageKeys.SPECFACE_G)) {
            face = "green";
            base = stripSpecial(base);
        }

        PaperCard card = ImageUtil.getPaperCardFromImageKey(base);
        if (card == null) {
            return null;
        }

        String fileKey;
        if ("back".equals(face)) {
            fileKey = card.getCardAltImageKey();
        } else if (!face.isEmpty()) {
            fileKey = ImageUtil.getImageKey(card, face, true);
        } else {
            fileKey = card.getCardImageKey();
        }

        File target = new File(
                ForgeConstants.CACHE_CARD_PICS_DIR,
                fileKey + ".jpg"
        );

        CardEdition edition =
                StaticData.instance().getEditions().get(card.getEdition());
        if (edition == null
                || IPaperCard.NO_COLLECTOR_NUMBER.equals(
                        card.getCollectorNumber()
                )) {
            return new ResolvedImage(fileKey, target, "");
        }

        String relative = ImageUtil.getScryfallDownloadUrl(
                card,
                face,
                edition.getScryfallCode(),
                edition.getCardsLangCode(),
                false
        );
        return new ResolvedImage(
                fileKey,
                target,
                ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD + relative
        );
    }

    private static ResolvedImage resolveToken(String identity) {
        String face = "";
        String base = identity;
        if (base.endsWith(ImageKeys.BACKFACE_POSTFIX)) {
            face = "back";
            base = base.substring(
                    0,
                    base.length() - ImageKeys.BACKFACE_POSTFIX.length()
            );
        }

        PaperToken token = ImageUtil.getPaperTokenFromImageKey(base);
        if (token == null) {
            return null;
        }

        String raw = base.substring(ImageKeys.TOKEN_PREFIX.length());
        String[] parts = raw.split("\\|");
        String tokenName = parts.length > 0
                ? parts[0]
                : token.getName().toLowerCase().replace(" ", "_");
        String editionCode = parts.length > 1
                ? parts[1]
                : token.getEdition();
        String collector = parts.length > 2
                ? parts[2]
                : token.getCollectorNumber();

        String relativeFile;
        if (collector != null
                && !collector.isEmpty()
                && !IPaperCard.NO_COLLECTOR_NUMBER.equals(collector)) {
            relativeFile = editionCode
                    + File.separator
                    + collector
                    + "_"
                    + tokenName
                    + ("back".equals(face) ? "_back" : "")
                    + ".jpg";
        } else {
            relativeFile = tokenName + "_" + editionCode + ".jpg";
        }

        File target = new File(
                ForgeConstants.CACHE_TOKEN_PICS_DIR,
                relativeFile
        );

        CardEdition edition =
                StaticData.instance().getEditions().get(editionCode);
        if (edition == null
                || collector == null
                || collector.isEmpty()
                || IPaperCard.NO_COLLECTOR_NUMBER.equals(collector)) {
            return new ResolvedImage(identity, target, "");
        }

        String relativeUrl = ImageUtil.getScryfallTokenDownloadUrl(
                collector,
                edition.getTokensCode(),
                edition.getCardsLangCode(),
                face
        );
        return new ResolvedImage(
                identity,
                target,
                ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD + relativeUrl
        );
    }

    private static String stripSpecial(String value) {
        return value.substring(
                0,
                value.length() - ImageKeys.SPECFACE_W.length()
        );
    }

    private static String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null) {
            return "unknown";
        }
        return error.getMessage().replace('\n', ' ');
    }

    private static final class ResolvedImage {
        private final String lookupKey;
        private final File target;
        private final String url;

        private ResolvedImage(
                String lookupKey,
                File target,
                String url
        ) {
            this.lookupKey = lookupKey;
            this.target = target;
            this.url = url == null ? "" : url;
        }
    }
}
