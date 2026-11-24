package dev.pgrazor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Volatile-in-memory publication boundary for private local resources. */
final class LocalApiData {
    enum Coverage { COMPLETE, PARTIAL, UNKNOWN }

    static final class Snapshot<T> {
        final String id;
        final String session;
        final long observedMs;
        final Instant observedUtc;
        final Coverage coverage;
        final T data;

        Snapshot(String id, String session, long observedMs, Instant observedUtc, Coverage coverage, T data) {
            this.id = id;
            this.session = session;
            this.observedMs = observedMs;
            this.observedUtc = observedUtc;
            this.coverage = coverage;
            this.data = data;
        }
    }

    static final class Slot<T> {
        private Snapshot<T> latest;
    }

    static final long SNAPSHOT_MAX_AGE_MS = 7_000;
    private static final long IDENTITY_MAX_AGE_MS = 10_000;
    private final List<Slot<?>> slots = new ArrayList<>();
    final Slot<LocalApiTrainer> trainer = slot();

    private <T> Slot<T> slot() {
        Slot<T> slot = new Slot<>();
        slots.add(slot);
        return slot;
    }
    private final LongSupplier monotonic;
    private final LongSupplier wall;
    private boolean enabled;
    private String identity;
    private String generation;
    private long minimumObservation;
    private long identityObservation = -1;
    private long revision;
    private long eventEpoch;

    LocalApiData(LongSupplier monotonic, LongSupplier wall) {
        this.monotonic = monotonic;
        this.wall = wall;
    }

    synchronized void enable() {
        enabled = true;
        loseIdentity();
    }

    synchronized void disable() {
        enabled = false;
        loseIdentity();
    }

    synchronized boolean enabled() { return enabled; }

    synchronized boolean requested() {
        if (identity != null) expireIdentity(monotonic.getAsLong());
        return enabled && identity != null;
    }

    synchronized void seeIdentity(String id, boolean verified) {
        if (!enabled) return;
        if (!id.equals(identity) || !verified) {
            loseIdentity();
            if (verified) {
                identity = id;
                generation = UUID.randomUUID().toString();
            }
        }
    }

    synchronized void loseIdentity() {
        if (identity != null) eventEpoch++;
        identity = null;
        generation = null;
        identityObservation = -1;
        for (Slot<?> slot : slots) slot.latest = null;
        minimumObservation = monotonic.getAsLong();
    }

    synchronized <T> boolean publish(Slot<T> slot, String id, long observed, Coverage coverage, T value) {
        long now = monotonic.getAsLong();
        expireIdentity(now);
        if (!enabled || identity == null || !identity.equals(id) || observed <= minimumObservation
                || observed > now || now - observed > SNAPSHOT_MAX_AGE_MS
                || (slot.latest != null && observed <= slot.latest.observedMs)) return false;
        identityObservation = Math.max(identityObservation, observed);
        slot.latest = new Snapshot<>(generation + "-" + (++revision), generation, observed,
            Instant.ofEpochMilli(wall.getAsLong() - (now - observed)), coverage, value);
        return true;
    }

    synchronized String session() {
        expireIdentity(monotonic.getAsLong());
        return identityObservation < 0 ? null : generation;
    }

    synchronized <T> Snapshot<T> latest(Slot<T> slot) {
        String session = session();
        return session != null && slot.latest != null && session.equals(slot.latest.session) ? slot.latest : null;
    }

    boolean fresh(Snapshot<?> snapshot) {
        long age = monotonic.getAsLong() - snapshot.observedMs;
        return age >= 0 && age <= SNAPSHOT_MAX_AGE_MS;
    }

    synchronized long eventEpoch() {
        session();
        return eventEpoch;
    }

    synchronized LocalApiWebSocket.State eventState() {
        String session = session();
        Snapshot<LocalApiTrainer> snapshot = latest(trainer);
        String snapshotId = snapshot != null && fresh(snapshot) ? snapshot.id : null;
        Map<String, String> topics = new LinkedHashMap<>();
        topics.put("server.status", "{\"status\":\"running\"}");
        topics.put("session.changed", "{\"account_ready\":" + (session != null) + "}");
        topics.put("trainer.changed", "{\"ready\":" + (snapshotId != null)
            + ",\"snapshot_id\":" + LocalApiEventProtocol.nullable(snapshotId) + "}");
        return new LocalApiWebSocket.State(eventEpoch, session, topics);
    }

    synchronized boolean ifCurrent(String expected, Snapshot<?> snapshot, Runnable enqueue) {
        if (!expected.equals(session()) || (snapshot != null && !fresh(snapshot))) return false;
        enqueue.run();
        return true;
    }

    private void expireIdentity(long now) {
        if (identityObservation >= 0 && now - identityObservation > IDENTITY_MAX_AGE_MS) loseIdentity();
    }
}
