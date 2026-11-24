package dev.pgrazor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HttpPreviewServerTest {
    private static final String TOKEN = "synthetic-token";
    private static final String AUTH = "Authorization: Bearer " + TOKEN + "\r\n";
    private static final InetAddress LOOPBACK;

    static {
        try {
            LOOPBACK = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    public static void main(String[] args) throws IOException {
        int port;
        try (LocalApiServer server = start(new InetSocketAddress(LOOPBACK, 0), TOKEN)) {
            port = server.port();
            String ok = request(port, "GET", "/api/v1/status", AUTH);
            status(ok, 200);
            expect(ok.contains("\"mode\":\"synthetic_preview\"") && ok.contains("cache-control: no-store"), ok);
            status(request(port, "GET", "/api/v1/status", ""), 401);
            status(request(port, "GET", "/api/v1/status", "Authorization: Bearer wrong\r\n"), 401);
            status(request(port, "GET", "/api/v1/status", AUTH + AUTH), 401);
            status(request(port, "GET", "/api/v1/status", AUTH + "Origin: null\r\n"), 403);
            status(request(port, "GET", "/api/v1/status", AUTH + "Host: example.invalid\r\n"), 403);
            status(exchange(port, "GET /api/v1/status HTTP/1.1\r\nHost: example.invalid\r\n" + AUTH + "\r\n"), 403);
            status(request(port, "GET", "/api/v1/pokemon", AUTH), 404);
            status(request(port, "POST", "/api/v1/status", AUTH), 405);
            status(request(port, "GET", "/api/v1/status", AUTH + "Content-Length: 1\r\n"), 413);
            status(request(port, "GET", "/api/v1/status",
                "X-Large: " + String.join("", Collections.nCopies(9000, "a")) + "\r\n"), 431);
            try (LocalApiServer unexpected = start(new InetSocketAddress(LOOPBACK, port), TOKEN)) {
                throw new AssertionError("Occupied port bound");
            } catch (IOException expected) {
                expect(expected.getCause() != null, "Bind error cause missing");
            }
            try (Socket slow = connect(port)) {
                slow.getOutputStream().write("GET ".getBytes(StandardCharsets.US_ASCII));
                expect(slow.getInputStream().read() == -1, "Deadline must close unfinished requests");
            }
            checkConnectionLimit(port);
        }
        try (Socket unexpected = connect(port)) {
            throw new AssertionError("Stopped listener accepted a client");
        } catch (IOException expected) {
            // Listener is gone.
        }
        try (LocalApiServer restarted = start(new InetSocketAddress(LOOPBACK, port), "new-test-token");
             Socket pending = connect(port)) {
            status(request(restarted.port(), "GET", "/api/v1/status", AUTH), 401);
            restarted.close();
            expect(pending.getInputStream().read() == -1, "Stop must close existing clients");
        }
        InetAddress wildcard = InetAddress.getByAddress(new byte[4]);
        try (LocalApiServer server = start(new InetSocketAddress(wildcard, 0), TOKEN)) {
            status(request(server.port(), "GET", "/api/v1/status", AUTH), 200);
        }
        System.out.println("PASS: HTTP preview status, authentication, request limits, timeout, bind failure and stop");
    }

    private static LocalApiServer start(InetSocketAddress address, String token) throws IOException {
        return LocalApiServer.start(address, token, LocalApiRoutes.preview(), () -> true);
    }

    private static void checkConnectionLimit(int port) throws IOException {
        List<Socket> open = new ArrayList<>();
        try {
            for (int i = 0; i < LocalApiServer.MAX_CONNECTIONS; i++) open.add(connect(port));
            try (Socket extra = connect(port)) {
                extra.setSoTimeout(1000);
                expect(extra.getInputStream().read() == -1, "Excess channel was not closed");
            }
        } finally {
            for (Socket client : open) client.close();
        }
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket(LOOPBACK, port);
        socket.setSoTimeout(5000);
        return socket;
    }

    static String request(int port, String method, String path, String headers) throws IOException {
        return exchange(port, method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n" + headers + "\r\n");
    }

    private static String exchange(int port, String request) throws IOException {
        try (Socket socket = connect(port)) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = socket.getInputStream().read(buffer)) != -1) response.write(buffer, 0, length);
            return response.toString("UTF-8");
        }
    }

    private static void status(String response, int code) {
        expect(response.startsWith("HTTP/1.1 " + code + " "), response);
    }

    private static void expect(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
