package dev.pgrazor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Only the preview receives a synthetic status fixture. */
final class LocalApiRoutes {
    private static final String PREFIX = "/api/v1/";
    private static final String[] RESOURCES = {
        "pokemon", "inventory", "trainer", "pokedex", "eggs", "location", "journal", "events"
    };
    private static final Set<String> DEFERRED = new HashSet<>(Arrays.asList(RESOURCES));
    private final Reply status;
    private final boolean preview;

    private LocalApiRoutes(Reply status, boolean preview) {
        this.status = status;
        this.preview = preview;
    }

    static LocalApiRoutes preview() {
        return new LocalApiRoutes(new Reply(200,
            "{\"api_version\":\"1\",\"mode\":\"synthetic_preview\",\"status\":\"running\"}"), true);
    }

    static LocalApiRoutes production() {
        StringBuilder json = new StringBuilder("{\"api_version\":\"1\",\"status\":\"running\","
            + "\"account_ready\":false,\"session_generation\":null,\"capabilities\":{");
        for (int index = 0; index < RESOURCES.length; index++) {
            if (index > 0) json.append(',');
            json.append('"').append(RESOURCES[index])
                .append("\":{\"supported\":false,\"ready\":false,\"coverage\":\"unknown\",\"observed_at\":null}");
        }
        return new LocalApiRoutes(new Reply(200, json.append("}}").toString()), false);
    }

    Reply get(String path) {
        if ((PREFIX + "status").equals(path)) return status;
        int query = path.indexOf('?');
        String resource = query < 0 ? path : path.substring(0, query);
        if (!preview && resource.startsWith(PREFIX)
                && (DEFERRED.contains(resource.substring(PREFIX.length()))
                    || resource.matches("/api/v1/pokemon/[0-9]+"))) {
            return new Reply(501, "{\"api_version\":\"1\",\"error\":{\"code\":\"RESOURCE_UNSUPPORTED\","
                + "\"message\":\"Resource is not available in this build\",\"retryable\":false}}");
        }
        return new Reply(404, "{\"error\":\"NOT_FOUND\"}");
    }

    static final class Reply {
        final int code;
        final String body;

        Reply(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
