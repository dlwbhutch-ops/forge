package com.housecommander.lab;

import android.content.Context;

import com.housecommander.core.DeckSpec;
import com.housecommander.core.HousePackage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class HouseInstall {
    private HouseInstall() {}

    public static File installRoot(Context context) throws IOException {
        File root = new File(context.getFilesDir(), "house19");
        File deckDir = new File(root, "forge_decks");
        if (!deckDir.exists() && !deckDir.mkdirs()) {
            throw new IOException("Could not create " + deckDir);
        }
        return root;
    }

    public static void ensureDeckFiles(Context context, HousePackage pack) throws IOException {
        File root = installRoot(context);
        for (DeckSpec deck : pack.decks()) {
            if (!"EXACT".equalsIgnoreCase(deck.status())) {
                continue;
            }

            File dest = new File(root, deck.dck());
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Could not create " + parent);
            }
            copyAsset(context, "house19/" + deck.dck(), dest);
        }
    }

    public static File deckFile(Context context, DeckSpec deck) throws IOException {
        return new File(installRoot(context), deck.dck());
    }

    private static void copyAsset(Context context, String asset, File dest) throws IOException {
        try (InputStream in = context.getAssets().open(asset);
             FileOutputStream out = new FileOutputStream(dest, false)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        }
    }
}
