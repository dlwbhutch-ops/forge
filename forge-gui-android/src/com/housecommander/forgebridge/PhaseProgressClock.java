package com.housecommander.forgebridge;

/**
 * Tracks movement through genuinely different game turns/phases.
 * Priority passes and repeated events in the same phase are NOT progress.
 * Uses caller-provided monotonic times so the policy is deterministic to test.
 */
public final class PhaseProgressClock {
    private long lastAdvanceNs;
    private String phaseKey = "";
    private long transitions;
    private long repeatedPhases;

    public PhaseProgressClock(long startedNs) {
        lastAdvanceNs = startedNs;
    }

    public synchronized void observe(String nextPhaseKey, long atNs) {
        if (nextPhaseKey == null || nextPhaseKey.isEmpty()) return;
        if (!nextPhaseKey.equals(phaseKey)) {
            phaseKey = nextPhaseKey;
            lastAdvanceNs = atNs;
            transitions++;
        } else {
            repeatedPhases++;
        }
    }

    public synchronized boolean hasPhase() {
        return transitions > 0L;
    }

    public synchronized long stationaryNanos(long nowNs) {
        return Math.max(0L, nowNs - lastAdvanceNs);
    }

    public synchronized String describe(long nowNs) {
        long stationaryMillis = stationaryNanos(nowNs) / 1_000_000L;
        return "phaseTransitions=" + transitions
                + ",duplicatePhaseEvents=" + repeatedPhases
                + ",lastPhase=" + phaseKey
                + ",samePhaseMs=" + stationaryMillis;
    }
}
