package com.example.lgygateway.netty;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.example.lgygateway.config.NettyConfig;
import com.example.lgygateway.filters.init.FiltersInit;
import com.example.lgygateway.filters.models.FilterChain;
import com.example.lgygateway.filters.models.FullContext;
import com.example.lgygateway.loadStrategy.LoadServer;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Component
public class AsyncNettyHttpServer {
    @Autowired
    private NettyConfig nettyConfig;
    @Autowired
    private LoadServer loadServer;
    @Autowired
    private FiltersInit filtersInit;
    private final Registry registry;
    private ConcurrentHashMap<String, List<Instance>> routeRules;
    private final Bootstrap clientBootstrap;
    private final NioEventLoopGroup clientGroup;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    @Autowired
    public AsyncNettyHttpServer(RegistryFactory registryFactory) {
        try {
            //获取注册中心
            Registry registry = registryFactory.getRegistry();
            if (registry == null) {
                throw new IllegalStateException("Registry initialization failed");
            }
            this.registry = registry;
            this.clientGroup = new NioEventLoopGroup();
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
            String path = request.uri();
            routeRules = registry.getRouteRules();

            for (Map.Entry<String, List<Instance>> entry : routeRules.entrySet()) {
                if (path.contains(entry.getKey()) && successFiltering(request)) {
                    List<Instance> instances = entry.getValue();
                    Instance selectedInstance = loadServer.getLoadBalancerStrategy().selectInstance(instances);
                    String backendPath = path.replace(entry.getKey(), "");
                    String targetUrl = "http://" + selectedInstance.getIp() + ":" + selectedInstance.getPort() + backendPath;

                    // 创建转发的请求
                    FullHttpRequest proxyRequest = createProxyRequest(request, targetUrl);
                    forwardRequestWithRetry(ctx, proxyRequest, 3);
                    return;
                }
            }

            sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND);
        }

        private FullHttpRequest createProxyRequest(FullHttpRequest original, String targetUrl) throws URISyntaxException {
            URI uri = new URI(targetUrl);
            // 创建新的请求对象
            FullHttpRequest newRequest = new DefaultFullHttpRequest(
                    HTTP_1_1,
                    original.method(),
                    uri.getRawPath(),
                    original.content().copy(),
                    original.headers().copy(),
                    original.trailingHeaders().copy()
            );
            // 设置必要的头信息
            newRequest.headers()
                    .set(HttpHeaderNames.HOST, uri.getHost())
                    .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            return newRequest;
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

    //重启netty操作
    public void restartNetty(int newPort) throws InterruptedException, IOException {
        shutdown();
        nettyConfig.setPort(newPort);
        start();
    }

    // 修改successFiltering方法（保持原有逻辑）
    private boolean successFiltering(FullHttpRequest request) {
        FilterChain filterChain = filtersInit.getFilterChain();
        FullContext fullContext = new FullContext();
        fullContext.setRequest(request);
        filterChain.doFilter(fullContext);
        return fullContext.getResponse() == null;
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