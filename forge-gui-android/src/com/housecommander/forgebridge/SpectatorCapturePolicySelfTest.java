package com.housecommander.forgebridge;

import java.util.concurrent.TimeUnit;

/** Host-only regression test for the spectator update budget. */
public final class SpectatorCapturePolicySelfTest {
    private static int count = 0;
    private SpectatorCapturePolicySelfTest() {}

    private static void check(boolean actual, boolean expected, String label) {
        count++;
        if (actual != expected) throw new AssertionError(label + ": expected " + expected);
    }

    public static void main(String[] args) {
        SpectatorCapturePolicy tournament = new SpectatorCapturePolicy(false);
        long t = TimeUnit.SECONDS.toNanos(10);
        check(tournament.shouldCapture("GameEventPlayerPriority", t), true, "initial state");
        tournament.captureCompleted(TimeUnit.MILLISECONDS.toNanos(8), t);
        check(tournament.shouldCapture("GameEventPlayerPriority", t + TimeUnit.MILLISECONDS.toNanos(10)),
                false, "priority event throttled");
        check(tournament.shouldCapture("GameEventCardChangeZone", t + TimeUnit.MILLISECONDS.toNanos(400)),
                false, "zone event throttled");
        check(tournament.shouldCapture("GameEventPlayerPriority", t + TimeUnit.MILLISECONDS.toNanos(751)),
                true, "periodic snapshot");
        check(tournament.shouldCapture("GameEventTurnPhase", t + TimeUnit.MILLISECONDS.toNanos(5)),
                true, "phase boundary immediate");
        check(tournament.shouldCapture("GameEventGameOutcome", t + TimeUnit.MILLISECONDS.toNanos(5)),
                true, "outcome immediate");
        check(tournament.shouldCapture("GameEventPlayerLost", t + TimeUnit.MILLISECONDS.toNanos(5)),
                true, "elimination immediate");

        tournament.captureCompleted(TimeUnit.MILLISECONDS.toNanos(200), t + TimeUnit.SECONDS.toNanos(1));
        check(tournament.intervalMillis() == 5000, true, "expensive capture backoff");
        check(tournament.shouldCapture("GameEventPlayerPriority", t + TimeUnit.SECONDS.toNanos(2)),
                false, "backoff enforced");
        check(tournament.shouldCapture("GameEventPlayerPriority", t + TimeUnit.SECONDS.toNanos(7)),
                true, "backoff expires");

        SpectatorCapturePolicy pilot = new SpectatorCapturePolicy(true);
        check(pilot.shouldCapture("GameEventPlayerPriority", t), true, "initial pilot");
        pilot.captureCompleted(TimeUnit.MILLISECONDS.toNanos(500), t);
        check(pilot.shouldCapture("GameEventPlayerPriority", t + TimeUnit.MILLISECONDS.toNanos(1)),
                true, "pilot priority updates immediate");
        check(pilot.intervalMillis() == 75, true, "pilot not throttled by capture cost");
        check(pilot.shouldCapture("GameEventOther", t + TimeUnit.MILLISECONDS.toNanos(76)),
                true, "pilot periodic state");
        System.out.println("PASS: " + count + " spectator policy regression checks");
    }
}
