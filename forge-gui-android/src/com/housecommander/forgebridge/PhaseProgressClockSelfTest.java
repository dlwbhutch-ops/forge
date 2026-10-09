package com.housecommander.forgebridge;

/** No Forge or Android dependencies; tests real monotonic times without sleeping. */
public final class PhaseProgressClockSelfTest {
    private PhaseProgressClockSelfTest() {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long now = 1_000_000_000L;
        PhaseProgressClock clock = new PhaseProgressClock(now);
        check(!clock.hasPhase(), "initial phase must be unobserved");
        clock.observe("turn=58,phase=COMBAT_DECLARE_ATTACKERS", now);
        check(clock.hasPhase(), "phase must be observed");
        clock.observe("turn=58,phase=COMBAT_DECLARE_ATTACKERS", now + 300_000_000_000L);
        check(clock.stationaryNanos(now + 600_000_000_000L) == 600_000_000_000L,
                "duplicate phase events must not reset watchdog");
        clock.observe("turn=58,phase=COMBAT_DECLARE_BLOCKERS", now + 601_000_000_000L);
        check(clock.stationaryNanos(now + 602_000_000_000L) == 1_000_000_000L,
                "real phase advance must reset watchdog");
        clock.observe("turn=59,phase=TURN_BEGIN", now + 605_000_000_000L);
        check(clock.stationaryNanos(now + 605_000_000_000L) == 0L,
                "new turn must count as progress");
        check(clock.describe(now + 606_000_000_000L).contains("duplicatePhaseEvents=1"),
                "description must reveal duplicate transitions");
        System.out.println("PHASE_PROGRESS_WATCHDOG_PASS");
    }
}
