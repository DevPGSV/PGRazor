package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

final class ResearchApiSettings implements LocalApiExtension.Page {
    private static final int[] MINUTES = {0, 15, 30, 60, 120, 360, 720, 1440};
    private static final String[] LABELS = {"Never expires", "15 minutes", "30 minutes", "1 hour", "2 hours", "6 hours", "12 hours", "24 hours"};
    private final Activity activity;
    private final ResearchApiSession session;
    private final Switch enabled;
    private final TextView status;
    private final TextView secret;
    private final Button lifetime;
    private final Button reveal;
    private final Button copy;
    private final Button rotate;
    private final Button revoke;
    private final LinearLayout links;
    private int selected;
    private boolean painting;
    private boolean revealed;
    private ResearchApiSession.Lease displayed;
    private List<String> bases = Collections.emptyList();
    private AlertDialog dialog;

    ResearchApiSettings(Activity activity, LinearLayout parent, ResearchApiSession session) {
        this.activity = activity;
        this.session = session;
        TextView title = note(parent, "Research API");
        title.setTag(SettingsUi.TAG_SEARCH_ROW, "Research API");
        note(parent, "HTTP and WebSocket are plaintext. Use only on a trusted private LAN.");
        enabled = new Switch(activity);
        enabled.setText("Enable Research API");
        parent.addView(enabled);
        enabled.setOnCheckedChangeListener((v, checked) -> {
            if (painting) return;
            if (checked) session.activate(MINUTES[selected]);
            else session.deactivate();
            refresh(bases);
        });
        status = note(parent, "Disabled");
        lifetime = button(parent, LABELS[selected], this::chooseLifetime);
        secret = note(parent, "••••••••••••••••");
        reveal = button(parent, "Reveal Research token", () -> {
            revealed = !revealed;
            refresh(bases);
        });
        copy = button(parent, "Copy Research token", () -> {
            ResearchApiSession.Lease active = session.current();
            if (active != null) LocalApiSettingsPage.copy(activity, "Research API token", active.token, true);
        });
        rotate = button(parent, "Regenerate Research token", this::confirmRotation);
        revoke = button(parent, "Revoke Research token", () -> {
            session.revoke();
            refresh(bases);
        });
        links = new LinearLayout(activity);
        links.setOrientation(LinearLayout.VERTICAL);
        parent.addView(links);
    }

    @Override public void refresh(List<String> values) {
        ResearchApiSession.Lease active = session.current();
        if (displayed != active) revealed = false;
        displayed = active;
        boolean running = session.listener();
        painting = true;
        enabled.setChecked(active != null);
        enabled.setEnabled(running);
        painting = false;
        reveal.setEnabled(active != null);
        copy.setEnabled(active != null);
        rotate.setEnabled(active != null);
        revoke.setEnabled(running);
        set(reveal, revealed ? "Hide Research token" : "Reveal Research token");
        set(secret, active != null && revealed ? active.token : "••••••••••••••••");
        long remaining = session.remaining(active);
        String label = running ? "Disabled · No active token" : "Disabled · Start HTTP API first";
        if (session.storageFailed()) label = "Disabled · Could not access private token storage";
        if (active != null && remaining < 0) {
            label = "Active · Never expires";
        } else if (active != null) {
            long seconds = (remaining + 999) / 1000;
            label = "Active · " + seconds / 60 + " min " + seconds % 60 + " s remaining · Expires around "
                + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(System.currentTimeMillis() + remaining));
        }
        set(status, label);
        if (!bases.equals(values)) {
            bases = values;
            links.removeAllViews();
            for (String base : values) {
                String authority = base.substring("http://".length(), base.length() - "/api/v1".length());
                String http = "http://" + authority + "/research/v1";
                String ws = "ws://" + authority + "/research/v1/events";
                note(links, http);
                button(links, "Copy Research HTTP URL",
                    () -> LocalApiSettingsPage.copy(activity, "Research HTTP URL", http, false));
                note(links, ws);
                button(links, "Copy Research WebSocket URL",
                    () -> LocalApiSettingsPage.copy(activity, "Research WebSocket URL", ws, false));
            }
        }
        links.setVisibility(active == null ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    @Override public void hidden() {
        if (dialog != null) dialog.dismiss();
        revealed = false;
        displayed = null;
        set(secret, "••••••••••••••••");
    }

    private void chooseLifetime() {
        show(new AlertDialog.Builder(SettingsUi.dialogContext(activity)).setTitle("Research token lifetime at issuance")
            .setSingleChoiceItems(LABELS, selected, (owner, choice) -> {
                selected = choice;
                set(lifetime, LABELS[selected]);
                owner.dismiss();
            }).setNegativeButton("Cancel", null));
    }

    private void confirmRotation() {
        ResearchApiSession.Lease expected = session.current();
        if (expected == null) return;
        show(new AlertDialog.Builder(SettingsUi.dialogContext(activity)).setTitle("Regenerate Research token?")
            .setMessage("Existing Research clients will disconnect and must use the new token.")
            .setNegativeButton("Cancel", null).setPositiveButton("Regenerate", (owner, which) -> {
                session.rotate(expected, MINUTES[selected]);
                refresh(bases);
            }));
    }

    private void show(AlertDialog.Builder builder) {
        if (dialog != null) return;
        dialog = builder.create();
        dialog.setOnDismissListener(value -> dialog = null);
        dialog.show();
    }

    private TextView note(LinearLayout parent, String text) {
        TextView view = SettingsUi.text(activity, text, 13, SettingsUi.muted());
        parent.addView(view);
        return view;
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button view = SettingsUi.button(activity, text);
        parent.addView(view);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private static void set(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }
}
