package dev.pgrazor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class LocalApiControllerTest {
    public static void main(String[] args) throws Exception {
        check(LocalApiConfig.DEFAULT.host.equals("0.0.0.0") && LocalApiConfig.DEFAULT.port == 8765);
        for (String invalid : new String[]{"localhost", "0.0.0.01", "256.0.0.1", "224.0.0.1", "::1"}) {
            try {
                new LocalApiConfig(invalid, 8765);
                throw new AssertionError("Invalid host accepted");
            } catch (IllegalArgumentException expected) {}
        }
        try {
            new LocalApiConfig("127.0.0.1", 80);
            throw new AssertionError("Invalid port accepted");
        } catch (IllegalArgumentException expected) {}

        MemoryStore store = new MemoryStore();
        LocalApiController controller = new LocalApiController(store);
        check(!controller.isRunning() && store.saved == null);
        int port = freePort();
        controller.reconfigure(new LocalApiConfig("127.0.0.1", port));
        String initial = controller.settings().token;
        check(initial.matches("[A-Za-z0-9_-]{43}") && !controller.isRunning());
        check(new LocalApiController(store).settings().token.equals(initial));
        controller.start();
        try {
            check(controller.isRunning());
            String body = request(port, initial, "/api/v1/status");
            check(body.startsWith("HTTP/1.1 200 ") && body.contains("\"account_ready\":false")
                && body.contains("\"events\":{\"supported\":true") && !body.contains("synthetic"));
            check(request(port, initial, "/api/v1/pokemon/123").startsWith("HTTP/1.1 501 "));
            check(request(port, initial, "/other").startsWith("HTTP/1.1 404 "));
            store.fail = true;
            try {
                controller.rotateToken();
                throw new AssertionError("Rotation should report persistence failure");
            } catch (IOException expected) {}
            check(request(port, initial, "/api/v1/status").startsWith("HTTP/1.1 200 "));
            store.fail = false;
            controller.rotateToken();
            String rotated = controller.settings().token;
            check(!initial.equals(rotated));
            check(request(port, initial, "/api/v1/status").startsWith("HTTP/1.1 401 "));
            check(request(port, rotated, "/api/v1/status").startsWith("HTTP/1.1 200 "));
            try (ServerSocket occupied = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
                try {
                    controller.reconfigure(new LocalApiConfig("127.0.0.1", occupied.getLocalPort()));
                    throw new AssertionError("Occupied port accepted");
                } catch (IOException expected) {}
                check(!controller.isRunning());
            }
            controller.reconfigure(new LocalApiConfig("127.0.0.1", port));
            controller.start();
            controller.stop();
            check(!controller.isRunning());
            controller.start(); // Must wait for the previous close before reusing its port.
            check(request(port, rotated, "/api/v1/status").startsWith("HTTP/1.1 200 "));
        } finally {
            controller.stop();
        }

        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        LocalApiController delayed = new LocalApiController(new LocalApiController.Store() {
            public LocalApiController.Settings load() throws IOException {
                reading.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) throw new IOException("Test timeout");
                } catch (InterruptedException error) {
                    throw new IOException(error);
                }
                return store.saved;
            }
            public void save(LocalApiController.Settings settings) { throw new AssertionError(); }
        });
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread starting = new Thread(() -> {
            try {
                delayed.start();
            } catch (Throwable failure) {
                error.set(failure);
            }
        });
        starting.start();
        check(reading.await(5, TimeUnit.SECONDS));
        delayed.stop();
        resume.countDown();
        starting.join(5000);
        check(!starting.isAlive() && error.get() instanceof IOException && !delayed.isRunning());
        System.out.println("PASS: production API defaults, persistence contract, routes, rotation, rebind and invalidation");
    }

    private static String request(int port, String token, String route) throws IOException {
        return HttpPreviewServerTest.request(port, "GET", route, "Authorization: Bearer "+token+"\r\n");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private static final class MemoryStore implements LocalApiController.Store {
        LocalApiController.Settings saved;
        boolean fail;
        public LocalApiController.Settings load() { return saved; }
        public void save(LocalApiController.Settings settings) throws IOException {
            if (fail) throw new IOException("Synthetic store failure");
            saved = settings;
        }
    }
}
