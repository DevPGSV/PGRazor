package dev.pgrazor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.function.LongSupplier;

/** In-memory authorization lease. No executor or timer is needed for expiry. */
final class ResearchApiSession {
    static final int DEFAULT_MINUTES = 30;
    private final LongSupplier clock;
    private final LongSupplier accountEpoch;
    private boolean listener;
    private Lease lease;

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
        this.clock = clock;
        this.accountEpoch = accountEpoch;
    }

    synchronized void listenerChanged(boolean running) {
        listener = running;
        if (!running) lease = null;
    }

    synchronized boolean listener() { return listener; }

    synchronized void issue(int minutes) {
        switch (minutes) {
            case 15: case 30: case 60: case 120: case 360: case 720: case 1440: break;
            default: throw new IllegalArgumentException("Unsupported research token lifetime");
        }
        if (!listener) return;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        lease = new Lease(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            clock.getAsLong() + minutes * 60_000L, accountEpoch.getAsLong(), java.util.UUID.randomUUID().toString());
    }

    synchronized void rotate(Lease expected, int minutes) {
        if (expected != null && current() == expected) issue(minutes);
    }

    synchronized void revoke() { lease = null; }

    synchronized Lease current() {
        if (lease != null && (clock.getAsLong() >= lease.deadline || accountEpoch.getAsLong() != lease.account)) {
            lease = null;
        }
        return lease;
    }

    synchronized long remaining(Lease expected) {
        return expected == current() && expected != null ? Math.max(0, expected.deadline - clock.getAsLong()) : 0;
    }

    synchronized Lease authenticate(List<String> headers) {
        Lease active = current();
        return active != null && headers.size() == 1 && MessageDigest.isEqual(active.authorization,
            headers.get(0).getBytes(StandardCharsets.ISO_8859_1)) ? active : null;
    }
}
