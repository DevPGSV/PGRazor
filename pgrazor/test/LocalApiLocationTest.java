package dev.pgrazor;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalApiLocationTest {
    private static final String PATH = "/api/v1/location";
    private static final String AUTH = "Authorization: Bearer fixture-location\r\n";

    public static void main(String[] args) throws Exception {
        AtomicLong now = new AtomicLong(100);
        AtomicInteger reads = new AtomicInteger();
        LocalApiData data = new LocalApiData(now::get, now::get);
        LocalApiRoutes routes = LocalApiRoutes.production(data, null, () -> {
            reads.incrementAndGet();
            return new LocationState(0, -1.25, 9, 0, false);
        });
        data.enable();
        try (LocalApiServer server = LocalApiServer.start(new InetSocketAddress("127.0.0.1", 0),
                "fixture-location", routes, () -> true)) {
            int port = server.port();
            check(HttpPreviewServerTest.request(port, "GET", PATH, "").startsWith("HTTP/1.1 401"));
            check(HttpPreviewServerTest.request(port, "GET", PATH, AUTH).startsWith("HTTP/1.1 503"));
            data.seeIdentity("fixture", true);
            check(HttpPreviewServerTest.request(port, "GET", PATH, AUTH).startsWith("HTTP/1.1 503"));
            check(reads.get() == 0);
            now.incrementAndGet();
            check(data.publish(data.trainer, "fixture", now.get(), LocalApiData.Coverage.PARTIAL, null));
            String result = HttpPreviewServerTest.request(port, "GET", PATH, AUTH);
            check(result.startsWith("HTTP/1.1 200") && result.contains("\"coverage\":\"partial\""));
            check(result.contains("\"latitude\":0.0,\"longitude\":-1.25,\"override_requested\":false"));
            check(result.contains("\"actual_device\":null,\"represented_to_game\":null"));
            check(HttpPreviewServerTest.request(port, "GET", PATH + "?actual=true", AUTH).startsWith("HTTP/1.1 400"));
            check(reads.get() == 1);
            check(routes.get("/api/v1/status").body.contains(
                "\"location\":{\"supported\":true,\"ready\":true,\"coverage\":\"partial\""));
            LocalApiRoutes.Reply old = routes.get(PATH);
            data.loseIdentity();
            check(!routes.publish(old, () -> { throw new AssertionError("Retired location was sent"); }));
        }
        System.out.println("PASS: configured location, explicit unavailable sources and verified account fencing");
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }
}
