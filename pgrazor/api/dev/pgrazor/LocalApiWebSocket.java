package dev.pgrazor;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Shared authenticated upgrade and bounded event connection. Owned by the listener event loop. */
final class LocalApiWebSocket {
    static final int MAX_CONNECTIONS = 8;
    static final int MAX_MESSAGE_BYTES = 2048;
    static final int MAX_PENDING_WRITES = 8;
    static final int MAX_COMMANDS_PER_SECOND = 32;
    private static final long PING_INTERVAL = TimeUnit.SECONDS.toNanos(15);
    private static final long PONG_TIMEOUT = TimeUnit.SECONDS.toNanos(10);

    interface Feed {
        State read();
    }

    static final class State {
        final long epoch;
        final String session;
        final Map<String, String> topics;

        State(long epoch, String session, Map<String, String> topics) {
            this.epoch = epoch;
            this.session = session;
            this.topics = Collections.unmodifiableMap(new LinkedHashMap<>(topics));
        }
    }

    static final class Limits {
        private int connections;

        HttpResponseStatus upgrade(ChannelHandlerContext ctx, HttpRequest request,
                ScheduledFuture<?> deadline, BooleanSupplier valid, Feed feed) {
            if (!oneHeader(request, HttpHeaderNames.UPGRADE, "websocket")
                    || !request.headers().containsValue(HttpHeaderNames.CONNECTION, "upgrade", true)
                    || !oneHeader(request, HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")) {
                return HttpResponseStatus.UPGRADE_REQUIRED;
            }
            List<String> keys = request.headers().getAll(HttpHeaderNames.SEC_WEBSOCKET_KEY);
            try {
                if (keys.size() != 1 || keys.get(0).length() != 24
                        || Base64.getDecoder().decode(keys.get(0)).length != 16) {
                    return HttpResponseStatus.BAD_REQUEST;
                }
            } catch (IllegalArgumentException malformed) {
                return HttpResponseStatus.BAD_REQUEST;
            }
            if (connections >= MAX_CONNECTIONS) return HttpResponseStatus.TOO_MANY_REQUESTS;

            State initial = feed.read();
            WebSocketServerHandshaker handshaker = new WebSocketServerHandshakerFactory(
                "ws://" + request.headers().get(HttpHeaderNames.HOST) + request.uri(),
                null, WebSocketDecoderConfig.newBuilder().maxFramePayloadLength(MAX_MESSAGE_BYTES)
                    .expectMaskedFrames(true).allowMaskMismatch(false).allowExtensions(false)
                    .closeOnProtocolViolation(true).withUTF8Validator(true).build()).newHandshaker(request);
            connections++;
            ctx.channel().closeFuture().addListener(done -> connections--);
            DefaultFullHttpRequest full = new DefaultFullHttpRequest(
                request.protocolVersion(), request.method(), request.uri());
            full.headers().set(request.headers());
            try {
                handshaker.handshake(ctx.channel(), full).addListener((ChannelFutureListener) done -> {
                    if (!done.isSuccess() || !valid.getAsBoolean()) {
                        ctx.close();
                        return;
                    }
                    deadline.cancel(false);
                    ctx.pipeline().addBefore(ctx.name(), "localApiUtf8", new Utf8FrameValidator(true));
                    ctx.pipeline().replace(ctx.name(), "localApiEvents", new Connection(feed, valid, initial));
                    ctx.channel().config().setAutoRead(true);
                });
            } finally {
                full.release();
            }
            return null;
        }

        private static boolean oneHeader(HttpRequest request, CharSequence name, String expected) {
            List<String> values = request.headers().getAll(name);
            return values.size() == 1 && expected.equalsIgnoreCase(values.get(0));
        }
    }

    static final class Connection extends SimpleChannelInboundHandler<WebSocketFrame> {
        private final String id = UUID.randomUUID().toString();
        private final Set<String> subscriptions = new LinkedHashSet<>();
        private final Feed feed;
        private final BooleanSupplier valid;
        private final long epoch;
        private State state;
        private long sequence;
        private int pending;
        private int commands;
        private long commandWindow = System.nanoTime();
        private long pingAt = commandWindow;
        private boolean awaitingPong;
        private boolean closing;
        private ScheduledFuture<?> timer;

        Connection(Feed feed, BooleanSupplier valid, State initial) {
            this.feed = feed;
            this.valid = valid;
            this.state = initial;
            this.epoch = initial.epoch;
        }

        @Override public void handlerAdded(ChannelHandlerContext ctx) {
            if (current(ctx) == null) return;
            reply(ctx, "hello", null, "");
            timer = ctx.executor().scheduleWithFixedDelay(() -> tick(ctx), 250, 250, TimeUnit.MILLISECONDS);
        }

        @Override public void handlerRemoved(ChannelHandlerContext ctx) {
            if (timer != null) timer.cancel(false);
        }

        @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (timer != null) timer.cancel(false);
            super.channelInactive(ctx);
        }

        @Override protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            State latest = current(ctx);
            if (latest == null) return;
            observe(ctx, latest);
            long now = System.nanoTime();
            if (now - commandWindow >= TimeUnit.SECONDS.toNanos(1)) {
                commandWindow = now;
                commands = 0;
            }
            if (++commands > MAX_COMMANDS_PER_SECOND) {
                close(ctx, 1008, "Input rate exceeded");
            } else if (frame instanceof CloseWebSocketFrame) {
                close(ctx, 1000, "");
            } else if (frame instanceof PingWebSocketFrame) {
                write(ctx, new PongWebSocketFrame(frame.content().retain()));
            } else if (frame instanceof PongWebSocketFrame) {
                if (awaitingPong && frame.content().readableBytes() == Long.BYTES
                        && frame.content().getLong(frame.content().readerIndex()) == pingAt) awaitingPong = false;
            } else if (!(frame instanceof TextWebSocketFrame) || !frame.isFinalFragment()) {
                close(ctx, 1003, "Only unfragmented JSON text is supported");
            } else {
                command(ctx, ((TextWebSocketFrame) frame).text());
            }
        }

        private void command(ChannelHandlerContext ctx, String text) {
            LocalApiEventProtocol.Command command = LocalApiEventProtocol.parse(text);
            if (command == null) {
                reply(ctx, "error", null, ",\"code\":\"INVALID_COMMAND\"");
                return;
            }
            switch (command.operation) {
                case "discover":
                    reply(ctx, "topics", command.id, ",\"topics\":" + LocalApiEventProtocol.array(state.topics.keySet()));
                    break;
                case "subscribe":
                case "unsubscribe":
                    if (!state.topics.keySet().containsAll(command.topics)) {
                        reply(ctx, "error", command.id, ",\"code\":\"UNKNOWN_TOPIC\"");
                        return;
                    }
                    boolean subscribe = command.operation.equals("subscribe");
                    Set<String> added = new LinkedHashSet<>(command.topics);
                    added.removeAll(subscriptions);
                    if (subscribe) subscriptions.addAll(command.topics);
                    else subscriptions.removeAll(command.topics);
                    reply(ctx, "ack", command.id, ",\"topics\":" + LocalApiEventProtocol.array(subscriptions));
                    if (subscribe) {
                        for (String topic : added) event(ctx, topic, true, state);
                    }
                    break;
                default:
                    reply(ctx, "error", command.id, ",\"code\":\"UNKNOWN_OPERATION\"");
            }
        }

        private void tick(ChannelHandlerContext ctx) {
            State next = current(ctx);
            if (next == null) return;
            observe(ctx, next);
            long now = System.nanoTime();
            if (awaitingPong && now - pingAt >= PONG_TIMEOUT) {
                close(ctx, 1008, "Heartbeat timeout");
            } else if (!awaitingPong && now - pingAt >= PING_INTERVAL) {
                pingAt = now;
                awaitingPong = true;
                write(ctx, new PingWebSocketFrame(Unpooled.buffer(Long.BYTES).writeLong(pingAt)));
            }
        }

        private void observe(ChannelHandlerContext ctx, State next) {
            State before = state;
            state = next;
            for (String topic : subscriptions) {
                if (!Objects.equals(before.topics.get(topic), next.topics.get(topic))) {
                    event(ctx, topic, false, next);
                }
            }
        }

        private void event(ChannelHandlerContext ctx, String topic, boolean initial, State value) {
            State latest = current(ctx);
            String payload = value.topics.get(topic);
            if (latest == null || !Objects.equals(value.session, latest.session)
                    || !Objects.equals(payload, latest.topics.get(topic))) return;
            envelope(ctx, "event", null, ",\"topic\":\"" + topic + "\",\"initial\":" + initial
                + ",\"data\":" + payload, latest);
        }

        private State current(ChannelHandlerContext ctx) {
            if (closing) return null;
            State latest = feed.read();
            if (!valid.getAsBoolean() || epoch != latest.epoch) {
                close(ctx, 1008, "Access revoked");
                return null;
            }
            return latest;
        }

        private void reply(ChannelHandlerContext ctx, String type, String request, String payload) {
            State latest = current(ctx);
            if (latest != null) envelope(ctx, type, request, payload, latest);
        }

        private void envelope(ChannelHandlerContext ctx, String type, String request, String payload, State latest) {
            write(ctx, new TextWebSocketFrame("{\"version\":1,\"type\":\"" + type
                + "\",\"connection_id\":\"" + id + "\",\"sequence\":" + (++sequence)
                + ",\"session_generation\":" + LocalApiEventProtocol.nullable(latest.session)
                + (request == null ? "" : ",\"id\":\"" + request + "\"") + payload + "}"));
        }

        private void write(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (closing || !ctx.channel().isWritable() || pending >= MAX_PENDING_WRITES) {
                frame.release();
                closing = true;
                ctx.close();
                return;
            }
            pending++;
            ctx.writeAndFlush(frame).addListener(done -> {
                pending--;
                if (!done.isSuccess()) ctx.close();
            });
        }

        private void close(ChannelHandlerContext ctx, int code, String reason) {
            if (closing) return;
            if (!ctx.channel().isWritable() || pending >= MAX_PENDING_WRITES) {
                closing = true;
                ctx.close();
                return;
            }
            closing = true;
            ctx.channel().config().setAutoRead(false);
            ScheduledFuture<?> deadline = ctx.executor().schedule(() -> ctx.close(), 1, TimeUnit.SECONDS);
            ctx.channel().closeFuture().addListener(done -> deadline.cancel(false));
            ctx.writeAndFlush(new CloseWebSocketFrame(code, reason)).addListener(ChannelFutureListener.CLOSE);
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) {
            ctx.close();
        }
    }
}
