package com.housecommander.shared;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Portable HOUSE deck-library format.
 *
 * The format is UTF-8, line based, deterministic, and safe for arbitrary deck
 * text because user-controlled strings are Base64 encoded. The exact same file
 * can move between Android, macOS, and Windows without losing deck IDs or
 * version history.
 */
public final class HouseLibraryCodec {
    private static final String HEADER = "HOUSE_DECK_LIBRARY\t1";

    private HouseLibraryCodec() {}

    public static void write(DeckLibrary library, OutputStream output) throws IOException {
        write(library, new OutputStreamWriter(output, StandardCharsets.UTF_8));
    }

    public static void write(DeckLibrary library, Writer output) throws IOException {
        BufferedWriter writer = output instanceof BufferedWriter
                ? (BufferedWriter) output
                : new BufferedWriter(output);

        writer.write(HEADER);
        writer.newLine();

        for (DeckRecord record : library.allVersions()) {
            writer.write("DECK");
            writer.write('\t');
            writer.write(encode(record.deckId()));
            writer.write('\t');
            writer.write(Integer.toString(record.version()));
            writer.write('\t');
            writer.write(Long.toString(record.createdEpochMillis()));
            writer.write('\t');
            writer.write(library.isArchived(record.deckId()) ? "1" : "0");
            writer.write('\t');
            writer.write(encode(record.name()));
            writer.write('\t');
            writer.write(encode(record.commander()));
            writer.write('\t');
            writer.write(encode(record.sourceFormat()));
            writer.write('\t');
            writer.write(record.contentHash());
            writer.write('\t');
            writer.write(encode(record.deckText()));
            writer.newLine();
        }
        writer.flush();
    }

    public static DeckLibrary read(InputStream input) throws IOException {
        return read(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    public static DeckLibrary read(Reader input) throws IOException {
        BufferedReader reader = input instanceof BufferedReader
                ? (BufferedReader) input
                : new BufferedReader(input);

        String header = reader.readLine();
        if (!HEADER.equals(header)) {
            throw new IOException("Unsupported HOUSE deck library format");
        }

        DeckLibrary library = new DeckLibrary();
        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.trim().isEmpty()) continue;

            String[] fields = line.split("\\t", -1);
            if (fields.length != 10 || !"DECK".equals(fields[0])) {
                throw new IOException("Malformed HOUSE library line " + lineNumber);
            }

            try {
                String deckId = decode(fields[1]);
                int version = Integer.parseInt(fields[2]);
                long created = Long.parseLong(fields[3]);
                boolean archived = "1".equals(fields[4]);
                String name = decode(fields[5]);
                String commander = decode(fields[6]);
                String sourceFormat = decode(fields[7]);
                String hash = fields[8];
                String deckText = decode(fields[9]);

                DeckRecord record = DeckRecord.restore(
                        deckId,
                        version,
                        name,
                        commander,
                        deckText,
                        sourceFormat,
                        created,
                        hash
                );
                library.add(record);
                library.setArchived(deckId, archived);
            } catch (RuntimeException e) {
                throw new IOException("Invalid HOUSE library line " + lineNumber, e);
            }
        }
        return library;
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
