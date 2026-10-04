package com.housecommander.desktop;

import com.housecommander.forgebridge.CardArtStore;
import com.housecommander.forgebridge.LiveGameState;
import com.housecommander.forgebridge.TokenArtResolver;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
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
        if (card == null) {
            return null;
        }
        if (card.token()) {
            return tokenIcon(card, maxWidth, maxHeight, card.tapped());
        }
        if (card.imageUrl().isEmpty()) {
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
        if (card == null) {
            return null;
        }
        if (card.token()) {
            return tokenIcon(card, maxWidth, maxHeight, false);
        }
        if (card.imageUrl().isEmpty()) {
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

    private ImageIcon tokenIcon(
            LiveGameState.CardState card,
            int maxWidth,
            int maxHeight,
            boolean rotate
    ) {
        TokenArtResolver.TokenArtSpec spec = TokenArtResolver.resolve(card);
        if (spec == null) {
            return null;
        }

        String key = "house-token|"
                + spec.signature()
                + "|"
                + rotate
                + "|"
                + maxWidth
                + "x"
                + maxHeight;
        ImageIcon ready = icons.get(key);
        if (ready != null) {
            return ready;
        }

        int sourceWidth = rotate ? maxHeight : maxWidth;
        int sourceHeight = rotate ? maxWidth : maxHeight;
        BufferedImage source = renderTokenArt(
                spec,
                Math.max(64, sourceWidth),
                Math.max(88, sourceHeight)
        );
        BufferedImage display = rotate ? rotate90(source) : source;
        display = scaleInside(display, maxWidth, maxHeight);
        ImageIcon icon = new ImageIcon(display);
        icons.put(key, icon);
        return icon;
    }

    private static BufferedImage renderTokenArt(
            TokenArtResolver.TokenArtSpec spec,
            int width,
            int height
    ) {
        BufferedImage image = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );
            Color primary = new Color(spec.primaryRgb());
            Color secondary = new Color(spec.secondaryRgb());
            graphics.setPaint(new GradientPaint(
                    0,
                    0,
                    primary,
                    width,
                    height,
                    secondary
            ));
            graphics.fillRoundRect(0, 0, width, height, 18, 18);

            int seed = spec.signature().hashCode();
            for (int i = 0; i < 14; i++) {
                seed = seed * 1664525 + 1013904223;
                int x = Math.floorMod(seed, Math.max(1, width));
                seed = seed * 1664525 + 1013904223;
                int y = Math.floorMod(seed, Math.max(1, height));
                seed = seed * 1664525 + 1013904223;
                int radius = 6 + Math.floorMod(seed, Math.max(8, width / 5));
                graphics.setColor(new Color(255, 255, 255, 26 + (i % 3) * 10));
                graphics.fillOval(x - radius, y - radius, radius * 2, radius * 2);
            }

            graphics.setColor(new Color(255, 255, 255, 210));
            graphics.drawRoundRect(2, 2, width - 5, height - 5, 18, 18);
            graphics.drawRoundRect(5, 5, width - 11, height - 11, 14, 14);

            int topSize = Math.max(10, Math.min(17, width / 7));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, topSize));
            graphics.setColor(new Color(255, 255, 255, 235));
            drawCentered(
                    graphics,
                    ellipsize(graphics, spec.displayName(), width - 14),
                    width,
                    topSize + 8
            );

            int glyphSize = Math.max(24, Math.min(width, height) / 3);
            graphics.setFont(new Font(Font.SERIF, Font.BOLD, glyphSize));
            graphics.setColor(new Color(255, 255, 255, 225));
            drawCentered(
                    graphics,
                    spec.glyph(),
                    width,
                    Math.max(topSize + glyphSize + 12, height / 2 + glyphSize / 3)
            );

            int labelSize = Math.max(9, Math.min(12, width / 9));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, labelSize));
            graphics.setColor(new Color(255, 255, 255, 225));
            drawCentered(
                    graphics,
                    ellipsize(graphics, spec.familyTitle(), width - 16),
                    width,
                    height - 28
            );

            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(8, labelSize - 1)));
            graphics.setColor(new Color(255, 255, 255, 205));
            String footer = spec.powerToughness().isEmpty()
                    ? "TOKEN"
                    : "TOKEN  " + spec.powerToughness();
            drawCentered(graphics, footer, width, height - 11);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void drawCentered(
            Graphics2D graphics,
            String text,
            int width,
            int baseline
    ) {
        FontMetrics metrics = graphics.getFontMetrics();
        int x = Math.max(4, (width - metrics.stringWidth(text)) / 2);
        graphics.drawString(text, x, baseline);
    }

    private static String ellipsize(
            Graphics2D graphics,
            String text,
            int maxWidth
    ) {
        if (text == null) {
            return "";
        }
        FontMetrics metrics = graphics.getFontMetrics();
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        String suffix = "…";
        String value = text;
        while (!value.isEmpty()
                && metrics.stringWidth(value + suffix) > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        return value + suffix;
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
