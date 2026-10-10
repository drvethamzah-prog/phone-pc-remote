package com.phonepcremote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class AdaptiveSocketTest {
    public static void main(String[] args) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Future<?> peer = worker.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    socket.setTcpNoDelay(true);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    for (int i = 0; i < 150; i++) {
                        if (!String.valueOf(i).equals(reader.readLine())) throw new AssertionError("Order/replay");
                        Thread.sleep(i < 30 ? 120 : 5);
                        socket.getOutputStream().write("ok\n".getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Exception exc) { throw new RuntimeException(exc); }
            });
            AdaptiveLatency on = new AdaptiveLatency();
            AdaptiveLatency off = new AdaptiveLatency();
            on.setEnabled(true);
            try (ControlConnection connection = new ControlConnection()) {
                for (int i = 0; i < 150; i++) {
                    long start = System.nanoTime();
                    if (!"ok".equals(connection.exchange("127.0.0.1", listener.getLocalPort(), String.valueOf(i))))
                        throw new AssertionError("Unexpected response");
                    double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                    on.record(elapsed);
                    off.record(elapsed);
                    if (off.windowMs() != 0) throw new AssertionError("Opt-out changed");
                    if (i == 29 && on.windowMs() != 40) throw new AssertionError("No adaptation");
                }
            }
            peer.get(5, TimeUnit.SECONDS);
            if (on.windowMs() != 0) throw new AssertionError("No recovery");
            System.out.println("PASS loopback TCP: 150 ordered replies, slow adaptation, fast recovery, opt-out unchanged");
        } finally { worker.shutdownNow(); }
    }
}
