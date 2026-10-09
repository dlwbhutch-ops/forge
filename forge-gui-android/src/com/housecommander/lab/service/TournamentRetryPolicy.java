package com.housecommander.lab.service;

import java.util.concurrent.TimeoutException;

/**
 * One bounded replay is allowed only for a Forge game that had no outcome
 * because of a watchdog timeout. All other errors fail closed.
 */
public final class TournamentRetryPolicy {
    private TournamentRetryPolicy() {}

    public static boolean replay(Throwable error, int retriesUsed) {
        return retriesUsed == 0 && error instanceof TimeoutException;
    }
}
