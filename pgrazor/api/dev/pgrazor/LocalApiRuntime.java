package dev.pgrazor;

import android.app.Application;
import android.os.SystemClock;

/** Non-root wiring. Creating the controller does not read settings or start workers. */
final class LocalApiRuntime {
    private static volatile LocalApiController controller;
    private static volatile LocalApiSettingsModel settings;
    private static volatile LocalApiData data;
    private static LocalApiExtension extension;
    private static volatile LocalApiPokemon pokemon;
    private static LocalApiPokemonNormalization normalization;

    static synchronized void init(Application app) {
        if (controller != null) return;
        data = new LocalApiData(SystemClock::elapsedRealtime, System::currentTimeMillis);
        extension = LocalApiExtensionFactory.create(app, data);
        pokemon = new LocalApiPokemon(data, SystemClock::elapsedRealtime, System::currentTimeMillis,
            NativeCore::collectionRequest, () -> pokemonLabels(app));
        controller = new LocalApiController(new LocalApiStore(app), data, extension, pokemon);
        settings = new LocalApiSettingsModel(controller);
    }

    static LocalApiSettingsModel settings() { return settings; }

    static LocalApiExtension extension() { return extension; }

    static boolean trainerRequested() {
        LocalApiData current = data;
        return current != null && current.requested();
    }

    static void accountObserved(String id, boolean verified) {
        LocalApiData current = data;
        if (current != null) {
            if (verified && extension != null) extension.accountVerified(id);
            current.seeIdentity(id, verified);
        }
    }

    static void identityLost() {
        LocalApiData current = data;
        if (current != null && current.enabled()) current.loseIdentity();
    }

    static void productionPokemonObserved(String packet) {
        LocalApiPokemon current = pokemon;
        if (current != null) current.accept(packet);
    }

    private static LocalApiPokemonNormalization pokemonLabels(Application app) {
        if (normalization != null) return normalization;
        try {
            org.json.JSONObject profile = pokemonAsset(app, "pokemon-max-v1.json");
            String version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
            if (!profile.getString("game_version").equals(version))
                return normalization = LocalApiPokemonNormalization.RAW;
            org.json.JSONObject names = pokemonAsset(app, "pokemon-names.json");
            java.util.Map<Integer, String> species = new java.util.HashMap<>();
            java.util.Iterator<String> keys = names.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!key.equals("0")) species.put(Integer.parseInt(key), names.getString(key));
            }
            java.util.Map<Integer, LocalApiPokemonNormalization.Category> modes = new java.util.HashMap<>();
            org.json.JSONObject rules = profile.getJSONObject("modes");
            keys = rules.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                org.json.JSONArray entry = rules.getJSONArray(key);
                modes.put(Integer.parseInt(key), new LocalApiPokemonNormalization.Category(
                    entry.getString(0), entry.getBoolean(1), entry.getBoolean(2), entry.getBoolean(3)));
            }
            java.util.Set<Integer> exceptions = new java.util.HashSet<>();
            org.json.JSONArray rawExceptions = profile.getJSONArray("normal_eligibility_unknown");
            for (int i = 0; i < rawExceptions.length(); i++) exceptions.add(rawExceptions.getInt(i));
            normalization = new LocalApiPokemonNormalization(profile.getString("version"), version, species,
                modes, exceptions, profile.getInt("special_mode"), profile.getInt("special_species"), profile.getInt("special_form"));
        } catch (Exception unavailable) {
            normalization = LocalApiPokemonNormalization.RAW;
        }
        return normalization;
    }

    private static org.json.JSONObject pokemonAsset(Application app, String name) throws Exception {
        try (java.io.InputStream in = PgAssets.open(app, "pgrazor/map-icons/" + name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 128 * 1024) throw new java.io.IOException("Asset too large");
                out.write(buffer, 0, count);
            }
            return new org.json.JSONObject(out.toString("UTF-8"));
        }
    }

    static void pokemonBagObserved(String packet) { if (extension != null) extension.pokemonBagObserved(packet); }

    static void progressionObserved(String packet) {
        if (extension != null) extension.progressionObserved(packet);
    }

    static void trainerObserved(String packet) {
        LocalApiData current = data;
        if (current != null) LocalApiTrainer.accept(current, packet);
    }

    static void invalidate() {
        LocalApiController current = controller;
        if (current != null) current.stop();
    }
}
