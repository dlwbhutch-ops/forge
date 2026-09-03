package com.housecommander.core;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;

/** Host-side smoke test: java ... HouseCoreSelfTest <assets-directory>. */
public final class HouseCoreSelfTest {
    private HouseCoreSelfTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected assets directory");
        Path assets = Path.of(args[0]);
        AssetSource source = path -> new FileInputStream(assets.resolve(path).toFile());
        HousePackage pack = HousePackageLoader.load(source, "house19");
        System.out.println("PASS: " + pack.validation().summary());
        System.out.println("First pod: R" + pack.schedule().get(0).round() + " P" + pack.schedule().get(0).pod()
                + " -> " + String.join(" | ", pack.schedule().get(0).members()));
    }
}
