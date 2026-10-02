package com.housecommander.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable version of one Commander deck.
 *
 * deckId remains stable for the lifetime of the deck. version changes whenever
 * the list changes. Results can therefore point at the exact list that played.
 */
public final class DeckRecord {
    private final String deckId;
    private final int version;
    private final String name;
    private final String commander;
    private final String deckText;
    private final String sourceFormat;
    private final long createdEpochMillis;
    private final String contentHash;

    private DeckRecord(
            String deckId,
            int version,
            String name,
            String commander,
            String deckText,
            String sourceFormat,
            long createdEpochMillis,
            String expectedHash
    ) {
        this.deckId = requireText(deckId, "deckId");
        if (version < 1) {
            throw new IllegalArgumentException("version must be at least 1");
        }
        this.version = version;
        this.name = requireText(name, "name");
        this.commander = commander == null ? "" : commander.trim();
        this.deckText = requireText(deckText, "deckText");
        this.sourceFormat = sourceFormat == null ? "" : sourceFormat.trim();
        this.createdEpochMillis = createdEpochMillis;
        this.contentHash = sha256(this.deckText);

        if (expectedHash != null
                && !expectedHash.trim().isEmpty()
                && !this.contentHash.equalsIgnoreCase(expectedHash.trim())) {
            throw new IllegalArgumentException(
                    "Deck content hash mismatch for " + this.name + " v" + this.version
            );
        }
    }

    public static DeckRecord create(
            String name,
            String commander,
            String deckText,
            String sourceFormat
    ) {
        return new DeckRecord(
                UUID.randomUUID().toString(),
                1,
                name,
                commander,
                deckText,
                sourceFormat,
                System.currentTimeMillis(),
                null
        );
    }

    public static DeckRecord restore(
            String deckId,
            int version,
            String name,
            String commander,
            String deckText,
            String sourceFormat,
            long createdEpochMillis,
            String expectedHash
    ) {
        return new DeckRecord(
                deckId,
                version,
                name,
                commander,
                deckText,
                sourceFormat,
                createdEpochMillis,
                expectedHash
        );
    }

    public DeckRecord nextVersion(
            String updatedName,
            String updatedCommander,
            String updatedDeckText,
            String updatedSourceFormat
    ) {
        return new DeckRecord(
                deckId,
                version + 1,
                updatedName,
                updatedCommander,
                updatedDeckText,
                updatedSourceFormat,
                System.currentTimeMillis(),
                null
        );
    }

    public String deckId() { return deckId; }
    public int version() { return version; }
    public String name() { return name; }
    public String commander() { return commander; }
    public String deckText() { return deckText; }
    public String sourceFormat() { return sourceFormat; }
    public long createdEpochMillis() { return createdEpochMillis; }
    public String contentHash() { return contentHash; }

    public String versionLabel() {
        return name + " v" + version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DeckRecord)) return false;
        DeckRecord that = (DeckRecord) other;
        return version == that.version
                && deckId.equals(that.deckId)
                && contentHash.equals(that.contentHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deckId, version, contentHash);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[bytes.length * 2];
            final char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < bytes.length; i++) {
                int value = bytes[i] & 0xff;
                hex[i * 2] = digits[value >>> 4];
                hex[i * 2 + 1] = digits[value & 0x0f];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
