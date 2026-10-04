/*
 * HOUSE Commander Lab spectator playback controls.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe visual playback buffer for the HOUSE battlefield viewer.
 *
 * <p>This deliberately never pauses or throttles Forge itself. Literal games and
 * tournaments continue at full engine speed while the UI may pause, step, or
 * replay buffered immutable {@link LiveGameState} snapshots independently.
 */
public final class SpectatorPlayback {
    public enum Speed {
        PAUSED("Paused", 0),
        X1("1x", 1),
        X2("2x", 2),
        X4("4x", 4),
        X8("8x", 8),
        MAX("Max", Integer.MAX_VALUE);

        private final String label;
        private final int framesPerTick;

        Speed(String label, int framesPerTick) {
            this.label = label;
            this.framesPerTick = framesPerTick;
        }

        public String label() {
            return label;
        }

        private int framesPerTick() {
            return framesPerTick;
        }
    }

    private static final Object LOCK = new Object();
    private static final int MAX_FRAMES = 512;
    private static final List<LiveGameState> FRAMES = new ArrayList<LiveGameState>();

    private static LiveGameState visible = LiveGameState.idle();
    private static Speed speed = Speed.MAX;

    private SpectatorPlayback() {
    }

    /** Starts a fresh visual timeline without affecting Forge execution. */
    public static void reset(LiveGameState initial) {
        synchronized (LOCK) {
            FRAMES.clear();
            visible = initial == null ? LiveGameState.idle() : initial;
            FRAMES.add(visible);
            FriendlyGameLog.reset(visible);
            speed = Speed.MAX;
        }
    }

    /** Records an immutable Forge snapshot into the bounded viewer history. */
    public static void record(LiveGameState state) {
        if (state == null) {
            return;
        }
        synchronized (LOCK) {
            if (!FRAMES.isEmpty()
                    && state.sequence() <= FRAMES.get(FRAMES.size() - 1).sequence()) {
                FRAMES.clear();
            }

            FRAMES.add(state);
            FriendlyGameLog.record(state);
            while (FRAMES.size() > MAX_FRAMES) {
                FRAMES.remove(0);
            }

            if (speed == Speed.MAX || visible.sequence() <= 1L) {
                visible = state;
            } else if (!containsSequence(visible.sequence())) {
                visible = FRAMES.get(0);
            }
        }
    }

    /**
     * Returns the frame the UI should render for this refresh tick.
     * Non-MAX speeds consume a bounded number of buffered frames per UI tick.
     */
    public static LiveGameState visibleState() {
        synchronized (LOCK) {
            if (FRAMES.isEmpty()) {
                return visible;
            }
            if (speed == Speed.MAX) {
                visible = FRAMES.get(FRAMES.size() - 1);
                return visible;
            }
            if (speed == Speed.PAUSED) {
                return visible;
            }

            int index = indexOfSequence(visible.sequence());
            if (index < 0) {
                index = 0;
                visible = FRAMES.get(0);
            }
            int next = Math.min(
                    FRAMES.size() - 1,
                    index + Math.max(1, speed.framesPerTick())
            );
            visible = FRAMES.get(next);
            return visible;
        }
    }

    public static LiveGameState latestState() {
        synchronized (LOCK) {
            return FRAMES.isEmpty()
                    ? visible
                    : FRAMES.get(FRAMES.size() - 1);
        }
    }

    public static void setSpeed(Speed next) {
        if (next == null) {
            return;
        }
        synchronized (LOCK) {
            if (next == Speed.PAUSED && speed == Speed.MAX && !FRAMES.isEmpty()) {
                visible = FRAMES.get(FRAMES.size() - 1);
            }
            speed = next;
            if (speed == Speed.MAX && !FRAMES.isEmpty()) {
                visible = FRAMES.get(FRAMES.size() - 1);
            }
        }
    }

    public static void pause() {
        setSpeed(Speed.PAUSED);
    }

    public static void goLive() {
        setSpeed(Speed.MAX);
    }

    public static Speed speed() {
        synchronized (LOCK) {
            return speed;
        }
    }

    /** Advance exactly one published Forge snapshot and remain paused. */
    public static LiveGameState nextAction() {
        synchronized (LOCK) {
            speed = Speed.PAUSED;
            visible = nextFrameAfter(visible.sequence());
            return visible;
        }
    }

    /** Advance to the next turn/phase boundary and remain paused. */
    public static LiveGameState nextPhase() {
        synchronized (LOCK) {
            speed = Speed.PAUSED;
            int start = indexOfSequence(visible.sequence());
            if (start < 0) {
                start = 0;
            }
            for (int i = Math.min(start + 1, FRAMES.size()); i < FRAMES.size(); i++) {
                LiveGameState candidate = FRAMES.get(i);
                if (candidate.turn() != visible.turn()
                        || !candidate.phase().equals(visible.phase())) {
                    visible = candidate;
                    return visible;
                }
            }
            if (!FRAMES.isEmpty()) {
                visible = FRAMES.get(FRAMES.size() - 1);
            }
            return visible;
        }
    }

    /** Advance to the next turn number and remain paused. */
    public static LiveGameState nextTurn() {
        synchronized (LOCK) {
            speed = Speed.PAUSED;
            int start = indexOfSequence(visible.sequence());
            if (start < 0) {
                start = 0;
            }
            for (int i = Math.min(start + 1, FRAMES.size()); i < FRAMES.size(); i++) {
                LiveGameState candidate = FRAMES.get(i);
                if (candidate.turn() != visible.turn()) {
                    visible = candidate;
                    return visible;
                }
            }
            if (!FRAMES.isEmpty()) {
                visible = FRAMES.get(FRAMES.size() - 1);
            }
            return visible;
        }
    }

    /** Approximate number of buffered snapshots between the viewer and live Forge state. */
    public static int framesBehind() {
        synchronized (LOCK) {
            if (FRAMES.isEmpty()) {
                return 0;
            }
            int index = indexOfSequence(visible.sequence());
            if (index < 0) {
                return FRAMES.size() - 1;
            }
            return Math.max(0, FRAMES.size() - 1 - index);
        }
    }

    public static int bufferedFrames() {
        synchronized (LOCK) {
            return FRAMES.size();
        }
    }

    private static LiveGameState nextFrameAfter(long sequence) {
        if (FRAMES.isEmpty()) {
            return visible;
        }
        int index = indexOfSequence(sequence);
        if (index < 0) {
            return FRAMES.get(0);
        }
        return FRAMES.get(Math.min(FRAMES.size() - 1, index + 1));
    }

    private static boolean containsSequence(long sequence) {
        return indexOfSequence(sequence) >= 0;
    }

    private static int indexOfSequence(long sequence) {
        for (int i = FRAMES.size() - 1; i >= 0; i--) {
            if (FRAMES.get(i).sequence() == sequence) {
                return i;
            }
        }
        return -1;
    }
}
