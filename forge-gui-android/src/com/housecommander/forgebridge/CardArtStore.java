/*
 * HOUSE Commander Lab card-art cache.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Small cross-platform cache for real card images exposed by Forge.
 *
 * <p>HOUSE stores only images it has actually needed. The rules engine does not
 * depend on this cache, so missing/offline art never blocks a game.
 */
public final class CardArtStore {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;

    private CardArtStore() {
    }

    public static File cachedFile(File root, String imageUrl) throws IOException {
        if (root == null) {
            throw new IllegalArgumentException("Card-art cache root is null");
        }
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            throw new IOException("Could not create card-art cache: " + root);
        }
        return new File(root, sha256(imageUrl) + ".jpg");
    }

    public static File fetch(File root, String imageUrl) throws IOException {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            return null;
        }

        File destination = cachedFile(root, imageUrl);
        if (destination.isFile() && destination.length() > 1024L) {
            return destination;
        }

        File partial = new File(destination.getAbsolutePath() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(imageUrl).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty(
                    "User-Agent",
                    "HOUSE-Commander-Lab/0.16 Card-Forge"
            );
            connection.setRequestProperty("Accept", "image/*");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException(
                        "Card art request returned HTTP " + status
                );
            }

            try (BufferedInputStream in =
                         new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out =
                         new FileOutputStream(partial, false)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
                out.getFD().sync();
            }

            if (partial.length() <= 1024L) {
                throw new IOException("Downloaded card image is unexpectedly small");
            }
            if (destination.exists() && !destination.delete()) {
                throw new IOException(
                        "Could not replace cached card image: " + destination
                );
            }
            if (!partial.renameTo(destination)) {
                throw new IOException(
                        "Could not finalize cached card image: " + destination
                );
            }
            return destination;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (partial.exists() && !destination.exists()) {
                partial.delete();
            }
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    String.valueOf(value).getBytes(StandardCharsets.UTF_8)
            );
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(String.format("%02x", b & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
