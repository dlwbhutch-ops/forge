package com.housecommander.forgebridge;

import java.util.concurrent.TimeUnit;

/**
 * Limits spectator-state materialization on Forge's game thread without
 * modifying, cancelling, or synthesizing any Forge action or result.
 *
 * HOUSE's full-state snapshots are expensive on large token-heavy boards.
 * During AI tournaments only important phase/outcome boundaries are forced.
 * Priority/combat/card-change events remain visible at bounded intervals.
 * Pilot mode retains its detailed event behavior.
 */
public final class SpectatorCapturePolicy {
    private static final long PILOT_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(75);
    private static final long TOURNAMENT_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(750);
    private static final long MAX_TOURNAMENT_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);
    private static final int CAPTURE_COST_MULTIPLIER = 25;

    private final boolean pilot;
    private long lastCaptureNs = Long.MIN_VALUE;
    private long intervalNs;

    public SpectatorCapturePolicy(boolean pilot) {
        this.pilot = pilot;
        intervalNs = pilot ? PILOT_INTERVAL_NS : TOURNAMENT_INTERVAL_NS;
    }

    public boolean shouldCapture(String eventName, long nowNs) {
        final String event = eventName == null ? "" : eventName;
        if (isImportantEvent(event)) return true;
        if (pilot && isDetailedPilotEvent(event)) return true;
        return lastCaptureNs == Long.MIN_VALUE
                || nowNs - lastCaptureNs >= intervalNs;
    }

    public void captureCompleted(long durationNs, long nowNs) {
        lastCaptureNs = nowNs;
        if (!pilot) {
            long cost = Math.max(0L, durationNs);
            // Avoid pathological overflow when a capture unexpectedly takes ages.
            long scaled = cost > Long.MAX_VALUE / CAPTURE_COST_MULTIPLIER
                    ? Long.MAX_VALUE : cost * CAPTURE_COST_MULTIPLIER;
            intervalNs = Math.max(TOURNAMENT_INTERVAL_NS,
                    Math.min(MAX_TOURNAMENT_INTERVAL_NS, scaled));
        }
    }

    public long intervalMillis() {
        return TimeUnit.NANOSECONDS.toMillis(intervalNs);
    }

    private static boolean isImportantEvent(String event) {
        return event.contains("Phase")
                || event.contains("Turn")
                || event.contains("PlayerLost")
                || event.contains("Outcome")
                || event.contains("GameFinished");
    }

    private static boolean isDetailedPilotEvent(String event) {
        return event.contains("Priority")
                || event.contains("Lives")
                || event.contains("Counters")
                || event.contains("ChangeZone")
                || event.contains("Tapped")
                || event.contains("Combat")
                || event.contains("Spell")
                || event.contains("Ability")
                || event.contains("Damage")
                || event.contains("Draw")
                || event.contains("Discard")
                || event.contains("Sacrifice")
                || event.contains("Destroyed")
                || event.contains("Started")
                || event.contains("Finished");
    }
}
