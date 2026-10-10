package com.phonepcremote;

import java.util.ArrayDeque;

/** Deterministic queue model, not an Android UI or physical network test. */
public final class AdaptiveQueueSimulationTest {
    private static final class Command {
        final boolean mouse;
        final int created;
        int updated;
        Command(boolean mouse, int now) { this.mouse = mouse; created = updated = now; }
    }

    private static void run(boolean enabled, int rtt) {
        AdaptiveLatency policy = new AdaptiveLatency();
        policy.setEnabled(enabled);
        ArrayDeque<Command> queue = new ArrayDeque<>();
        Command inFlight = null;
        int complete = -1, wake = -1, sent = 0, keys = 0, dropped = 0;
        long keyWait = 0;
        int maxKeyWait = 0;
        for (int now = 0; now < 14000; now++) {
            if (now < 10000 && now % 16 == 0) {
                Command tail = queue.peekLast();
                if (tail != null && tail.mouse) tail.updated = now;
                else { queue.addLast(new Command(true, now)); wake = -1; }
            }
            if (now < 10000 && now % 503 == 0) {
                queue.addLast(new Command(false, now));
                wake = -1;
            }
            if (inFlight != null && now == complete) {
                policy.record(rtt);
                inFlight = null;
            }
            if (inFlight != null || queue.isEmpty()) continue;
            Command first = queue.peekFirst();
            if (wake < 0 && first.mouse && queue.size() == 1 && policy.windowMs() > 0)
                wake = now + policy.windowMs();
            if (wake > now) continue;
            wake = -1;
            first = queue.removeFirst();
            if (first.mouse && now - first.updated > 250) { dropped++; continue; }
            if (!first.mouse) {
                keys++;
                int wait = now - first.created;
                keyWait += wait;
                maxKeyWait = Math.max(maxKeyWait, wait);
            }
            inFlight = first;
            sent++;
            complete = now + rtt;
        }
        if (keys != 20 || !queue.isEmpty() || inFlight != null)
            throw new AssertionError("Missing or duplicated keyboard command");
        System.out.printf("enabled=%s RTT=%dms sent=%d keyWaitMean=%.1fms keyWaitMax=%dms staleMoves=%d window=%dms%n",
                enabled, rtt, sent, keyWait / (double) keys, maxKeyWait, dropped, policy.windowMs());
    }

    public static void main(String[] args) {
        run(false, 10);
        run(true, 10);
        run(false, 120);
        run(true, 120);
        System.out.println("PASS: all keyboard commands preserved in queue simulation");
    }
}
