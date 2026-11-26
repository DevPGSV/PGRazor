package dev.pgrazor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class LocalApiPokemon {
    static final String PATH = "/api/v1/pokemon";
    static final int HTTP_SECONDS = 45;
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_WAITERS = 2;
    private static final Set<String> FIELDS = new HashSet<>();
    static {
        FIELDS.addAll(Arrays.asList(LocalApiPokemonBag.Row.KEYS));
        FIELDS.addAll(Arrays.asList(LocalApiPokemonNormalization.KEYS));
    }

    static final class Status {
        final String state, account, observedAt;
        Status(String state, String account, String observedAt) {
            this.state = state;
            this.account = account;
            this.observedAt = observedAt;
        }
    }

    static final class Request {
        final LocalApiPokemonCapture.Capture capture;
        final String id;
        final boolean includeEggs;
        final Set<String> fields;
        boolean released;
        Request(LocalApiPokemonCapture.Capture capture, String id, boolean includeEggs, Set<String> fields) {
            this.capture = capture;
            this.id = id;
            this.includeEggs = includeEggs;
            this.fields = fields;
        }
    }

    static final class Start {
        final Request request;
        final LocalApiRoutes.Reply error;
        Start(Request request, LocalApiRoutes.Reply error) {
            this.request = request;
            this.error = error;
        }
    }

    private final LocalApiData data;
    private final LocalApiPokemonCapture capture;
    private final Supplier<LocalApiPokemonNormalization> labels;
    private final Map<LocalApiPokemonCapture.Capture, Integer> references = new HashMap<>();
    private int waiters;
    private final ThreadPoolExecutor serializer = new ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(MAX_WAITERS), task -> {
            Thread thread = new Thread(task, "local-api-pokemon-json");
            thread.setDaemon(true);
            return thread;
        });

    LocalApiPokemon(LocalApiData data, LongSupplier clock, LongSupplier wall,
                    LocalApiPokemonCapture.Demand demand, Supplier<LocalApiPokemonNormalization> labels) {
        this.data = data;
        this.capture = new LocalApiPokemonCapture(data, clock, wall, demand);
        this.labels = labels;
    }

    void listener(long generation) { capture.leaseChanged(generation); }
    void accept(String packet) { capture.accept(packet); }
    Status status() {
        synchronized (capture) {
            LocalApiPokemonCapture.Capture value = capture.current();
            String account = data.session();
            if (!capture.active()) return new Status("provider_inactive", account, null);
            if (account == null) return new Status("account_unavailable", null, null);
            if (value == null || !account.equals(value.account)) return new Status("no_snapshot", account, null);
            if (capture.fresh(value))
                return new Status("retained_snapshot", account, value.finishedUtc.toString());
            if ("capturing".equals(value.state)) return new Status("acquiring", account, null);
            if ("completed".equals(value.state)) return new Status("snapshot_stale", account, null);
            if ("native_capture_unavailable".equals(value.reason)) return new Status("scheduling_failed", account, null);
            return new Status("capture_unavailable", account, null);
        }
    }

    static boolean owns(String path) {
        return path.equals(PATH) || path.startsWith(PATH + "?") || path.startsWith(PATH + "/");
    }

    synchronized Start start(String uri) {
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        String id = path.equals(PATH) ? null : path.substring(PATH.length() + 1);
        boolean eggs = true, refresh = false, eggsSeen = false, refreshSeen = false;
        Set<String> fields = null;
        if (id != null) {
            try {
                if (!id.matches("[1-9][0-9]{0,19}") || Long.parseUnsignedLong(id) == 0)
                    return invalid();
            } catch (NumberFormatException invalid) { return invalid(); }
        }
        if (query >= 0) {
            for (String part : uri.substring(query + 1).split("&", -1)) {
                if (!refreshSeen && (part.equals("refresh=true") || part.equals("refresh=false"))) {
                    refreshSeen = true;
                    refresh = part.endsWith("true");
                } else if (id == null && !eggsSeen && (part.equals("include_eggs=true") || part.equals("include_eggs=false"))) {
                    eggsSeen = true;
                    eggs = part.endsWith("true");
                } else if (fields == null && part.startsWith("fields=")) {
                    fields = new HashSet<>();
                    for (String field : part.substring("fields=".length()).split(",", -1))
                        if (!FIELDS.contains(field) || !fields.add(field)) return invalid();
                } else return invalid();
            }
        }
        if (waiters >= MAX_WAITERS)
            return new Start(null, failure(429, "RESOURCE_BUSY", "Too many Pokémon requests", true));
        LocalApiPokemonCapture.Capture value;
        synchronized (capture) {
            if (!capture.active())
                return new Start(null, failure(503, "RESOURCE_PROVIDER_INACTIVE", "Pokémon provider is inactive", true));
            value = capture.current();
            if (refresh || !capture.fresh(value)) value = capture.request(10);
            if (value == null)
                return new Start(null, failure(503, "RESOURCE_NOT_READY", "Verified account is unavailable", true));
        }
        waiters++;
        references.put(value, references.getOrDefault(value, 0) + 1);
        return new Start(new Request(value, id, eggs, fields), null);
    }

    private static Start invalid() {
        return new Start(null, failure(400, "INVALID_QUERY", "Invalid Pokémon identifier or query", false));
    }

    synchronized void release(Request request) {
        if (request.released) return;
        request.released = true;
        waiters--;
        int remaining = references.get(request.capture) - 1;
        if (remaining == 0) {
            references.remove(request.capture);
            capture.abandon(request.capture);
        } else references.put(request.capture, remaining);
    }

    String state(Request request) { return capture.state(request.capture); }

    void serialize(Request request, Consumer<LocalApiRoutes.Reply> ready) {
        try {
            serializer.execute(() -> {
                LocalApiRoutes.Reply answer;
                try { answer = render(request, labels.get()); }
                catch (RuntimeException failure) {
                    answer = failure(503, "RESOURCE_UNAVAILABLE", "Could not serialize Pokémon snapshot", true);
                }
                ready.accept(answer);
            });
        } catch (RejectedExecutionException full) {
            ready.accept(failure(429, "RESOURCE_BUSY", "Pokémon serialization queue is full", true));
        }
    }

    LocalApiRoutes.Reply unavailable(Request request, String state) {
        if ("invalidated".equals(state)) return LocalApiRoutes.sessionChanged();
        String reason = request.capture.reason;
        if ("deadline".equals(reason)) return timeout();
        if ("native_capture_unavailable".equals(reason))
            return failure(503, "RESOURCE_SCHEDULING_FAILED", "Could not schedule Pokémon acquisition", true);
        if ("source_limit".equals(reason))
            return failure(413, "RESOURCE_CAPTURE_LIMIT", "Pokémon collection exceeds the capture limit", false);
        if ("source_unavailable".equals(reason))
            return failure(503, "RESOURCE_NOT_READY", "Pokémon source is empty or unready", true);
        if ("structure_changed".equals(reason))
            return failure(409, "RESOURCE_CHANGED", "Pokémon collection changed during acquisition", true);
        return failure(503, "RESOURCE_UNAVAILABLE", "Complete Pokémon observation unavailable", true);
    }

    static LocalApiRoutes.Reply timeout() {
        return failure(504, "RESOURCE_TIMEOUT", "Pokémon observation deadline reached", true);
    }

    static LocalApiRoutes.Reply render(Request request, LocalApiPokemonNormalization normalization) {
        LocalApiPokemonCapture.Capture value = request.capture;
        String metadata = "{\"api_version\":\"1\",\"snapshot_id\":\"" + value.id + "\",\"revision\":\"" + value.revision
            + "\",\"coverage\":\"complete\",\"collection_complete\":true,\"structural_consistency\":\"verified\","
            + "\"consistency\":\"non_atomic\",\"attribute_completeness\":\"nullable\",\"freshness\":\"retained\","
            + "\"capture_started_at\":\"" + value.startedUtc + "\",\"capture_finished_at\":\"" + value.finishedUtc
            + "\",\"capture_elapsed_ms\":" + (value.finished - value.started)
            + ",\"source_entry_count\":" + value.expected + ",\"classified_pokemon_rows\":" + (value.expected - value.eggs)
            + ",\"classified_egg_rows\":" + value.eggs + ",\"storage_usage\":null,\"storage_capacity\":null"
            + ",\"normalization_version\":" + LocalApiPokemonNormalization.quote(normalization.version)
            + ",\"normalization_game_version\":" + LocalApiPokemonNormalization.quote(normalization.game);
        if (request.id != null) {
            for (LocalApiPokemonBag.Row row : value.rows) {
                if (request.id.equals(row.instanceId))
                    return new LocalApiRoutes.Reply(200, metadata + ",\"data\":" + record(row, normalization, request.fields) + "}", value.account, null);
            }
            LocalApiRoutes.Reply missing = failure(404, "RESOURCE_NOT_FOUND", "No such Pokémon or egg in the verified collection", false);
            return new LocalApiRoutes.Reply(missing.code, missing.body, value.account, null);
        }
        StringBuilder pokemon = new StringBuilder("["), eggs = new StringBuilder("[");
        for (LocalApiPokemonBag.Row row : value.rows) {
            if (row.egg && !request.includeEggs) continue;
            StringBuilder group = row.egg ? eggs : pokemon;
            if (group.length() > 1) group.append(',');
            group.append(record(row, normalization, request.fields));
            if (pokemon.length() + eggs.length() > MAX_RESPONSE_BYTES)
                return failure(413, "RESOURCE_RESPONSE_LIMIT", "Complete response exceeds the byte limit", false);
        }
        String body = metadata + ",\"include_eggs\":" + request.includeEggs + ",\"pokemon\":" + pokemon + "]"
            + (request.includeEggs ? ",\"eggs\":" + eggs + "]" : "") + "}";
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        return encoded.length <= MAX_RESPONSE_BYTES
            ? new LocalApiRoutes.Reply(200, encoded, value.account)
            : failure(413, "RESOURCE_RESPONSE_LIMIT", "Complete response exceeds the byte limit", false);
    }

    private static String record(LocalApiPokemonBag.Row row, LocalApiPokemonNormalization normalization, Set<String> fields) {
        StringBuilder result = new StringBuilder(row.jsonFields(fields));
        normalization.appendFields(result, row, fields);
        return "{" + result + "}";
    }

    private static LocalApiRoutes.Reply failure(int code, String error, String message, boolean retry) {
        return new LocalApiRoutes.Reply(code, "{\"api_version\":\"1\",\"error\":{\"code\":\"" + error
            + "\",\"message\":\"" + message + "\",\"retryable\":" + retry + "}}");
    }
}
