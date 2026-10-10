package com.phonepcremote;

import java.io.IOException;

/** Retries only the idempotent handshake, never a mouse or keyboard command. */
final class PairingHandshake {
    interface Exchange {
        Reply exchange(String token, String requestedToken) throws IOException;
    }

    static final class Reply {
        final String type;
        final String token;
        final String reason;

        Reply(String type, String token, String reason) {
            this.type = type;
            this.token = token;
            this.reason = reason;
        }
    }

    static final class Rejected extends IOException {
        Rejected(String reason) { super(reason); }
    }

    private final String savedToken;
    private final String pendingToken;

    PairingHandshake(String savedToken, String pendingToken) {
        this.savedToken = savedToken == null ? "" : savedToken;
        this.pendingToken = pendingToken == null ? "" : pendingToken;
    }

    String connect(Exchange exchange, Runnable retryNotice) throws IOException {
        String offeredToken = savedToken.isEmpty() ? pendingToken : savedToken;
        for (int attempt = 0; attempt < 2; attempt++) {
            Reply reply;
            try {
                reply = exchange.exchange(offeredToken, pendingToken);
            } catch (IOException exc) {
                if (attempt == 1) throw exc;
                retryNotice.run();
                continue;
            }
            if ("hello_ok".equals(reply.type) && !offeredToken.isEmpty()) {
                return offeredToken;
            }
            if ("paired".equals(reply.type) && reply.token != null && !reply.token.isEmpty()) {
                return reply.token;
            }
            if ("error".equals(reply.type)) {
                if (attempt == 0 && "unauthorized".equals(reply.reason)
                        && !pendingToken.isEmpty() && !pendingToken.equals(offeredToken)) {
                    offeredToken = pendingToken;
                    retryNotice.run();
                    continue;
                }
                throw new Rejected(reply.reason);
            }
            throw new Rejected("invalid_pairing_response");
        }
        throw new IOException("Pairing did not complete");
    }
}
