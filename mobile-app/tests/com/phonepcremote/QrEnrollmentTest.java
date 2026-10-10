package com.phonepcremote;

public final class QrEnrollmentTest {
    private static void reject(String uri, long now) throws Exception {
        try {
            QrEnrollment.parse(uri, now);
        } catch (Exception expected) { return; }
        throw new AssertionError("Unsafe QR accepted");
    }

    public static void main(String[] args) throws Exception {
        String uri = args[0];
        long now = Long.parseLong(args[1]);
        QrEnrollment parsed = QrEnrollment.parse(uri, now);
        if (!parsed.host.equals("10.10.10.124") || parsed.port != 8765) throw new AssertionError("endpoint");
        reject(uri + "&host=10.10.10.125", now);
        reject(uri.replace("10.10.10.124", "8.8.8.8"), now);
        reject(uri.replace("10.10.10.124", "example.com"), now);
        reject(uri.replace("10.10.10.124", "127.0.0.1"), now);
        reject(uri.replace("10.10.10.124", "010.10.10.124"), now);
        reject(uri.replace("v=1", "v=2"), now);
        reject(uri.replace("port=8765", "port=65536"), now);
        reject(uri, now + 301);
        reject(uri, now - 301);
        reject(uri + "#fragment", now);
        reject(uri.replace("qareeb://pair", "https://pair"), now);
        System.out.println("PASS QR compatibility and unsafe payload rejection");
    }
}
