package com.phonepcremote;

/** Bounded mouse coalescing; never reconnects or retries input. */
final class AdaptiveLatency {
    private boolean enabled;
    private double average;
    private double jitter;
    private int slowSamples;
    private int fastSamples;
    private int windowMs;

    synchronized void setEnabled(boolean value) {
        enabled = value;
        reset();
    }

    synchronized void reset() {
        average = 0;
        jitter = 0;
        slowSamples = fastSamples = windowMs = 0;
    }

    synchronized void record(double milliseconds) {
        if (!enabled || !Double.isFinite(milliseconds) || milliseconds < 0) return;
        if (average == 0) average = milliseconds;
        jitter += 0.2 * (Math.abs(milliseconds - average) - jitter);
        average += 0.2 * (milliseconds - average);
        if (average > 80 || jitter > 35) {
            fastSamples = 0;
            if (++slowSamples >= 6) {
                windowMs = Math.min(40, windowMs + 10);
                slowSamples = 0;
            }
        } else if (average < 45 && jitter < 15) {
            slowSamples = 0;
            if (++fastSamples >= 12) {
                windowMs = Math.max(0, windowMs - 10);
                fastSamples = 0;
            }
        } else {
            slowSamples = fastSamples = 0;
        }
    }

    synchronized int windowMs() { return enabled ? windowMs : 0; }
}
