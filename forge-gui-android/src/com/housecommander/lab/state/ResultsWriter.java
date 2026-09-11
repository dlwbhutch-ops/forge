package com.housecommander.lab.state;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import com.housecommander.core.DeckSpec;
import com.housecommander.core.HousePackage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Durable, idempotent writer for literal HOUSE gauntlet summaries.
 *
 * A gauntlet is replaced by gauntlet number rather than blindly appended.
 * That makes recovery safe if Android kills the process after the CSV was
 * written but before the checkpoint advanced to the next gauntlet.
 */
public final class ResultsWriter {
    private static final String FILE_NAME = "literal_house19_gauntlets.csv";
    private static final String HEADER = "gauntlet,deck,pod_wins,games,win_rate";

    private ResultsWriter() {
    }

    public static File resultsFile(Context context) {
        Context app = context.getApplicationContext();
        Context safe = app != null ? app : context;
        return new File(new File(safe.getFilesDir(), "results"), FILE_NAME);
    }

    /**
     * Writes or replaces exactly one gauntlet's 19 deck rows.
     */
    public static synchronized void writeGauntlet(
            Context context,
            HousePackage pack,
            int gauntlet,
            Map<String, Integer> wins,
            Map<String, Integer> games
    ) throws IOException {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        if (pack == null) {
            throw new IllegalArgumentException("HousePackage must not be null");
        }
        if (gauntlet < 1) {
            throw new IllegalArgumentException("Gauntlet number must be at least 1");
        }

        File file = resultsFile(context);
        File dir = file.getParentFile();
        if (dir == null) {
            throw new IOException("Results directory is unavailable");
        }
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Could not create results directory: " + dir.getAbsolutePath());
        }

        List<String> retainedRows = readRowsExcept(file, gauntlet);
        File temp = new File(dir, FILE_NAME + ".tmp");

        writeReplacement(temp, retainedRows, pack, gauntlet, wins, games);

        /*
         * Both paths live in the same app-private directory. Linux rename(2)
         * replaces the destination atomically, so a process death cannot leave
         * a half-written CSV in place.
         */
        try {
            Os.rename(temp.getAbsolutePath(), file.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException(
                    "Could not atomically replace results file: "
                            + file.getAbsolutePath()
                            + " (temporary file retained at "
                            + temp.getAbsolutePath()
                            + ")",
                    e
            );
        }
    }

    /**
     * Backward-compatible method name for any older caller.
     */
    @Deprecated
    public static void appendGauntlet(
            Context context,
            HousePackage pack,
            int gauntlet,
            Map<String, Integer> wins,
            Map<String, Integer> games
    ) throws IOException {
        writeGauntlet(context, pack, gauntlet, wins, games);
    }

    public static synchronized void reset(Context context) throws IOException {
        File file = resultsFile(context);
        File temp = new File(file.getParentFile(), FILE_NAME + ".tmp");

        if (file.exists() && !file.delete()) {
            throw new IOException("Could not delete results file: " + file.getAbsolutePath());
        }
        if (temp.exists() && !temp.delete()) {
            throw new IOException("Could not delete temporary results file: " + temp.getAbsolutePath());
        }
    }

    private static List<String> readRowsExcept(File file, int gauntlet) throws IOException {
        List<String> rows = new ArrayList<>();
        if (!file.isFile() || file.length() == 0L) {
            return rows;
        }

        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            boolean firstNonEmpty = true;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                if (firstNonEmpty && HEADER.equals(line)) {
                    firstNonEmpty = false;
                    continue;
                }
                firstNonEmpty = false;

                int rowGauntlet = parseGauntletNumber(line);
                if (rowGauntlet != gauntlet) {
                    rows.add(line);
                }
            }
        }
        return rows;
    }

    private static void writeReplacement(
            File temp,
            List<String> retainedRows,
            HousePackage pack,
            int gauntlet,
            Map<String, Integer> wins,
            Map<String, Integer> games
    ) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(temp, false);
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {

            out.write(HEADER);
            out.newLine();

            for (String row : retainedRows) {
                out.write(row);
                out.newLine();
            }

            for (DeckSpec d : pack.decks()) {
                int win = mapValue(wins, d.deck());
                int game = mapValue(games, d.deck());
                double rate = game == 0 ? 0.0d : ((double) win / (double) game);

                out.write(Integer.toString(gauntlet));
                out.write(',');
                out.write(csv(d.deck()));
                out.write(',');
                out.write(Integer.toString(win));
                out.write(',');
                out.write(Integer.toString(game));
                out.write(',');
                out.write(Double.toString(rate));
                out.newLine();
            }

            out.flush();
            fos.getFD().sync();
        }
    }

    private static int mapValue(Map<String, Integer> map, String key) {
        if (map == null) {
            return 0;
        }
        Integer value = map.get(key);
        return value == null ? 0 : Math.max(0, value.intValue());
    }

    private static int parseGauntletNumber(String row) throws IOException {
        int comma = row.indexOf(',');
        if (comma <= 0) {
            throw new IOException("Malformed HOUSE results row: " + row);
        }
        try {
            int value = Integer.parseInt(row.substring(0, comma));
            if (value < 1) {
                throw new NumberFormatException("gauntlet < 1");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IOException("Malformed gauntlet number in HOUSE results row: " + row, e);
        }
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
