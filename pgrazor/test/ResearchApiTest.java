package dev.pgrazor;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

public final class ResearchApiTest {
    private static final String EVENTS = "/research/v1/events";
    private static final String STATUS = "/research/v1/status";
    private static final String PROD = "fixture-production";

    public static void main(String[] args) throws Exception {
        AtomicLong clock = new AtomicLong(100);
        LocalApiData data = new LocalApiData(clock::get, clock::get);
        ResearchApiSession session = new ResearchApiSession(clock::get, data::eventEpoch);
        ResearchApi module = new ResearchApi(session);
        session.issue(30);
        check(session.current() == null && !session.listener());

        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        LocalApiController controller = new LocalApiController(new LocalApiController.Store() {
            @Override public LocalApiController.Settings load() {
                return new LocalApiController.Settings(new LocalApiConfig("127.0.0.1", port), PROD);
            }
            @Override public void save(LocalApiController.Settings settings) { throw new AssertionError(); }
        }, data, module);
        controller.start();
        try {
            check(session.listener() && session.current() == null);
            check(request(port, STATUS, PROD).startsWith("HTTP/1.1 401"));
            session.issue(ResearchApiSession.DEFAULT_MINUTES);
            ResearchApiSession.Lease initial = session.current();
            check(initial != null && session.remaining(initial) == 1_800_000);
            check(request(port, STATUS, initial.token).contains("\"ready\":true"));
            String capabilities = request(port, "/research/v1/capabilities", initial.token);
            check(capabilities.contains("\"research_api\":true") && !capabilities.contains(initial.token));
            check(request(port, "/api/v1/status", initial.token).startsWith("HTTP/1.1 401"));
            check(request(port, STATUS + "?test=yes", initial.token).startsWith("HTTP/1.1 400"));
            try (LocalApiWebSocketTest.Wire denied = wire(port, EVENTS, PROD)) {
                check(denied.response.startsWith("HTTP/1.1 401"));
            }
            try (LocalApiWebSocketTest.Wire denied = wire(port, "/api/v1/events", initial.token)) {
                check(denied.response.startsWith("HTTP/1.1 401"));
            }
            try (LocalApiWebSocketTest.Wire production = wire(port, "/api/v1/events", PROD);
                    LocalApiWebSocketTest.Wire research = wire(port, EVENTS, initial.token)) {
                check(production.response.startsWith("HTTP/1.1 101") && production.text().contains("hello"));
                check(research.response.startsWith("HTTP/1.1 101") && research.text().contains(initial.id));
                research.command("{\"version\":1,\"id\":\"d\",\"op\":\"discover\"}");
                check(research.text().contains("\"topics\":[\"research.status\"]"));
                research.command("{\"version\":1,\"id\":\"s\",\"op\":\"subscribe\",\"topics\":[\"research.status\"]}");
                check(research.text().contains("\"type\":\"ack\""));
                check(research.text().contains("\"initial\":true"));
                session.rotate(initial, 15);
                ResearchApiSession.Lease rotated = session.current();
                check(rotated != initial && !rotated.token.equals(initial.token));
                check(research.opcode() == 8);
                check(request(port, STATUS, initial.token).startsWith("HTTP/1.1 401"));
                production.command("{\"version\":1,\"id\":\"p\",\"op\":\"discover\"}");
                check(production.text().contains("trainer.changed"));
                try (LocalApiWebSocketTest.Wire expiring = wire(port, EVENTS, rotated.token)) {
                    expiring.text();
                    clock.set(rotated.deadline - 1);
                    check(session.current() == rotated && session.remaining(rotated) == 1);
                    clock.incrementAndGet();
                    check(expiring.opcode() == 8 && session.current() == null);
                }
                check(request(port, STATUS, rotated.token).startsWith("HTTP/1.1 401"));
                session.issue(1440);
                ResearchApiSession.Lease revoked = session.current();
                try (LocalApiWebSocketTest.Wire explicit = wire(port, EVENTS, revoked.token)) {
                    explicit.text();
                    session.revoke();
                    session.rotate(revoked, 15);
                    check(explicit.opcode() == 8 && session.current() == null);
                }
                data.seeIdentity("test-account", true);
                session.issue(30);
                ResearchApiSession.Lease account = session.current();
                try (LocalApiWebSocketTest.Wire accountWire = wire(port, EVENTS, account.token)) {
                    accountWire.text();
                    data.loseIdentity();
                    check(accountWire.opcode() == 8 && session.current() == null);
                    check(request(port, STATUS, account.token).startsWith("HTTP/1.1 401"));
                }
                session.issue(30);
                try (LocalApiWebSocketTest.Wire stopping = wire(port, EVENTS, session.current().token)) {
                    stopping.text();
                    controller.stop();
                    int first = stopping.input.read();
                    check(first == -1 || (first & 15) == 8);
                }
                check(!session.listener() && session.current() == null);
                controller.start();
                check(session.listener() && session.current() == null);
            }
        } finally {
            controller.stop();
        }
        try (LocalApiServer normal = LocalApiServer.start(new InetSocketAddress("127.0.0.1", 0),
                PROD, LocalApiRoutes.production(data), () -> true)) {
            check(request(normal.port(), STATUS, PROD).startsWith("HTTP/1.1 404"));
        }
        check(!module.owns("/api/v1/status") && module.authenticate(Collections.emptyList()) == null);
        System.out.println("PASS: Research API build seam, separate realms, lifecycle, monotonic expiry and subscriptions");
    }

    private static String request(int port, String path, String token) throws Exception {
        return HttpPreviewServerTest.request(port, "GET", path, "Authorization: Bearer " + token + "\r\n");
    }

    private static LocalApiWebSocketTest.Wire wire(int port, String path, String token) throws Exception {
        return new LocalApiWebSocketTest.Wire(port, path, "Authorization: Bearer " + token + "\r\n");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
