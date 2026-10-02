package com.housecommander.desktop;

import com.housecommander.core.AssetSource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** Reads the HOUSE package bundled into the desktop JAR. */
public final class ClasspathAssets implements AssetSource {
    private static final String PREFIX = "housecommander/";

    @Override
    public InputStream open(String path) throws IOException {
        String normalized = path == null ? "" : path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        InputStream in = ClasspathAssets.class.getClassLoader()
                .getResourceAsStream(PREFIX + normalized);
        if (in == null) {
            throw new FileNotFoundException("Bundled HOUSE resource not found: " + normalized);
        }
        return in;
    }

    public static InputStream openHouseResource(String name) throws IOException {
        return new ClasspathAssets().open(name);
    }
}
