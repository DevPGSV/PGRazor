package dev.pgrazor;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.TooLongHttpHeaderException;
import io.netty.util.concurrent.ScheduledFuture;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.BooleanSupplier;
import io.netty.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Shared HTTP transport. All socket work stays on one NIO event loop. */
final class LocalApiServer implements AutoCloseable {
    static final int MAX_CONNECTIONS = 16;
    static final int DEADLINE_SECONDS = 3;
    private final EventLoopGroup loops = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final ChannelGroup clients = new DefaultChannelGroup(loops.next(), true);
    private final Channel listener;
    private boolean closed;

    static LocalApiServer start(InetSocketAddress address, String token, LocalApiRoutes routes, BooleanSupplier valid) throws IOException {
        if (!(address.getAddress() instanceof Inet4Address) || token.isEmpty()) {
            throw new IllegalArgumentException("IPv4 and a bearer token are required");
        }
        return new LocalApiServer(address, ("Bearer " + token).getBytes(StandardCharsets.US_ASCII), routes, valid);
    }

    private LocalApiServer(InetSocketAddress address, byte[] authorization, LocalApiRoutes routes, BooleanSupplier valid) throws IOException {
        try {
            ChannelFuture bound = new ServerBootstrap()
                .group(loops)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, MAX_CONNECTIONS)
                .childOption(ChannelOption.ALLOCATOR, new UnpooledByteBufAllocator(false))
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel channel) {
                        if (!valid.getAsBoolean() || clients.size() >= MAX_CONNECTIONS) {
                            channel.close();
                            return;
                        }
                        clients.add(channel);
                        ScheduledFuture<?> timeout = channel.eventLoop().schedule(
                            () -> channel.close(), DEADLINE_SECONDS, TimeUnit.SECONDS);
                        channel.closeFuture().addListener(future -> timeout.cancel(false));
                        channel.pipeline().addLast(new HttpServerCodec(new HttpDecoderConfig()
                            .setMaxInitialLineLength(2048).setMaxHeaderSize(8192)
                            .setMaxChunkSize(1024).setStrictLineParsing(true).setChunkedSupported(false)));
                        channel.pipeline().addLast(new StatusHandler(authorization, routes, valid));
                    }
                })
                .bind(address).awaitUninterruptibly();
            if (!bound.isSuccess()) throw new IOException("Could not bind Local API listener", bound.cause());
            listener = bound.channel();
        } catch (IOException | RuntimeException | LinkageError failure) {
            stopLoops();
            throw failure;
        }
    }

    int port() {
        return ((InetSocketAddress) listener.localAddress()).getPort();
    }

    /** Schedule cleanup without waiting on Android lifecycle threads. */
    synchronized Future<?> stopAsync() {
        if (!closed) {
            closed = true;
            listener.close();
            clients.close();
            loops.shutdownGracefully(0, DEADLINE_SECONDS, TimeUnit.SECONDS);
        }
        return loops.terminationFuture();
    }

    /** Called off the UI and NIO threads. */
    @Override public void close() {
        stopAsync().awaitUninterruptibly();
    }

    private void stopLoops() {
        loops.shutdownGracefully(0, DEADLINE_SECONDS, TimeUnit.SECONDS).awaitUninterruptibly();
    }

    private static final class StatusHandler extends SimpleChannelInboundHandler<HttpRequest> {
        private final byte[] authorization;
        private final LocalApiRoutes routes;
        private final BooleanSupplier valid;
        private boolean handled;

        StatusHandler(byte[] authorization, LocalApiRoutes routes, BooleanSupplier valid) {
            this.authorization = authorization;
            this.routes = routes;
            this.valid = valid;
        }

        @Override protected void channelRead0(ChannelHandlerContext ctx, HttpRequest request) {
            if (handled) return;
            handled = true;
            ctx.channel().config().setAutoRead(false);

            HttpResponseStatus status;
            String body;
            if (!request.decoderResult().isSuccess() || !HttpVersion.HTTP_1_1.equals(request.protocolVersion())) {
                status = request.decoderResult().cause() instanceof TooLongHttpHeaderException
                    ? HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE : HttpResponseStatus.BAD_REQUEST;
                body = "{\"error\":\"INVALID_REQUEST\"}";
            } else if (request.headers().contains(HttpHeaderNames.TRANSFER_ENCODING)
                    || request.headers().contains(HttpHeaderNames.EXPECT)
                    || !request.headers().getAll(HttpHeaderNames.CONTENT_LENGTH).stream().allMatch("0"::equals)) {
                status = HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE;
                body = "{\"error\":\"BODY_NOT_SUPPORTED\"}";
            } else if (request.headers().contains(HttpHeaderNames.ORIGIN)
                    || !validHost(ctx, request.headers().getAll(HttpHeaderNames.HOST))) {
                status = HttpResponseStatus.FORBIDDEN;
                body = "{\"error\":\"FORBIDDEN\"}";
            } else if (!authorized(request.headers().getAll(HttpHeaderNames.AUTHORIZATION))) {
                status = HttpResponseStatus.UNAUTHORIZED;
                body = "{\"error\":\"UNAUTHORIZED\"}";
            } else if (!HttpMethod.GET.equals(request.method())) {
                status = HttpResponseStatus.METHOD_NOT_ALLOWED;
                body = "{\"error\":\"METHOD_NOT_ALLOWED\"}";
            } else {
                LocalApiRoutes.Reply reply = routes.get(request.uri());
                status = HttpResponseStatus.valueOf(reply.code);
                body = reply.body;
            }

            if (!valid.getAsBoolean()) {
                ctx.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(bytes));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
                .set(HttpHeaderNames.CACHE_CONTROL, "no-store")
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
            if (status.equals(HttpResponseStatus.UNAUTHORIZED)) {
                response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer");
            }
            if (status.equals(HttpResponseStatus.METHOD_NOT_ALLOWED)) {
                response.headers().set(HttpHeaderNames.ALLOW, "GET");
            }
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        private boolean authorized(List<String> headers) {
            return headers.size() == 1 && MessageDigest.isEqual(authorization,
                headers.get(0).getBytes(StandardCharsets.ISO_8859_1));
        }

        private static boolean validHost(ChannelHandlerContext ctx, List<String> hosts) {
            InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
            return local.getAddress() instanceof Inet4Address && hosts.size() == 1 && hosts.get(0).equals(local.getAddress().getHostAddress() + ":" + local.getPort());
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) {
            ctx.close();
        }
    }
}
