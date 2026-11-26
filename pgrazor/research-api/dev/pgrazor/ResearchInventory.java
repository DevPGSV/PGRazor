package dev.pgrazor;

/** Raw, account-bound counters. No item enumeration or empty-bag assertion. */
final class ResearchInventory {
    static final class Values {
        final long lease;
        final int totalItems, maxItems, elapsed;
        Values(long lease, int totalItems, int maxItems, int elapsed) {
            this.lease = lease;
            this.totalItems = totalItems;
            this.maxItems = maxItems;
            this.elapsed = elapsed;
        }
    }

    private final LocalApiData data;
    private final LocalApiData.Slot<Values> slot;
    private volatile long lease;

    ResearchInventory(LocalApiData data) {
        this.data = data;
        slot = data == null ? null : data.slot();
    }

    void leaseChanged(long value) { lease = value; }

    boolean accept(String packet) {
        if (slot == null || packet == null || packet.length() > 1160) return false;
        String[] parts = packet.split("\\|", -1);
        if (parts.length != 6) return false;
        try {
            long observed = Long.parseLong(parts[0]);
            String owner = LocalApiTrainer.utf16(parts[1], 256);
            long active = Long.parseLong(parts[2]);
            int total = Integer.parseInt(parts[3]);
            int max = Integer.parseInt(parts[4]);
            int elapsed = Integer.parseInt(parts[5]);
            if (active <= 0 || active != lease || total < 0 || max <= 0 || elapsed < 0 || elapsed >= 8) return false;
            return data.publish(slot, owner, observed, LocalApiData.Coverage.PARTIAL,
                new Values(active, total, max, elapsed));
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    LocalApiData.Snapshot<Values> latest() {
        if (slot == null) return null;
        LocalApiData.Snapshot<Values> snapshot = data.latest(slot);
        return snapshot != null && snapshot.data.lease == lease && data.fresh(snapshot) ? snapshot : null;
    }
}
