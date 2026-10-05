package com.housecommander.lab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Canvas;
import android.util.LruCache;

import com.housecommander.forgebridge.CardArtStore;
import com.housecommander.forgebridge.LiveGameState;
import com.housecommander.token.TokenArtResolver;

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
        if (TokenArtResolver.useIllustration(card)) return tokenBitmap(card, maxWidth, maxHeight, card.tapped());
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
        if (TokenArtResolver.useIllustration(card)) return tokenBitmap(card, maxWidth, maxHeight, false);
        if (card == null || card.imageUrl().isEmpty()) {
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

    private Bitmap tokenBitmap(LiveGameState.CardState card,int width,int height,boolean tapped) {
        String key="token|"+TokenArtResolver.identity(card)+"|"+tapped+"|"+width+"x"+height;
        Bitmap ready=bitmaps.get(key);
        if(ready!=null&&!ready.isRecycled())return ready;
        Bitmap art=Bitmap.createBitmap(200,280,Bitmap.Config.ARGB_8888);
        TokenArtResolver.paint(card,new AndroidTokenPainter(new Canvas(art)));
        Bitmap display=tapped?rotate90(art):art;
        Bitmap result=scaleInside(display,width,height);
        if(display!=result)display.recycle();
        if(art!=display&&art!=result)art.recycle();
        bitmaps.put(key,result);return result;
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
