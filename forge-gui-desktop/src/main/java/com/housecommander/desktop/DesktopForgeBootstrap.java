package com.housecommander.desktop;

import com.housecommander.forgebridge.HouseForgeRuntime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs the same bundled Forge rules resources used by Android. */
public final class DesktopForgeBootstrap {
    private DesktopForgeBootstrap() {}

    public static synchronized void ensureReady(Consumer<String> status) throws IOException {
        Consumer<String> reporter = status == null ? value -> { } : status;
        if (HouseForgeRuntime.isReady()) {
            reporter.accept(HouseForgeRuntime.status());
            return;
        }
        if (HouseForgeRuntime.hasFailed()) {
            throw new IOException(HouseForgeRuntime.status());
        }

        reporter.accept("Installing bundled Forge card resources…");
        String id = readText("house-forge-runtime.id").trim();
        if (!id.matches("[a-f0-9]{64}")) {
            throw new IOException("Invalid bundled Forge resource identifier");
        }

        File root = new File(HouseDesktopPaths.runtimeRoot(), id);
        install(root, id, reporter);

        reporter.accept("Initializing Forge rules database…");
        try {
            HouseForgeRuntime.initialize(root, "HOUSE Desktop 0.9");
        } catch (RuntimeException error) {
            throw new IOException(HouseForgeRuntime.status(), error);
        }
        reporter.accept(HouseForgeRuntime.status());
    }

    private static void install(File root, String id, Consumer<String> reporter) throws IOException {
        File marker = new File(root, ".installed");
        if (marker.isFile()
                && new File(root, "res/lists/TypeLists.txt").isFile()
                && new File(root, "res/cardsfolder/cardsfolder.zip").isFile()) {
            return;
        }

        HouseDesktopPaths.ensureDirectory(root);
        String allowed = root.getCanonicalPath() + File.separator;
        try (InputStream raw = ClasspathAssets.openHouseResource("house-forge-runtime.zip");
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            int count = 0;
            byte[] buffer = new byte[65536];
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(root, entry.getName());
                if (!target.getCanonicalPath().startsWith(allowed)) {
                    throw new IOException("Unsafe bundled resource path: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    HouseDesktopPaths.ensureDirectory(target);
                } else {
                    File parent = target.getParentFile();
                    if (parent != null) {
                        HouseDesktopPaths.ensureDirectory(parent);
                    }
                    try (FileOutputStream output = new FileOutputStream(target, false)) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) {
                            output.write(buffer, 0, n);
                        }
                        output.getFD().sync();
                    }
                    count++;
                    if (count % 100 == 0) {
                        reporter.accept("Installing Forge resources • " + count + " files");
                    }
                }
                zip.closeEntry();
            }
        }

        if (!new File(root, "res/cardsfolder/cardsfolder.zip").isFile()
                || !new File(root, "res/lists/TypeLists.txt").isFile()
                || !new File(root, "res/languages/en-US.properties").isFile()) {
            throw new IOException("Bundled Forge resources are incomplete");
        }
        Files.write(marker.toPath(), id.getBytes(StandardCharsets.UTF_8));
    }

    private static String readText(String name) throws IOException {
        try (InputStream input = ClasspathAssets.openHouseResource(name);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int n;
            while ((n = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, n);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
