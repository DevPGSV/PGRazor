package dev.pgrazor;

/** Copied scalar values from the existing account-owner PlayerStats observation. */
final class ResearchProgression {
    static final class Values {
        final long lease, xp;
        final int level;
        final Long previous, next;

        Values(long lease, int level, long xp, long previous, long next) {
            this.lease = lease;
            this.level = level;
            this.xp = xp;
            this.previous = previous < 0 ? null : previous;
            this.next = next < 0 ? null : next;
        }
    }

    private final LocalApiData data;
    private final LocalApiData.Slot<Values> slot;
    private volatile long activeLease;

    ResearchProgression(LocalApiData data) {
        this.data = data;
        slot = data == null ? null : data.slot();
    }

    void setLease(long lease, long deadline) {
        activeLease = lease;
        try {
            ResearchDiscoveryNative.setProgressionLease(lease, deadline);
        } catch (LinkageError unavailable) {
            // The optional native bridge may be unavailable, including JVM fixtures.
        }
    }

    boolean accept(String packet) {
        if (slot == null || packet == null || packet.length() > 1200) return false;
        String[] fields = packet.split("\\|", -1);
        if (fields.length != 7) return false;
        try {
            long observed = Long.parseLong(fields[0]);
            String id = LocalApiTrainer.utf16(fields[1], 256);
            long lease = Long.parseLong(fields[2]);
            int level = Integer.parseInt(fields[3]);
            long xp = Long.parseLong(fields[4]);
            long previous = Long.parseLong(fields[5]);
            long next = Long.parseLong(fields[6]);
            if (observed <= 0 || lease <= 0 || lease != activeLease || level <= 0 || xp <= 0
                    || previous < -1 || previous > xp || next < -1 || next == 0
                    || next > 0 && previous >= next) return false;
            return data.publish(slot, id, observed, LocalApiData.Coverage.PARTIAL,
                new Values(lease, level, xp, previous, next));
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    LocalApiData.Snapshot<Values> latest() {
        if (slot == null) return null;
        LocalApiData.Snapshot<Values> snapshot = data.latest(slot);
        return snapshot != null && snapshot.data.lease == activeLease && data.fresh(snapshot) ? snapshot : null;
    }
}
