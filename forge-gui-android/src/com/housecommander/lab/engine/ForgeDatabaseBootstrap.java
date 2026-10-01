package com.housecommander.lab.engine;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import com.housecommander.forgebridge.HouseForgeRuntime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs bundled rules resources in private storage and initializes Forge in HOUSE. */
public final class ForgeDatabaseBootstrap {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private ForgeDatabaseBootstrap() { }

    public static boolean ensureReady(Activity activity) {
        if (activity == null) throw new IllegalArgumentException("Activity must not be null");
        if (HouseForgeRuntime.isReady()) return true;
        if (!STARTED.compareAndSet(false, true)) return false;
        final Context context = activity.getApplicationContext();
        HouseForgeRuntime.report("Installing bundled Forge card resources…");
        Thread worker = new Thread(() -> {
            try {
                String id;
                try (InputStream input = context.getAssets().open("house-forge-runtime.id");
                     ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[256];
                    int n;
                    while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                    id = new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
                }
                if (!id.matches("[a-f0-9]{64}")) throw new IOException("Invalid bundled resource identifier");
                File root = new File(context.getFilesDir(), "forge-runtime/" + id);
                install(context, root, id);
                String version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
                HouseForgeRuntime.initialize(root, version);
            } catch (Throwable error) {
                Log.e("HOUSE-Forge", "Database startup failed", error);
                HouseForgeRuntime.fail(error);
            }
        }, "HOUSE-Forge-Bootstrap");
        worker.setDaemon(true);
        worker.start();
        return false;
    }

    private static void install(Context context, File root, String id) throws IOException {
        File marker = new File(root, ".installed");
        if (marker.isFile() && new File(root, "res/lists/TypeLists.txt").isFile()
                && new File(root, "res/cardsfolder/cardsfolder.zip").isFile()) return;
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create Forge runtime directory");
        String allowed = root.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(context.getAssets().open("house-forge-runtime.zip"))) {
            ZipEntry entry;
            int count = 0;
            byte[] buffer = new byte[65536];
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(root, entry.getName());
                if (!target.getCanonicalPath().startsWith(allowed)) throw new IOException("Unsafe resource path");
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Cannot create " + entry.getName());
                } else {
                    File parent = target.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create resource directory");
                    try (FileOutputStream output = new FileOutputStream(target)) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) output.write(buffer, 0, n);
                    }
                    if (++count % 100 == 0) HouseForgeRuntime.report("Installing bundled Forge resources • " + count + " files");
                }
                zip.closeEntry();
            }
            if (!new File(root, "res/cardsfolder/cardsfolder.zip").isFile()
                    || !new File(root, "res/lists/TypeLists.txt").isFile()
                    || !new File(root, "res/languages/en-US.properties").isFile()) {
                throw new IOException("Bundled Forge resources are incomplete");
            }
        }
        Files.write(marker.toPath(), id.getBytes(StandardCharsets.UTF_8));
    }
}
