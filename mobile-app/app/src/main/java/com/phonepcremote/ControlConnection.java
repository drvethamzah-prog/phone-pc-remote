package com.phonepcremote;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/** Used by one ordered worker; close also cancels an in-flight request. */
final class ControlConnection implements AutoCloseable {
    interface SocketProvider { Socket create() throws IOException; }
    interface SocketSecurity { Socket upgrade(Socket socket, String host, int port) throws IOException; }
    private final SocketProvider socketProvider;
    private final SocketSecurity socketSecurity;
    private volatile Socket socket;
    private volatile boolean closed;
    private BufferedReader reader;
    private BufferedWriter writer;
    private String host = "";
    private int port;
    private long lastUsedAt;
    private final int timeoutMs;

    static final class RequestTimeout extends SocketTimeoutException {
        final String phase;

        RequestTimeout(String phase, SocketTimeoutException cause) {
            super(cause.getMessage());
            this.phase = phase;
            initCause(cause);
        }
    }

    ControlConnection() { this(2000); }

    ControlConnection(int timeoutMs) { this(timeoutMs, Socket::new); }

    ControlConnection(int timeoutMs, SocketProvider socketProvider) {
        this(timeoutMs, socketProvider, (socket, host, port) -> socket);
    }

    ControlConnection(int timeoutMs, SocketProvider socketProvider, SocketSecurity socketSecurity) {
        this.timeoutMs = timeoutMs;
        this.socketProvider = socketProvider;
        this.socketSecurity = socketSecurity;
    }

    String exchange(String targetHost, int targetPort, String request) throws IOException {
        if (closed) {
            throw new IOException("Connection closed");
        }
        String phase = "connect";
        try {
            if (socket == null || !host.equals(targetHost) || port != targetPort
                    || System.nanoTime() - lastUsedAt > 10_000_000_000L) {
                disconnect();
                Socket next = socketProvider.create();
                socket = next;
                if (closed) {
                    next.close();
                    throw new IOException("Connection closed");
                }
                next.setTcpNoDelay(true);
                next.connect(new InetSocketAddress(targetHost, targetPort), timeoutMs);
                next.setSoTimeout(timeoutMs);
                phase = "tls";
                next = socketSecurity.upgrade(next, targetHost, targetPort);
                socket = next;
                if (closed) {
                    next.close();
                    throw new IOException("Connection closed");
                }
                reader = new BufferedReader(new InputStreamReader(next.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(next.getOutputStream(), StandardCharsets.UTF_8));
                host = targetHost;
                port = targetPort;
            }
            phase = "send";
            writer.write(request);
            writer.write("\n");
            writer.flush();
            phase = "response";
            String response = reader.readLine();
            if (response == null) {
                throw new EOFException("Server closed the control connection");
            }
            lastUsedAt = System.nanoTime();
            return response;
        } catch (SocketTimeoutException exc) {
            disconnect();
            throw new RequestTimeout(phase, exc);
        } catch (IOException exc) {
            // Never replay an input command: the PC may already have executed it.
            disconnect();
            throw exc;
        }
    }

    void disconnect() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        disconnect();
    }
}
