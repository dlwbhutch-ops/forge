package com.housecommander.desktop;

import com.housecommander.core.DeckSpec;
import com.housecommander.core.HousePackage;
import com.housecommander.core.HousePackageLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Shared package entry point for the Mac/Windows shell. */
public final class HouseDesktopRuntime {
    private HouseDesktopRuntime() {}

    public static HousePackage loadTemplatePackage() throws IOException {
        HousePackage template = HousePackageLoader.load(new ClasspathAssets(), "house19");
        ensureBundledDeckFiles(template);
        return template;
    }

    public static HousePackage loadActivePackage() throws IOException {
        HousePackage template = loadTemplatePackage();
        return new DesktopDeckLibraryStore().activePackage(template);
    }

    public static File deckFile(DeckSpec deck) throws IOException {
        if (deck == null) {
            throw new IllegalArgumentException("Deck must not be null");
        }
        return new File(HouseDesktopPaths.deckRoot(), deck.dck());
    }

    private static void ensureBundledDeckFiles(HousePackage template) throws IOException {
        for (DeckSpec deck : template.decks()) {
            File destination = deckFile(deck);
            if (destination.isFile() && destination.length() > 0L) {
                continue;
            }
            File parent = destination.getParentFile();
            if (parent != null) {
                HouseDesktopPaths.ensureDirectory(parent);
            }
            try (InputStream in = new ClasspathAssets().open("house19/" + deck.dck());
                 FileOutputStream out = new FileOutputStream(destination, false)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
                out.getFD().sync();
            }
        }
    }
}
