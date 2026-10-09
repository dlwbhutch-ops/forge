package com.housecommander.forgebridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Capture worker evidence before cancellation changes the stalled game. */
public final class HouseStallDiagnostics {
    private static volatile String[] snapshot = new String[0];
    private HouseStallDiagnostics() { }

    public static void reset() { snapshot = new String[0]; }

    public static void capture(String reason) {
        List<String> lines = new ArrayList<>();
        lines.add("HOUSE_ABORT_REASON=" + reason);
        try {
            Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
            int helpers = 0;
            for (Map.Entry<Thread, StackTraceElement[]> entry : traces.entrySet()) {
                Thread thread = entry.getKey();
                String name = thread.getName();
                if (!name.equals("HOUSE-Forge-Game")) continue;
                lines.add(format(thread, entry.getValue()));
            }
            for (Map.Entry<Thread, StackTraceElement[]> entry : traces.entrySet()) {
                Thread thread = entry.getKey();
                if (!thread.getName().contains("ForkJoinPool") || helpers >= 6) continue;
                lines.add(format(thread, entry.getValue()));
                helpers++;
            }
        } catch (Throwable error) {
            lines.add("HOUSE_WORKER_STACK_UNAVAILABLE=" + error.getClass().getName());
        }
        snapshot = lines.toArray(new String[0]);
    }

    private static String format(Thread thread, StackTraceElement[] stack) {
        StringBuilder out = new StringBuilder("HOUSE_WORKER_STACK=");
        out.append(thread.getName()).append(" state=").append(thread.getState());
        for (int i = 0; i < Math.min(stack.length, 18); i++) {
            out.append(" | at ").append(stack[i]);
        }
        return out.toString();
    }

    public static String[] append(String[] markers) {
        List<String> lines = new ArrayList<>();
        if (markers != null) lines.addAll(Arrays.asList(markers));
        lines.add("HOUSE_APK_BUILD=195");
        lines.addAll(Arrays.asList(snapshot));
        return lines.toArray(new String[0]);
    }
}
