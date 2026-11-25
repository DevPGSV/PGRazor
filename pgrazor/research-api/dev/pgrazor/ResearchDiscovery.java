package dev.pgrazor;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Structural observations only. Each result belongs to the Research lease that submitted it. */
final class ResearchDiscovery {
    private static final int MAX_JOBS = 8;
    private static final int MAX_RESULT = 8192;
    private static final long DEADLINE = 2500;
    private static final long RETAIN = 30_000;

    interface Observer { String observe(Query query); }

    static final class Query {
        final String operation, assembly, namespace, name, kind, filter;
        final int offset, limit;

        Query(Map<String, String> args) {
            operation = take(args, "op", "");
            assembly = take(args, "assembly", "");
            namespace = take(args, "ns", "");
            name = take(args, "name", "");
            kind = take(args, "kind", "methods");
            filter = take(args, "filter", "");
            offset = integer(take(args, "offset", "0"), 0, 51_200_000);
            limit = integer(take(args, "limit", "10"), 1, 20);
            boolean scope = !assembly.isEmpty() || !namespace.isEmpty() || !name.isEmpty();
            if (!args.isEmpty()
                    || !(operation.equals("support") || operation.equals("assemblies")
                         || operation.equals("search") || operation.equals("type"))
                    || !(kind.equals("fields") || kind.equals("properties")
                         || kind.equals("methods") || kind.equals("interfaces"))
                    || (operation.equals("type") && (namespace.isEmpty() || name.isEmpty() || offset > 1024))
                    || (operation.equals("search") && !scope)
                    || (operation.equals("assemblies") && (scope || offset > 256))
                    || (operation.equals("support") && (scope || offset != 0))
                    || (!operation.equals("type") && (!filter.isEmpty() || !kind.equals("methods")))) {
                throw new IllegalArgumentException();
            }
        }

        String observe() {
            return ResearchDiscoveryNative.query(operation, assembly, namespace, name, kind, filter, offset, limit);
        }
    }

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final String owner;
        final BooleanSupplier valid;
        final long deadline, expires;
        String state = "queued";
        String result = "null";
        String error = "null";
        Runnable task;

        Job(String owner, BooleanSupplier valid, long now) {
            this.owner = owner;
            this.valid = valid;
            deadline = now + DEADLINE;
            expires = now + RETAIN;
        }

        boolean open() { return state.equals("queued") || state.equals("running"); }

        String json(boolean full) {
            return "{\"id\":\"" + id + "\",\"state\":\"" + state + "\",\"error\":" + error
                + (full ? ",\"result\":" + result : "") + "}";
        }
    }

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Observer observer;
    private final LongSupplier clock;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4), task -> {
            Thread thread = new Thread(task, "research-metadata");
            thread.setDaemon(true);
            return thread;
        });

    ResearchDiscovery(LongSupplier clock, Observer observer) {
        this.clock = clock;
        this.observer = observer;
        worker.allowCoreThreadTimeOut(true);
    }

    synchronized LocalApiRoutes.Reply route(String path, boolean post, String owner, BooleanSupplier valid) {
        reap();
        int split = path.indexOf('?');
        String endpoint = split < 0 ? path : path.substring(0, split);
        if (!(endpoint.equals("discover") || endpoint.equals("job") || endpoint.equals("cancel"))) {
            return error(404, "NOT_FOUND");
        }
        if (post != !endpoint.equals("job")) return error(405, "METHOD_NOT_ALLOWED");
        try {
            Map<String, String> args = params(split < 0 ? "" : path.substring(split + 1));
            if (endpoint.equals("discover")) return submit(new Query(args), owner, valid);
            String id = args.remove("id");
            if (id == null || !args.isEmpty()) return error(400, "INVALID_QUERY");
            Job job = jobs.get(id);
            if (job == null || !job.owner.equals(owner)) return error(404, "JOB_NOT_FOUND");
            if (post) {
                if (job.open()) cancel(job, "cancelled", "cancelled_by_client");
            }
            return new LocalApiRoutes.Reply(200, job.json(!post));
        } catch (IllegalArgumentException invalid) {
            return error(400, "INVALID_QUERY");
        }
    }

    private LocalApiRoutes.Reply submit(Query query, String owner, BooleanSupplier valid) {
        if (!valid.getAsBoolean()) return error(401, "UNAUTHORIZED");
        if (jobs.size() >= MAX_JOBS) {
            Iterator<Job> iterator = jobs.values().iterator();
            while (iterator.hasNext()) {
                if (!iterator.next().open()) {
                    iterator.remove();
                    break;
                }
            }
            if (jobs.size() >= MAX_JOBS) return error(429, "JOB_LIMIT");
        }
        Job job = new Job(owner, valid, clock.getAsLong());
        job.task = () -> execute(job, query);
        jobs.put(job.id, job);
        try {
            worker.execute(job.task);
            return new LocalApiRoutes.Reply(202, job.json(false));
        } catch (RejectedExecutionException full) {
            jobs.remove(job.id);
            return error(429, "QUEUE_LIMIT");
        }
    }

    private void execute(Job job, Query query) {
        synchronized (this) {
            reap();
            if (!job.open() || !job.valid.getAsBoolean()) return;
            job.state = "running";
        }
        String result;
        try {
            result = observer.observe(query);
        } catch (LinkageError unavailable) {
            result = "{\"availability\":\"unavailable\",\"reason\":\"native_bridge_unavailable\",\"provenance\":\"il2cpp_runtime\"}";
        } catch (RuntimeException failure) {
            result = null;
        }
        synchronized (this) {
            reap();
            if (!job.open() || !job.valid.getAsBoolean()) return;
            if (result == null || result.length() > MAX_RESULT) {
                cancel(job, "failed", "invalid_observation");
            } else {
                job.state = "completed";
                job.result = result;
            }
        }
    }

    private void cancel(Job job, String state, String code) {
        worker.remove(job.task);
        job.state = state;
        job.error = "\"" + code + "\"";
        job.result = "null";
    }

    private void reap() {
        long now = clock.getAsLong();
        Iterator<Job> iterator = jobs.values().iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (!job.valid.getAsBoolean() || now >= job.expires) {
                cancel(job, "cancelled", "lease_ended");
                iterator.remove();
            } else if (job.open() && now >= job.deadline) {
                cancel(job, "failed", "job_deadline");
            }
        }
    }

    synchronized String topic(String owner) {
        reap();
        StringBuilder states = new StringBuilder("{\"jobs\":[");
        boolean comma = false;
        for (Job job : jobs.values()) {
            if (!job.owner.equals(owner)) continue;
            if (comma) states.append(',');
            states.append(job.json(false));
            comma = true;
        }
        return states.append("]}").toString();
    }

    private static String take(Map<String, String> args, String key, String fallback) {
        String value = args.remove(key);
        return value == null ? fallback : value;
    }

    private static int integer(String input, int min, int max) {
        if (!input.matches("[0-9]{1,8}")) throw new IllegalArgumentException();
        int value = Integer.parseInt(input);
        if (value < min || value > max) throw new IllegalArgumentException();
        return value;
    }

    private static Map<String, String> params(String query) {
        if (query.isEmpty() || query.length() > 512) throw new IllegalArgumentException();
        Map<String, String> args = new LinkedHashMap<>();
        for (String entry : query.split("&", -1)) {
            String[] pair = entry.split("=", -1);
            if (pair.length != 2 || !pair[0].matches("[a-z]{1,10}")
                    || !pair[1].matches("[A-Za-z0-9_.`-]{1,96}")
                    || args.put(pair[0], pair[1]) != null) throw new IllegalArgumentException();
        }
        return args;
    }

    private static LocalApiRoutes.Reply error(int status, String code) {
        return new LocalApiRoutes.Reply(status, "{\"error\":\"" + code + "\"}");
    }
}
