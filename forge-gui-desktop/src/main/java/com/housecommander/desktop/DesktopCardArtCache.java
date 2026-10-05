package com.housecommander.desktop;

import com.housecommander.forgebridge.CardArtStore;
import com.housecommander.forgebridge.LiveGameState;
import com.housecommander.token.TokenArtResolver;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** On-demand Swing image cache for HOUSE battlefield card faces. */
public final class DesktopCardArtCache {
    private final ExecutorService loader = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "HOUSE-Card-Art");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, ImageIcon> icons = new ConcurrentHashMap<String, ImageIcon>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    public ImageIcon cardIcon(
            LiveGameState.CardState card,
            int maxWidth,
            int maxHeight,
            Runnable onReady
    ) {
        if (TokenArtResolver.useIllustration(card)) return tokenIcon(card, maxWidth, maxHeight, card.tapped());
        if (card == null || card.imageUrl().isEmpty()) {
            return null;
        }
        String key = card.imageUrl()
                + "|"
                + card.tapped()
                + "|"
                + maxWidth
                + "x"
                + maxHeight;
        ImageIcon ready = icons.get(key);
        if (ready != null) {
            return ready;
        }
        request(
                key,
                card.imageUrl(),
                card.tapped(),
                maxWidth,
                maxHeight,
                onReady
        );
        return null;
    }

    public ImageIcon zoomIcon(
            LiveGameState.CardState card,
            int maxWidth,
            int maxHeight,
            Runnable onReady
    ) {
        if (TokenArtResolver.useIllustration(card)) return tokenIcon(card, maxWidth, maxHeight, false);
        if (card == null || card.imageUrl().isEmpty()) {
            return null;
        }
        String key = card.imageUrl()
                + "|zoom|"
                + maxWidth
                + "x"
                + maxHeight;
        ImageIcon ready = icons.get(key);
        if (ready != null) {
            return ready;
        }
        request(
                key,
                card.imageUrl(),
                false,
                maxWidth,
                maxHeight,
                onReady
        );
        return null;
    }

    public void shutdown() {
        loader.shutdownNow();
        pending.clear();
    }

    private ImageIcon tokenIcon(LiveGameState.CardState card, int width, int height, boolean tapped) {
        String key = "token|" + TokenArtResolver.identity(card) + "|" + tapped + "|" + width + "x" + height;
        ImageIcon ready = icons.get(key);
        if (ready != null) return ready;
        BufferedImage art = new BufferedImage(200,280,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = art.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            TokenArtResolver.paint(card,new DesktopTokenPainter(g));
        } finally { g.dispose(); }
        ImageIcon result = new ImageIcon(scaleInside(tapped ? rotate90(art) : art,width,height));
        if (icons.size() > 2048) icons.clear();
        icons.put(key,result);
        return result;
    }

    private void request(
            String key,
            String imageUrl,
            boolean rotate,
            int maxWidth,
            int maxHeight,
            Runnable onReady
    ) {
        if (!pending.add(key)) {
            return;
        }

        loader.execute(() -> {
            try {
                File file = CardArtStore.fetch(
                        HouseDesktopPaths.cardArtDir(),
                        imageUrl
                );
                if (file == null) {
                    return;
                }
                BufferedImage source = ImageIO.read(file);
                if (source == null) {
                    return;
                }

                BufferedImage display = rotate ? rotate90(source) : source;
                display = scaleInside(display, maxWidth, maxHeight);
                icons.put(key, new ImageIcon(display));

                if (onReady != null) {
                    SwingUtilities.invokeLater(onReady);
                }
            } catch (Throwable ignored) {
                // Art is optional. HOUSE gameplay must stay functional offline.
            } finally {
                pending.remove(key);
            }
        });
    }

    private static BufferedImage scaleInside(
            BufferedImage source,
            int maxWidth,
            int maxHeight
    ) {
        double ratio = Math.min(
                (double) maxWidth / source.getWidth(),
                (double) maxHeight / source.getHeight()
        );
        ratio = Math.min(1.0d, Math.max(0.01d, ratio));
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));

        BufferedImage scaled = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY
            );
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static BufferedImage rotate90(BufferedImage source) {
        BufferedImage rotated = new BufferedImage(
                source.getHeight(),
                source.getWidth(),
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = rotated.createGraphics();
        try {
            graphics.translate(rotated.getWidth(), 0);
            graphics.rotate(Math.PI / 2.0d);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rotated;
    }
}
