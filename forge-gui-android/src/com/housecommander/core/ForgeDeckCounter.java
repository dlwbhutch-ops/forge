package com.housecommander.core;

import java.io.IOException;
import java.io.InputStream;

public final class ForgeDeckCounter {
    private ForgeDeckCounter() {}

    /** Counts commander + main-deck quantities. Sideboards and metadata are ignored. */
    public static int countCards(InputStream input) throws IOException {
        return DeckFileParser.parse(input, "").cardCount();
    }
}
