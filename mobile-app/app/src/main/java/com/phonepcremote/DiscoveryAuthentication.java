package com.phonepcremote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Proves possession of the existing pairing secret without broadcasting it. */
final class DiscoveryAuthentication {
    static String proof(String token, String nonce, int port) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(("qareeb-discovery-v1\n" + nonce + "\n" + port).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) result.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return result.toString();
    }

    static boolean verifies(String token, String nonce, int port, String received) {
        if (token == null || token.isEmpty() || nonce == null || nonce.length() < 32
                || port < 1 || port > 65535 || received == null || !received.matches("[0-9a-f]{64}")) return false;
        try {
            return MessageDigest.isEqual(proof(token, nonce, port).getBytes(StandardCharsets.US_ASCII),
                    received.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ignored) { return false; }
    }
}
