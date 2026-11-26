package dev.pgrazor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Retains one private capture per active Research lease. All rows are copied from native packets. */
final class ResearchPokemonExport {
    private static final int ROWS = 2048, SLICE_ROWS = 32, BYTES = 768 * 1024;
    private static final long DEADLINE = 35_000, RETENTION = 120_000;
    private static final AtomicLong SEQUENCE = new AtomicLong();

    interface Demand { void set(long revision, long lease, long deadline); }

    private static final class Capture {
        final String id = UUID.randomUUID().toString();
        final long revision = SEQUENCE.incrementAndGet();
        final String account;
        final long deadline;
        final int pageSize;
        final List<ResearchPokemonBag.Row> rows = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        String state = "capturing", reason;
        long started = -1, finished, expires, sliceTotal;
        Instant startedUtc, finishedUtc;
        int expected, bytes, slices, maxSlice, lastSlice, eggs;

        Capture(String account, long deadline, int pageSize) {
            this.account = account;
            this.deadline = deadline;
            this.pageSize = pageSize;
        }
    }

    private final LocalApiData data;
    private final LongSupplier clock, wall;
    private final Demand demand;
    private Capture capture;
    private long lease;
    private long attempts, completed, discarded, cancelled, deadlines, structuralChanges;

    ResearchPokemonExport(LocalApiData data, LongSupplier clock, LongSupplier wall, Demand demand) {
        this.data = data;
        this.clock = clock;
        this.wall = wall;
        this.demand = demand;
    }

    synchronized void leaseChanged(long value) {
        if (lease == value) return;
        if (capture != null && "capturing".equals(capture.state)) retire("cancelled", "lease_retired");
        capture = null;
        lease = value;
    }

    private void expire(long now) {
        if (capture == null) return;
        if (!capture.account.equals(data.session())) {
            if ("capturing".equals(capture.state)) retire("cancelled", "account_changed");
            capture = null;
        } else if ("capturing".equals(capture.state) && now >= capture.deadline) {
            retire("failed", "deadline");
        } else if (!"capturing".equals(capture.state) && now >= capture.expires) {
            capture = null;
        }
    }

    private void retire(String state, String reason) {
        demand.set(0, 0, 0);
        capture.state = state;
        capture.reason = reason;
        capture.expires = clock.getAsLong() + RETENTION;
        capture.rows.clear();
        capture.ids.clear();
        if ("cancelled".equals(state)) cancelled++;
        else discarded++;
        if ("deadline".equals(reason)) deadlines++;
        if ("structure_changed".equals(reason)) structuralChanges++;
    }

    synchronized void accept(String packet) {
        long now = clock.getAsLong();
        expire(now);
        if (capture == null || !"capturing".equals(capture.state) || packet.length() > 12_288) return;
        String[] p = packet.split("\\|", 11);
        try {
            if (p.length < 6 || Long.parseLong(p[1]) != capture.revision || Long.parseLong(p[2]) != lease) return;
            if (!data.verifiedOwner(capture.account, LocalApiTrainer.utf16(p[3], 256))) {
                retire("cancelled", "account_changed");
                return;
            }
            if ("failed".equals(p[4]) && p.length == 6) {
                String reason = p[5];
                if (!reason.matches("structure_changed|slice_budget|source_unavailable_or_over_limit|invalid_source"))
                    throw new IllegalArgumentException();
                retire("failed", reason);
                return;
            }
            boolean done = "done".equals(p[4]);
            if (p.length != 11 || (!done && !"slice".equals(p[4]))) throw new IllegalArgumentException();
            int count = Integer.parseInt(p[5]), offset = Integer.parseInt(p[6]);
            long start = Long.parseLong(p[7]), end = Long.parseLong(p[8]);
            int duration = Integer.parseInt(p[9]);
            String[] rows = p[10].split(";", -1);
            if (start < capture.deadline - DEADLINE || end < start || end > now || end >= capture.deadline
                    || duration < 0 || duration >= 12 || count < 1 || count > ROWS
                    || offset != capture.rows.size() || rows.length > SLICE_ROWS
                    || offset + rows.length > count || done != (offset + rows.length == count)
                    || (capture.started >= 0 && (capture.started != start || capture.expected != count || end < capture.finished))
                    || capture.bytes + p[10].length() > BYTES)
                throw new IllegalArgumentException();
            if (capture.started < 0) {
                capture.started = start;
                capture.startedUtc = Instant.ofEpochMilli(wall.getAsLong() - (now - start));
                capture.expected = count;
            }
            for (String encoded : rows) {
                ResearchPokemonBag.Row row = new ResearchPokemonBag.Row(encoded);
                if (!capture.ids.add(row.instanceId)) throw new IllegalArgumentException();
                capture.rows.add(row);
                if (row.egg) capture.eggs++;
            }
            capture.bytes += p[10].length();
            capture.finished = end;
            capture.lastSlice = duration;
            capture.sliceTotal += duration;
            capture.maxSlice = Math.max(capture.maxSlice, duration);
            capture.slices++;
            if (done) {
                demand.set(0, 0, 0);
                capture.finishedUtc = Instant.ofEpochMilli(capture.startedUtc.toEpochMilli() + end - start);
                capture.expires = now + RETENTION;
                capture.ids.clear();
                capture.state = "completed";
                completed++;
            }
        } catch (IllegalArgumentException badPacket) {
            retire("failed", "invalid_or_missing_slice");
        }
    }

    synchronized LocalApiRoutes.Reply route(String path, boolean post) {
        long now = clock.getAsLong();
        expire(now);
        if (lease == 0) return error(401, "research_inactive");
        if (post && path.matches("start\\?page_size=([1-9]|10)")) {
            if (capture != null && "capturing".equals(capture.state)) return error(409, "capture_in_progress");
            String account = data.session();
            if (account == null) return error(409, "verified_account_unavailable");
            int size = Integer.parseInt(path.substring(path.indexOf('=') + 1));
            capture = new Capture(account, now + DEADLINE, size);
            attempts++;
            try {
                demand.set(capture.revision, lease, capture.deadline);
            } catch (LinkageError unavailable) {
                capture = null;
                return error(503, "native_capture_unavailable");
            }
            return reply(202, status(), account);
        }
        if (!post && path.equals("diagnostics")) {
            return reply(200, "{\"attempts\":" + attempts + ",\"completed\":" + completed
                + ",\"discarded\":" + discarded + ",\"cancelled\":" + cancelled
                + ",\"deadlines\":" + deadlines + ",\"structural_changes\":" + structuralChanges + "}", null);
        }
        if (capture == null) return error(404, "snapshot_unavailable");
        if (post && path.equals("cancel?id=" + capture.id) && "capturing".equals(capture.state))
            retire("cancelled", "requested");
        else if (!post && path.startsWith("page?cursor="))
            return page(path.substring("page?cursor=".length()));
        else if (post || !path.equals("status?id=" + capture.id))
            return error(404, "snapshot_unavailable");
        return reply(200, status(), capture.account);
    }

    private String status() {
        boolean done = "completed".equals(capture.state);
        return "{\"id\":\"" + capture.id + "\",\"revision\":\"" + capture.revision + "\",\"state\":\""
            + capture.state + "\",\"reason\":" + (capture.reason == null ? "null" : "\"" + capture.reason + "\"")
            + ",\"source_entry_count\":" + (capture.expected == 0 ? "null" : capture.expected)
            + ",\"captured_entry_count\":" + capture.rows.size() + ",\"slice_count\":" + capture.slices
            + ",\"copied_bytes\":" + capture.bytes + ",\"last_slice_ms\":" + capture.lastSlice
            + ",\"max_slice_ms\":" + capture.maxSlice + ",\"slice_total_ms\":" + capture.sliceTotal
            + ",\"cursor\":" + (done ? "\"" + cursor(0) + "\"" : "null") + "}";
    }

    private String cursor(int index) {
        return capture.id + "." + capture.revision + "." + index;
    }

    private LocalApiRoutes.Reply page(String token) {
        if (!"completed".equals(capture.state)) return error(409, "snapshot_not_complete");
        String prefix = capture.id + "." + capture.revision + ".";
        if (!token.startsWith(prefix) || !token.substring(prefix.length()).matches("0|[1-9][0-9]{0,3}"))
            return error(400, "invalid_cursor");
        int index = Integer.parseInt(token.substring(prefix.length()));
        if (index >= capture.rows.size() || index % capture.pageSize != 0) return error(400, "invalid_cursor");
        int end = Math.min(index + capture.pageSize, capture.rows.size());
        StringBuilder out = new StringBuilder("{\"snapshot_id\":\"").append(capture.id)
            .append("\",\"revision\":\"").append(capture.revision)
            .append("\",\"coverage\":\"complete\",\"collection_complete\":true,\"structural_consistency\":\"verified\",")
            .append("\"consistency\":\"non_atomic\",\"attribute_completeness\":\"nullable\",\"immutable\":true,\"freshness\":\"retained\",")
            .append("\"capture_started_at\":\"").append(capture.startedUtc)
            .append("\",\"capture_finished_at\":\"").append(capture.finishedUtc)
            .append("\",\"capture_elapsed_ms\":").append(capture.finished - capture.started)
            .append(",\"source_entry_count\":").append(capture.expected)
            .append(",\"captured_entry_count\":").append(capture.rows.size())
            .append(",\"classified_egg_rows\":").append(capture.eggs)
            .append(",\"classified_pokemon_rows\":").append(capture.rows.size() - capture.eggs)
            .append(",\"offset\":").append(index).append(",\"next_cursor\":")
            .append(end == capture.rows.size() ? "null" : "\"" + cursor(end) + "\"").append(",\"items\":[");
        for (int i = index; i < end; i++) {
            if (i != index) out.append(',');
            out.append(ResearchObservations.pokemonRow(capture.rows.get(i)));
        }
        String body = out.append("]}").toString();
        return body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 8192
            ? reply(200, body, capture.account) : error(500, "page_size_exceeded");
    }

    private static LocalApiRoutes.Reply error(int code, String reason) {
        return reply(code, "{\"error\":\"" + reason + "\"}", null);
    }

    private static LocalApiRoutes.Reply reply(int code, String body, String account) {
        return new LocalApiRoutes.Reply(code, body, account, null);
    }
}
