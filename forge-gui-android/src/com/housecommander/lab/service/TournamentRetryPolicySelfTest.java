package com.housecommander.lab.service;

import java.util.concurrent.TimeoutException;

public final class TournamentRetryPolicySelfTest {
    private TournamentRetryPolicySelfTest() {}

    public static void main(String[] args) {
        if (!TournamentRetryPolicy.replay(new TimeoutException("phase stagnation"), 0))
            throw new AssertionError("Timed-out game should replay once");
        if (TournamentRetryPolicy.replay(new TimeoutException("hard timeout"), 1))
            throw new AssertionError("A replay must never replay again");
        if (TournamentRetryPolicy.replay(new IllegalStateException("invalid winner"), 0))
            throw new AssertionError("Identity error must not trigger replay");
        if (TournamentRetryPolicy.replay(new InterruptedException("user stopped"), 0))
            throw new AssertionError("Stop request must not replay");
        System.out.println("TOURNAMENT_RETRY_POLICY_PASS");
    }
}
