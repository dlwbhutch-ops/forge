package com.housecommander.lab.state;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** One worker's stop request, including the boundary where a verified game is checkpointed. */
public final class GameCancellation {
    private volatile boolean requested;
    private volatile long requestedNs;
    private Thread worker;

    public synchronized void bindToCurrentThread() {
        worker = Thread.currentThread();
        if (requested) worker.interrupt();
    }

    public synchronized void releaseWorker() {
        if (worker == Thread.currentThread()) worker = null;
    }

    public synchronized boolean requestStop() {
        if (requested) return false;
        requestedNs = System.nanoTime();
        requested = true;
        if (worker != null) worker.interrupt();
        return true;
    }

    public boolean isStopRequested() { return requested; }

    public boolean stopHasTimedOut() {
        return requested && System.nanoTime() - requestedNs >= TimeUnit.SECONDS.toNanos(8L);
    }

    public void check() {
        if (requested) throw new CancellationException("Game stopped by user");
    }

    /** Stop and checkpoint serialize: a cancelled game cannot add a winner or advance its pod. */
    public synchronized void commitVerifiedGame(Runnable checkpoint) {
        check();
        checkpoint.run();
    }
}
