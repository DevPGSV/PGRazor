package dev.pgrazor;

/** A single copied backing-list entry. No assumptions about complete collection coverage. */
final class ResearchPokemonBag {
    static final class Values {
        final long lease;
        final String instanceId;
        final int entries;
        final Integer species, cp, attack, defense, stamina;
        final Boolean favorite;
        final boolean egg;

        Values(long lease, String instanceId, int entries, int species, int cp, int attack,
                int defense, int stamina, int favorite, boolean egg) {
            this.lease = lease;
            this.instanceId = instanceId;
            this.entries = entries;
            this.species = known(species);
            this.cp = known(cp);
            this.attack = known(attack);
            this.defense = known(defense);
            this.stamina = known(stamina);
            this.favorite = favorite < 0 ? null : favorite == 1;
            this.egg = egg;
        }

        private static Integer known(int value) { return value < 0 ? null : value; }
    }

    private final LocalApiData data;
    private final LocalApiData.Slot<Values> slot;
    private volatile long activeLease;

    ResearchPokemonBag(LocalApiData data) {
        this.data = data;
        slot = data == null ? null : data.slot();
    }

    void setLease(long lease) { activeLease = lease; }

    boolean accept(String packet) {
        if (slot == null || packet == null || packet.length() > 1200) return false;
        String[] fields = packet.split("\\|", -1);
        if (fields.length != 12) return false;
        try {
            long observed = Long.parseLong(fields[0]);
            String owner = LocalApiTrainer.utf16(fields[1], 256);
            long lease = Long.parseLong(fields[2]);
            int count = Integer.parseInt(fields[3]);
            long instance = Long.parseUnsignedLong(fields[4]);
            int species = Integer.parseInt(fields[5]);
            int cp = Integer.parseInt(fields[6]);
            int attack = Integer.parseInt(fields[7]);
            int defense = Integer.parseInt(fields[8]);
            int stamina = Integer.parseInt(fields[9]);
            int favorite = Integer.parseInt(fields[10]);
            int egg = Integer.parseInt(fields[11]);
            if (observed <= 0 || lease <= 0 || lease != activeLease || count <= 0 || count > 30000
                    || instance == 0 || species < -1 || species == 0 || cp < -1 || cp == 0
                    || !iv(attack) || !iv(defense) || !iv(stamina) || favorite < -1 || favorite > 1
                    || egg < 0 || egg > 1 || egg == 1 && (cp != -1 || attack != -1 || defense != -1 || stamina != -1))
                return false;
            return data.publish(slot, owner, observed, LocalApiData.Coverage.PARTIAL,
                new Values(lease, Long.toUnsignedString(instance), count, species, cp, attack, defense,
                           stamina, favorite, egg == 1));
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    private static boolean iv(int value) { return value >= -1 && value <= 15; }

    LocalApiData.Snapshot<Values> latest() {
        if (slot == null) return null;
        LocalApiData.Snapshot<Values> snapshot = data.latest(slot);
        return snapshot != null && snapshot.data.lease == activeLease && data.fresh(snapshot) ? snapshot : null;
    }
}
