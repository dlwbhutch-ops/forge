package com.housecommander.core;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface AssetSource {
    InputStream open(String path) throws IOException;
}
