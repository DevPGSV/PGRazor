package dev.pgrazor;

import android.app.Application;

/** Compatibility hooks for the controls-only Root DEX. */
final class LocalApiRuntime {
    static void init(Application app) {}
    static void invalidate() {}
}
