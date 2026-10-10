package com.phonepcremote;

import java.io.IOException;
import java.net.Socket;

public final class PinnedTlsTest {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String pin = args[1];
        String token = args[2];
        if (!PinnedTls.normalize(pin.toUpperCase(java.util.Locale.ROOT)).equals(pin)) throw new AssertionError("normalization");
        try {
            PinnedTls.normalize("1234");
            throw new AssertionError("short pin accepted");
        } catch (IllegalArgumentException expected) { }
        try (ControlConnection connection = new ControlConnection(2000, Socket::new,
                (socket, host, targetPort) -> PinnedTls.upgrade(socket, host, targetPort, pin))) {
            String hello = "{\"type\":\"hello\",\"deviceId\":\"test-phone\",\"token\":\"" + token + "\",\"keepAlive\":true}";
            if (!connection.exchange("127.0.0.1", port, hello).contains("hello_ok")) throw new AssertionError("TLS hello");
            String request = "{\"type\":\"type_text\",\"text\":\"مرحبا\",\"deviceId\":\"test-phone\",\"token\":\"" + token + "\",\"keepAlive\":true}";
            if (!connection.exchange("127.0.0.1", port, request).contains("keyboard_ok")) throw new AssertionError("TLS input");
        }
        String wrong = (pin.startsWith("0") ? "1" : "0") + pin.substring(1);
        try (ControlConnection connection = new ControlConnection(2000, Socket::new,
                (socket, host, targetPort) -> PinnedTls.upgrade(socket, host, targetPort, wrong))) {
            try {
                connection.exchange("127.0.0.1", port, "{\"type\":\"ping\"}");
                throw new AssertionError("wrong pin accepted");
            } catch (IOException expected) { }
        }
        try (ControlConnection plaintext = new ControlConnection(1000)) {
            try {
                plaintext.exchange("127.0.0.1", port, "{\"type\":\"ping\"}");
                throw new AssertionError("TLS server accepted plaintext");
            } catch (IOException expected) { }
        }
        System.out.println("PASS pinned TLS hello, Arabic input, malformed and wrong fingerprint rejection");
    }
}
