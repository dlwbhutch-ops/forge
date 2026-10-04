/*
 * HOUSE Commander Lab pilot decision model.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable UI-safe decision request emitted by the literal Forge game thread. */
public final class PilotDecision {
    public enum Kind {
        NONE,
        ACTION,
        CONFIRM,
        BINARY
    }

    private final long id;
    private final Kind kind;
    private final String player;
    private final String prompt;
    private final List<String> options;

    public PilotDecision(
            long id,
            Kind kind,
            String player,
            String prompt,
            List<String> options
    ) {
        this.id = id;
        this.kind = kind == null ? Kind.NONE : kind;
        this.player = safe(player);
        this.prompt = safe(prompt);
        this.options = immutable(options);
    }

    public static PilotDecision idle() {
        return new PilotDecision(
                0L,
                Kind.NONE,
                "",
                "No pilot decision pending",
                Collections.emptyList()
        );
    }

    public long id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public String player() {
        return player;
    }

    public String prompt() {
        return prompt;
    }

    public List<String> options() {
        return options;
    }

    public boolean pending() {
        return id > 0L && kind != Kind.NONE && !options.isEmpty();
    }

    private static List<String> immutable(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>(source.size());
        for (String value : source) {
            out.add(safe(value));
        }
        return Collections.unmodifiableList(out);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
