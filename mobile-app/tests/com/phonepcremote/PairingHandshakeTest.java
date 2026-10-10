package com.phonepcremote;

import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class PairingHandshakeTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger notices = new AtomicInteger();
        String confirmed = new PairingHandshake("working-token", "unused-candidate").connect((token, candidate) -> {
            check("working-token".equals(token), "working credential changed on retry");
            if (attempts.getAndIncrement() == 0) throw new SocketTimeoutException("dropped hello_ok");
            return new PairingHandshake.Reply("hello_ok", "", "");
        }, notices::incrementAndGet);
        check("working-token".equals(confirmed), "reconnect adopted an unapproved candidate");
        check(attempts.get() == 2 && notices.get() == 1, "retry was not bounded");
        System.out.println("PASS lost reconnect response preserves confirmed token");

        attempts.set(0);
        confirmed = new PairingHandshake("", "pending-token").connect((token, candidate) -> {
            check("pending-token".equals(token) && token.equals(candidate), "candidate changed across retries");
            if (attempts.getAndIncrement() == 0) throw new SocketTimeoutException("dropped paired");
            return new PairingHandshake.Reply("hello_ok", "", "");
        }, () -> {});
        check("pending-token".equals(confirmed), "lost pairing response could not be confirmed");
        System.out.println("PASS lost pairing response recovered with the same candidate");

        attempts.set(0);
        confirmed = new PairingHandshake("obsolete-token", "pending-token").connect((token, candidate) -> {
            if (attempts.getAndIncrement() == 0) {
                return new PairingHandshake.Reply("error", "", "unauthorized");
            }
            check("pending-token".equals(token), "did not verify the pending credential");
            return new PairingHandshake.Reply("hello_ok", "", "");
        }, () -> {});
        check("pending-token".equals(confirmed), "pending credential recovery failed");
        System.out.println("PASS reconnect verifies pending token after explicit rejection of old token");

        attempts.set(0);
        boolean timedOut = false;
        try {
            new PairingHandshake("working-token", "pending-token").connect((token, candidate) -> {
                attempts.incrementAndGet();
                throw new SocketTimeoutException("response lost");
            }, () -> {});
        } catch (SocketTimeoutException expected) { timedOut = true; }
        check(timedOut && attempts.get() == 2, "timeout falsely returned a confirmed credential");
        System.out.println("PASS repeated timeouts never report successful pairing");

        attempts.set(0);
        boolean rejected = false;
        try {
            new PairingHandshake("invalid-token", "").connect((token, candidate) -> {
                attempts.incrementAndGet();
                return new PairingHandshake.Reply("error", "", "unauthorized");
            }, () -> {});
        } catch (PairingHandshake.Rejected expected) { rejected = true; }
        check(rejected && attempts.get() == 1, "authentication rejection retried as a timeout");
        System.out.println("PASS authentication rejection is distinct from transport timeout");
    }
}
