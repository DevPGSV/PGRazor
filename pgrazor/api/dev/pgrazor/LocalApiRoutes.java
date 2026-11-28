package dev.pgrazor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.time.Instant;

/** Only the preview receives a synthetic status fixture. */
final class LocalApiRoutes {
    private static final String PREFIX = "/api/v1/";
    static final String EVENTS_PATH = PREFIX + "events";
    private static final String[] RESOURCES = {
        "pokemon", "inventory", "trainer", "pokedex", "eggs", "location", "journal", "events"
    };
    private static final Set<String> DEFERRED = new HashSet<>(Arrays.asList(RESOURCES));
    private final LocalApiData data;
    private final Supplier<LocationState> location;

    final LocalApiWebSocket.Feed events;
    final LocalApiPokemon pokemon;

    private LocalApiRoutes(LocalApiData data, LocalApiPokemon pokemon, Supplier<LocationState> location) {
        this.location = location;
        this.data = data;
        this.pokemon = pokemon;
        events = data == null ? null : data::eventState;
    }

    static LocalApiRoutes preview() { return new LocalApiRoutes(null, null, null); }

    static LocalApiRoutes production(LocalApiData data) { return new LocalApiRoutes(data, null, null); }

    static LocalApiRoutes production(LocalApiData data, LocalApiPokemon pokemon) {
        return new LocalApiRoutes(data, pokemon, null);
    }

    static LocalApiRoutes production(LocalApiData data, LocalApiPokemon pokemon, Supplier<LocationState> location) {
        return new LocalApiRoutes(data, pokemon, location);
    }

    Reply get(String path) {
        if ((PREFIX + "status").equals(path)) return status();
        if (data != null && (PREFIX + "trainer").equals(path)) return trainer();
        if (location != null && (PREFIX + "location").equals(path)) return location();
        if (location != null && path.startsWith(PREFIX + "location?"))
            return error(400, "INVALID_QUERY", "Location does not accept query parameters", false);
        if (data != null && path.startsWith(PREFIX + "trainer?")) {
            return error(400, "INVALID_QUERY", "Trainer does not accept query parameters", false);
        }
        if (events != null && path.startsWith(EVENTS_PATH + "?")) {
            return error(400, "INVALID_QUERY", "Events does not accept query parameters", false);
        }
        int query = path.indexOf('?');
        String resource = query < 0 ? path : path.substring(0, query);
        if (data != null && resource.startsWith(PREFIX)
                && (DEFERRED.contains(resource.substring(PREFIX.length()))
                    || resource.matches("/api/v1/pokemon/[0-9]+"))) {
            return error(501, "RESOURCE_UNSUPPORTED", "Resource is not available in this build", false);
        }
        return new Reply(404, "{\"error\":\"NOT_FOUND\"}");
    }

    private Reply location() {
        String session = data.session();
        if (session == null) return error(503, "RESOURCE_NOT_READY", "Verified account is unavailable", true);
        LocationState state;
        try { state = location.get(); }
        catch (IllegalArgumentException invalid) { state = null; }
        if (state == null) return error(503, "RESOURCE_UNAVAILABLE", "Configured simulated location is unavailable", true);
        String body = "{\"api_version\":\"1\",\"coverage\":\"partial\",\"freshness\":\"observed_at_request\","
            + "\"observed_at\":\"" + Instant.now() + "\",\"data\":{\"source\":\"pgr_motion_engine\",\"configured_simulated\":{\"latitude\":"
            + state.latitude + ",\"longitude\":" + state.longitude
            + ",\"override_requested\":" + state.enabled + ",\"configured_speed_kmh\":" + state.speedKmh
            + ",\"coordinate_semantics\":\"motion_engine_model\"},\"configured_anchor\":null,\"actual_device\":null,\"represented_to_game\":null}}";
        return new Reply(200, body, session, null);
    }

    private Reply trainer() {
        synchronized (data) {
            LocalApiData.Snapshot<LocalApiTrainer> snapshot = data.latest(data.trainer);
            if (snapshot == null) return error(503, "RESOURCE_NOT_READY", "Verified trainer observation is unavailable", true);
            if (!data.fresh(snapshot)) return error(503, "RESOURCE_STALE", "Trainer observation has expired", true);
            return new Reply(200, "{\"api_version\":\"1\",\"snapshot_id\":\"" + snapshot.id
                + "\",\"observed_at\":\"" + snapshot.observedUtc + "\",\"freshness\":\"fresh\",\"coverage\":\""
                + coverage(snapshot) + "\",\"data\":" + snapshot.data.json() + "}", snapshot.session, snapshot);
        }
    }

    private Reply status() {
        if (data == null) {
            return new Reply(200, "{\"api_version\":\"1\",\"mode\":\"synthetic_preview\",\"status\":\"running\"}");
        }
        LocalApiPokemon.Status collectionStatus = pokemon == null ? null : pokemon.status();
        synchronized (data) {
            String session = data.session();
            boolean currentCollection = collectionStatus != null && java.util.Objects.equals(collectionStatus.account, session);
            boolean pokemonReady = currentCollection && collectionStatus.observedAt != null;
            LocalApiData.Snapshot<LocalApiTrainer> snapshot = data.latest(data.trainer);
            StringBuilder json = new StringBuilder("{\"api_version\":\"1\",\"status\":\"running\",\"account_ready\":")
                .append(session != null).append(",\"session_generation\":")
                .append(session == null ? "null" : "\"" + session + "\"").append(",\"capabilities\":{");
            for (int index = 0; index < RESOURCES.length; index++) {
                if (index > 0) json.append(',');
                String resource = RESOURCES[index];
                boolean trainer = resource.equals("trainer");
                boolean fresh = trainer && snapshot != null && data.fresh(snapshot);
                boolean collection = pokemon != null && (resource.equals("pokemon") || resource.equals("eggs"));
                boolean locationSupported = location != null && resource.equals("location");
                boolean eventStream = resource.equals("events");
                json.append('"').append(resource).append("\":{\"supported\":").append(trainer || eventStream || collection || locationSupported)
                    .append(",\"ready\":").append(fresh || eventStream || collection && pokemonReady || locationSupported && session != null).append(",\"coverage\":\"")
                    .append(trainer && snapshot != null ? coverage(snapshot) : collection && pokemonReady ? "complete" : locationSupported && session != null ? "partial" : "unknown")
                    .append("\",\"observed_at\":")
                    .append(trainer && snapshot != null ? "\"" + snapshot.observedUtc + "\""
                        : collection && pokemonReady ? "\"" + collectionStatus.observedAt + "\"" : "null");
                if (collection) json.append(",\"provider_state\":\"")
                    .append(currentCollection ? collectionStatus.state : session == null ? "account_unavailable" : "no_snapshot")
                    .append('"');
                if (trainer) json.append(",\"freshness\":").append(snapshot == null ? "null" : fresh ? "\"fresh\"" : "\"stale\"");
                json.append('}');
            }
            return new Reply(200, json.append("}}").toString(), session, null);
        }
    }

    boolean publish(Reply reply, Runnable enqueue) {
        if (reply == null || reply.session == null) {
            enqueue.run();
            return true;
        }
        return data.ifCurrent(reply.session, reply.snapshot, enqueue);
    }

    private static String coverage(LocalApiData.Snapshot<?> value) {
        return value.coverage.name().toLowerCase(Locale.ROOT);
    }

    static Reply sessionChanged() { return error(409, "SESSION_CHANGED", "Account observation is no longer current", true); }

    private static Reply error(int code, String type, String message, boolean retry) {
        return new Reply(code, "{\"api_version\":\"1\",\"error\":{\"code\":\"" + type
            + "\",\"message\":\"" + message + "\",\"retryable\":" + retry + "}}");
    }

    static final class Reply {
        final int code;
        final String body;
        final byte[] bytes;
        final String session;
        final LocalApiData.Snapshot<?> snapshot;

        Reply(int code, String body) { this(code, body, null, null); }

        Reply(int code, String body, String session, LocalApiData.Snapshot<?> snapshot) {
            this(code, body, null, session, snapshot);
        }

        Reply(int code, byte[] bytes, String session) {
            this(code, null, bytes, session, null);
        }

        private Reply(int code, String body, byte[] bytes, String session, LocalApiData.Snapshot<?> snapshot) {
            this.code = code;
            this.body = body;
            this.bytes = bytes;
            this.session = session;
            this.snapshot = snapshot;
        }
    }
}
