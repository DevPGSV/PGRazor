package dev.pgrazor;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalApiTrainerTest {
    private static final String PATH = "/api/v1/trainer";
    private static final String AUTH = "Authorization: Bearer fixture-token\r\n";

    public static void main(String[] args) throws Exception {
        AtomicLong now = new AtomicLong(100);
        LocalApiData data = new LocalApiData(now::get, () -> 1_700_000_000_000L);
        LocalApiRoutes routes = LocalApiRoutes.production(data);
        check(!data.requested());
        data.seeIdentity("a", true);
        check(!LocalApiTrainer.accept(data, packet(101, "0061", "0041", "")));
        data.enable();
        check(!data.requested() && routes.get(PATH).code == 503);
        data.seeIdentity("a", true);
        check(data.requested() && data.session() == null);
        now.set(200);
        check(!LocalApiTrainer.accept(data, packet(200, "0062", "0042", "")));
        check(!LocalApiTrainer.accept(data, packet(200, "0061", "0041", "|42|-1|0|0|0|0")));
        check(!LocalApiTrainer.accept(data, packet(201, "0061", "0041", "")));
        check(LocalApiTrainer.accept(data, packet(200, "0061", "0041", "")));
        check(routes.get(PATH).body.contains("\"xp\":null"));
        now.set(201);
        check(LocalApiTrainer.accept(data, packet(201, "0061", "0041", "|42|0|1|0|1250|0")));
        check(!LocalApiTrainer.accept(data, packet(200, "0061", "0041", "")));
        LocalApiRoutes.Reply old = routes.get(PATH);
        check(old.code == 200 && old.body.contains("\"pokemon_caught\":0") && old.body.contains("1.250"));
        check(old.body.contains("\"coverage\":\"partial\"") && old.body.contains("2023-11-14T22:13:20Z"));
        check(routes.get("/api/v1/inventory").code == 501 && routes.get(PATH + "?test=1").code == 400);
        try (LocalApiServer server = LocalApiServer.start(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), "fixture-token", routes, () -> true)) {
            check(HttpPreviewServerTest.request(server.port(), "GET", PATH, "").startsWith("HTTP/1.1 401"));
            AtomicInteger accepted = new AtomicInteger();
            Thread publisher = new Thread(() -> {
                for (int i = 202; i < 400; i++) {
                    now.set(i);
                    if (LocalApiTrainer.accept(data, packet(i, "0061", "0041", "|42|0|1|0|1250|0"))) accepted.incrementAndGet();
                }
            });
            publisher.start();
            for (int i = 0; i < 20; i++) {
                check(HttpPreviewServerTest.request(server.port(), "GET", PATH, AUTH).startsWith("HTTP/1.1 200"));
            }
            publisher.join();
            check(accepted.get() == 198);
            now.set(8_000);
            check(routes.get(PATH).body.contains("RESOURCE_STALE"));
            check(routes.get("/api/v1/status").body.contains("\"freshness\":\"stale\""));
            now.set(12_000);
            check(routes.get(PATH).body.contains("RESOURCE_NOT_READY") && data.session() == null);
            data.seeIdentity("b", true);
            now.incrementAndGet();
            check(!LocalApiTrainer.accept(data, packet(now.get(), "0061", "0041", "")));
            check(LocalApiTrainer.accept(data, packet(now.get(), "0062", "0042", "")));
            check(!old.session.equals(data.session()));
            check(!routes.publish(old, () -> { throw new AssertionError("Old reply was published"); }));
            check(HttpPreviewServerTest.request(server.port(), "GET", PATH, AUTH).contains("\"name\":\"B\""));
            data.disable();
            check(!data.requested() && routes.get(PATH).code == 503);
        }
        System.out.println("PASS: trainer demand, live partial fields, freshness, auth and concurrent session invalidation");
    }

    private static String packet(long now, String id, String name, String fields) {
        return now + "|" + id + "|" + name + fields;
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
