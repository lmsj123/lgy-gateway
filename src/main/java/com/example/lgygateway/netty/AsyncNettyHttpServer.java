package com.example.lgygateway.netty;
/*
关键方法详细解读
1. initializeClientBootstrap()
功能：配置客户端连接参数，初始化ChannelPipeline。
异步点：
HttpClientCodec和HttpObjectAggregator实现HTTP请求的编码与聚合。
BackendHandler异步处理后端响应，通过ChannelHandlerContext回写结果。
2. GatewayHandler.channelRead0()
流程：
接收客户端请求，通过路由表匹配目标服务地址。
调用forwardRequestWithRetry()发起异步转发。
异步点：
路由匹配和转发操作均在EventLoop线程中执行，不阻塞其他请求
3. forwardRequestWithRetry()
流程：
解析目标URI，建立异步连接（clientBootstrap.connect()）。
通过ChannelFuture监听连接结果，成功时异步写入请求，失败时触发重试。
异步点：
连接和写入操作均通过addListener注册回调，避免阻塞当前线程
4. BackendHandler.channelRead0()
流程：
接收后端响应，复制响应头和内容。
通过frontendCtx.writeAndFlush()异步回写给客户端。
异步点：
响应回写通过ChannelFutureListener.CLOSE在完成时关闭连接，全程非阻塞
5. handleRetry()
功能：在EventLoop线程中调度延迟重试任务。
异步点：
使用eventLoop.schedule()实现非阻塞定时任务，避免阻塞I/O线程
 */
import com.example.lgygateway.config.NettyConfig;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
import com.example.lgygateway.route.RouteTable;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Component
public class AsyncNettyHttpServer {
    @Autowired
    private NettyConfig nettyConfig;
    // 客户端连接池，复用长连接与线程资源，避免每次转发都新建连接。
    private final Bootstrap clientBootstrap;
    private final NioEventLoopGroup clientGroup;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    @Autowired
    private RouteTable routeTable;
    @Autowired
    public AsyncNettyHttpServer(RegistryFactory registryFactory) {
        try {
            //获取注册中心
            Registry registry = registryFactory.getRegistry();
            if (registry == null) {
                throw new IllegalStateException("Registry initialization failed");
            }
            //内部是使用了io多路复用
            this.clientGroup = new NioEventLoopGroup();
            //建立长连接，复用线程和连接资源，避免传统同步HTTP客户端的线程阻塞问题
            this.clientBootstrap = new Bootstrap();
            initializeClientBootstrap();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize NettyHttpServer", e);
        }
    }
    private void initializeClientBootstrap() {
        clientBootstrap.group(clientGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpClientCodec())
                                .addLast(new HttpObjectAggregator(65536))
                                .addLast(new BackendHandler());
                    }
                });
    }
    @PostConstruct
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup();
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new HttpObjectAggregator(65536))
                                    .addLast(new HttpRequestDecoder())
                                    .addLast(new HttpResponseEncoder())
                                    .addLast(new GatewayHandler());
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture f = b.bind(nettyConfig.getPort()).sync();
            f.channel().closeFuture().sync();
        } finally {
            shutdown();
        }
    }
    private class GatewayHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
            FullHttpRequest httpRequest = routeTable.matchRouteAsync(request.uri(), request);
            if (httpRequest != null) {
                forwardRequestWithRetry(ctx, httpRequest, 3);
                return;
            }
            sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND);
        }
        private void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status) {
            FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
    private void forwardRequestWithRetry(ChannelHandlerContext ctx, FullHttpRequest request, int retries) {
        try {
            URI uri = new URI(request.uri());
            ChannelFuture connectFuture = clientBootstrap.connect(uri.getHost(), uri.getPort());

            connectFuture.addListener((ChannelFuture future) -> {
                if (future.isSuccess()) {
                    future.channel().writeAndFlush(request).addListener((ChannelFuture writeFuture) -> {
                        if (!writeFuture.isSuccess()) {
                            handleRetry(ctx, request, retries, writeFuture.cause());
                        }
                    });
                } else {
                    handleRetry(ctx, request, retries, future.cause());
                }
            });
        } catch (URISyntaxException e) {
            sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST);
        }
    }
    private void handleRetry(ChannelHandlerContext ctx, FullHttpRequest request, int retries, Throwable cause) {
        if (retries > 0) {
            ctx.channel().eventLoop().schedule(() ->
                            forwardRequestWithRetry(ctx, request, retries - 1),
                    1, TimeUnit.SECONDS
            );
        } else {
            sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY);
        }
    }
    private class BackendHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        private ChannelHandlerContext frontendCtx;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            this.frontendCtx = null;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse backendResponse) {
            if (frontendCtx != null && frontendCtx.channel().isActive()) {
                // 复制响应给客户端
                FullHttpResponse clientResponse = new DefaultFullHttpResponse(
                        HTTP_1_1,
                        backendResponse.status(),
                        backendResponse.content().copy(),
                        backendResponse.headers().copy(),
                        backendResponse.trailingHeaders().copy()
                );

                // 添加Keep-Alive头
                clientResponse.headers()
                        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                        .set(HttpHeaderNames.CONTENT_LENGTH, clientResponse.content().readableBytes());

                frontendCtx.writeAndFlush(clientResponse)
                        .addListener(ChannelFutureListener.CLOSE);
            }
            ctx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (frontendCtx != null) {
                sendErrorResponse(frontendCtx, HttpResponseStatus.BAD_GATEWAY);
            }
            ctx.close();
        }
    }
    // 修改后的shutdown方法
    private void shutdown() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
        }
    }
    private void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1,
                status,
                Unpooled.EMPTY_BUFFER  // 空内容，可根据需要填充错误信息
        );

        // 设置必要的响应头
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        // 发送响应并关闭连接
        ctx.writeAndFlush(response)
                .addListener(ChannelFutureListener.CLOSE);
    }
}