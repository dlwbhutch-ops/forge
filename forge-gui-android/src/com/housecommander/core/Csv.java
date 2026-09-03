package com.housecommander.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

public final class Csv {
    private Csv() {}

    public static List<List<String>> readAll(Reader reader) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isEmpty()) rows.add(parseLine(line));
            }
        }
        return rows;
    }

    public static List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unclosed CSV quote: " + line);
        out.add(field.toString());
        return out;
    }
}
