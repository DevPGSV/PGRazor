package dev.pgrazor;

import android.app.Activity;
import android.os.SystemClock;
import android.widget.LinearLayout;

import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

final class ResearchApi implements LocalApiExtension {
    private static final String PREFIX = "/research/v1/";
    private static final String FEATURES = "{\"production_api\":true,\"websocket\":true,\"research_api\":true}";
    private static final String CAPABILITIES = "{\"api_version\":\"1\",\"features\":" + FEATURES
        + ",\"operations\":[\"status\",\"capabilities\",\"events\"],\"topics\":[\"research.status\"]}";
    private final ResearchApiSession session;

    ResearchApi(LongSupplier accountEpoch) {
        this(new ResearchApiSession(SystemClock::elapsedRealtime, accountEpoch));
    }

    ResearchApi(ResearchApiSession session) { this.session = session; }

    @Override public boolean owns(String uri) {
        return uri.startsWith(PREFIX) || uri.equals("/research/v1") || uri.startsWith("/research/v1?");
    }

    @Override public void listenerChanged(boolean running) { session.listenerChanged(running); }

    @Override public Page mount(Activity activity, LinearLayout parent) {
        return new ResearchApiSettings(activity, parent, session);
    }

    @Override public Access authenticate(List<String> authorization) {
        ResearchApiSession.Lease lease = session.authenticate(authorization);
        return lease == null ? null : new Access() {
            @Override public boolean valid() { return session.current() == lease; }

            @Override public LocalApiRoutes.Reply get(String uri) {
                if (uri.indexOf('?') >= 0) return new LocalApiRoutes.Reply(400, "{\"error\":\"INVALID_QUERY\"}");
                if ((PREFIX + "capabilities").equals(uri)) return new LocalApiRoutes.Reply(200, CAPABILITIES);
                if ((PREFIX + "status").equals(uri)) {
                    return new LocalApiRoutes.Reply(200, "{\"api_version\":\"1\",\"features\":" + FEATURES
                        + ",\"ready\":true,\"research_generation\":\"" + lease.id
                        + "\",\"remaining_ms\":" + session.remaining(lease) + "}");
                }
                return new LocalApiRoutes.Reply(404, "{\"error\":\"NOT_FOUND\"}");
            }

            @Override public LocalApiWebSocket.Feed events(String uri) {
                if (!(PREFIX + "events").equals(uri)) return null;
                return () -> new LocalApiWebSocket.State(0, lease.id,
                    Collections.singletonMap("research.status", "{\"ready\":true}"));
            }
        };
    }
}
