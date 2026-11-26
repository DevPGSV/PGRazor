package dev.pgrazor;

import android.app.Activity;
import android.app.Application;
import android.os.SystemClock;
import android.widget.LinearLayout;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

final class ResearchApi implements LocalApiExtension {
    private static final String PREFIX = "/research/v1/";
    private static final String FEATURES = "{\"production_api\":true,\"websocket\":true,\"research_api\":true,\"research_discovery\":true}";
    private static final String CAPABILITIES = "{\"api_version\":\"1\",\"features\":" + FEATURES
        + ",\"operations\":[\"status\",\"capabilities\",\"events\",\"runtime\",\"assemblies\",\"types\",\"type\",\"contracts\",\"validate\",\"diagnostics\",\"live/discover\",\"live/job\",\"live/cancel\",\"live/observe\",\"observations/contracts\"],\"topics\":[\"research.status\",\"research.diagnostics\",\"research.jobs\"]}";
    private final ResearchApiSession session;

    private final ResearchMetadata metadata;
    private final ResearchDiscovery discovery;
    private final ResearchObservations observations;

    ResearchApi(Application app, LocalApiData data) {
        this(new ResearchApiSession(SystemClock::elapsedRealtime, data::eventEpoch,
            new ResearchTokenStore(app), ResearchTokenStore.boot(app)), data,
            new ResearchDiscovery(SystemClock::elapsedRealtime, ResearchDiscovery.Query::observe));
    }

    ResearchApi(ResearchApiSession session, LocalApiData data) {
        this(session, data, new ResearchDiscovery(() -> System.nanoTime() / 1_000_000L, ResearchDiscovery.Query::observe));
    }

    ResearchApi(ResearchApiSession session, LocalApiData data, ResearchDiscovery discovery) {
        this.discovery = discovery;
        this.session = session;
        metadata = new ResearchMetadata(data);
        observations = new ResearchObservations(data);
    }

    @Override public void accountVerified(String id) { session.accountVerified(id); }

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
            @Override public boolean ifValid(Runnable write) { return session.ifActive(lease, write); }

            @Override public boolean acceptsPost(String uri) {
                return uri.equals(PREFIX + "live/discover") || uri.startsWith(PREFIX + "live/discover?")
                    || uri.equals(PREFIX + "live/observe") || uri.startsWith(PREFIX + "live/observe?")
                    || uri.equals(PREFIX + "live/cancel") || uri.startsWith(PREFIX + "live/cancel?");
            }

            @Override public LocalApiRoutes.Reply post(String uri) {
                return discovery.route(uri.substring((PREFIX + "live/").length()), true, lease.id, this::valid, observations);
            }

            @Override public LocalApiRoutes.Reply get(String uri) {
                if (uri.equals(PREFIX + "observations/contracts")) return observations.status();
                if (uri.startsWith(PREFIX + "live/")) {
                    return discovery.route(uri.substring((PREFIX + "live/").length()), false, lease.id, this::valid);
                }
                if (uri.matches("/research/v1/(status|capabilities|events)\\?.*")) return new LocalApiRoutes.Reply(400, "{\"error\":\"INVALID_QUERY\"}");
                if ((PREFIX + "capabilities").equals(uri)) return new LocalApiRoutes.Reply(200, CAPABILITIES);
                if ((PREFIX + "status").equals(uri)) {
                    return new LocalApiRoutes.Reply(200, "{\"api_version\":\"1\",\"features\":" + FEATURES
                        + ",\"ready\":true,\"research_generation\":\"" + lease.id
                        + "\",\"remaining_ms\":" + (lease.deadline == Long.MAX_VALUE ? "null" : session.remaining(lease))
                        + ",\"expires\":" + (lease.deadline != Long.MAX_VALUE) + "}");
                }
                return uri.startsWith(PREFIX) ? metadata.get(uri.substring(PREFIX.length()))
                    : new LocalApiRoutes.Reply(404, "{\"error\":\"NOT_FOUND\"}");
            }

            @Override public LocalApiWebSocket.Feed events(String uri) {
                if (!(PREFIX + "events").equals(uri)) return null;
                return () -> {
                    Map<String, String> topics = new LinkedHashMap<>();
                    topics.put("research.status", "{\"ready\":true}");
                    topics.put("research.diagnostics", metadata.readiness());
                    topics.put("research.jobs", discovery.topic(lease.id));
                    return new LocalApiWebSocket.State(0, lease.id, topics);
                };
            }
        };
    }
}
