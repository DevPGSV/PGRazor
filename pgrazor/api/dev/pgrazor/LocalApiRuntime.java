package dev.pgrazor;

import android.app.Application;
import android.os.SystemClock;

/** Non-root wiring. Creating the controller does not read settings or start workers. */
final class LocalApiRuntime {
    private static volatile LocalApiController controller;
    private static volatile LocalApiSettingsModel settings;
    private static volatile LocalApiData data;
    private static LocalApiExtension extension;

    static synchronized void init(Application app) {
        if (controller != null) return;
        data = new LocalApiData(SystemClock::elapsedRealtime, System::currentTimeMillis);
        extension = LocalApiExtensionFactory.create(app, data);
        controller = new LocalApiController(new LocalApiStore(app), data, extension);
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
