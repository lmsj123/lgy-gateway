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
import com.example.lgygateway.limit.SlidingWindowCounter;
import com.example.lgygateway.limit.TokenBucket;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
import com.example.lgygateway.route.RouteTable;
import com.example.lgygateway.utils.Log;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Component
public class AsyncNettyHttpServer {
    @Autowired
    private NettyConfig nettyConfig;

    // 请求上下文对象（包含前端上下文、重试次数等元数据）
    @AllArgsConstructor
    @Getter
    static class RequestContext {
        private ChannelHandlerContext frontendCtx;
        // 存储原始请求
        private FullHttpRequest originalRequest;
        // 存储剩余重试次数
        private int remainingRetries;
        // 存储长连接信息
        private boolean keepAlive;
    }

    // 定义全局的请求上下文缓存（线程安全）
    private static final ConcurrentHashMap<String, RequestContext> requestContextMap = new ConcurrentHashMap<>();
    @Autowired
    private RouteTable routeTable; // 动态路由表
    // 初始化连接池
    private final ChannelPoolMap<InetSocketAddress, FixedChannelPool> poolMap;
    private static final int MAX_CONNECTIONS = 50;      // 每个后端地址最大连接数
    private static final int ACQUIRE_TIMEOUT_MS = 5000; // 获取连接超时时间

    // 自定义ChannelPoolHandler
    static class CustomChannelPoolHandler extends AbstractChannelPoolHandler {
        private final TokenBucket tokenBucket;

        public CustomChannelPoolHandler(TokenBucket tokenBucket) {
            this.tokenBucket = tokenBucket;
        }

        @Override
        public void channelAcquired(Channel ch) throws Exception {
            if (!tokenBucket.tryAcquire(1)) {
                throw new RuntimeException("Rate limit exceeded");
            }
        }

        @Override
        public void channelCreated(Channel ch) {
            // 初始化Channel的Pipeline（与客户端Bootstrap配置一致）
            ch.pipeline()
                    .addLast(new HttpClientCodec())
                    .addLast(new HttpObjectAggregator(65536));
        }

        @Override
        public void channelReleased(Channel ch) {
            // 可选：释放时清理Channel状态
            ch.pipeline().remove(BackendHandler.class);
        }
    }

    // 客户端连接池，复用长连接与线程资源，避免每次转发都新建连接。
    private final Bootstrap clientBootstrap; // 客户端连接池启动器
    private final NioEventLoopGroup clientGroup; // 客户端io线程组 io多路复用
    private EventLoopGroup bossGroup; // 服务端主线程组 接受连接
    private EventLoopGroup workerGroup; // 服务端工作线程组 处理io
    // 唯一标识符的AttributeKey
    private static final AttributeKey<String> REQUEST_ID_KEY = AttributeKey.valueOf("requestId");
    //令牌桶相关
    private final ConcurrentHashMap<InetSocketAddress, TokenBucket> bucketMap = new ConcurrentHashMap<>();

    @Autowired
    public AsyncNettyHttpServer(RegistryFactory registryFactory) {
        //获取注册中心
        Registry registry = registryFactory.getRegistry();
        if (registry == null) {
            throw new IllegalStateException("Registry initialization failed");
        }
        //内部是使用了io多路复用 支持高并发连接 创建客户端线程组（默认cpu核心数*2）
        this.clientGroup = new NioEventLoopGroup();
        //建立长连接，复用线程和连接资源，避免传统同步HTTP客户端的线程阻塞问题
        this.clientBootstrap = new Bootstrap();
        this.poolMap = new AbstractChannelPoolMap<>() {
            @Override
            protected FixedChannelPool newPool(InetSocketAddress key) {
                TokenBucket tokenBucket = bucketMap.computeIfAbsent(key, k -> new TokenBucket(nettyConfig.getMaxBurst(), nettyConfig.getTokenRefillRate()));
                return new FixedChannelPool(
                        clientBootstrap.remoteAddress(key),
                        new CustomChannelPoolHandler(tokenBucket),
                        ChannelHealthChecker.ACTIVE, //使用默认健康检查
                        FixedChannelPool.AcquireTimeoutAction.FAIL,//获取超时后抛出异常
                        5000,
                        MAX_CONNECTIONS,
                        ACQUIRE_TIMEOUT_MS
                );
            }
        };
        //配置客户端bootstrap
        initializeClientBootstrap();
    }

    private void initializeClientBootstrap() {
        clientBootstrap.group(clientGroup) // 绑定客户端线程组
                .channel(NioSocketChannel.class) // 使用nio模型
                // 启用tcp keep-alive 当连接双方长时间未发送信息 会产生探活报文判断连接是否存在
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpClientCodec()) // http请求编码/响应编码
                                .addLast(new HttpObjectAggregator(65536))// 聚合分块http消息为完整对象
                                .addLast(new BackendHandler());// 处理后端服务请求
                    }
                });
    }

    @PostConstruct
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(); // 主线程组（处理连接请求）
        workerGroup = new NioEventLoopGroup(); // 工作线程组 （处理io操作）
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class) // 服务端nio通道
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpObjectAggregator(65536)) // 聚合客户端请求
                                .addLast(new HttpRequestDecoder()) // 将字节流解析成http请求对象
                                .addLast(new HttpResponseEncoder()) // 将http响应对象编码成字节流
                                .addLast(new GatewayHandler()); // 处理网关逻辑
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128) // 等待连接队列大小
                .childOption(ChannelOption.SO_KEEPALIVE, true); // 保持客户端连接

        ChannelFuture f = b.bind(nettyConfig.getPort()).sync(); // 绑定端口并启动
        f.channel().closeFuture().sync(); // 阻塞直至服务端通道关闭
    }

    //发送端
    private class GatewayHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final SlidingWindowCounter counter = new SlidingWindowCounter(60, 1);

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
            // 全局限流检查
            if (!counter.allowRequest(nettyConfig.getMaxQps())) {
                sendErrorResponse(ctx, HttpResponseStatus.TOO_MANY_REQUESTS);
                return;
            }
            //路由匹配
            FullHttpRequest httpRequest = routeTable.matchRouteAsync(request.uri(), request);

            if (httpRequest != null) {
                forwardRequestWithRetry(ctx, httpRequest, nettyConfig.getTimes());// 转发请求并允许重试 次数可配置
                return;
            }
            sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND); // 未匹配路由返回404
        }
    }

    //使用了连接池长连接 避免每次都进行tcp连接
    private void forwardRequestWithRetry(ChannelHandlerContext ctx, FullHttpRequest request, int retries) {
        try {

            String requestId = UUID.randomUUID().toString(); // 生成唯一ID
            ctx.channel().attr(REQUEST_ID_KEY).set(requestId); // 绑定到Channel属性（仅存储ID）

            // 存储到全局缓存（包含完整上下文）
            requestContextMap.put(requestId, new RequestContext(ctx, request, retries, HttpUtil.isKeepAlive(request)));

            URI uri = new URI(request.uri());
            InetSocketAddress inetSocketAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
            //获取或创建连接池
            FixedChannelPool pool = poolMap.get(inetSocketAddress);

            //从池中获取channel
            pool.acquire().addListener((Future<Channel> future) -> {
                //当异步获取pool中的channel成功时会进入下面的分支
                if (future.isSuccess()) {
                    Channel channel = future.getNow();
                    try {
                        //绑定元数据到channel
                        channel.attr(REQUEST_ID_KEY).set(requestId);
                        //发送请求
                        channel.writeAndFlush(request);
                    } catch (Exception e) {
                        handleSendError(ctx, pool, channel, e);
                    } finally {
                        pool.release(channel);
                    }
                } else {
                    // 获取连接失败处理
                    handleAcquireFailure(ctx, request, retries, future.cause());
                }
            });
        } catch (URISyntaxException e) {
            sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST);
            request.release();
        }
    }

    // 处理发送失败
    private void handleSendError(ChannelHandlerContext ctx, FixedChannelPool pool, Channel ch, Throwable cause) {
        // 自动触发健康检查
        if (ch.isActive()) {
            pool.release(ch);
        }
        sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY);
    }

    // 处理连接获取失败
    private void handleAcquireFailure(ChannelHandlerContext ctx, FullHttpRequest request, int retries, Throwable cause) {
        if (retries > 0) {
            ctx.channel().eventLoop().schedule(() ->
                            forwardRequestWithRetry(ctx, request, retries - 1),
                    1000, TimeUnit.MILLISECONDS
            );
        } else {
            request.release();
            sendErrorResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE);
        }
    }

    // 修改后的handleRetry方法
    private void handleRetry(ChannelHandlerContext ctx, FullHttpRequest request, Integer retries) {
        Log.logger.warn("Retrying request to {} (remaining {} retries)", request.uri(), retries);
        if (retries != null && retries > 0) {
            // 计算指数退避时间（例：2^retries秒）
            int baseDelay = 1;
            int delaySeconds = (int) Math.pow(2, (nettyConfig.getTimes() - retries)) * baseDelay;
            // 最大退避时间（例如32秒）
            int maxBackoff = 32;
            delaySeconds = Math.min(delaySeconds, maxBackoff);
            // 添加随机抖动（0~1000ms）
            int jitter = new Random().nextInt(1000);
            long totalDelayMs = (delaySeconds * 1000L) + jitter;

            ctx.channel().eventLoop().schedule(
                    () -> forwardRequestWithRetry(ctx, request, retries - 1),
                    totalDelayMs, TimeUnit.MILLISECONDS
            );
        } else {
            sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY);
        }
    }

    //回复端
    private class BackendHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        @Override
        protected void channelRead0(ChannelHandlerContext backendCtx, FullHttpResponse backendResponse) {
            // 获取关联的原始请求和剩余重试次数
            String requestId = backendCtx.channel().attr(REQUEST_ID_KEY).get();
            RequestContext context = requestContextMap.get(requestId);
            if (context != null) {
                // 判断是否符合重试条件
                if (shouldRetry(backendResponse, context.getOriginalRequest())) {
                    handleRetry(context.getFrontendCtx(), context.originalRequest, context.remainingRetries);
                } else {
                    forwardResponseToClient(context.getFrontendCtx(), backendResponse, backendCtx);// 正常响应转发
                }
            }
            backendCtx.close();// 关闭后端连接
            //释放连接
            if (context != null && context.originalRequest != null) {
                context.originalRequest.release();
            }
        }

        private boolean shouldRetry(FullHttpResponse response, FullHttpRequest request) {
            // 5xx错误且非POST请求时触发重试
            return response.status().code() >= 500 &&
                    response.status().code() < 600 &&
                    request != null &&
                    !request.method().equals(HttpMethod.POST);
        }

        // 在BackendHandler中获取前端上下文
        private ChannelHandlerContext getFrontendContext(ChannelHandlerContext backendCtx) {
            return backendCtx.channel().attr(AttributeKey.<ChannelHandlerContext>valueOf("frontendCtx")).get();
        }

        private void forwardResponseToClient(ChannelHandlerContext ctx, FullHttpResponse response, ChannelHandlerContext backendCtx) {
            FullHttpResponse clientResponse = new DefaultFullHttpResponse(
                    HTTP_1_1,
                    response.status(),
                    response.content().copy()
            ); // 复制响应内容
            // 添加Keep-Alive头 保持长连接
            String requestId = backendCtx.channel().attr(REQUEST_ID_KEY).get();
            HttpUtil.setKeepAlive(clientResponse, requestContextMap.get(requestId).keepAlive);
            requestContextMap.remove(requestId);
            if (HttpUtil.isTransferEncodingChunked(response)) {
                HttpUtil.setTransferEncodingChunked(clientResponse, true);
            } else {
                clientResponse.headers()
                        .set(HttpHeaderNames.CONTENT_LENGTH, clientResponse.content().readableBytes());
            }
            ctx.writeAndFlush(clientResponse)
                    .addListener(ChannelFutureListener.CLOSE); // 发送并关闭
        }
    }

    // 修改后的shutdown方法
    private void shutdown() throws InterruptedException {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().sync();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().sync();
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully().sync();
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