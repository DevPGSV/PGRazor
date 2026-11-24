package dev.pgrazor;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

import io.netty.util.concurrent.Future;

/** Process-owned API controls. Explicit disk and bind operations are called off the UI thread. */
final class LocalApiController {
    interface Store {
        Settings load() throws IOException;
        void save(Settings settings) throws IOException;
    }

    static final class Settings {
        final LocalApiConfig config;
        final String token;

        Settings(LocalApiConfig config, String token) {
            this.config = config;
            this.token = token;
        }
    }

    private final Store store;
    private final Object stateLock = new Object();
    private volatile long generation;
    private LocalApiServer active;
    private LocalApiConfig bound;
    private Future<?> retiring;

    LocalApiController(Store store) {
        this.store = store;
    }

    synchronized Settings settings() throws IOException {
        Settings saved = store.load();
        if (saved != null) return saved;
        Settings defaults = new Settings(LocalApiConfig.DEFAULT, newToken());
        store.save(defaults);
        return defaults;
    }

    void start() throws IOException {
        start(generation);
    }

    long generation() { return generation; }

    synchronized void start(long epoch) throws IOException {
        synchronized (stateLock) {
            if (generation != epoch) throw new IOException("Local API start was cancelled");
            if (active != null) return;
        }
        bind(settings(), epoch);
    }

    synchronized void reconfigure(LocalApiConfig config) throws IOException {
        long epoch = generation;
        replace(new Settings(config, settings().token), epoch);
    }

    synchronized void rotateToken() throws IOException {
        long epoch = generation;
        replace(new Settings(settings().config, newToken()), epoch);
    }

    private void replace(Settings updated, long epoch) throws IOException {
        store.save(updated);
        synchronized (stateLock) {
            if (generation != epoch || active == null) return;
            invalidateLocked();
            epoch = generation;
        }
        bind(updated, epoch);
    }

    private void bind(Settings settings, long epoch) throws IOException {
        Future<?> previous;
        synchronized (stateLock) {
            previous = retiring;
        }
        if (previous != null) previous.awaitUninterruptibly();
        if (generation != epoch) throw new IOException("Local API start was cancelled");
        LocalApiServer bound = LocalApiServer.start(settings.config.socketAddress(), settings.token,
            LocalApiRoutes.production(), () -> generation == epoch);
        synchronized (stateLock) {
            if (generation == epoch) {
                retiring = null;
                active = bound;
                this.bound = settings.config;
                return;
            }
        }
        bound.close();
        throw new IOException("Local API start was cancelled");
    }

    LocalApiConfig binding() {
        synchronized (stateLock) {
            return bound;
        }
    }

    boolean isStopping() {
        synchronized (stateLock) {
            return retiring != null && !retiring.isDone();
        }
    }

    boolean isRunning() {
        synchronized (stateLock) {
            return active != null;
        }
    }

    /** Also invalidates the lease for any in-flight bind or response. No private snapshots exist yet. */
    void stop() {
        synchronized (stateLock) {
            invalidateLocked();
        }
    }

    private void invalidateLocked() {
        generation++;
        if (active != null) {
            retiring = active.stopAsync();
            active = null;
            bound = null;
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
