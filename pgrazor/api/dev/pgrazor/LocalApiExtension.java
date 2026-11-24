package dev.pgrazor;

import android.app.Activity;
import android.widget.LinearLayout;

import java.util.List;

interface LocalApiExtension {
    boolean owns(String uri);
    Access authenticate(List<String> authorization);
    void listenerChanged(boolean running);
    Page mount(Activity activity, LinearLayout parent);

    interface Access {
        boolean valid();
        LocalApiRoutes.Reply get(String uri);
        LocalApiWebSocket.Feed events(String uri);
    }

    interface Page {
        void refresh(List<String> bases);
        void hidden();
    }
}
