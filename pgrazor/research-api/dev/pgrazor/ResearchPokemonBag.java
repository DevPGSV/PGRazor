package dev.pgrazor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable, bounded prefix copied from one backing list observation. */
final class ResearchPokemonBag {
    static final int MAX_SAMPLES = 10;

    static final class Row {
        final String instanceId, nickname;
        final Integer species, cp, attack, defense, stamina, hp, maxHp, fastMove, chargedMove, secondChargedMove;
        final Boolean favorite, lucky, shiny;
        final Integer form, costume, gender, alignment, breadMode;
        final boolean egg;
        final Double cpMultiplier;
        final Long creationTimeMs;

        Row(String packet) {
            String[] f = packet.split("\\|", -1);
            if (f.length != 8 && f.length != 17 && f.length != 23) throw new IllegalArgumentException();
            long instance = Long.parseUnsignedLong(f[0]);
            if (instance == 0) throw new IllegalArgumentException();
            instanceId = Long.toUnsignedString(instance);
            species = bounded(f[1], 1, Integer.MAX_VALUE);
            cp = bounded(f[2], 1, Integer.MAX_VALUE);
            attack = bounded(f[3], 0, 15);
            defense = bounded(f[4], 0, 15);
            stamina = bounded(f[5], 0, 15);
            favorite = flag(f[6]);
            Integer classified = bounded(f[7], 0, 1);
            if (classified == null) throw new IllegalArgumentException();
            egg = classified == 1;
            boolean extended = f.length >= 17;
            hp = extended ? bounded(f[8], 0, Integer.MAX_VALUE) : null;
            maxHp = extended ? bounded(f[9], 1, Integer.MAX_VALUE) : null;
            fastMove = extended ? bounded(f[10], 1, Integer.MAX_VALUE) : null;
            chargedMove = extended ? bounded(f[11], 1, Integer.MAX_VALUE) : null;
            secondChargedMove = extended ? bounded(f[12], 1, Integer.MAX_VALUE) : null;
            lucky = extended ? flag(f[13]) : null;
            double multiplier = extended ? Double.parseDouble(f[14]) : -1;
            long creation = extended ? Long.parseLong(f[15]) : -1;
            if ((multiplier != -1 && (!Double.isFinite(multiplier) || multiplier <= 0))
                    || (creation != -1 && creation <= 0)) throw new IllegalArgumentException();
            cpMultiplier = multiplier == -1 ? null : multiplier;
            creationTimeMs = creation == -1 ? null : creation;
            nickname = extended ? decodeNickname(f[16]) : null;
            boolean display = f.length == 23;
            shiny = display ? flag(f[17]) : null;
            form = display ? bounded(f[18], 0, Integer.MAX_VALUE) : null;
            costume = display ? bounded(f[19], 0, Integer.MAX_VALUE) : null;
            gender = display ? bounded(f[20], 0, Integer.MAX_VALUE) : null;
            alignment = display ? bounded(f[21], 0, Integer.MAX_VALUE) : null;
            breadMode = display ? bounded(f[22], 0, Integer.MAX_VALUE) : null;
            if (egg && (cp != null || attack != null || defense != null || stamina != null
                    || hp != null || maxHp != null || fastMove != null || chargedMove != null
                    || secondChargedMove != null || lucky != null || cpMultiplier != null
                    || shiny != null || form != null || costume != null || gender != null || alignment != null || breadMode != null))
                throw new IllegalArgumentException();
        }

        private static String decodeNickname(String hex) {
            if (hex.equals("-")) return null;
            if (hex.isEmpty()) return "";
            try {
                return LocalApiTrainer.utf16(hex, 32);
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }

        private static Integer bounded(String text, int lower, int upper) {
            int n = Integer.parseInt(text);
            if (n == -1) return null;
            if (n < lower || n > upper) throw new IllegalArgumentException();
            return n;
        }

        private static Boolean flag(String text) {
            Integer n = bounded(text, 0, 1);
            return n == null ? null : n == 1;
        }
    }

    static final class Values {
        final long lease;
        final int entries;
        final Integer captureElapsedMs;
        final List<Row> rows;

        Values(long lease, int entries, Integer captureElapsedMs, List<Row> rows) {
            this.lease = lease;
            this.entries = entries;
            this.captureElapsedMs = captureElapsedMs;
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        }
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
        if (slot == null || packet == null || packet.length() > 4096) return false;
        String[] fields = packet.split("\\|", 5);
        if (fields.length != 5) return false;
        try {
            long observed = Long.parseLong(fields[0]);
            String owner = LocalApiTrainer.utf16(fields[1], 256);
            long lease = Long.parseLong(fields[2]);
            String[] stats = fields[3].split(",", -1);
            if (stats.length > 2) return false;
            int count = Integer.parseInt(stats[0]);
            Integer elapsed = stats.length == 2 ? Integer.valueOf(stats[1]) : null;
            if (elapsed != null && (elapsed < 0 || elapsed >= 20)) return false;
            String[] packets = fields[4].split(";", -1);
            if (observed <= 0 || lease <= 0 || lease != activeLease || count <= 0 || count > 30000
                    || packets.length > Math.min(count, MAX_SAMPLES)) return false;
            List<Row> rows = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (String encoded : packets) {
                Row row = new Row(encoded);
                if (!ids.add(row.instanceId)) return false;
                rows.add(row);
            }
            return data.publish(slot, owner, observed, LocalApiData.Coverage.PARTIAL, new Values(lease, count, elapsed, rows));
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
