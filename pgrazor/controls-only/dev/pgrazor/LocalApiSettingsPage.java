package dev.pgrazor;

import android.app.Activity;
import android.widget.LinearLayout;

/** Root and research controls do not expose the non-root API page. */
final class LocalApiSettingsPage {
    static boolean available() { return false; }
    LocalApiSettingsPage(Activity activity, LinearLayout listener, LinearLayout authentication, LinearLayout addresses) {}
    void visible(boolean value) {}
}
