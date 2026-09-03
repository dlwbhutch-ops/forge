package com.housecommander.lab;

import android.content.Context;

import com.housecommander.core.AssetSource;

import java.io.IOException;
import java.io.InputStream;

public final class AndroidAssets {
    private AndroidAssets() {}

    public static AssetSource from(final Context context) {
        return new AssetSource() {
            @Override
            public InputStream open(String path) throws IOException {
                return context.getAssets().open(path);
            }
        };
    }
}
