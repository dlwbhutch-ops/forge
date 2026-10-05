package com.housecommander.spectator;

import java.io.*;
import java.util.Properties;

/** Portable viewer preferences. Does not modify a roster, game, or rules setting. */
public final class BroadcastSettings {
    public boolean focusResponses = true;
    public boolean showArrows = true;
    public boolean animate = true;
    public boolean themedTokens = true;

    public static BroadcastSettings load(File file) throws IOException {
        BroadcastSettings settings = new BroadcastSettings();
        if (!file.isFile()) return settings;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(file)) { p.load(in); }
        settings.focusResponses = flag(p, "focusResponses", true);
        settings.showArrows = flag(p, "showArrows", true);
        settings.animate = flag(p, "animate", true);
        settings.themedTokens = flag(p, "themedTokens", true);
        return settings;
    }

    public void save(File file) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create preferences folder");
        Properties p = new Properties();
        p.setProperty("focusResponses", String.valueOf(focusResponses));
        p.setProperty("showArrows", String.valueOf(showArrows));
        p.setProperty("animate", String.valueOf(animate));
        p.setProperty("themedTokens", String.valueOf(themedTokens));
        File temporary = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            p.store(out, "HOUSE broadcast preferences"); out.getFD().sync();
        }
        java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean flag(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return "true".equals(v) ? true : ("false".equals(v) ? false : fallback);
    }
}
