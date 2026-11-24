package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LocalApiSettingsPage {
    private static final String MASK = "••••••••••••••••";
    private final Activity activity;
    private final LocalApiSettingsModel model = LocalApiRuntime.settings();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ConnectivityManager connectivity;
    private final Switch enabled;
    private final EditText host;
    private final EditText port;
    private final Button apply;
    private final Button reveal;
    private final Button copyToken;
    private final Button rotate;
    private final TextView status;
    private final TextView token;
    private final TextView addressNote;
    private final LinearLayout urls;
    private final LocalApiExtension.Page extensionPage;
    private final Runnable tick = this::refresh;
    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network value) { addressDirty = true; }
        @Override public void onLost(Network value) { addressDirty = true; }
        @Override public void onLinkPropertiesChanged(Network value, LinkProperties links) { addressDirty = true; }
        @Override public void onCapabilitiesChanged(Network value, NetworkCapabilities caps) { addressDirty = true; }
    };

    private boolean visible;
    private boolean painting;
    private boolean revealed;
    private boolean watching;
    private boolean watchTried;
    private boolean scanning;
    private volatile boolean addressDirty;
    private int addressEpoch;
    private int ticks;
    private AlertDialog confirmation;
    private LocalApiController.Settings displayed;
    private LocalApiConfig addressBinding;
    private List<String> candidates = Collections.emptyList();

    static boolean available() { return LocalApiRuntime.settings() != null; }

    LocalApiSettingsPage(Activity activity, LinearLayout listener, LinearLayout auth, LinearLayout addresses) {
        this.activity = activity;
        connectivity = activity.getSystemService(ConnectivityManager.class);
        note(listener, "HTTP is plaintext. Use the server only on a trusted network.");
        enabled = new Switch(activity);
        enabled.setText("Enable HTTP API");
        enabled.setMinHeight(SettingsUi.dp(activity, 48));
        LinearLayout switchRow = new LinearLayout(activity);
        switchRow.setTag(SettingsUi.TAG_SEARCH_ROW, "Enable HTTP API");
        switchRow.addView(enabled, new LinearLayout.LayoutParams(-1, -2));
        listener.addView(switchRow);
        status = note(listener, "Stopped");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        host = field(listener, "Bind host", LocalApiConfig.DEFAULT.host,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        port = field(listener, "Port", Integer.toString(LocalApiConfig.DEFAULT.port), InputType.TYPE_CLASS_NUMBER);
        note(listener, "Apply changes explicitly. Applying while running restarts the listener.");
        apply = button(listener, "Apply configuration");
        apply.setTag(SettingsUi.TAG_SEARCH_ROW, "HTTP API apply configuration");
        apply.setOnClickListener(v -> {
            if (displayed == null) {
                submit(LocalApiSettingsModel.Action.LOAD, null);
                return;
            }
            LocalApiConfig config = validate();
            if (config != null) submit(LocalApiSettingsModel.Action.APPLY, config);
        });
        enabled.setOnCheckedChangeListener((view, checked) -> {
            if (!painting) submit(checked ? LocalApiSettingsModel.Action.START : LocalApiSettingsModel.Action.STOP, null);
        });

        token = note(auth, MASK);
        token.setContentDescription("Authentication token hidden");
        reveal = button(auth, "Reveal token");
        reveal.setOnClickListener(v -> { revealed = !revealed; paintToken(); });
        copyToken = button(auth, "Copy token");
        copyToken.setOnClickListener(v -> {
            LocalApiSettingsModel.State state = model.state();
            if (state.action == null && state.settings != null) copy("HTTP API token", state.settings.token, true);
        });
        rotate = button(auth, "Regenerate token");
        rotate.setOnClickListener(v -> confirmRotation());

        addressNote = note(addresses, "Start the server to see LAN URLs.");
        urls = new LinearLayout(activity);
        urls.setOrientation(LinearLayout.VERTICAL);
        addresses.addView(urls);
        LocalApiExtension extension = LocalApiRuntime.extension();
        extensionPage = extension == null ? null : extension.mount(activity, addresses);
        TextWatcher changes = new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            public void onTextChanged(CharSequence value, int start, int before, int count) {}
            public void afterTextChanged(Editable value) { if (!painting && visible) paint(); }
        };
        host.addTextChangedListener(changes);
        port.addTextChangedListener(changes);
        host.setOnFocusChangeListener((view, focused) -> { if (!focused && visible && !painting) validate(); });
        port.setOnFocusChangeListener((view, focused) -> { if (!focused && visible && !painting) validate(); });
    }

    void visible(boolean show) {
        if (visible == show) return;
        visible = show;
        addressEpoch++;
        addressDirty = show;
        ui.removeCallbacks(tick);
        if (!show) {
            if (confirmation != null) confirmation.dismiss();
            confirmation = null;
            resetNetwork();
            if (extensionPage != null) extensionPage.hidden();
            revealed = false;
            paintToken();
            displayed = null;
            return;
        }
        if (model.state().settings == null && model.state().action == null) submit(LocalApiSettingsModel.Action.LOAD, null);
        refresh();
    }

    private void refresh() {
        if (!visible) return;
        paint();
        updateAddresses();
        if (extensionPage != null) extensionPage.refresh(candidates);
        ui.postDelayed(tick, 250);
    }

    private void submit(LocalApiSettingsModel.Action action, LocalApiConfig config) {
        model.submit(action, config);
        paint();
    }

    private void paint() {
        LocalApiSettingsModel.State state = model.state();
        LocalApiConfig binding = model.binding();
        boolean busy = state.action != null || model.stopping();
        painting = true;
        try {
            if (state.settings != null && displayed != state.settings) {
                boolean configChanged = displayed == null
                    || !displayed.config.host.equals(state.settings.config.host)
                    || displayed.config.port != state.settings.config.port;
                if (configChanged) {
                    host.setText(state.settings.config.host);
                    port.setText(Integer.toString(state.settings.config.port));
                    host.setError(null);
                    port.setError(null);
                }
                if (displayed == null || !displayed.token.equals(state.settings.token)) revealed = false;
                displayed = state.settings;
            }
            boolean dirty = displayed != null && (!displayed.config.host.equals(host.getText().toString().trim())
                || !Integer.toString(displayed.config.port).equals(port.getText().toString().trim()));
            boolean ready = displayed != null && !busy;
            enabled.setChecked(binding != null);
            enabled.setEnabled(ready && (binding != null || !dirty));
            set(apply, displayed == null ? "Retry loading settings" : "Apply configuration");
            apply.setEnabled(!busy && (dirty || displayed == null));
            host.setEnabled(ready);
            port.setEnabled(ready);
            reveal.setEnabled(ready);
            copyToken.setEnabled(ready);
            rotate.setEnabled(ready);
            String label = binding != null ? "Running on " + binding.host + ":" + binding.port : "Stopped";
            if (state.action == LocalApiSettingsModel.Action.START) label = "Starting…";
            else if (state.action == LocalApiSettingsModel.Action.STOP || model.stopping()) label = "Stopping…";
            else if (state.action == LocalApiSettingsModel.Action.LOAD) label = "Loading settings…";
            else if (state.action != null) {
                label = binding == null && state.rebinding ? "Starting…" : label + " · Applying changes…";
            }
            else if (state.error != null) label = "Error · " + state.error + (binding == null ? " Server stopped." : " " + label + ".");
            else if (dirty) label += " · Apply changes to save.";
            set(status, label);
            paintToken();
        } finally {
            painting = false;
        }
    }

    private void paintToken() {
        boolean show = visible && revealed && displayed != null;
        set(token, show ? displayed.token : MASK);
        token.setContentDescription(show ? null : "Authentication token hidden");
        set(reveal, show ? "Hide token" : "Reveal token");
    }

    private LocalApiConfig validate() {
        String hostValue = host.getText().toString();
        String portValue = port.getText().toString();
        try {
            LocalApiSettingsModel.parse(hostValue, "8765");
            host.setError(null);
        } catch (IllegalArgumentException invalid) {
            host.setError(invalid.getMessage());
            return null;
        }
        try {
            LocalApiConfig config = LocalApiSettingsModel.parse(hostValue, portValue);
            port.setError(null);
            return config;
        } catch (IllegalArgumentException invalid) {
            port.setError(invalid.getMessage());
            return null;
        }
    }

    private void confirmRotation() {
        if (confirmation != null) return;
        confirmation = new AlertDialog.Builder(SettingsUi.dialogContext(activity))
            .setTitle("Regenerate HTTP API token?")
            .setMessage("Existing clients must use the new token. A running listener will restart.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Regenerate", (dialog, which) -> {
                revealed = false;
                submit(LocalApiSettingsModel.Action.ROTATE, null);
            }).create();
        confirmation.setOnDismissListener(dialog -> confirmation = null);
        confirmation.show();
    }

    private void updateAddresses() {
        LocalApiConfig binding = model.binding();
        if (binding != addressBinding) {
            addressEpoch++;
            addressBinding = binding;
            addressDirty = true;
            showUrls(Collections.emptyList());
        }
        if (binding == null) {
            resetNetwork();
            set(addressNote, "Start the server to see LAN URLs.");
            return;
        }
        if (!watchTried && connectivity != null) {
            watchTried = true;
            try {
                connectivity.registerNetworkCallback(new NetworkRequest.Builder().build(), network);
                watching = true;
            } catch (RuntimeException unavailable) {
                // A bounded periodic refresh still covers changes when registration is unavailable.
            }
        }
        if (++ticks >= 12) { ticks = 0; addressDirty = true; }
        if (!addressDirty || scanning) return;
        addressDirty = false;
        scanning = true;
        int epoch = addressEpoch;
        new Thread(() -> {
            List<String> found = LocalApiAddresses.candidates(binding, networkAddresses());
            ui.post(() -> {
                scanning = false;
                if (visible && !addressDirty && epoch == addressEpoch && binding == model.binding()) {
                    showUrls(found);
                    set(addressNote, found.isEmpty()
                        ? "No matching Wi-Fi or Ethernet IPv4 address. Check the bind address and network."
                        : "Candidate addresses for clients on this network.");
                }
            });
        }, "local-api-addresses").start();
    }

    private List<InetAddress> networkAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        if (connectivity == null) return addresses;
        try {
            for (Network net : connectivity.getAllNetworks()) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(net);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
                LinkProperties links = connectivity.getLinkProperties(net);
                if (links != null) for (LinkAddress link : links.getLinkAddresses()) addresses.add(link.getAddress());
            }
        } catch (RuntimeException unavailable) {
            addresses.clear();
        }
        return addresses;
    }

    private void resetNetwork() {
        if (watching) {
            try { connectivity.unregisterNetworkCallback(network); }
            catch (RuntimeException unavailable) { /* Callback is already unavailable. */ }
        }
        watching = false;
        watchTried = false;
        addressBinding = null;
        ticks = 0;
        showUrls(Collections.emptyList());
    }

    private void showUrls(List<String> values) {
        if (candidates.equals(values)) return;
        candidates = values;
        urls.removeAllViews();
        for (String base : values) {
            note(urls, base);
            button(urls, "Copy API base URL").setOnClickListener(v -> copyCurrent(base, false));
            button(urls, "Copy status endpoint").setOnClickListener(v -> copyCurrent(base, true));
        }
    }

    private void copyCurrent(String base, boolean statusEndpoint) {
        if (addressBinding == null || addressBinding != model.binding() || !candidates.contains(base)) return;
        copy("HTTP API URL", statusEndpoint ? base + "/status" : base, false);
    }

    private void copy(String label, String value, boolean sensitive) {
        copy(activity, label, value, sensitive);
    }

    static void copy(Activity activity, String label, String value, boolean sensitive) {
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        try {
            if (clipboard == null) throw new IllegalStateException();
            ClipData clip = ClipData.newPlainText(label, value);
            if (sensitive) {
                PersistableBundle flags = new PersistableBundle();
                flags.putBoolean("android.content.extra.IS_SENSITIVE", true);
                ClipDescription description = clip.getDescription();
                description.setExtras(flags);
            }
            clipboard.setPrimaryClip(clip);
            Toast.makeText(activity, "Copied " + label, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException unavailable) {
            Toast.makeText(activity, "Clipboard unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private EditText field(LinearLayout owner, String label, String defaultValue, int type) {
        TextView name = note(owner, label);
        EditText edit = new EditText(activity);
        edit.setId(View.generateViewId());
        name.setLabelFor(edit.getId());
        edit.setTag(SettingsUi.TAG_SEARCH_ROW, "HTTP API " + label);
        edit.setSingleLine(true);
        edit.setInputType(type);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        edit.setMinHeight(SettingsUi.dp(activity, 48));
        edit.setText(defaultValue);
        owner.addView(edit);
        return edit;
    }

    private TextView note(LinearLayout owner, String message) {
        TextView view = SettingsUi.text(activity, message, 13, SettingsUi.muted());
        int padding = SettingsUi.dp(activity, 8);
        view.setPadding(padding, padding, padding, padding);
        owner.addView(view);
        return view;
    }

    private Button button(LinearLayout owner, String label) {
        Button button = SettingsUi.button(activity, label);
        owner.addView(button);
        return button;
    }

    private static void set(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }
}
