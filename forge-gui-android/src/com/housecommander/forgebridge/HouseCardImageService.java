/*
 * HOUSE Commander Lab card image service.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import forge.ImageKeys;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.util.BuildInfo;
import forge.util.ImageUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Resolves Forge card art locally and, when missing, downloads the exact
 * printing into Forge's existing image cache.
 */
public final class HouseCardImageService {
    private static final long MIN_REQUEST_GAP_MS = 125L;
    private static final Set<String> IN_FLIGHT =
            Collections.synchronizedSet(new HashSet<String>());
    private static final ExecutorService DOWNLOADS =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "HOUSE-Card-Art");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static volatile long lastRequestMs;

    private HouseCardImageService() {
    }

    public static File localFile(String imageKey) {
        if (imageKey == null || imageKey.isEmpty()) {
            return null;
        }
        try {
            return ImageKeys.getImageFile(imageKey);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Request exact card-print art without blocking Forge or the UI.
     *
     * @return true when a background request was started.
     */
    public static boolean request(
            final String imageKey,
            final String imageFetchKey,
            final Runnable onReady
    ) {
        if (imageKey == null
                || imageKey.isEmpty()
                || imageFetchKey == null
                || imageFetchKey.isEmpty()
                || !imageFetchKey.startsWith(ImageKeys.CARD_PREFIX)) {
            return false;
        }
        if (localFile(imageKey) != null) {
            return false;
        }

        final String identity = imageKey + "|" + imageFetchKey;
        if (!IN_FLIGHT.add(identity)) {
            return false;
        }

        DOWNLOADS.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    downloadCard(imageKey, imageFetchKey);
                } catch (Throwable error) {
                    System.err.println(
                            "HOUSE card image fetch failed for "
                                    + imageKey
                                    + ": "
                                    + error.getMessage()
                    );
                } finally {
                    IN_FLIGHT.remove(identity);
                    if (localFile(imageKey) != null && onReady != null) {
                        try {
                            onReady.run();
                        } catch (Throwable ignored) {
                            // UI callbacks are best-effort only.
                        }
                    }
                }
            }
        });
        return true;
    }

    private static void downloadCard(
            String imageKey,
            String imageFetchKey
    ) throws Exception {
        PaperCard card = ImageUtil.getPaperCardFromImageKey(imageFetchKey);
        if (card == null) {
            return;
        }

        String relative = ImageUtil.getScryfallDownloadUrl(
                card,
                "",
                card.getEdition().toLowerCase(),
                "en",
                false
        );
        if (relative == null || relative.isEmpty()) {
            return;
        }

        pace();
        URL url = new URL(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD + relative);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "image/*");
        connection.setRequestProperty("User-Agent", BuildInfo.getUserAgent());

        int response = connection.getResponseCode();
        if (response != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            return;
        }

        String contentType = connection.getContentType();
        if (contentType != null && !contentType.startsWith("image/")) {
            connection.disconnect();
            return;
        }

        File destination = new File(
                ForgeConstants.CACHE_CARD_PICS_DIR,
                imageKey + ".jpg"
        );
        File parent = destination.getParentFile();
        if (parent != null
                && !parent.exists()
                && !parent.mkdirs()
                && !parent.isDirectory()) {
            connection.disconnect();
            return;
        }

        File temporary = new File(destination.getAbsolutePath() + ".tmp");
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }

        if (!temporary.renameTo(destination)) {
            if (destination.exists() && !destination.delete()) {
                temporary.delete();
                return;
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete();
                return;
            }
        }

        ImageKeys.clearMissingCards();
    }

    private static synchronized void pace() {
        long now = System.currentTimeMillis();
        long wait = MIN_REQUEST_GAP_MS - (now - lastRequestMs);
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
