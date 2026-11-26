package dev.pgrazor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalApiPokemonTest {
    private static final String TOKEN = "pokemon-fixture-token";

    public static void main(String[] args) throws Exception {
        AtomicLong clock = new AtomicLong(100), revision = new AtomicLong(), starts = new AtomicLong();
        LocalApiData data = new LocalApiData(clock::get, clock::get);
        LocalApiPokemon provider = new LocalApiPokemon(data, clock::get, clock::get, (request, lease, deadline) -> {
            revision.set(request);
            if (request != 0) starts.incrementAndGet();
        }, () -> LocalApiPokemonNormalization.RAW);
        int port;
        try (ServerSocket reserved = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            port = reserved.getLocalPort();
        }
        LocalApiController controller = new LocalApiController(new LocalApiController.Store() {
            private LocalApiController.Settings current = new LocalApiController.Settings(
                new LocalApiConfig("127.0.0.1", port), TOKEN);
            public LocalApiController.Settings load() { return current; }
            public void save(LocalApiController.Settings settings) { current = settings; }
        }, data, null, provider);
        controller.start();
        long lease = controller.generation();
        try {
            check(http(port, "", "").startsWith("HTTP/1.1 401"));
            String unready = http(port, "", TOKEN);
            check(unready.startsWith("HTTP/1.1 503") && unready.contains("Verified account is unavailable"));
            data.seeIdentity("a", true);
            clock.incrementAndGet();
            check(LocalApiTrainer.accept(data, clock.get() + "|0061|0041"));
            String status = HttpPreviewServerTest.request(port, "GET", "/api/v1/status", "Authorization: Bearer " + TOKEN + "\r\n");
            check(status.contains("\"account_ready\":true"));
            check(status.contains("\"pokemon\":{\"supported\":true,\"ready\":false"));
            String trainer = HttpPreviewServerTest.request(port, "GET", "/api/v1/trainer", "Authorization: Bearer " + TOKEN + "\r\n");
            check(trainer.startsWith("HTTP/1.1 200") && trainer.contains("\"freshness\":\"fresh\""));
            CompletableFuture<String> one = fetch(port, "");
            waitFor(() -> starts.get() == 1 || one.isDone());
            if (one.isDone()) throw new AssertionError("Cold GET failed before scheduling acquisition: "
                + one.get(1, TimeUnit.SECONDS).split("\r?\n", 2)[0]);
            check(lease > 0 && status.contains("\"provider_state\":\"no_snapshot\""));
            check(HttpPreviewServerTest.request(port, "GET", "/api/v1/status", "Authorization: Bearer " + TOKEN + "\r\n")
                .contains("\"provider_state\":\"acquiring\""));
            try (Socket two = request(port, "?include_eggs=false", TOKEN)) {
                Thread.sleep(150);
                check(starts.get() == 1);
                capture(provider, clock, revision.get(), lease, 623);
                String first = one.get(6, TimeUnit.SECONDS);
                String filtered = read(two);
                check(first.startsWith("HTTP/1.1 200") && first.length() > 8192);
                check(first.contains("\"source_entry_count\":623") && first.contains("\"classified_egg_rows\":1"));
                check(first.contains("\"eggs\":[{\"instance_id\":\"623\"") && first.contains("\"defense_iv\":0"));
                check(filtered.startsWith("HTTP/1.1 200") && !filtered.contains("\"eggs\":"));
            }
            check(HttpPreviewServerTest.request(port, "GET", "/api/v1/status", "Authorization: Bearer " + TOKEN + "\r\n")
                .contains("\"pokemon\":{\"supported\":true,\"ready\":true,\"coverage\":\"complete\""));
            check(http(port, "/618", TOKEN).contains("\"instance_id\":\"618\""));
            String projected = http(port, "/618?fields=instance_id,defense_iv,iv_percent", TOKEN);
            check(projected.contains("\"data\":{\"instance_id\":\"618\",\"defense_iv\":0,\"iv_percent\":46.67}"));
            check(http(port, "?fields=cp,cp", TOKEN).startsWith("HTTP/1.1 400"));
            check(http(port, "/999", TOKEN).startsWith("HTTP/1.1 404"));
            check(starts.get() == 1);
            check(http(port, "/18446744073709551616", TOKEN).startsWith("HTTP/1.1 400"));
            check(http(port, "?refresh=true&refresh=false", TOKEN).startsWith("HTTP/1.1 400"));
            CompletableFuture<String> refreshed = fetch(port, "?refresh=true");
            waitFor(() -> starts.get() == 2);
            provider.accept("export|" + revision.get() + "|" + lease + "|0061|failed|structure_changed");
            check(refreshed.get(5, TimeUnit.SECONDS).startsWith("HTTP/1.1 409"));
            CompletableFuture<String> over = fetch(port, "");
            waitFor(() -> starts.get() == 3);
            provider.accept("export|" + revision.get() + "|" + lease + "|0061|failed|source_limit");
            check(over.get(5, TimeUnit.SECONDS).contains("RESOURCE_CAPTURE_LIMIT"));

            try (Socket abandoned = request(port, "?refresh=true", TOKEN)) {
                waitFor(() -> starts.get() == 4);
            }
            waitFor(() -> revision.get() == 0);

            CompletableFuture<String> late = fetch(port, "");
            waitFor(() -> starts.get() == 5);
            for (int i = 0; i < 7; ++i) {
                long now = clock.addAndGet(5000);
                check(data.publish(data.trainer, "a", now, LocalApiData.Coverage.PARTIAL, null));
            }
            check(late.get(5, TimeUnit.SECONDS).startsWith("HTTP/1.1 504"));

            CompletableFuture<String> switched = fetch(port, "");
            waitFor(() -> starts.get() == 6);
            data.seeIdentity("b", true);
            check(switched.get(5, TimeUnit.SECONDS).startsWith("HTTP/1.1 409"));
            check(revision.get() == 0);

            clock.incrementAndGet();
            check(LocalApiTrainer.accept(data, clock.get() + "|0062|0042"));
            try (Socket pending = request(port, "", TOKEN)) {
                waitFor(() -> starts.get() == 7);
                controller.rotateToken();
                check(!read(pending).startsWith("HTTP/1.1 200") && revision.get() == 0);
            }
            String rotated = controller.settings().token;
            check(http(port, "", TOKEN).startsWith("HTTP/1.1 401"));
            check(http(port, "", rotated).startsWith("HTTP/1.1 503"));
            data.seeIdentity("b", true);
            clock.incrementAndGet();
            check(LocalApiTrainer.accept(data, clock.get() + "|0062|0042"));
            try (Socket pending = request(port, "", rotated)) {
                waitFor(() -> starts.get() == 8);
                controller.stop();
                check(!read(pending).startsWith("HTTP/1.1 200") && revision.get() == 0);
            }
        } finally {
            controller.stop();
        }
        System.out.println("PASS: authenticated complete Pokémon over one GET, coalescing, IDs, limits, disconnect and account fencing");
    }

    private static void capture(LocalApiPokemon provider, AtomicLong clock, long revision, long lease, int count) {
        long start = clock.get();
        for (int offset = 0; offset < count; offset += 32) {
            int end = Math.min(count, offset + 32);
            StringBuilder rows = new StringBuilder();
            for (int index = offset; index < end; index++) {
                if (index != offset) rows.append(';');
                rows.append(index + 1).append(index + 1 == count ? "|-1|-1|-1|-1|-1|0|1" : "|25|100|15|0|6|0|0");
            }
            provider.accept("export|" + revision + "|" + lease + "|0061|" + (end == count ? "done" : "slice")
                + "|" + count + "|" + offset + "|" + start + "|" + clock.incrementAndGet() + "|1|" + rows);
        }
    }

    private static CompletableFuture<String> fetch(int port, String path) {
        return CompletableFuture.supplyAsync(() -> {
            try { return http(port, path, TOKEN); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    private static String http(int port, String path, String token) throws Exception {
        try (Socket socket = request(port, path, token)) { return read(socket); }
    }

    private static String read(Socket socket) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        for (String line; (line = in.readLine()) != null;) text.append(line).append('\n');
        return text.toString();
    }

    private static Socket request(int port, String path, String token) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(8000);
        socket.getOutputStream().write(("GET /api/v1/pokemon" + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
            + "\r\nAuthorization: Bearer " + token + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        return socket;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Timed out");
            Thread.sleep(15);
        }
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
