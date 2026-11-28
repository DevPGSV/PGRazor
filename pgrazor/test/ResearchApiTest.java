package dev.pgrazor;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ResearchApiTest {
    private static final String EVENTS = "/research/v1/events";
    private static final String STATUS = "/research/v1/status";
    private static final String PROD = "fixture-production";

    public static void main(String[] args) throws Exception {
        persistence();
        observations();
        progression();
        pokemonBag();
        inventorySummary();
        pokemonExport();
        discovery();
        AtomicLong clock = new AtomicLong(100);
        LocalApiData data = new LocalApiData(clock::get, clock::get);
        ResearchApiSession session = new ResearchApiSession(clock::get, data::eventEpoch);
        LocalApiData metadata = new LocalApiData(clock::get, clock::get);
        metadata.enable();
        ResearchApi module = new ResearchApi(session, metadata);
        session.issue(30);
        check(session.current() == null && !session.listener());

        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        LocalApiController controller = new LocalApiController(new LocalApiController.Store() {
            @Override public LocalApiController.Settings load() {
                return new LocalApiController.Settings(new LocalApiConfig("127.0.0.1", port), PROD);
            }
            @Override public void save(LocalApiController.Settings settings) { throw new AssertionError(); }
        }, data, module);
        controller.start();
        try {
            check(session.listener() && session.current() == null);
            check(request(port, STATUS, PROD).startsWith("HTTP/1.1 401"));
            session.issue(ResearchApiSession.DEFAULT_MINUTES);
            ResearchApiSession.Lease initial = session.current();
            check(initial != null && session.remaining(initial) == -1);
            check(request(port, STATUS, initial.token).contains("\"ready\":true"));
            check(request(port, "/research/v1/runtime", initial.token).contains("account_unavailable"));
            check(new ResearchMetadata(null).get("runtime").body.contains("runtime_unavailable"));
            check(request(port, "/research/v1/assemblies", initial.token).contains("holo-protos.dll"));
            check(request(port, "/research/v1/types?limit=1", initial.token).contains("\"next_offset\":1"));
            check(request(port, "/research/v1/types?offset=100", initial.token).contains("\"items\":[]"));
            check(request(port, "/research/v1/types?limit=2&limit=2", initial.token).startsWith("HTTP/1.1 400"));
            check(request(port, "/research/v1/types?limit=13", initial.token).startsWith("HTTP/1.1 400"));
            check(request(port, "/research/v1/type?id=Missing", initial.token).startsWith("HTTP/1.1 404"));
            check(request(port, "/research/v1/type?id=PlayerStatsProto", initial.token).contains("NumEggsHatched"));
            check(request(port, "/research/v1/contracts", initial.token).contains("trainer.stats.v1"));
            check(request(port, "/research/v1/validate?contract=missing", initial.token).startsWith("HTTP/1.1 404"));
            String validation = "/research/v1/validate?contract=trainer.stats.v1";
            check(request(port, validation, initial.token).contains("\"compatible\":null"));
            metadata.seeIdentity("acct", true);
            clock.incrementAndGet();
            check(LocalApiTrainer.accept(metadata, "101|0061006300630074|0050"));
            check(request(port, validation, initial.token).contains("inconclusive"));
            clock.incrementAndGet();
            check(LocalApiTrainer.accept(metadata, "102|0061006300630074|0050|10|0|0|0|0|0"));
            check(request(port, validation, initial.token).contains("\"runtime_metadata_observed\":true"));
            java.util.concurrent.ExecutorService readers = java.util.concurrent.Executors.newFixedThreadPool(3);
            try {
                java.util.List<java.util.concurrent.Future<String>> responses = new java.util.ArrayList<>();
                for (int index = 0; index < 6; index++)
                    responses.add(readers.submit(() -> request(port, validation, initial.token)));
                for (java.util.concurrent.Future<String> response : responses)
                    check(response.get().contains("\"provider_observed\":true"));
            } finally {
                readers.shutdownNow();
            }

            String capabilities = request(port, "/research/v1/capabilities", initial.token);
            check(capabilities.contains("\"research_api\":true") && !capabilities.contains(initial.token));
            check(request(port, "/api/v1/status", initial.token).startsWith("HTTP/1.1 401"));
            check(request(port, STATUS + "?test=yes", initial.token).startsWith("HTTP/1.1 400"));
            try (LocalApiWebSocketTest.Wire denied = wire(port, EVENTS, PROD)) {
                check(denied.response.startsWith("HTTP/1.1 401"));
            }
            try (LocalApiWebSocketTest.Wire denied = wire(port, "/api/v1/events", initial.token)) {
                check(denied.response.startsWith("HTTP/1.1 401"));
            }
            try (LocalApiWebSocketTest.Wire production = wire(port, "/api/v1/events", PROD);
                    LocalApiWebSocketTest.Wire research = wire(port, EVENTS, initial.token)) {
                check(production.response.startsWith("HTTP/1.1 101") && production.text().contains("hello"));
                check(research.response.startsWith("HTTP/1.1 101") && research.text().contains(initial.id));
                research.command("{\"version\":1,\"id\":\"d\",\"op\":\"discover\"}");
                check(research.text().contains("\"topics\":[\"research.status\",\"research.diagnostics\",\"research.jobs\"]"));
                research.command("{\"version\":1,\"id\":\"s\",\"op\":\"subscribe\",\"topics\":[\"research.status\",\"research.diagnostics\",\"research.jobs\"]}");
                check(research.text().contains("\"type\":\"ack\""));
                for (String topic : new String[]{"research.status", "research.diagnostics", "research.jobs"}) {
                    String event = research.text();
                    check(event.contains("\"initial\":true") && event.contains("\"topic\":\"" + topic + "\""));
                }
                session.rotate(initial, 15);
                ResearchApiSession.Lease rotated = session.current();
                check(rotated != initial && !rotated.token.equals(initial.token));
                check(research.opcode() == 8);
                check(request(port, STATUS, initial.token).startsWith("HTTP/1.1 401"));
                production.command("{\"version\":1,\"id\":\"p\",\"op\":\"discover\"}");
                check(production.text().contains("trainer.changed"));
                try (LocalApiWebSocketTest.Wire expiring = wire(port, EVENTS, rotated.token)) {
                    expiring.text();
                    clock.set(rotated.deadline - 1);
                    check(session.current() == rotated && session.remaining(rotated) == 1);
                    clock.incrementAndGet();
                    check(expiring.opcode() == 8 && session.current() == null);
                }
                check(request(port, STATUS, rotated.token).startsWith("HTTP/1.1 401"));
                session.issue(1440);
                ResearchApiSession.Lease revoked = session.current();
                try (LocalApiWebSocketTest.Wire explicit = wire(port, EVENTS, revoked.token)) {
                    explicit.text();
                    session.revoke();
                    session.rotate(revoked, 15);
                    check(explicit.opcode() == 8 && session.current() == null);
                }
                data.seeIdentity("test-account", true);
                session.issue(30);
                ResearchApiSession.Lease account = session.current();
                try (LocalApiWebSocketTest.Wire accountWire = wire(port, EVENTS, account.token)) {
                    accountWire.text();
                    data.loseIdentity();
                    check(accountWire.opcode() == 8 && session.current() == null);
                    check(request(port, STATUS, account.token).startsWith("HTTP/1.1 401"));
                }
                session.issue(30);
                try (LocalApiWebSocketTest.Wire stopping = wire(port, EVENTS, session.current().token)) {
                    stopping.text();
                    controller.stop();
                    int first = stopping.input.read();
                    check(first == -1 || (first & 15) == 8);
                }
                check(!session.listener() && session.current() == null);
                controller.start();
                check(session.listener() && session.current() == null);
            }
        } finally {
            controller.stop();
        }
        try (LocalApiServer normal = LocalApiServer.start(new InetSocketAddress("127.0.0.1", 0),
                PROD, LocalApiRoutes.production(data), () -> true)) {
            check(request(normal.port(), STATUS, PROD).startsWith("HTTP/1.1 404"));
        }
        check(!module.owns("/api/v1/status") && module.authenticate(Collections.emptyList()) == null);
        System.out.println("PASS: Research API build seam, separate realms, lifecycle, monotonic expiry and subscriptions");
    }

    private static void discovery() throws Exception {
        AtomicLong now = new AtomicLong(100);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean valid = new AtomicBoolean(true);
        ResearchDiscovery discovery = new ResearchDiscovery(now::get, query -> {
            if (query.name.equals("FixtureHeld")) {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            return "{\"availability\":\"unavailable\",\"provenance\":\"il2cpp_runtime\"}";
        });
        ResearchApiSession session = new ResearchApiSession(now::get, () -> 0);
        session.listenerChanged(true);
        session.issue(30);
        String token = session.current().token;
        String auth = "Authorization: Bearer " + token + "\r\n";
        ResearchApi module = new ResearchApi(session, null, discovery);
        try (LocalApiServer server = LocalApiServer.start(new InetSocketAddress("127.0.0.1", 0), PROD,
                LocalApiRoutes.production(new LocalApiData(now::get, now::get)), valid::get, module)) {
            int port = server.port();
            String start = "/research/v1/live/discover?op=search&name=";
            check(HttpPreviewServerTest.request(port, "POST", start + "FixtureHeld", "")
                .startsWith("HTTP/1.1 401"));
            check(HttpPreviewServerTest.request(port, "POST", start + "FixtureHeld&limit=21", auth)
                .startsWith("HTTP/1.1 400"));
            String result = HttpPreviewServerTest.request(port, "POST", start + "FixtureHeld", auth);
            check(result.startsWith("HTTP/1.1 202"));
            String id = result.split("\"id\":\"")[1].split("\"")[0];
            check(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            check(request(port, "/research/v1/live/job?id=" + id, token).contains("running"));
            String queued = HttpPreviewServerTest.request(port, "POST", start + "FixtureLater", auth);
            String later = queued.split("\"id\":\"")[1].split("\"")[0];
            check(queued.contains("queued"));
            now.addAndGet(2501);
            check(request(port, "/research/v1/live/job?id=" + later, token).contains("job_deadline"));
            String pending = HttpPreviewServerTest.request(port, "POST", start + "FixtureOther", auth);
            String other = pending.split("\"id\":\"")[1].split("\"")[0];
            check(HttpPreviewServerTest.request(port, "POST", "/research/v1/live/cancel?id=" + other, auth)
                .contains("cancelled_by_client"));
            check(discovery.topic(session.current().id).contains("cancelled"));
            session.revoke();
            check(discovery.topic("unknown").equals("{\"jobs\":[]}"));
            check(request(port, "/research/v1/live/job?id=" + id, token).startsWith("HTTP/1.1 401"));
            session.issue(30);
            String rotated = session.current().token;
            check(request(port, "/research/v1/live/job?id=" + id, rotated).startsWith("HTTP/1.1 404"));
            release.countDown();
            String next = HttpPreviewServerTest.request(port, "POST", start + "FixtureNew",
                "Authorization: Bearer " + rotated + "\r\n");
            String fresh = next.split("\"id\":\"")[1].split("\"")[0];
            String observation = "";
            for (int retry = 0; retry < 20 && !observation.contains("completed"); retry++) {
                observation = request(port, "/research/v1/live/job?id=" + fresh, rotated);
                if (!observation.contains("completed")) Thread.sleep(10);
            }
            check(observation.contains("completed") && observation.contains("il2cpp_runtime"));
        } finally {
            release.countDown();
        }
    }

    private static void pokemonBag() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        LocalApiData data = new LocalApiData(clock::get, clock::get);
        data.enable();
        LocalApiPokemonBag bag = new LocalApiPokemonBag(data);
        ResearchObservations registry = new ResearchObservations(data, new ResearchProgression(data), bag);
        ResearchDiscovery jobs = new ResearchDiscovery(clock::get, ignored -> null);
        String query = "observe?contract=pokemon.bag.v1";
        check(observationJob(jobs, registry, query).contains("verified_snapshot_unavailable"));
        check(jobs.route(query + "&limit=11", true, "lease", () -> true, registry).code == 400);
        check(jobs.route(query + "&fields=original_owner", true, "lease", () -> true, registry).code == 400);
        check(jobs.route(query + "&fields=cp.cp", true, "lease", () -> true, registry).code == 400);
        data.seeIdentity("a", true);
        bag.setLease(9);
        clock.incrementAndGet();
        check(!bag.accept("101|0062|9|10|15|25|390|15|0|9|1|0"));
        check(!bag.accept("101|0061|8|10|15|25|390|15|0|9|1|0"));
        check(!bag.accept("101|0061|9|0|15|25|390|15|0|9|1|0"));
        check(!bag.accept("101|0061|9|10|0|25|390|15|0|9|1|0"));
        check(!bag.accept("101|0061|9|10|15|25|390|15|0|9|1|1"));
        check(bag.accept("101|0061|9|10|18446744073709551615|25|390|15|0|9|1|0"));
        check(!bag.accept("101|0061|9|10|18446744073709551615|25|390|15|0|9|1|0"));
        String observed = observationJob(jobs, registry, query);
        check(observed.contains("\"instance_id\":\"18446744073709551615\"")
              && observed.contains("\"total_collection_entries\":10")
              && observed.contains("\"defense_iv\":0") && observed.contains("\"collection_complete\":null"));
        check(observationJob(jobs, registry, query + "&offset=1").contains("\"items\":[]"));
        clock.incrementAndGet();
        check(bag.accept("102|0061|9|10|16|-1|-1|-1|-1|-1|-1|1"));
        String egg = observationJob(jobs, registry, query + "&fields=is_egg.cp.favorite");
        check(egg.contains("\"is_egg\":true") && egg.contains("\"cp\":null")
              && egg.contains("\"favorite\":null") && !egg.contains("\"instance_id\""));
        clock.incrementAndGet();
        String first = "20|25|390|15|0|9|1|0|0|42|10|20|-1|0|0.73|1234|00410022|0|0|2|1|-1|0";
        String second = "21|-1|-1|-1|-1|-1|-1|1|-1|-1|-1|-1|-1|-1|-1|-1|-";
        check(!bag.accept("103|0061|9|2|" + first + ";" + first));
        check(!bag.accept("103|0061|9|1|" + first + ";" + second));
        check(!bag.accept("103|0061|9|10,20|" + first));
        StringBuilder sample = new StringBuilder(first).append(';').append(second);
        for (int i = 22; i < 30; i++) sample.append(';').append(i).append(first.substring(2));
        check(bag.accept("103|0061|9|10,7|" + sample));
        String prefix = observationJob(jobs, registry, query + "&limit=5&fields=instance_id.nickname.hp.lucky.cp_multiplier");
        check(prefix.contains("backing_list_prefix") && prefix.contains("\"instance_id\":\"20\"")
              && prefix.contains("\"instance_id\":\"21\"") && prefix.contains("\"hp\":0")
              && !prefix.contains("\"instance_id\":\"25\"") && prefix.contains("\"capture_elapsed_ms\":7")
              && prefix.contains("\"lucky\":false") && prefix.contains("\"nickname\":null"));
        String tail = observationJob(jobs, registry, query + "&limit=10&offset=1");
        check(!tail.contains("\"instance_id\":\"20\"") && tail.contains("\"instance_id\":\"21\""));
        clock.addAndGet(60_000);
        check(bag.latest() == null);
        bag.setLease(10);
        check(bag.latest() == null);
        data.seeIdentity("a", true);
        clock.incrementAndGet();
        check(bag.accept("60104|0061|10|10|15|25|390|15|0|9|1|0"));
        data.seeIdentity("b", true);
        check(bag.latest() == null);
        check(!bag.accept("60104|0061|10|10|15|25|390|15|0|9|1|0"));
    }

    private static String jsonString(String json, String key) {
        String prefix = "\"" + key + "\":\"";
        int start = json.indexOf(prefix) + prefix.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private static void pokemonExport() {
        AtomicLong clock = new AtomicLong(100);
        AtomicLong requested = new AtomicLong();
        LocalApiData data = new LocalApiData(clock::get, clock::get);
        data.enable();
        LocalApiPokemonCapture export = new LocalApiPokemonCapture(data, clock::get, clock::get,
            (revision, lease, deadline) -> requested.set(revision));
        export.leaseChanged(9);
        check(export.route("start?page_size=1", true).code == 409);
        data.seeIdentity("a", true);
        clock.incrementAndGet();
        check(data.publish(data.trainer, "a", 101, LocalApiData.Coverage.PARTIAL, null));
        String start = export.route("start?page_size=1", true).body;
        long revision = requested.get();
        String id = jsonString(start, "id");
        String p = "export|" + revision + "|9|0061|";
        check(export.route("start?page_size=1", true).code == 409);
        clock.set(102);
        export.accept(p + "slice|2|0|101|102|1|1|25|120|5|0|8|1|0");
        clock.set(103);
        export.accept(p + "done|2|1|101|103|1|2|25|119|5|0|8|0|0");
        check(requested.get() == 0);
        String cursor = jsonString(export.route("status?id=" + id, false).body, "cursor");
        String first = export.route("page?cursor=" + cursor, false).body;
        check(first.contains("\"consistency\":\"non_atomic\"") && first.contains("\"cp\":120"));
        String next = jsonString(first, "next_cursor");
        String second = export.route("page?cursor=" + next, false).body;
        check(second.contains("\"cp\":119") && second.contains("\"next_cursor\":null"));
        check(export.route("page?cursor=" + id + ".9.9999", false).code == 400);
        for (int i = 0; i < 24; i++) {
            long current = clock.addAndGet(5000);
            check(data.publish(data.trainer, "a", current, LocalApiData.Coverage.PARTIAL, null));
        }
        check(export.route("page?cursor=" + next, false).code == 404);
        export.leaseChanged(10);
        check(export.route("page?cursor=" + next, false).code == 404);

        id = jsonString(export.route("start?page_size=2", true).body, "id");
        p = "export|" + requested.get() + "|10|0061|";
        export.accept(p + "done|2|0|" + clock.get() + "|" + clock.get() + "|0|1|25|100|5|0|8|0|0;1|25|120|5|0|8|1|0");
        check(export.route("status?id=" + id, false).body.contains("invalid_or_missing_slice"));
        id = jsonString(export.route("start?page_size=1", true).body, "id");
        export.accept("export|" + requested.get() + "|10|0061|failed|structure_changed");
        check(export.route("status?id=" + id, false).body.contains("structure_changed"));
        id = jsonString(export.route("start?page_size=1", true).body, "id");
        check(export.route("cancel?id=" + id, true).body.contains("cancelled") && requested.get() == 0);
        id = jsonString(export.route("start?page_size=1", true).body, "id");
        for (int i = 0; i < 7; i++) {
            long current = clock.addAndGet(5000);
            check(data.publish(data.trainer, "a", current, LocalApiData.Coverage.PARTIAL, null));
        }
        check(export.route("status?id=" + id, false).body.contains("deadline") && requested.get() == 0);
        id = jsonString(export.route("start?page_size=1", true).body, "id");
        data.seeIdentity("b", true);
        check(export.route("status?id=" + id, false).code == 404 && requested.get() == 0);
    }

    private static void inventorySummary() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        LocalApiData data = new LocalApiData(clock::get, clock::get);
        data.enable();
        ResearchInventory inventory = new ResearchInventory(data);
        ResearchObservations registry = new ResearchObservations(data, new ResearchProgression(data),
            new LocalApiPokemonBag(data), inventory);
        ResearchDiscovery jobs = new ResearchDiscovery(clock::get, ignored -> null);
        String query = "observe?contract=inventory.bag.v1";
        check(observationJob(jobs, registry, query).contains("verified_snapshot_unavailable"));
        data.seeIdentity("a", true);
        inventory.leaseChanged(9);
        clock.incrementAndGet();
        check(!inventory.accept("101|0061|8|0|350|1"));
        check(!inventory.accept("101|0062|9|0|350|1"));
        check(!inventory.accept("101|0061|9|-1|350|1"));
        check(!inventory.accept("101|0061|9|0|0|1"));
        check(!inventory.accept("101|0061|9|0|350|8"));
        check(inventory.accept("101|0061|9|0|350|1"));
        String value = observationJob(jobs, registry, query);
        check(value.contains("\"coverage\":\"partial\"") && value.contains("\"collection_complete\":null")
            && value.contains("\"raw_total_items\":0") && value.contains("\"raw_max_items\":350")
            && value.contains("\"raw_poke_ball_quantity\":null"));
        check(observationJob(jobs, registry, query + "&offset=1").contains("\"items\":[]"));
        check(jobs.route(query + "&fields=storage_usage", true, "lease", () -> true, registry).code == 400);
        inventory.leaseChanged(10);
        check(inventory.latest() == null);
        clock.incrementAndGet();
        check(!inventory.accept("102|0061|10|5|350|0|-1"));
        check(inventory.accept("102|0061|10|5|350|0|0"));
        check(observationJob(jobs, registry, query + "&fields=raw_poke_ball_quantity")
            .contains("\"raw_poke_ball_quantity\":0"));
        data.seeIdentity("b", true);
        check(inventory.latest() == null);
    }

    private static void progression() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        LocalApiData data = new LocalApiData(clock::get, clock::get);
        data.enable();
        ResearchProgression progression = new ResearchProgression(data);
        ResearchObservations registry = new ResearchObservations(data, progression);
        ResearchDiscovery jobs = new ResearchDiscovery(clock::get, ignored -> null);
        String query = "observe?contract=trainer.progression.v1&fields=level.xp.previous_level_xp_requirement.next_level_xp_requirement";
        check(observationJob(jobs, registry, query).contains("verified_snapshot_unavailable"));
        data.seeIdentity("a", true);
        progression.setLease(9, Long.MAX_VALUE);
        clock.incrementAndGet();
        check(!progression.accept("101|0061|8|40|800|400|900"));
        check(!progression.accept("101|0062|9|40|800|400|900"));
        check(!progression.accept("101|0061|9|0|800|400|900"));
        check(!progression.accept("101|0061|9|40|800|900|1000"));
        check(progression.accept("101|0061|9|40|800|400|-1"));
        check(!progression.accept("101|0061|9|41|801|400|900"));
        String result = observationJob(jobs, registry, query);
        check(result.contains("\"level\":40") && result.contains("\"xp\":800")
              && result.contains("\"next_level_xp_requirement\":null"));
        check(observationJob(jobs, registry, query + "&offset=1").contains("\"items\":[]"));
        check(jobs.route(query + ".name", true, "lease", () -> true, registry).code == 400);
        progression.setLease(10, Long.MAX_VALUE);
        check(progression.latest() == null);
        check(!progression.accept("101|0061|9|40|800|400|-1"));
        clock.incrementAndGet();
        check(progression.accept("102|0061|10|40|800|400|900"));
        data.seeIdentity("b", true);
        check(progression.latest() == null);
        check(!progression.accept("102|0061|10|40|800|400|900"));
    }

    private static void observations() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        LocalApiData data = new LocalApiData(clock::get, clock::get);
        data.enable();
        ResearchObservations contracts = new ResearchObservations(data);
        ResearchDiscovery jobs = new ResearchDiscovery(clock::get, ignored -> null);
        check(contracts.status().body.contains("approved_source_unavailable"));
        String query = "observe?contract=trainer.snapshot.v1&fields=xp.name";
        check(jobs.route(query + "&limit=2", true, "lease", () -> true, contracts).code == 400);
        check(jobs.route(query + ".unknown", true, "lease", () -> true, contracts).code == 400);
        check(jobs.route(query + ".xp", true, "lease", () -> true, contracts).code == 400);
        String missing = observationJob(jobs, contracts, query);
        check(missing.contains("verified_snapshot_unavailable"));
        check(observationJob(jobs, contracts, "observe?contract=pokemon.bag.v1").contains("verified_snapshot_unavailable"));
        data.seeIdentity("acct", true);
        clock.incrementAndGet();
        check(LocalApiTrainer.accept(data, "101|0061006300630074|0050"));
        String fresh = observationJob(jobs, contracts, query);
        check(fresh.contains("\"xp\":null") && fresh.contains("\"name\":\"P\"") && fresh.contains("partial"));
        check(observationJob(jobs, contracts, query + "&offset=1").contains("\"items\":[]"));
        String id = fresh.split("\"id\":\"")[1].split("\"")[0];
        check(jobs.route("job?id=" + id, false, "different", () -> true).code == 404);
        clock.addAndGet(LocalApiData.SNAPSHOT_MAX_AGE_MS + 1);
        check(jobs.route("job?id=" + id, false, "lease", () -> true).code == 404);
    }

    private static String observationJob(ResearchDiscovery jobs, ResearchObservations contracts, String query)
            throws Exception {
        LocalApiRoutes.Reply reply = jobs.route(query, true, "lease", () -> true, contracts);
        check(reply.code == 202);
        String id = reply.body.split("\"id\":\"")[1].split("\"")[0];
        for (int i = 0; i < 100; i++) {
            String status = jobs.route("job?id=" + id, false, "lease", () -> true).body;
            if (status.contains("\"state\":\"completed\"")) return status;
            Thread.sleep(5);
        }
        throw new AssertionError("Observation did not complete");
    }

    private static void persistence() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        class Store implements ResearchApiSession.Store {
            ResearchApiSession.Saved saved;
            boolean corrupt;
            public ResearchApiSession.Saved load() throws java.io.IOException {
                if (corrupt) throw new java.io.IOException();
                return saved;
            }
            public void save(ResearchApiSession.Saved value) { saved = value; corrupt = false; }
        }
        Store store = new Store();
        ResearchApiSession first = new ResearchApiSession(clock::get, () -> 0, store, 7);
        first.listenerChanged(true);
        first.activate(15);
        ResearchApiSession.Lease initial = first.current();
        check(initial != null && initial.deadline == 900100);
        clock.set(501);
        ResearchApiSession restarted = new ResearchApiSession(clock::get, () -> 0, store, 7);
        check(restarted.current() == null);
        restarted.listenerChanged(true);
        restarted.activate(60);
        check(restarted.current().token.equals(initial.token) && restarted.current().deadline == initial.deadline);
        restarted.accountVerified("account");
        check(restarted.current().token.equals(initial.token));
        restarted.rotate(restarted.current(), 15);
        check(!restarted.current().token.equals(initial.token));
        restarted.accountVerified("changed");
        check(store.saved != null && restarted.current() == null);
        restarted.activate(15);
        String old = restarted.current().token;
        clock.set(restarted.current().deadline);
        ResearchApiSession expired = new ResearchApiSession(clock::get, () -> 0, store, 7);
        expired.listenerChanged(true);
        expired.activate(15);
        check(!expired.current().token.equals(old));
        String sameBoot = expired.current().token;
        ResearchApiSession reboot = new ResearchApiSession(clock::get, () -> 0, store, 8);
        reboot.listenerChanged(true);
        reboot.activate(15);
        check(!reboot.current().token.equals(sameBoot));
        String beforeRegression = reboot.current().token;
        clock.decrementAndGet();
        ResearchApiSession backwards = new ResearchApiSession(clock::get, () -> 0, store, 8);
        backwards.listenerChanged(true);
        backwards.activate(15);
        check(!backwards.current().token.equals(beforeRegression));
        backwards.listenerChanged(false);
        check(store.saved != null && backwards.current() == null);
        backwards.listenerChanged(true);
        backwards.issue(0);
        String permanent = backwards.current().token;
        ResearchApiSession nextBoot = new ResearchApiSession(clock::get, () -> 0, store, 9);
        check(nextBoot.current() == null);
        nextBoot.listenerChanged(true);
        check(nextBoot.current() == null);
        nextBoot.activate(0);
        check(nextBoot.current().token.equals(permanent));
        nextBoot.deactivate();
        nextBoot.activate(0);
        check(nextBoot.current().token.equals(permanent));
        nextBoot.revoke();
        check(store.saved == null && nextBoot.current() == null);
        store.corrupt = true;
        ResearchApiSession corrupt = new ResearchApiSession(clock::get, () -> 0, store, 8);
        corrupt.listenerChanged(true);
        corrupt.activate(15);
        check(corrupt.current() == null && corrupt.storageFailed());
    }

    private static String request(int port, String path, String token) throws Exception {
        return HttpPreviewServerTest.request(port, "GET", path, "Authorization: Bearer " + token + "\r\n");
    }

    private static LocalApiWebSocketTest.Wire wire(int port, String path, String token) throws Exception {
        return new LocalApiWebSocketTest.Wire(port, path, "Authorization: Bearer " + token + "\r\n");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
