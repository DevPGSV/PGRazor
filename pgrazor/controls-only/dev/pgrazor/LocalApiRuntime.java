package dev.pgrazor;

import android.app.Application;

/** Compatibility hooks for the controls-only Root DEX. */
final class LocalApiRuntime {
    static void init(Application app) {}
    static boolean trainerRequested() { return false; }
    static void accountObserved(String id, boolean verified) {}
    static void identityLost() {}
    static void productionPokemonObserved(String packet) {}
    static void pokemonBagObserved(String packet) {}

    static void progressionObserved(String packet) {}
    static void trainerObserved(String packet) {}
    static void invalidate() {}
}
