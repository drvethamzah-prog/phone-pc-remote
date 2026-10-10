package com.phonepcremote;

public final class AdaptiveLatencyTest {
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
    public static void main(String[] args) {
        AdaptiveLatency policy = new AdaptiveLatency();
        for (int i = 0; i < 100; i++) policy.record(150);
        check(policy.windowMs() == 0);
        policy.setEnabled(true);
        policy.record(150);
        check(policy.windowMs() == 0);
        for (int i = 0; i < 100; i++) policy.record(150);
        check(policy.windowMs() == 40);
        for (int i = 0; i < 200; i++) policy.record(10);
        check(policy.windowMs() == 0);
        policy.record(Double.NaN);
        policy.record(Double.POSITIVE_INFINITY);
        policy.record(-1);
        check(policy.windowMs() == 0);
        for (int i = 0; i < 100; i++) policy.record(150);
        policy.reset();
        check(policy.windowMs() == 0);
        for (int i = 0; i < 100; i++) policy.record(150);
        policy.setEnabled(false);
        check(policy.windowMs() == 0);
        System.out.println("AdaptiveLatencyTest passed");
    }
}
