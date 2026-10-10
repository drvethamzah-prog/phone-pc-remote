package com.phonepcremote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ControlConnectionTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            try (ServerSocket listener = new ServerSocket(0); ControlConnection connection = new ControlConnection()) {
                Future<?> peer = workers.submit(() -> {
                    try (Socket socket = listener.accept();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                        socket.setTcpNoDelay(true);
                        for (int i = 0; i < 100; i++) {
                            String line = reader.readLine();
                            check(String.valueOf(i).equals(line), "input order changed");
                            socket.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (IOException exc) { throw new RuntimeException(exc); }
                });
                for (int i = 0; i < 100; i++) {
                    check(String.valueOf(i).equals(connection.exchange("127.0.0.1", listener.getLocalPort(), String.valueOf(i))), "response mismatch");
                }
                peer.get(5, TimeUnit.SECONDS);
                System.out.println("PASS 100 ordered exchanges on one socket");
            }

            try (ServerSocket listener = new ServerSocket(0); ControlConnection connection = new ControlConnection()) {
                CountDownLatch received = new CountDownLatch(1);
                Future<?> peer = workers.submit(() -> {
                    try (Socket first = listener.accept();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(first.getInputStream(), StandardCharsets.UTF_8))) {
                        check("first".equals(reader.readLine()), "first request missing");
                        received.countDown();
                        check(reader.readLine() == null, "timed-out command was replayed");
                    } catch (IOException exc) { throw new RuntimeException(exc); }
                    try (Socket next = listener.accept();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(next.getInputStream(), StandardCharsets.UTF_8))) {
                        check("second".equals(reader.readLine()), "unexpected automatic retry");
                        next.getOutputStream().write("ok\n".getBytes(StandardCharsets.UTF_8));
                    } catch (IOException exc) { throw new RuntimeException(exc); }
                });
                boolean timedOut = false;
                try { connection.exchange("127.0.0.1", listener.getLocalPort(), "first"); }
                catch (SocketTimeoutException expected) { timedOut = true; }
                check(timedOut && received.await(1, TimeUnit.SECONDS), "read timeout not enforced");
                check("ok".equals(connection.exchange("127.0.0.1", listener.getLocalPort(), "second")), "reconnect failed");
                peer.get(5, TimeUnit.SECONDS);
                System.out.println("PASS timeout drops connection without replay; next request reconnects");
            }

            try (ServerSocket listener = new ServerSocket(0); ControlConnection connection = new ControlConnection()) {
                CountDownLatch received = new CountDownLatch(1);
                Future<?> peer = workers.submit(() -> {
                    try (Socket socket = listener.accept();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                        reader.readLine();
                        received.countDown();
                        check(reader.readLine() == null, "close did not reach server");
                    } catch (IOException exc) { throw new RuntimeException(exc); }
                });
                Future<?> pending = workers.submit(() -> {
                    try {
                        connection.exchange("127.0.0.1", listener.getLocalPort(), "pending");
                        throw new AssertionError("request unexpectedly succeeded");
                    } catch (IOException expected) { }
                });
                check(received.await(3, TimeUnit.SECONDS), "request not sent");
                connection.close();
                pending.get(1, TimeUnit.SECONDS);
                peer.get(1, TimeUnit.SECONDS);
                try {
                    connection.exchange("127.0.0.1", listener.getLocalPort(), "after-close");
                    throw new AssertionError("closed connection reopened");
                } catch (IOException expected) { }
                System.out.println("PASS activity destruction cancels blocked input and prevents reopening");
            }
        } finally {
            workers.shutdownNow();
        }
    }
}
