package com.phonepcremote;

/** A bounded foreground recovery cycle; only handshakes may be retried. */
final class ReconnectPolicy {
    private static final long[] DELAYS = {600, 1500, 3500, 7000};
    private int attempt;

    void reset() { attempt = 0; }

    long nextDelay(int jitterMs) {
        if (attempt == DELAYS.length) return -1;
        return DELAYS[attempt++] + Math.max(0, Math.min(250, jitterMs));
    }
}
