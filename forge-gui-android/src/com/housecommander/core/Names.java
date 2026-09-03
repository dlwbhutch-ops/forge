package com.housecommander.core;

import java.text.Normalizer;
import java.util.Locale;

public final class Names {
    private Names() {}

    public static String canonical(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u00A0', ' ')
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
