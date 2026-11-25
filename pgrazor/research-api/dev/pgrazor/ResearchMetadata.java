package dev.pgrazor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/** Bounded static declarations plus evidence from the existing owner-thread trainer adapter. */
final class ResearchMetadata {
    private static final String SOURCE = "offline_v39_0.437.0";
    private static final String TRAINER = "trainer.stats.v1";
    private static final String DECLARATION = "PlayerStatsProto";
    private static final String FIELDS = "{\"Experience\":\"System.Int64\",\"NumPokemonCaptured\":\"System.Int32\","
        + "\"NumPokemonEncountered\":\"System.Int32\",\"PokeStopVisits\":\"System.Int32\","
        + "\"NumEggsHatched\":\"System.Int32\",\"KmWalked\":\"System.Single\"}";
    private final LocalApiData data;

    ResearchMetadata(LocalApiData data) { this.data = data; }

    LocalApiRoutes.Reply get(String uri) {
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        try {
            Map<String, String> args = query < 0 ? new HashMap<>() : params(uri.substring(query + 1));
            switch (path) {
                case "runtime":
                case "diagnostics":
                    if (!args.isEmpty()) return error(400, "INVALID_QUERY");
                    return ok(readiness());
                case "assemblies": return assemblies(args);
                case "types": return types(args);
                case "type":
                    String id = args.remove("id");
                    if (id == null || !args.isEmpty()) return error(400, "INVALID_QUERY");
                    for (String[] type : ResearchDeclarations.TYPES) {
                        if (type[0].equals(id)) return ok(type[3]);
                    }
                    return error(404, "DECLARATION_NOT_FOUND");
                case "contracts":
                    if (!args.isEmpty()) return error(400, "INVALID_QUERY");
                    return ok("{\"contracts\":[{\"id\":\"" + TRAINER + "\",\"type\":\"" + DECLARATION
                        + "\",\"scope\":\"six_instance_fields\",\"mode\":\"passive_existing_provider\"}]}");
                case "validate":
                    String contract = args.remove("contract");
                    if (contract == null || !args.isEmpty()) return error(400, "INVALID_QUERY");
                    if (!TRAINER.equals(contract)) return error(404, "CONTRACT_NOT_FOUND");
                    return ok(validation());
                default: return error(404, "NOT_FOUND");
            }
        } catch (IllegalArgumentException invalid) {
            return error(400, "INVALID_QUERY");
        }
    }

    String readiness() {
        Evidence e = evidence();
        return "{\"provenance\":\"existing_trainer_provider\",\"account_ready\":" + e.account
            + ",\"trainer\":\"" + e.status + "\",\"live_catalog\":\"probe_required\"}";
    }

    private String validation() {
        Evidence e = evidence();
        return "{\"contract\":\"" + TRAINER + "\",\"type\":\"" + DECLARATION + "\",\"statically_declared\":true"
            + ",\"runtime_metadata_observed\":" + e.verified + ",\"compatible\":" + (e.verified ? "true" : "null")
            + ",\"provider_observed\":" + e.verified + ",\"status\":\"" + e.status
            + "\",\"provenance\":\"existing_trainer_provider\",\"checked_fields\":" + (e.verified ? FIELDS : "{}")
            + ",\"observed_at\":" + LocalApiEventProtocol.nullable(e.observed) + "}";
    }

    private Evidence evidence() {
        if (data == null) return new Evidence(false, false, "runtime_unavailable", null);
        synchronized (data) {
            if (data.session() == null) return new Evidence(false, false, "account_unavailable", null);
            LocalApiData.Snapshot<LocalApiTrainer> snapshot = data.latest(data.trainer);
            if (snapshot == null || !data.fresh(snapshot)) return new Evidence(true, false, "observation_unavailable", null);
            if (snapshot.data.xp == null) return new Evidence(true, false, "inconclusive", null);
            return new Evidence(true, true, "observed", snapshot.observedUtc.toString());
        }
    }

    private static final class Evidence {
        final boolean account, verified;
        final String status, observed;

        Evidence(boolean account, boolean verified, String status, String observed) {
            this.account = account;
            this.verified = verified;
            this.status = status;
            this.observed = observed;
        }
    }

    private static LocalApiRoutes.Reply assemblies(Map<String, String> args) {
        Set<String> unique = new LinkedHashSet<>();
        for (String[] type : ResearchDeclarations.TYPES) unique.add(LocalApiEventProtocol.nullable(type[1]));
        return page(new ArrayList<>(unique), args);
    }

    private static LocalApiRoutes.Reply types(Map<String, String> args) {
        String filter = args.remove("q");
        String q = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        List<String> rows = new ArrayList<>();
        for (String[] type : ResearchDeclarations.TYPES) {
            if ((type[2] + "." + type[0]).toLowerCase(Locale.ROOT).contains(q)) {
                rows.add("{\"id\":" + LocalApiEventProtocol.nullable(type[0])
                    + ",\"assembly\":" + LocalApiEventProtocol.nullable(type[1])
                    + ",\"namespace\":" + LocalApiEventProtocol.nullable(type[2]) + "}");
            }
        }
        return page(rows, args);
    }

    private static LocalApiRoutes.Reply page(List<String> rows, Map<String, String> args) {
        int offset = number(args.remove("offset"), 0, 100);
        int limit = number(args.remove("limit"), 10, 12);
        if (!args.isEmpty() || limit == 0) return error(400, "INVALID_QUERY");
        int start = Math.min(offset, rows.size());
        int end = Math.min(start + limit, rows.size());
        return ok("{\"provenance\":\"" + SOURCE + "\",\"total\":" + rows.size()
            + ",\"next_offset\":" + (end == rows.size() ? "null" : Integer.toString(end))
            + ",\"items\":[" + String.join(",", rows.subList(start, end)) + "]}");
    }

    private static int number(String value, int fallback, int max) {
        if (value == null) return fallback;
        if (!value.matches("[0-9]{1,3}")) throw new IllegalArgumentException();
        int number = Integer.parseInt(value);
        if (number > max) throw new IllegalArgumentException();
        return number;
    }

    private static Map<String, String> params(String query) {
        if (query.length() > 192 || query.isEmpty()) throw new IllegalArgumentException();
        Map<String, String> result = new HashMap<>();
        for (String pair : query.split("&", -1)) {
            String[] item = pair.split("=", -1);
            if (item.length != 2 || !item[0].matches("[a-z]{1,12}")
                    || !item[1].matches("[A-Za-z0-9_.-]{1,80}")
                    || result.put(item[0], item[1]) != null) throw new IllegalArgumentException();
        }
        return result;
    }

    private static LocalApiRoutes.Reply ok(String body) {
        if (body.length() > 8192) return error(503, "RESPONSE_LIMIT");
        return new LocalApiRoutes.Reply(200, body);
    }

    private static LocalApiRoutes.Reply error(int status, String code) {
        return new LocalApiRoutes.Reply(status, "{\"error\":\"" + code + "\"}");
    }
}
