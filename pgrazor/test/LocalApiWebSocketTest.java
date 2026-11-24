package dev.pgrazor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalApiWebSocketTest {
    private static final String PATH = "/api/v1/events";
    private static final String AUTH = "Authorization: Bearer fixture-token\r\n";

    public static void main(String[] args) throws Exception {
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicLong now = new AtomicLong(100);
        LocalApiData data = new LocalApiData(now::get, now::get);
        data.enable();
        try (LocalApiServer server = start(data, valid, "fixture-token")) {
            int port = server.port();
            denied(port, PATH, "", 401);
            denied(port, PATH, AUTH + AUTH, 401);
            denied(port, PATH, AUTH + "Origin: http://127.0.0.1\r\n", 403);
            denied(port, PATH, AUTH + "Host: other\r\n", 403);
            denied(port, PATH + "?token=fixture-token", "", 401);
            denied(port, PATH + "?token=fixture-token", AUTH, 400);
            check(HttpPreviewServerTest.request(port, "GET", PATH, AUTH).startsWith("HTTP/1.1 426"));
            try (Wire ws = open(port, AUTH)) {
                String hello = ws.text();
                check(hello.contains("\"sequence\":1") && hello.contains("\"session_generation\":null"));
                ws.command("{\"id\":\"d\",\"op\":\"discover\",\"version\":1}");
                check(ws.text().contains("\"id\":\"d\",\"topics\":[\"server.status\",\"session.changed\",\"trainer.changed\"]"));
                ws.command("{\"version\":1,\"id\":\"s\",\"op\":\"subscribe\",\"topics\":[\"trainer.changed\"]}");
                check(ws.text().contains("\"type\":\"ack\""));
                check(ws.text().contains("\"initial\":true,\"data\":{\"ready\":false"));
                data.seeIdentity("fixture-account", true);
                now.incrementAndGet();
                check(LocalApiTrainer.accept(data, "101|0066006900780074007500720065002d006100630063006f0075006e0074|0041"));
                check(ws.text().contains("\"initial\":false,\"data\":{\"ready\":true"));
                ws.command("{\"version\":1,\"id\":\"u\",\"op\":\"unsubscribe\",\"topics\":[\"trainer.changed\"]}");
                check(ws.text().contains("\"topics\":[]"));
                ws.command("{\"version\":1,\"version\":1}");
                check(ws.text().contains("INVALID_COMMAND"));
                ws.frame(0x89, new byte[]{7});
                check(ws.opcode() == 10 && ws.payload()[0] == 7);
                data.loseIdentity();
                check(ws.opcode() == 8);
                try (Wire reconnected = open(port, AUTH)) {
                    String replacement = reconnected.text();
                    check(replacement.contains("\"sequence\":1") && !replacement.equals(hello));
                }
            }
            try (Wire ws = open(port, AUTH)) {
                ws.text();
                ws.frame(0x81, new byte[LocalApiWebSocket.MAX_MESSAGE_BYTES + 1]);
                check(ws.opcode() == 8 && ws.closeCode() == 1009);
            }
            try (Wire ws = open(port, AUTH)) {
                ws.text();
                ws.frame(0x81, new byte[]{(byte) 0xc3, 0x28});
                check(ws.opcode() == 8 && ws.closeCode() == 1007);
            }
            try (Wire ws = open(port, AUTH)) {
                ws.text();
                ws.frame(0x01, new byte[]{'{', '}'});
                check(ws.opcode() == 8 && ws.closeCode() == 1003);
            }
            List<Wire> clients = new ArrayList<>();
            try {
                for (int i = 0; i < LocalApiWebSocket.MAX_CONNECTIONS; i++) {
                    Wire ws = open(port, AUTH);
                    clients.add(ws);
                    ws.text();
                }
                denied(port, PATH, AUTH, 429);
                check(HttpPreviewServerTest.request(port, "GET", "/api/v1/status", AUTH).startsWith("HTTP/1.1 200"));
                valid.set(false);
                for (Wire ws : clients) check(ws.opcode() == 8);
            } finally {
                for (Wire ws : clients) ws.close();
            }
        }
        valid.set(true);
        try (LocalApiServer server = start(data, valid, "replacement-token")) {
            denied(server.port(), PATH, AUTH, 401);
            try (Wire ws = open(server.port(), "Authorization: Bearer replacement-token\r\n")) {
                ws.text();
                server.close();
                check(ws.input.read() == -1);
            }
        }
        testControllerRotation();
        testBackpressure(data);
        System.out.println("PASS: WebSocket auth, subscriptions, invalid frames, connection limits, backpressure, revocation and reconnect");
    }

    private static void testControllerRotation() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        LocalApiController controller = new LocalApiController(new LocalApiController.Store() {
            private LocalApiController.Settings saved = new LocalApiController.Settings(
                new LocalApiConfig("127.0.0.1", port), "fixture-token");

            @Override public LocalApiController.Settings load() { return saved; }
            @Override public void save(LocalApiController.Settings settings) { saved = settings; }
        });
        controller.start();
        try (Wire ws = open(port, AUTH)) {
            ws.text();
            Thread.sleep(LocalApiServer.DEADLINE_SECONDS * 1000L + 200);
            ws.command("{\"version\":1,\"id\":\"still-live\",\"op\":\"discover\"}");
            check(ws.text().contains("still-live"));
            controller.rotateToken();
            int first = ws.input.read();
            check(first == -1 || (first & 15) == 8);
            denied(port, PATH, AUTH, 401);
            try (Wire fresh = open(port, "Authorization: Bearer " + controller.settings().token + "\r\n")) {
                check(fresh.text().contains("\"sequence\":1"));
            }
        } finally {
            controller.stop();
        }
    }

    private static void testBackpressure(LocalApiData data) {
        List<ChannelPromise> writes = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object value, ChannelPromise promise) {
                ReferenceCountUtil.release(value);
                writes.add(promise);
            }
        }, new LocalApiWebSocket.Connection(data::eventState, () -> true, data.eventState()));
        try {
            for (int i = 0; i < LocalApiWebSocket.MAX_PENDING_WRITES; i++) {
                channel.writeInbound(new TextWebSocketFrame("{\"version\":1,\"id\":\"d\",\"op\":\"discover\"}"));
            }
            check(!channel.isOpen() && writes.size() == LocalApiWebSocket.MAX_PENDING_WRITES);
        } finally {
            for (ChannelPromise promise : writes) promise.trySuccess();
            channel.finishAndReleaseAll();
        }
    }

    private static LocalApiServer start(LocalApiData data, AtomicBoolean valid, String token) throws IOException {
        return LocalApiServer.start(new InetSocketAddress("127.0.0.1", 0), token,
            LocalApiRoutes.production(data), valid::get);
    }

    private static void denied(int port, String path, String headers, int expected) throws IOException {
        try (Wire ws = new Wire(port, path, headers)) {
            check(ws.response.startsWith("HTTP/1.1 " + expected));
        }
    }

    private static Wire open(int port, String headers) throws IOException {
        Wire ws = new Wire(port, PATH, headers);
        if (ws.response.startsWith("HTTP/1.1 101")) return ws;
        ws.close();
        throw new AssertionError(ws.response);
    }

    static final class Wire implements AutoCloseable {
        final Socket socket;
        final DataInputStream input;
        final String response;
        private int length;

        Wire(int port, String path, String headers) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(2500);
            input = new DataInputStream(socket.getInputStream());
            String request = "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Version: 13"
                + "\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + headers + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int tail = 0;
            while (tail != 0x0d0a0d0a && bytes.size() < 8192) {
                int value = input.readUnsignedByte();
                bytes.write(value);
                tail = (tail << 8) | value;
            }
            response = bytes.toString("US-ASCII");
        }

        void command(String text) throws IOException {
            frame(0x81, text.getBytes(StandardCharsets.UTF_8));
        }

        void frame(int header, byte[] payload) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(header);
            if (payload.length < 126) bytes.write(128 + payload.length);
            else {
                bytes.write(254);
                bytes.write(payload.length >> 8);
                bytes.write(payload.length & 255);
            }
            byte[] mask = {3, 9, 4, 6};
            bytes.write(mask);
            for (int i = 0; i < payload.length; i++) bytes.write(payload[i] ^ mask[i % 4]);
            socket.getOutputStream().write(bytes.toByteArray());
        }

        int opcode() throws IOException {
            int opcode = input.readUnsignedByte() & 15;
            length = input.readUnsignedByte();
            check(length < 128);
            if (length == 126) length = input.readUnsignedShort();
            return opcode;
        }

        byte[] payload() throws IOException {
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            return bytes;
        }

        int closeCode() throws IOException {
            byte[] close = payload();
            return (close[0] & 255) * 256 + (close[1] & 255);
        }

        String text() throws IOException {
            check(opcode() == 1);
            return new String(payload(), StandardCharsets.UTF_8);
        }

        @Override public void close() throws IOException {
            socket.close();
        }
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
