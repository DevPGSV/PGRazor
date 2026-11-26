package dev.pgrazor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.function.LongSupplier;

/** Active lease is process-local. Saved credentials and activation have separate lifetimes. */
final class ResearchApiSession {
    static final int DEFAULT_MINUTES = 0;
    static final long PERMANENT_BOOT = -2;
    private final LongSupplier clock;
    private final LongSupplier accountEpoch;
    interface Store {
        Saved load() throws IOException;
        void save(Saved value) throws IOException;
        default void clearAfterFailure() {}
    }

    static final class Saved {
        final String token;
        final long boot, issued, deadline;

        Saved(String token, long boot, long issued, long deadline) {
            this.token = token;
            this.boot = boot;
            this.issued = issued;
            this.deadline = deadline;
        }
    }

    private final Store store;
    private final long boot;
    private Saved saved;
    private boolean loaded;
    private boolean storageFailed;
    private boolean listener;
    private Lease lease;
    private String lastVerifiedAccount;

    static final class Lease {
        final String token;
        final byte[] authorization;
        final long deadline;
        final long account;
        final String id;

        Lease(String token, long deadline, long account, String id) {
            this.token = token;
            authorization = ("Bearer " + token).getBytes(StandardCharsets.US_ASCII);
            this.deadline = deadline;
            this.account = account;
            this.id = id;
        }
    }

    ResearchApiSession(LongSupplier clock, LongSupplier accountEpoch) {
        this(clock, accountEpoch, null, -1);
    }

    ResearchApiSession(LongSupplier clock, LongSupplier accountEpoch, Store store, long boot) {
        this.clock = clock;
        this.accountEpoch = accountEpoch;
        this.store = store;
        this.boot = boot;
    }

    synchronized void listenerChanged(boolean running) {
        listener = running;
        if (!running) deactivate();
    }

    synchronized boolean listener() { return listener; }

    synchronized void accountVerified(String id) {
        if (lastVerifiedAccount != null && !lastVerifiedAccount.equals(id)) deactivate();
        lastVerifiedAccount = id;
    }

    synchronized void deactivate() { lease = null; }

    synchronized boolean storageFailed() { return storageFailed; }

    synchronized void activate(int minutes) {
        if (!listener || current() != null) return;
        if (!loaded && store != null) {
            loaded = true;
            try {
                saved = store.load();
            } catch (IOException | RuntimeException invalid) {
                revoke();
                storageFailed = true;
                return;
            }
        }
        long now = clock.getAsLong();
        if (saved != null && saved.token.matches("[A-Za-z0-9_-]{43}")
                && (saved.boot == PERMANENT_BOOT && saved.issued == 0 && saved.deadline == Long.MAX_VALUE
                    || (boot >= 0 || store == null) && saved.boot == boot && saved.issued >= 0
                        && saved.issued <= now && saved.deadline > now && saved.deadline - saved.issued <= 86_400_000L)) {
            lease = new Lease(saved.token, saved.deadline, accountEpoch.getAsLong(), java.util.UUID.randomUUID().toString());
        } else issue(minutes);
    }

    synchronized void issue(int minutes) {
        switch (minutes) {
            case 0: case 15: case 30: case 60: case 120: case 360: case 720: case 1440: break;
            default: throw new IllegalArgumentException("Unsupported research token lifetime");
        }
        if (!listener) return;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        long now = clock.getAsLong();
        Saved next = new Saved(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            minutes == 0 ? PERMANENT_BOOT : boot, minutes == 0 ? 0 : now,
            minutes == 0 ? Long.MAX_VALUE : now + minutes * 60_000L);
        lease = null;
        if (!persist(next)) return;
        lease = new Lease(next.token, next.deadline, accountEpoch.getAsLong(), java.util.UUID.randomUUID().toString());
    }

    synchronized void rotate(Lease expected, int minutes) {
        if (expected != null && current() == expected) issue(minutes);
    }

    synchronized void revoke() {
        lease = null;
        persist(null);
    }

    private boolean persist(Saved next) {
        saved = null;
        loaded = true;
        storageFailed = false;
        if (store != null) {
            try {
                store.save(next);
            } catch (IOException | RuntimeException failure) {
                try { store.clearAfterFailure(); }
                catch (RuntimeException ignored) { /* Access remains disabled in this process. */ }
                storageFailed = true;
                return false;
            }
        }
        saved = next;
        return true;
    }

    synchronized Lease current() {
        if (lease != null && (clock.getAsLong() >= lease.deadline
                || (saved != null && clock.getAsLong() < saved.issued)
                || accountEpoch.getAsLong() != lease.account)) {
            lease = null;
        }
        return lease;
    }

    synchronized long remaining(Lease expected) {
        return expected == current() && expected != null ? (expected.deadline == Long.MAX_VALUE ? -1 : Math.max(0, expected.deadline - clock.getAsLong())) : 0;
    }

    synchronized boolean ifActive(Lease expected, Runnable write) {
        if (current() != expected) return false;
        write.run();
        return true;
    }

    synchronized Lease authenticate(List<String> headers) {
        Lease active = current();
        return active != null && headers.size() == 1 && MessageDigest.isEqual(active.authorization,
            headers.get(0).getBytes(StandardCharsets.ISO_8859_1)) ? active : null;
    }
}
