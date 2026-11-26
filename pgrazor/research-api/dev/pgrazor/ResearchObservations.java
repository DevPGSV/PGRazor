package dev.pgrazor;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Registry for reviewed observations. Capture uses existing immutable Java snapshots only. */
final class ResearchObservations {
    private static final String TRAINER = "trainer.snapshot.v1";
    private static final String[] CANDIDATES = {
        "pokemon.bag.v1", "inventory.bag.v1", "trainer.progression.v1", "incubators.v1"
    };
    private final LocalApiData data;

    private enum Field {
        name("string"), xp("integer"), pokemon_caught("integer"), pokemon_encountered("integer"),
        pokestops_visited("integer"), distance_walked_km("decimal"), eggs_hatched("integer");

        final String type;
        Field(String type) { this.type = type; }

        String value(LocalApiTrainer trainer) {
            switch (this) {
                case name: return "\"" + trainer.name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                case xp: return String.valueOf(trainer.xp);
                case pokemon_caught: return String.valueOf(trainer.caught);
                case pokemon_encountered: return String.valueOf(trainer.seen);
                case pokestops_visited: return String.valueOf(trainer.stops);
                case eggs_hatched: return String.valueOf(trainer.eggs);
                case distance_walked_km: return trainer.walkedMetres == null ? "null"
                    : BigDecimal.valueOf(trainer.walkedMetres, 3).toPlainString();
                default: throw new AssertionError();
            }
        }
    }

    ResearchObservations(LocalApiData data) { this.data = data; }

    private LocalApiData.Snapshot<LocalApiTrainer> fresh() {
        if (data == null) return null;
        LocalApiData.Snapshot<LocalApiTrainer> value = data.latest(data.trainer);
        return value != null && data.fresh(value) ? value : null;
    }

    LocalApiRoutes.Reply status() {
        boolean ready = fresh() != null;
        StringBuilder out = new StringBuilder("{\"contracts\":[{\"id\":\"" + TRAINER
            + "\",\"source\":\"validated_trainer_snapshot\",\"account_required\":true,\"max_samples\":1,"
            + "\"max_result_bytes\":8192,\"job_deadline_ms\":2500,\"coverage\":\"partial\",\"ready\":" + ready
            + ",\"reason\":" + (!ready ? "\"verified_snapshot_unavailable\"" : "null") + ",\"fields\":{");
        for (Field field : Field.values()) {
            if (field.ordinal() != 0) out.append(',');
            out.append('"').append(field).append("\":\"").append(field.type).append('"');
        }
        out.append("}}");
        for (String id : CANDIDATES) {
            out.append(",{\"id\":\"").append(id)
                .append("\",\"ready\":false,\"reason\":\"approved_source_unavailable\",\"fields\":{}}");
        }
        return new LocalApiRoutes.Reply(200, out.append("]}").toString());
    }

    Capture prepare(Map<String, String> args) {
        String id = args.remove("contract");
        String fields = args.remove("fields");
        String count = args.remove("limit");
        String offset = args.remove("offset");
        if (!args.isEmpty() || id == null || count != null && !"1".equals(count)
                || offset != null && !"0".equals(offset) && !"1".equals(offset)) throw new IllegalArgumentException();
        Set<Field> projection = new LinkedHashSet<>();
        if (TRAINER.equals(id)) {
            if (fields == null) throw new IllegalArgumentException();
            for (String name : fields.split("\\.", -1)) {
                if (!projection.add(Field.valueOf(name))) throw new IllegalArgumentException();
            }
            return new Capture(id, projection, fresh(), "1".equals(offset), "verified_snapshot_unavailable");
        }
        for (String candidate : CANDIDATES) {
            if (candidate.equals(id) && fields == null)
                return new Capture(id, projection, null, false, "approved_source_unavailable");
        }
        throw new IllegalArgumentException();
    }

    final class Capture {
        final LocalApiData.Snapshot<LocalApiTrainer> snapshot;
        private final String id, reason;
        private final Set<Field> projection;
        private final boolean end;

        Capture(String id, Set<Field> projection, LocalApiData.Snapshot<LocalApiTrainer> snapshot,
                boolean end, String reason) {
            this.id = id;
            this.projection = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(projection));
            this.snapshot = snapshot;
            this.end = end;
            this.reason = reason;
        }

        boolean current() {
            return snapshot == null || data.ifCurrent(snapshot.session, snapshot, () -> {});
        }

        String json() {
            String prefix = "{\"contract\":\"" + id + "\",\"provenance\":\"registered_provider\",";
            if (snapshot == null) return prefix + "\"availability\":\"unavailable\",\"reason\":\"" + reason + "\"}";
            if (end) return prefix + "\"availability\":\"observed\",\"coverage\":\"partial\",\"freshness\":\"fresh\","
                + "\"next_offset\":null,\"items\":[]}";
            StringBuilder fields = new StringBuilder();
            for (Field field : projection) {
                if (fields.length() > 0) fields.append(',');
                fields.append('"').append(field).append("\":").append(field.value(snapshot.data));
            }
            return prefix + "\"availability\":\"observed\",\"coverage\":\"partial\",\"freshness\":\"fresh\",\"observed_at\":\""
                + snapshot.observedUtc + "\",\"next_offset\":null,\"items\":[{" + fields + "}]}";
        }
    }
}
