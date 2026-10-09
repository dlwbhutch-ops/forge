package com.housecommander.core;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The same read-only, explicit update check on Android and desktop. */
public final class CardDataUpdates {
    public static final String RELEASES = "https://github.com/Card-Forge/forge/releases";
    public static final String SOURCE = "https://github.com/Card-Forge/forge";
    public interface Fetcher { String get(String url) throws IOException; }
    private CardDataUpdates() { }

    public static String check(String metadata, Fetcher fetcher) throws IOException {
        JsonObject bundled = JsonParser.parseString(metadata).getAsJsonObject();
        StringBuilder out = new StringBuilder("Installed card data: ")
                .append(bundled.get("date").getAsString()).append("\n");
        int changed = 0, failed = 0;
        for (JsonElement item : bundled.getAsJsonArray("categories")) {
            JsonObject category = item.getAsJsonObject();
            String label = category.get("label").getAsString();
            try {
                String path = category.get("path").getAsString();
                if (!path.startsWith("forge-gui/res/")) throw new IOException("Invalid data category");
                String url = "https://api.github.com/repos/Card-Forge/forge/commits?per_page=1&path="
                        + URLEncoder.encode(path, "UTF-8");
                JsonArray commits = JsonParser.parseString(fetcher.get(url)).getAsJsonArray();
                if (commits.size() == 0) throw new IOException("No update information returned");
                JsonObject latest = commits.get(0).getAsJsonObject();
                String sha = latest.get("sha").getAsString();
                if (!sha.matches("[a-f0-9]{40}")) throw new IOException("Invalid update information");
                boolean newer = !sha.equals(category.get("revision").getAsString());
                if (newer) changed++;
                out.append("\n").append(label).append(newer ? ": newer data available" : ": matches bundled revision");
                out.append("\nLatest published change: ").append(latest.getAsJsonObject("commit")
                        .getAsJsonObject("committer").get("date").getAsString());
            } catch (Exception error) {
                failed++;
                out.append("\n").append(label).append(": could not check. Try again with an internet connection.");
            }
        }
        int pending = bundled.get("enginePending").getAsInt();
        if (pending > 0) out.append("\n\n").append(pending)
                .append(" upstream card scripts need newer engine support; existing supported versions are retained where available.");
        if (failed == 0 && changed == 0 && pending == 0) out.append("\n\nYour bundled card rules and set data are current with the checked Forge revisions.");
        else if (changed > 0) out.append("\n\nNew Forge data is available. A compatible HOUSE app update is needed to apply it.");
        if (failed > 0) out.append("\n\nCheck incomplete; no up-to-date status was confirmed.");
        out.append("\n\nNew mechanics can require an engine update. This check does not change a running game. Future unreleased cards appear only after Forge publishes support.");
        return out.toString();
    }

    public static String check(String metadata) throws IOException { return check(metadata, CardDataUpdates::fetch); }

    static String fetch(String address) throws IOException {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol()) || !"api.github.com".equals(url.getHost())) {
            throw new IOException("Untrusted update host");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "HOUSE-Commander-Lab-196");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Update server returned HTTP " + status);
            try (InputStream input = connection.getInputStream()) { return read(input); }
        } finally { connection.disconnect(); }
    }

    public static String read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Bundled update information is missing");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = input.read(buffer)) != -1) {
            if (bytes.size() + n > 2097152) throw new IOException("Update response too large");
            bytes.write(buffer, 0, n);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
