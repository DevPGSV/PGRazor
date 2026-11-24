package dev.pgrazor;

import android.app.Application;

/** Non-root wiring. Creating the controller does not read settings or start workers. */
final class LocalApiRuntime {
    private static volatile LocalApiController controller;

    static synchronized void init(Application app) {
        if (controller == null) controller = new LocalApiController(new LocalApiStore(app));
    }

    static LocalApiController controller() {
        return controller;
    }

    static void invalidate() {
        LocalApiController current = controller;
        if (current != null) current.stop();
    }
}
