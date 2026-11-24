package dev.pgrazor;

import java.io.IOException;
import java.util.Locale;

/** Serializes explicit settings operations across all Activity instances. No idle worker. */
final class LocalApiSettingsModel {
    enum Action { LOAD, START, STOP, APPLY, ROTATE }

    static final class State {
        final Action action;
        final LocalApiController.Settings settings;
        final String error;
        final boolean rebinding;

        State(Action action, LocalApiController.Settings settings, String error, boolean rebinding) {
            this.action = action;
            this.settings = settings;
            this.error = error;
            this.rebinding = rebinding;
        }
    }

    private final LocalApiController controller;
    private volatile State state = new State(null, null, null, false);

    LocalApiSettingsModel(LocalApiController controller) {
        this.controller = controller;
    }

    State state() { return state; }
    LocalApiConfig binding() { return controller.binding(); }
    boolean stopping() { return controller.isStopping(); }

    static LocalApiConfig parse(String host, String port) {
        String value = port.trim();
        if (!value.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Enter a port from 1024 to 65535.");
        return new LocalApiConfig(host.trim(), Integer.parseInt(value));
    }

    synchronized boolean submit(Action action, LocalApiConfig config) {
        if (state.action != null) return false;
        long epoch = controller.generation();
        LocalApiController.Settings previous = state.settings;
        state = new State(action, previous, null,
            (action == Action.APPLY || action == Action.ROTATE) && controller.binding() != null);
        new Thread(() -> run(action, config, epoch, previous), "local-api-settings").start();
        return true;
    }

    private void run(Action action, LocalApiConfig config, long epoch, LocalApiController.Settings settings) {
        String error = null;
        try {
            switch (action) {
                case START: controller.start(epoch); break;
                case STOP: controller.stop(); break;
                case APPLY: controller.reconfigure(config); break;
                case ROTATE: controller.rotateToken(); break;
                case LOAD: break;
            }
            settings = controller.settings();
        } catch (IOException | RuntimeException | LinkageError fault) {
            error = message(fault, action);
            try {
                settings = controller.settings();
            } catch (IOException | RuntimeException unavailable) {
                // Keep the last successfully read settings available for display.
            }
        }
        state = new State(null, settings, error, false);
    }

    private static String message(Throwable fault, Action action) {
        boolean bindFailure = false;
        for (Throwable cause = fault; cause != null; cause = cause.getCause()) {
            String detail = cause.getMessage();
            if (detail == null) continue;
            String lower = detail.toLowerCase(Locale.ROOT);
            if (lower.contains("address already in use") || lower.contains("eaddrinuse")) return "Port is already in use.";
            if (lower.contains("cannot assign requested address") || lower.contains("eaddrnotavail")) return "Bind address is unavailable on this phone.";
            if (lower.contains("cancelled")) return "The server start was cancelled.";
            if (lower.contains("could not bind")) bindFailure = true;
        }
        if (bindFailure) return "Could not bind the server. Check the address and port.";
        if (fault instanceof LinkageError) return "HTTP transport is unavailable on this device.";
        return action == Action.LOAD ? "Could not read or create HTTP API settings."
            : "Could not complete the operation. Check settings storage and try again.";
    }
}
