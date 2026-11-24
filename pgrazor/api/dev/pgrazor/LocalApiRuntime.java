package dev.pgrazor;

import android.app.Application;

/** Non-root wiring. Creating the controller does not read settings or start workers. */
final class LocalApiRuntime {
    private static volatile LocalApiController controller;
    private static volatile LocalApiSettingsModel settings;

    static synchronized void init(Application app) {
        if (controller != null) return;
        controller = new LocalApiController(new LocalApiStore(app));
        settings = new LocalApiSettingsModel(controller);
    }

    static LocalApiSettingsModel settings() { return settings; }

    static void invalidate() {
        LocalApiController current = controller;
        if (current != null) current.stop();
    }
}
