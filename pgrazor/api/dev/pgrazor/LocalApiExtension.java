package dev.pgrazor;

import android.app.Activity;
import android.widget.LinearLayout;

import java.util.List;

interface LocalApiExtension {
    boolean owns(String uri);
    Access authenticate(List<String> authorization);
    void listenerChanged(boolean running);
    default void accountVerified(String id) {}
    Page mount(Activity activity, LinearLayout parent);

    interface Access {
        boolean valid();
        LocalApiRoutes.Reply get(String uri);
        default boolean acceptsPost(String uri) { return false; }
        default LocalApiRoutes.Reply post(String uri) {
            return new LocalApiRoutes.Reply(405, "{\"error\":\"METHOD_NOT_ALLOWED\"}");
        }
        LocalApiWebSocket.Feed events(String uri);
    }

    interface Page {
        void refresh(List<String> bases);
        void hidden();
    }
}
