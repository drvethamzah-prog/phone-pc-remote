package com.phonepcremote;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Trust comes from a fingerprint copied locally, never from discovery. */
final class PinnedTls {
    static String normalize(String value) {
        String normalized = value.replace(":", "").replace(" ", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256 fingerprint");
        return normalized;
    }

    static Socket upgrade(Socket socket, String host, int port, String fingerprint) throws IOException {
        final String expected = normalize(fingerprint);
        try {
            X509TrustManager trust = new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    throw new CertificateException("Client certificates are not supported");
                }
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    if (chain == null || chain.length != 1) throw new CertificateException("Unexpected certificate chain");
                    chain[0].checkValidity();
                    try {
                        byte[] digest = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                        StringBuilder actual = new StringBuilder();
                        for (byte item : digest) actual.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
                        if (!MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                                actual.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                            throw new CertificateException("Computer identity does not match");
                        }
                    } catch (GeneralSecurityException error) {
                        throw new CertificateException("Computer identity verification failed", error);
                    }
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trust}, null);
            SSLSocket secure = (SSLSocket) context.getSocketFactory().createSocket(socket, host, port, true);
            try {
                java.util.List<String> protocols = new java.util.ArrayList<>();
                for (String protocol : secure.getSupportedProtocols()) {
                    if ("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol)) protocols.add(protocol);
                }
                secure.setEnabledProtocols(protocols.toArray(new String[0]));
                secure.setSoTimeout(socket.getSoTimeout());
                secure.startHandshake();
                return secure;
            } catch (IOException | RuntimeException error) {
                secure.close();
                throw error;
            }
        } catch (GeneralSecurityException error) {
            throw new IOException("TLS initialization failed", error);
        }
    }
}
