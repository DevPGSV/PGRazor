package dev.pgrazor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Only the preview receives a synthetic status fixture. */
final class LocalApiRoutes {
    private static final String PREFIX = "/api/v1/";
    static final String EVENTS_PATH = PREFIX + "events";
    private static final String[] RESOURCES = {
        "pokemon", "inventory", "trainer", "pokedex", "eggs", "location", "journal", "events"
    };
    private static final Set<String> DEFERRED = new HashSet<>(Arrays.asList(RESOURCES));
    private final LocalApiData data;

    final LocalApiWebSocket.Feed events;

    private LocalApiRoutes(LocalApiData data) {
        this.data = data;
        events = data == null ? null : data::eventState;
    }

    static LocalApiRoutes preview() { return new LocalApiRoutes(null); }

    static LocalApiRoutes production(LocalApiData data) { return new LocalApiRoutes(data); }

    Reply get(String path) {
        if ((PREFIX + "status").equals(path)) return status();
        if (data != null && (PREFIX + "trainer").equals(path)) return trainer();
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
        synchronized (data) {
            String session = data.session();
            LocalApiData.Snapshot<LocalApiTrainer> snapshot = data.latest(data.trainer);
            StringBuilder json = new StringBuilder("{\"api_version\":\"1\",\"status\":\"running\",\"account_ready\":")
                .append(session != null).append(",\"session_generation\":")
                .append(session == null ? "null" : "\"" + session + "\"").append(",\"capabilities\":{");
            for (int index = 0; index < RESOURCES.length; index++) {
                if (index > 0) json.append(',');
                String resource = RESOURCES[index];
                boolean trainer = resource.equals("trainer");
                boolean fresh = trainer && snapshot != null && data.fresh(snapshot);
                boolean eventStream = resource.equals("events");
                json.append('"').append(resource).append("\":{\"supported\":").append(trainer || eventStream)
                    .append(",\"ready\":").append(fresh || eventStream).append(",\"coverage\":\"")
                    .append(trainer && snapshot != null ? coverage(snapshot) : "unknown")
                    .append("\",\"observed_at\":")
                    .append(trainer && snapshot != null ? "\"" + snapshot.observedUtc + "\"" : "null");
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

    static Reply sessionChanged() { return error(409, "SESSION_CHANGED", "Trainer observation is no longer current", true); }

    private static Reply error(int code, String type, String message, boolean retry) {
        return new Reply(code, "{\"api_version\":\"1\",\"error\":{\"code\":\"" + type
            + "\",\"message\":\"" + message + "\",\"retryable\":" + retry + "}}");
    }

    static final class Reply {
        final int code;
        final String body;
        final String session;
        final LocalApiData.Snapshot<?> snapshot;

        Reply(int code, String body) { this(code, body, null, null); }

        Reply(int code, String body, String session, LocalApiData.Snapshot<?> snapshot) {
            this.code = code;
            this.body = body;
            this.session = session;
            this.snapshot = snapshot;
        }
    }
}
