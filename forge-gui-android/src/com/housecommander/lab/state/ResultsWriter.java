package com.housecommander.lab.state;

import android.content.Context;

import com.housecommander.core.DeckSpec;
import com.housecommander.core.HousePackage;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;

public final class ResultsWriter {
    private ResultsWriter() {}

    public static File resultsFile(Context context) {
        File dir = new File(context.getFilesDir(), "results");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "literal_house19_gauntlets.csv");
    }

    public static synchronized void appendGauntlet(Context context, HousePackage pack, int gauntlet,
                                                    Map<String, Integer> wins, Map<String, Integer> games) throws IOException {
        File file = resultsFile(context);
        boolean header = !file.exists() || file.length() == 0;
        try (FileWriter w = new FileWriter(file, true)) {
            if (header) w.write("gauntlet,deck,pod_wins,games,win_rate\n");
            for (DeckSpec d : pack.decks()) {
                Integer winValue = wins.get(d.deck());
                Integer gameValue = games.get(d.deck());
                int win = winValue == null ? 0 : winValue.intValue();
                int game = gameValue == null ? 0 : gameValue.intValue();
                double rate = game == 0 ? 0.0 : ((double) win / game);
                w.write(gauntlet + "," + csv(d.deck()) + "," + win + "," + game + "," + rate + "\n");
            }
        }
    }

    private static String csv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
