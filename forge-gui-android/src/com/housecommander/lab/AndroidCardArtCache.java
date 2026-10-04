package com.housecommander.lab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.LruCache;

import com.housecommander.forgebridge.CardArtStore;
import com.housecommander.forgebridge.LiveGameState;
import com.housecommander.forgebridge.TokenArtResolver;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** On-demand Android image cache for HOUSE battlefield card faces. */
public final class AndroidCardArtCache {
    private static final int MEMORY_CACHE_KB = 32 * 1024;

    private final Context context;
    private final ExecutorService loader = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "HOUSE-Card-Art");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final LruCache<String, Bitmap> bitmaps =
            new LruCache<String, Bitmap>(MEMORY_CACHE_KB) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return Math.max(1, value.getByteCount() / 1024);
                }
            };

    public AndroidCardArtCache(Context context) {
        this.context = context.getApplicationContext();
    }

    public Bitmap cardBitmap(
            LiveGameState.CardState card,
            int maxWidth,
            int maxHeight,
            Runnable onReady
    ) {
        if (card == null) {
            return null;
        }
        if (card.token()) {
            return tokenBitmap(card, maxWidth, maxHeight, card.tapped());
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
        Bitmap ready = bitmaps.get(key);
        if (ready != null && !ready.isRecycled()) {
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

    public Bitmap zoomBitmap(
            LiveGameState.CardState card,
            int maxWidth,
            int maxHeight,
            Runnable onReady
    ) {
        if (card == null) {
            return null;
        }
        if (card.token()) {
            return tokenBitmap(card, maxWidth, maxHeight, false);
        }
        if (card.imageUrl().isEmpty()) {
            return null;
        }
        String key = card.imageUrl()
                + "|zoom|"
                + maxWidth
                + "x"
                + maxHeight;
        Bitmap ready = bitmaps.get(key);
        if (ready != null && !ready.isRecycled()) {
            return ready;
        }
        request(key, card.imageUrl(), false, maxWidth, maxHeight, onReady);
        return null;
    }

    public void shutdown() {
        loader.shutdownNow();
        pending.clear();
        bitmaps.evictAll();
    }

    private Bitmap tokenBitmap(
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
        Bitmap ready = bitmaps.get(key);
        if (ready != null && !ready.isRecycled()) {
            return ready;
        }

        int sourceWidth = rotate ? maxHeight : maxWidth;
        int sourceHeight = rotate ? maxWidth : maxHeight;
        Bitmap source = renderTokenArt(
                spec,
                Math.max(64, sourceWidth),
                Math.max(88, sourceHeight)
        );
        Bitmap display = rotate ? rotate90(source) : source;
        Bitmap scaled = scaleInside(display, maxWidth, maxHeight);

        if (display != scaled && !display.isRecycled()) {
            display.recycle();
        }
        if (source != display
                && source != scaled
                && !source.isRecycled()) {
            source.recycle();
        }

        bitmaps.put(key, scaled);
        return scaled;
    }

    private static Bitmap renderTokenArt(
            TokenArtResolver.TokenArtSpec spec,
            int width,
            int height
    ) {
        Bitmap image = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(image);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF bounds = new RectF(0f, 0f, width, height);

        int primary = 0xFF000000 | spec.primaryRgb();
        int secondary = 0xFF000000 | spec.secondaryRgb();
        paint.setShader(new LinearGradient(
                0f,
                0f,
                width,
                height,
                primary,
                secondary,
                Shader.TileMode.CLAMP
        ));
        canvas.drawRoundRect(bounds, 18f, 18f, paint);
        paint.setShader(null);

        int seed = spec.signature().hashCode();
        for (int i = 0; i < 14; i++) {
            seed = seed * 1664525 + 1013904223;
            float x = Math.floorMod(seed, Math.max(1, width));
            seed = seed * 1664525 + 1013904223;
            float y = Math.floorMod(seed, Math.max(1, height));
            seed = seed * 1664525 + 1013904223;
            float radius = 6 + Math.floorMod(seed, Math.max(8, width / 5));
            paint.setColor(0x22FFFFFF + ((i % 3) << 24));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, radius, paint);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(0xDDFFFFFF);
        canvas.drawRoundRect(
                new RectF(2f, 2f, width - 3f, height - 3f),
                18f,
                18f,
                paint
        );
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);

        float topSize = Math.max(10f, Math.min(17f, width / 7f));
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        paint.setTextSize(topSize);
        paint.setColor(0xF2FFFFFF);
        canvas.drawText(
                ellipsize(paint, spec.displayName(), width - 14f),
                width / 2f,
                topSize + 8f,
                paint
        );

        float glyphSize = Math.max(24f, Math.min(width, height) / 3f);
        paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        paint.setTextSize(glyphSize);
        paint.setColor(0xE6FFFFFF);
        canvas.drawText(
                spec.glyph(),
                width / 2f,
                Math.max(topSize + glyphSize + 12f, height / 2f + glyphSize / 3f),
                paint
        );

        float labelSize = Math.max(9f, Math.min(12f, width / 9f));
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        paint.setTextSize(labelSize);
        paint.setColor(0xE6FFFFFF);
        canvas.drawText(
                ellipsize(paint, spec.familyTitle(), width - 16f),
                width / 2f,
                height - 28f,
                paint
        );

        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        paint.setTextSize(Math.max(8f, labelSize - 1f));
        paint.setColor(0xCCFFFFFF);
        String footer = spec.powerToughness().isEmpty()
                ? "TOKEN"
                : "TOKEN  " + spec.powerToughness();
        canvas.drawText(footer, width / 2f, height - 11f, paint);
        return image;
    }

    private static String ellipsize(
            Paint paint,
            String text,
            float maxWidth
    ) {
        if (text == null) {
            return "";
        }
        if (paint.measureText(text) <= maxWidth) {
            return text;
        }
        String suffix = "…";
        String value = text;
        while (!value.isEmpty()
                && paint.measureText(value + suffix) > maxWidth) {
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
                File root = new File(context.getFilesDir(), "card-art");
                File file = CardArtStore.fetch(root, imageUrl);
                if (file == null) {
                    return;
                }
                Bitmap source = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (source == null) {
                    return;
                }

                Bitmap display = rotate ? rotate90(source) : source;
                Bitmap scaled = scaleInside(display, maxWidth, maxHeight);

                if (display != scaled && !display.isRecycled()) {
                    display.recycle();
                }
                if (source != display
                        && source != scaled
                        && !source.isRecycled()) {
                    source.recycle();
                }

                bitmaps.put(key, scaled);

                if (onReady != null) {
                    android.os.Handler handler =
                            new android.os.Handler(context.getMainLooper());
                    handler.post(onReady);
                }
            } catch (Throwable ignored) {
                // Card art is optional and must never block literal gameplay.
            } finally {
                pending.remove(key);
            }
        });
    }

    private static Bitmap scaleInside(
            Bitmap source,
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
        if (width == source.getWidth() && height == source.getHeight()) {
            return source;
        }
        return Bitmap.createScaledBitmap(source, width, height, true);
    }

    private static Bitmap rotate90(Bitmap source) {
        Matrix matrix = new Matrix();
        matrix.postRotate(90f);
        return Bitmap.createBitmap(
                source,
                0,
                0,
                source.getWidth(),
                source.getHeight(),
                matrix,
                true
        );
    }
}
