package dev.pgrazor;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

final class LocalApiPokemonNormalization {
    static final class Category {
        final String name;
        final Boolean dynamax, gigantamax, eligible;
        Category(String name, Boolean dynamax, Boolean gigantamax, Boolean eligible) {
            this.name = name;
            this.dynamax = dynamax;
            this.gigantamax = gigantamax;
            this.eligible = eligible;
        }
    }

    private static final Category UNKNOWN = new Category("unknown", null, null, null);
    private static final Category EGG = new Category("not_applicable", null, null, null);
    private static final Category SPECIAL = new Category("special_max", false, false, true);
    static final LocalApiPokemonNormalization RAW = new LocalApiPokemonNormalization(null, null,
        Collections.emptyMap(), Collections.emptyMap(), Collections.emptySet(), -1, -1, -1);

    final String version, game;
    private final Map<Integer, String> species;
    private final Map<Integer, Category> modes;
    private final Set<Integer> unknownNormalEligibility;
    private final int specialMode, specialSpecies, specialForm;

    LocalApiPokemonNormalization(String version, String game, Map<Integer, String> species,
            Map<Integer, Category> modes, Set<Integer> unknownNormalEligibility,
            int specialMode, int specialSpecies, int specialForm) {
        this.version = version;
        this.game = game;
        this.species = species;
        this.modes = modes;
        this.unknownNormalEligibility = unknownNormalEligibility;
        this.specialMode = specialMode;
        this.specialSpecies = specialSpecies;
        this.specialForm = specialForm;
    }

    static final String[] KEYS = ("species_name iv_percent max_category can_dynamax can_gigantamax "
        + "max_battle_eligible fast_move_name charged_move_name second_charged_move_name").split(" ");

    void appendFields(StringBuilder result, LocalApiPokemonBag.Row row, Set<String> selected) {
        Category category = category(row);
        String label = row.egg || row.species == null ? null : species.get(row.species);
        String iv = row.attack == null || row.defense == null || row.stamina == null ? "null" :
            java.math.BigDecimal.valueOf((row.attack + row.defense + row.stamina) * 100)
                .divide(java.math.BigDecimal.valueOf(45), 2, java.math.RoundingMode.HALF_EVEN).toPlainString();
        Object[] values = {quote(label), iv, quote(category.name), category.dynamax,
            category.gigantamax, category.eligible, null, null, null};
        LocalApiPokemonBag.Row.appendFields(result, KEYS, values, selected);
    }

    private Category category(LocalApiPokemonBag.Row row) {
        if (row.egg) return EGG;
        if (row.breadMode == null) return UNKNOWN;
        if (row.breadMode == specialMode && Integer.valueOf(specialSpecies).equals(row.species)
                && Integer.valueOf(specialForm).equals(row.form)) return SPECIAL;
        Category value = modes.get(row.breadMode);
        if (value == null) return UNKNOWN;
        return row.breadMode == 0 && unknownNormalEligibility.contains(row.species)
            ? new Category(value.name, value.dynamax, value.gigantamax, null) : value;
    }

    static String quote(String value) { return LocalApiPokemonBag.Row.quote(value); }
}
