package dev.pgrazor;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Registry for reviewed observations. Capture uses existing immutable Java snapshots only. */
final class ResearchObservations {
    private static final String TRAINER = "trainer.snapshot.v1";
    private static final String PROGRESSION = "trainer.progression.v1";
    private static final String POKEMON = "pokemon.bag.v1";
    private static final String INVENTORY = "inventory.bag.v1";
    private static final String[] CANDIDATES = {
        "incubators.v1"
    };
    private final LocalApiData data;
    private final ResearchProgression progression;
    private final LocalApiPokemonBag pokemonBag;
    private final ResearchInventory inventory;

    private enum Field {
        name("string"), xp("integer"), pokemon_caught("integer"), pokemon_encountered("integer"),
        pokestops_visited("integer"), distance_walked_km("decimal"), eggs_hatched("integer"),
        level("integer"), previous_level_xp_requirement("integer"), next_level_xp_requirement("integer"),
        instance_id("string"), species_id("integer"), cp("integer"), attack_iv("integer"),
        defense_iv("integer"), stamina_iv("integer"), favorite("boolean"), is_egg("boolean"),
        nickname("string"), hp("integer"), max_hp("integer"), fast_move_id("integer"),
        charged_move_id("integer"), second_charged_move_id("integer"), lucky("boolean"),
        cp_multiplier("decimal"), creation_time_ms("integer"), shiny("boolean"), form_id("integer"),
        costume_id("integer"), gender_id("integer"), alignment_id("integer"), bread_mode_raw("integer"),
        raw_total_items("integer"), raw_max_items("integer"), capture_elapsed_ms("integer");

        final String type;
        Field(String type) { this.type = type; }

        String value(Object value) {
            if (value instanceof ResearchInventory.Values) {
                ResearchInventory.Values counts = (ResearchInventory.Values) value;
                switch (this) {
                    case raw_total_items: return Integer.toString(counts.totalItems);
                    case raw_max_items: return Integer.toString(counts.maxItems);
                    case capture_elapsed_ms: return Integer.toString(counts.elapsed);
                    default: throw new AssertionError();
                }
            }
            if (value instanceof LocalApiPokemonBag.Row) {
                LocalApiPokemonBag.Row pokemon = (LocalApiPokemonBag.Row) value;
                switch (this) {
                    case instance_id: return "\"" + pokemon.instanceId + "\"";
                    case species_id: return String.valueOf(pokemon.species);
                    case cp: return String.valueOf(pokemon.cp);
                    case attack_iv: return String.valueOf(pokemon.attack);
                    case defense_iv: return String.valueOf(pokemon.defense);
                    case stamina_iv: return String.valueOf(pokemon.stamina);
                    case favorite: return String.valueOf(pokemon.favorite);
                    case is_egg: return Boolean.toString(pokemon.egg);
                    case nickname: return pokemon.nickname == null ? "null"
                        : "\"" + pokemon.nickname.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                    case hp: return String.valueOf(pokemon.hp);
                    case max_hp: return String.valueOf(pokemon.maxHp);
                    case fast_move_id: return String.valueOf(pokemon.fastMove);
                    case charged_move_id: return String.valueOf(pokemon.chargedMove);
                    case second_charged_move_id: return String.valueOf(pokemon.secondChargedMove);
                    case lucky: return String.valueOf(pokemon.lucky);
                    case cp_multiplier: return String.valueOf(pokemon.cpMultiplier);
                    case creation_time_ms: return String.valueOf(pokemon.creationTimeMs);
                    case shiny: return String.valueOf(pokemon.shiny);
                    case form_id: return String.valueOf(pokemon.form);
                    case costume_id: return String.valueOf(pokemon.costume);
                    case gender_id: return String.valueOf(pokemon.gender);
                    case alignment_id: return String.valueOf(pokemon.alignment);
                    case bread_mode_raw: return String.valueOf(pokemon.breadMode);
                    default: throw new AssertionError();
                }
            }
            if (value instanceof ResearchProgression.Values) {
                ResearchProgression.Values stats = (ResearchProgression.Values) value;
                switch (this) {
                    case xp: return String.valueOf(stats.xp);
                    case level: return String.valueOf(stats.level);
                    case previous_level_xp_requirement: return String.valueOf(stats.previous);
                    case next_level_xp_requirement: return String.valueOf(stats.next);
                    default: throw new AssertionError();
                }
            }
            LocalApiTrainer trainer = (LocalApiTrainer) value;
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

    ResearchObservations(LocalApiData data) { this(data, new ResearchProgression(data)); }

    ResearchObservations(LocalApiData data, ResearchProgression progression) {
        this(data, progression, new LocalApiPokemonBag(data));
    }

    ResearchObservations(LocalApiData data, ResearchProgression progression, LocalApiPokemonBag pokemonBag) {
        this(data, progression, pokemonBag, new ResearchInventory(data));
    }

    ResearchObservations(LocalApiData data, ResearchProgression progression, LocalApiPokemonBag pokemonBag,
                         ResearchInventory inventory) {
        this.data = data;
        this.progression = progression;
        this.pokemonBag = pokemonBag;
        this.inventory = inventory;
    }

    private static boolean supported(String id, Field field) {
        boolean inventoryField = field.ordinal() >= Field.raw_total_items.ordinal();
        if (INVENTORY.equals(id) || inventoryField) return INVENTORY.equals(id) && inventoryField;
        if (POKEMON.equals(id)) return field.ordinal() >= Field.instance_id.ordinal();
        if (field.ordinal() >= Field.instance_id.ordinal()) return false;
        boolean progressionField = field.ordinal() >= Field.level.ordinal();
        return PROGRESSION.equals(id) ? progressionField || field == Field.xp : !progressionField;
    }

    private LocalApiData.Snapshot<LocalApiTrainer> fresh() {
        if (data == null) return null;
        LocalApiData.Snapshot<LocalApiTrainer> value = data.latest(data.trainer);
        return value != null && data.fresh(value) ? value : null;
    }

    LocalApiRoutes.Reply status() {
        StringBuilder out = new StringBuilder("{\"contracts\":[");
        describe(out, TRAINER, "validated_trainer_snapshot", fresh() != null);
        out.append(',');
        describe(out, PROGRESSION, "account_owner_player_stats", progression.latest() != null);
        out.append(',');
        describe(out, POKEMON, "verified_bag_backing_list_prefix", pokemonBag.latest() != null);
        out.append(',');
        describe(out, INVENTORY, "verified_item_bag_raw_counters", inventory.latest() != null);
        for (String id : CANDIDATES) {
            out.append(",{\"id\":\"").append(id)
                .append("\",\"ready\":false,\"reason\":\"approved_source_unavailable\",\"fields\":{}}");
        }
        return new LocalApiRoutes.Reply(200, out.append("]}").toString());
    }

    private static void describe(StringBuilder out, String id, String source, boolean ready) {
        out.append("{\"id\":\"").append(id).append("\",\"source\":\"").append(source)
            .append("\",\"account_required\":true,\"max_samples\":").append(maxSamples(id)).append(",\"max_result_bytes\":8192,")
            .append("\"job_deadline_ms\":2500,\"coverage\":\"partial\",\"ready\":").append(ready)
            .append(",\"reason\":").append(ready ? "null" : "\"verified_snapshot_unavailable\"").append(",\"fields\":{");
        boolean comma = false;
        for (Field field : Field.values()) {
            if (!supported(id, field)) continue;
            if (comma) out.append(',');
            out.append('"').append(field).append("\":\"").append(field.type).append('"');
            comma = true;
        }
        out.append("}}");
    }

    Capture prepare(Map<String, String> args) {
        String id = args.remove("contract");
        String fields = args.remove("fields");
        String count = args.remove("limit");
        String offset = args.remove("offset");
        if (!args.isEmpty() || id == null) throw new IllegalArgumentException();
        int limit = bounded(count, 1, 1, maxSamples(id));
        int start = bounded(offset, 0, 0, limit);
        Set<Field> projection = new LinkedHashSet<>();
        if (TRAINER.equals(id) || PROGRESSION.equals(id) || POKEMON.equals(id) || INVENTORY.equals(id)) {
            if (fields == null && (POKEMON.equals(id) || INVENTORY.equals(id))) {
                for (Field field : Field.values()) if (supported(id, field)) projection.add(field);
            }
            if (fields == null && projection.isEmpty()) throw new IllegalArgumentException();
            if (fields != null) for (String name : fields.split("\\.", -1)) {
                Field field = Field.valueOf(name);
                if (!supported(id, field) || !projection.add(field)) throw new IllegalArgumentException();
            }
            return new Capture(id, projection, PROGRESSION.equals(id) ? progression.latest() : POKEMON.equals(id) ? pokemonBag.latest() : INVENTORY.equals(id) ? inventory.latest() : fresh(), start, limit, "verified_snapshot_unavailable");
        }
        for (String candidate : CANDIDATES) {
            if (candidate.equals(id) && fields == null)
                return new Capture(id, projection, null, 0, 1, "approved_source_unavailable");
        }
        throw new IllegalArgumentException();
    }

    private static int maxSamples(String id) {
        return POKEMON.equals(id) ? LocalApiPokemonBag.MAX_SAMPLES : 1;
    }

    private static int bounded(String text, int fallback, int min, int max) {
        if (text == null) return fallback;
        if (!text.matches("[0-9]{1,2}")) throw new IllegalArgumentException();
        int value = Integer.parseInt(text);
        if (value < min || value > max) throw new IllegalArgumentException();
        return value;
    }

    final class Capture {
        final LocalApiData.Snapshot<?> snapshot;
        private final String id, reason;
        private final Set<Field> projection;
        private final int offset, limit;

        Capture(String id, Set<Field> projection, LocalApiData.Snapshot<?> snapshot,
                int offset, int limit, String reason) {
            this.id = id;
            this.projection = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(projection));
            this.snapshot = snapshot;
            this.offset = offset;
            this.limit = limit;
            this.reason = reason;
        }

        boolean current() {
            return snapshot == null || data.ifCurrent(snapshot.session, snapshot, () -> {});
        }

        String json() {
            String prefix = "{\"contract\":\"" + id + "\",\"provenance\":\"registered_provider\",";
            if (snapshot == null) return prefix + "\"availability\":\"unavailable\",\"reason\":\"" + reason + "\"}";
            if (INVENTORY.equals(id)) prefix += "\"sample_scope\":\"raw_counters\",\"collection_complete\":null,";
            java.util.List<?> rows;
            if (snapshot.data instanceof LocalApiPokemonBag.Values) {
                LocalApiPokemonBag.Values pokemon = (LocalApiPokemonBag.Values) snapshot.data;
                int end = Math.min(limit, pokemon.rows.size());
                rows = pokemon.rows.subList(Math.min(offset, end), end);
                String scope = limit == 1 ? "backing_list_head" : "backing_list_prefix";
                prefix += "\"sample_scope\":\"" + scope + "\",\"collection_complete\":null,"
                    + "\"captured_sample_size\":" + pokemon.rows.size() + ",\"capture_elapsed_ms\":" + pokemon.captureElapsedMs + ","
                    + "\"counts\":{\"total_collection_entries\":" + pokemon.entries
                    + ",\"owned_pokemon\":null,\"eggs\":null,\"incubated_eggs\":null,\"storage_capacity\":null},";
            } else {
                rows = offset == 0 ? java.util.Collections.singletonList(snapshot.data) : java.util.Collections.emptyList();
            }
            StringBuilder items = new StringBuilder();
            for (Object row : rows) {
                if (items.length() > 0) items.append(',');
                items.append('{');
                boolean comma = false;
                for (Field field : projection) {
                    if (comma) items.append(',');
                    items.append('"').append(field).append("\":").append(field.value(row));
                    comma = true;
                }
                items.append('}');
            }
            return prefix + "\"availability\":\"observed\",\"coverage\":\"partial\",\"freshness\":\"fresh\",\"observed_at\":\""
                + snapshot.observedUtc + "\",\"next_offset\":null,\"items\":[" + items + "]}";
        }
    }
}
