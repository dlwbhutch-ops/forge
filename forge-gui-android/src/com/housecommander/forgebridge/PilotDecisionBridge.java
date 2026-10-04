/*
 * HOUSE Commander Lab pilot decision bridge.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread boundary between Forge's game thread and the HOUSE UI.
 *
 * <p>Forge blocks only while a real human decision is pending. The UI polls
 * {@link #current()} and submits the selected option by request id.
 */
public final class PilotDecisionBridge {
    private static final Object LOCK = new Object();
    private static final AtomicLong IDS = new AtomicLong(0L);

    private static volatile PilotDecision current = PilotDecision.idle();
    private static Integer responseIndex;
    private static boolean cancelled;

    private PilotDecisionBridge() {
    }

    public static PilotDecision current() {
        return current;
    }

    public static boolean hasPending() {
        return current.pending();
    }

    public static void reset() {
        synchronized (LOCK) {
            cancelled = false;
            responseIndex = null;
            current = PilotDecision.idle();
            LOCK.notifyAll();
        }
    }

    public static void cancel() {
        synchronized (LOCK) {
            cancelled = true;
            responseIndex = null;
            current = PilotDecision.idle();
            LOCK.notifyAll();
        }
    }

    public static boolean submit(long id, int optionIndex) {
        synchronized (LOCK) {
            PilotDecision pending = current;
            if (!pending.pending() || pending.id() != id) {
                return false;
            }
            if (optionIndex < 0 || optionIndex >= pending.options().size()) {
                return false;
            }
            responseIndex = optionIndex;
            current = PilotDecision.idle();
            LOCK.notifyAll();
            return true;
        }
    }

    public static int request(
            PilotDecision.Kind kind,
            String player,
            String prompt,
            List<String> options
    ) {
        if (options == null || options.isEmpty()) {
            return -1;
        }

        synchronized (LOCK) {
            if (cancelled) {
                return -1;
            }

            long id = IDS.incrementAndGet();
            responseIndex = null;
            current = new PilotDecision(
                    id,
                    kind,
                    player,
                    prompt,
                    options
            );
            LOCK.notifyAll();

            while (responseIndex == null && !cancelled) {
                try {
                    LOCK.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    current = PilotDecision.idle();
                    return -1;
                }
            }

            if (cancelled) {
                current = PilotDecision.idle();
                return -1;
            }

            int result = responseIndex == null ? -1 : responseIndex;
            responseIndex = null;
            current = PilotDecision.idle();
            return result;
        }
    }
}
