package com.housecommander.lab;

import android.content.Context;

import com.housecommander.core.HousePackage;
import com.housecommander.core.HousePackageLoader;

import java.io.IOException;

/** Single entry point for loading the bundled template and the user's active roster. */
public final class HouseRuntime {
    private HouseRuntime() {}

    public static HousePackage loadTemplatePackage(Context context) throws IOException {
        HousePackage template = HousePackageLoader.load(AndroidAssets.from(context), "house19");
        HouseInstall.ensureDeckFiles(context, template);
        return template;
    }

    public static HousePackage loadActivePackage(Context context) throws IOException {
        HousePackage template = loadTemplatePackage(context);
        return new DeckLibraryStore(context).activePackage(template);
    }
}
