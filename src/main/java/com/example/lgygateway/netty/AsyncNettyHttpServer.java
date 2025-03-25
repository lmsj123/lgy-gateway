package com.example.lgygateway.netty;

import cn.hutool.json.JSONUtil;
import com.example.lgygateway.config.NettyConfig;
import com.example.lgygateway.limit.SlidingWindowCounter;
import com.example.lgygateway.limit.TokenBucket;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
import com.example.lgygateway.route.RouteTable;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.*;

import static io.netty.handler.codec.http.HttpResponseStatus.TOO_MANY_REQUESTS;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
//请求接收 → 全局限流 → 用户级限流 → 路由匹配 → 服务级限流 → 连接池获取 → 后端请求 → 响应处理
/*
潜在问题
(1) 性能瓶颈
路由匹配效率低：RouteTable.matchRouteAsync 使用线性遍历 url.contains(entry.getKey())，时间复杂度为 O(N)，无法支持大规模路由规则。
锁竞争：ConcurrentHashMap 在频繁更新时可能导致线程竞争（如 routeRules 动态更新）。
(2) 资源管理缺陷
连接池预热不足：预热仅初始化10%连接，突发流量下可能触发连接创建延迟。
内存泄漏风险：
requestContextMap 未完全清理（如请求失败未移除 requestId）。
ByteBuf 未在异常分支完全释放（如 handleAcquireFailure 中可能遗漏）。
(3) 异常处理不足
重试逻辑缺陷：仅对 ConnectException 和 TimeoutException 触发重试，未覆盖其他可恢复异常（如 IOException）。
错误响应标准化缺失：sendErrorResponse 返回的JSON格式未统一，可能暴露内部错误信息。
(4) 可维护性问题
硬编码配置：线程池大小、超时时间、限流参数等硬编码，无法动态调整。
熔断器缺失：无熔断机制，无法在后端服务不可用时快速失败。
 */
// 后续修改为异步日志避免影响性能
@Component
public class AsyncNettyHttpServer {
    private static final Logger logger = LoggerFactory.getLogger(AsyncNettyHttpServer.class);
    @Autowired
    private NettyConfig nettyConfig;
    @Autowired
    private RouteTable routeTable; // 动态路由表
    // 定义全局的请求上下文缓存（线程安全）
    private static final ConcurrentHashMap<String, RequestContext> requestContextMap = new ConcurrentHashMap<>();
    // 初始化连接池
    private final ChannelPoolMap<InetSocketAddress, FixedChannelPool> poolMap;
    private static final int MAX_CONNECTIONS = 50;      // 每个后端地址最大连接数
    private static final int ACQUIRE_TIMEOUT_MS = 5000; // 获取连接超时时间
    // 客户端连接池，复用长连接与线程资源，避免每次转发都新建连接。
    private final Bootstrap clientBootstrap; // 客户端连接池启动器
    private final NioEventLoopGroup clientGroup; // 客户端io线程组 io多路复用
    // 唯一标识符的AttributeKey
    private static final AttributeKey<String> REQUEST_ID_KEY = AttributeKey.valueOf("requestId");
    // 滑动窗口限流(全局限流体系)
    private final SlidingWindowCounter counter = new SlidingWindowCounter(60, 600);
    // 服务令牌桶相关(服务限流体系)
    private final ConcurrentHashMap<InetSocketAddress, TokenBucket> serviceBucketMap = new ConcurrentHashMap<>();
    // 用户令牌桶相关(用户限流体系)
    private final ConcurrentHashMap<String, TokenBucket> userBucketMap = new ConcurrentHashMap<>();
    // 用户令牌桶相关(游客限流体系)
    private final ConcurrentHashMap<String, TokenBucket> noUserBucketMap = new ConcurrentHashMap<>();

    // 自定义ChannelPoolHandler
    class CustomChannelPoolHandler extends AbstractChannelPoolHandler {

        @Override
        public void channelAcquired(Channel ch) {

        }

        @Override
        public void channelCreated(Channel ch) {
            // 初始化Channel的Pipeline（与客户端Bootstrap配置一致）
            ch.pipeline()
                    .addLast(new HttpClientCodec()) // 客户端应使用HttpClientCodec
                    .addLast(new HttpObjectAggregator(65536))
                    .addLast(new BackendHandler());
        }

        @Override
        public void channelReleased(Channel ch) {
        }
    }

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
        // 存储过期时间
        private long lastAccessTime;
    }

    @Autowired
    public AsyncNettyHttpServer(RegistryFactory registryFactory) {
        //获取注册中心
        logger.info("正在获取注册中心");
        Registry registry = registryFactory.getRegistry();
        if (registry == null) {
            throw new IllegalStateException("Registry initialization failed");
        }
        logger.info("获取注册中心成功");
        //内部是使用了io多路复用 支持高并发连接 创建客户端线程组（默认cpu核心数*2）
        this.clientGroup = new NioEventLoopGroup();
        //建立长连接，复用线程和连接资源，避免传统同步HTTP客户端的线程阻塞问题
        this.clientBootstrap = new Bootstrap();
        this.poolMap = new AbstractChannelPoolMap<>() {
            @Override
            protected FixedChannelPool newPool(InetSocketAddress key) {
                logger.info("正在初始化由ip端口获取的连接池（线程隔离的思路）");
                FixedChannelPool pool = new FixedChannelPool(
                        clientBootstrap.remoteAddress(key),
                        new CustomChannelPoolHandler(),
                        ChannelHealthChecker.ACTIVE, //使用默认健康检查
                        FixedChannelPool.AcquireTimeoutAction.FAIL,//获取超时后抛出异常
                        5000,
                        MAX_CONNECTIONS,
                        ACQUIRE_TIMEOUT_MS
                );
                logger.info("正在预热连接");
                // 异步预热10%连接
                new Thread(() -> {
                    int warmupConnections = (int) (MAX_CONNECTIONS * 0.1);
                    for (int i = 0; i < warmupConnections; i++) {
                        pool.acquire().addListener(future -> {
                            if (future.isSuccess()) {
                                Channel ch = (Channel) future.getNow();
                                pool.release(ch); // 立即释放连接至池中
                                logger.info("预热连接{}已释放", ch.id());
                            } else {
                                logger.error("预热失败: {}", future.cause().getMessage());
                            }
                        });
                    }
                }).start();
                return pool;
            }
        };
        //配置客户端bootstrap
        initializeClientBootstrap();
        //删除过期内容
        deleteOutTime();
    }

    private void deleteOutTime() {
        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(() -> {
                    Iterator<Map.Entry<String, RequestContext>> it = requestContextMap.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, RequestContext> entry = it.next();
                        if (System.currentTimeMillis() - entry.getValue().getLastAccessTime() > 300_000) { // 5分钟
                            it.remove();
                            entry.getValue().getOriginalRequest().release(); // 释放缓冲区
                        }
                    }
                }, 5, 5, TimeUnit.MINUTES);
    }

    private void initializeClientBootstrap() {
        // [线程组绑定]
        clientBootstrap.group(clientGroup) // 绑定NioEventLoopGroup线程组，用于处理IO事件
                // [通道类型选择]
                .channel(NioSocketChannel.class) // 使用NIO非阻塞Socket通道（对比：OioSocketChannel为阻塞式）
                // [TCP保活机制]
                .option(ChannelOption.SO_KEEPALIVE, true) // 启用TCP Keep-Alive探活包，默认间隔2小时（需系统内核支持）
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000) // 连接超时5秒
                // [管道处理器链配置]
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        logger.info("正在初始化BackendHandler（响应阶段）");
                        ch.pipeline()
                                // 1. 编解码器
                                .addLast(new HttpClientCodec())
                                // 2. 聚合器
                                .addLast(new HttpObjectAggregator(65536))
                                // 3. 超时控制（应在业务处理器前）
                                .addLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS)) // 读超时
                                .addLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)) // 新增写超时
                                // 4. 业务处理器
                                .addLast(new BackendHandler());
                    }
                });
    }

    //默认线程数=CPU核心数×2，在32核服务器上产生64线程，可能导致上下文切换开销
    private final EventExecutorGroup businessGroup = new DefaultEventExecutorGroup(Runtime.getRuntime().availableProcessors() * 2);

    @PostConstruct
    public void start() throws InterruptedException {
        // 1. 线程组初始化
        // 默认NioEventLoopGroup线程数=CPU核心数×2，在32核服务器上产生64线程，可能导致上下文切换开销
        EventLoopGroup bossGroup = new NioEventLoopGroup(2);// 主线程组（处理TCP连接请求）
        EventLoopGroup workerGroup = new NioEventLoopGroup(16);// 工作线程组（处理IO读写）
        // 2. 服务端引导类配置
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                // 3. 通道类型选择
                .channel(NioServerSocketChannel.class) // 使用NIO模型（对比：OioServerSocketChannel为阻塞式）
                // 4. 管道处理器链配置
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                // 4.1 HTTP协议编解码器（必须第一顺位）
                                .addLast(new HttpServerCodec()) // 组合HttpRequestDecoder+HttpResponseEncoder
                                // 4.2 请求聚合器
                                .addLast(new HttpObjectAggregator(10 * 1024 * 1024)) // 10MB
                                .addLast(new ChannelInboundHandlerAdapter() {
                                    // 请求内容太大了
                                    @Override
                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                        if (cause instanceof TooLongFrameException) {
                                            sendErrorResponse(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
                                        }
                                    }
                                })
                                // 4.3 业务处理器（绑定独立线程池）
                                .addLast(businessGroup, new GatewayHandler()); // 避免阻塞IO线程
                    }
                })
                // 5. TCP参数配置
                .option(ChannelOption.SO_BACKLOG, 1024) // 根据预估并发量调整 等待连接队列大小
                .childOption(ChannelOption.SO_KEEPALIVE, true) // 启用TCP Keep-Alive探活
                .childOption(ChannelOption.AUTO_READ, true); // 自动触发Channel读事件
        // 6. 端口绑定与启动
        logger.info("netty绑定端口为{}", nettyConfig.getPort());
        ChannelFuture f = b.bind(nettyConfig.getPort()).sync(); // 同步阻塞直至端口绑定成功
        f.addListener(future -> {
            if (!future.isSuccess()) {
                logger.error("端口绑定失败", future.cause());
            }
        });
        // 添加优雅关机逻辑
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }));
        // 7. 阻塞直至服务端关闭
        f.channel().closeFuture().sync(); // 阻塞主线程（需结合优雅关机逻辑）

    }

    //发送端
    private class GatewayHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
            // [1] 处理OPTIONS预检请求（CORS）
            if (request.method().equals(HttpMethod.OPTIONS)) {
                FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.OK);
                response.headers()
                        .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*") // 允许所有源  后续可讲允许源通过配置中心配置 这里为简便 下面类似
                        .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE") // 允许方法
                        .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization") // 允许头
                        .set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "3600"); // 预检缓存时间（秒）
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE); // 立即关闭连接
                return;
            }
            logger.info("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
            // [2] 请求日志记录
            logger.info("接受到来自{}的请求", request.uri());

            // [3] 全局限流检查（滑动窗口算法）
            logger.info("正在根据滑动窗口判断是否需要限流（全局限流体系）");
            if (limit(ctx, request)) { // 触发限流则阻断请求
                return;
            }
            // [4] 路由匹配（异步匹配）
            FullHttpRequest httpRequest = routeTable.matchRouteAsync(request.uri(), request);
            if (httpRequest != null) { // 匹配成功
                logger.info("路由匹配成功");
                // [5] 带重试机制的请求转发
                forwardRequestWithRetry(ctx, httpRequest, nettyConfig.getTimes()); // 可配置重试次数
            } else { // 匹配失败
                logger.info("路由匹配失败");
                // [6] 返回404错误
                sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND);
            }
        }
    }

    //使用了连接池长连接 避免每次都进行tcp连接
    private void forwardRequestWithRetry(ChannelHandlerContext ctx, FullHttpRequest request, int retries) {
        try {
            URI uri = new URI(request.uri());
            InetSocketAddress inetSocketAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
            logger.info("正在初始化服务令牌桶限流体系");
            /*
               后续可在这里扩展熔断器相关的逻辑

             */
            TokenBucket tokenBucket = serviceBucketMap.computeIfAbsent(inetSocketAddress, k -> new TokenBucket(nettyConfig.getServiceMaxBurst(), nettyConfig.getServiceTokenRefillRate()));
            if (!tokenBucket.tryAcquire(1)) {
                logger.info("该请求被限流");
                sendErrorResponse(ctx, TOO_MANY_REQUESTS);
                return;
            }
            String requestId = UUID.randomUUID().toString(); // 生成唯一ID
            ctx.channel().attr(REQUEST_ID_KEY).set(requestId); // 绑定到Channel属性（仅存储ID）

            // 存储到全局缓存（包含完整上下文）
            requestContextMap.put(requestId, new RequestContext(ctx, request, retries, HttpUtil.isKeepAlive(request), System.currentTimeMillis()));


            //获取或创建连接池
            FixedChannelPool pool = poolMap.get(inetSocketAddress);
            request.headers().set(HttpHeaderNames.HOST, uri.getHost() + ':' + uri.getPort());
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            //从池中获取channel
            logger.info("正在从连接池中获取连接");
            pool.acquire().addListener((Future<Channel> future) -> {
                //当异步获取pool中的channel成功时会进入下面的分支
                if (future.isSuccess()) {
                    logger.info("获取连接成功");
                    Channel channel = future.getNow();
                    try {
                        //绑定元数据到channel
                        channel.attr(REQUEST_ID_KEY).set(requestId);
                        //发送请求
                        channel.writeAndFlush(request).addListener((ChannelFuture writeFuture) -> {
                            if (writeFuture.isSuccess()) {
                                logger.info("成功发送请求");
                                // 可在此处记录成功日志
                                pool.release(channel);
                            } else {
                                // 处理失败：记录异常、重试或响应错误
                                logger.info("发送请求失败");
                                ReferenceCountUtil.safeRelease(request.content());
                                handleSendError(ctx, pool, channel, writeFuture.cause());
                                pool.release(channel); // 确保异常后释放连接
                            }
                        });
                    } catch (Exception e) {
                        ReferenceCountUtil.safeRelease(request.content());
                        handleSendError(ctx, pool, channel, e);
                    }
                } else {
                    logger.info("获取连接失败");
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
    private void handleAcquireFailure(ChannelHandlerContext ctx, FullHttpRequest request, int retries, Throwable cause) throws URISyntaxException {
        if (retries > 0) {
            // 错误类型判断
            if (!isRetriableError(cause)) {
                sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY);
                return;
            }
            logger.info("正在重试 重试次数还剩余 {}", retries);
            if (limit(ctx, request)) {
                return;
            }
            URI uri = new URI(request.uri());
            InetSocketAddress inetSocketAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
            TokenBucket tokenBucket = serviceBucketMap.computeIfAbsent(inetSocketAddress, k -> new TokenBucket(nettyConfig.getServiceMaxBurst(), nettyConfig.getServiceTokenRefillRate()));
            if (!tokenBucket.tryAcquire(1)) {
                logger.info("该请求被限流");
                sendErrorResponse(ctx, TOO_MANY_REQUESTS);
                return;
            }
            // 指数退避+随机抖动（替换固定1秒）
            int totalDelayMs = calculateBackoffDelay(retries);
            request.retain();
            try {
                ctx.channel().eventLoop().schedule(() ->
                                forwardRequestWithRetry(ctx, request, retries - 1),
                        totalDelayMs, TimeUnit.MILLISECONDS
                );
            }finally {
                request.release(); // 确保释放
            }
        } else {
            logger.info("重试次数为0 发送响应失败请求");
            request.release();
            sendErrorResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE);
        }
    }

    // 修改后的handleRetry方法
    private void handleRetry(ChannelHandlerContext ctx, FullHttpRequest request, Integer retries) throws URISyntaxException {
        logger.warn("Retrying request to {} (remaining {} retries)", request.uri(), retries);
        if (retries != null && retries > 0) {
            if (limit(ctx, request)) {
                return;
            }
            URI uri = new URI(request.uri());
            InetSocketAddress inetSocketAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
            TokenBucket tokenBucket = serviceBucketMap.computeIfAbsent(inetSocketAddress, k -> new TokenBucket(nettyConfig.getServiceMaxBurst(), nettyConfig.getServiceTokenRefillRate()));
            if (!tokenBucket.tryAcquire(1)) {
                logger.info("该请求被限流");
                sendErrorResponse(ctx, TOO_MANY_REQUESTS);
                return;
            }
            int totalDelayMs = calculateBackoffDelay(retries);
            try {
                request.retain();
                ctx.channel().eventLoop().schedule(
                        () -> forwardRequestWithRetry(ctx, request, retries - 1),
                        totalDelayMs, TimeUnit.MILLISECONDS
                );
            }finally {
                request.release();
            }

        } else {
            sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY);
        }
    }

    // 全局限流和用户级限流的校验
    private boolean limit(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!counter.allowRequest(nettyConfig.getMaxQps())) {
            logger.info("该请求被限流");
            sendErrorResponse(ctx, TOO_MANY_REQUESTS);
            return true;
        }
        String userId = extractUserId(request);
        if (userId.isEmpty()) {
            TokenBucket userTokenBucket = userBucketMap.computeIfAbsent(userId, k -> new TokenBucket(nettyConfig.getUserMaxBurst(), nettyConfig.getUserTokenRefillRate()));
            if (!userTokenBucket.tryAcquire(1)) {
                logger.info("该用户{}被限流", userId);
                sendErrorResponse(ctx, TOO_MANY_REQUESTS);
                return true;
            }
        } else {
            TokenBucket noUserTokenBucket = noUserBucketMap.computeIfAbsent("noUser", k -> new TokenBucket(nettyConfig.getNoUserMaxBurst(), nettyConfig.getNoUserTokenRefillRate()));
            if (!noUserTokenBucket.tryAcquire(1)) {
                logger.info("游客被限流");
                sendErrorResponse(ctx, TOO_MANY_REQUESTS);
                return true;
            }
        }
        return false;
    }

    //回复端
    private class BackendHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        @Override
        protected void channelRead0(ChannelHandlerContext backendCtx, FullHttpResponse backendResponse) throws URISyntaxException {
            logger.info("正在解析响应");
            // 获取关联的原始请求和剩余重试次数
            String requestId = backendCtx.channel().attr(REQUEST_ID_KEY).get();
            logger.info("Received backend response for ID: {}  status : {}", requestId, backendResponse.status());
            RequestContext context = requestContextMap.computeIfPresent(requestId, (k, v) -> {
                v.lastAccessTime = System.currentTimeMillis();
                return v;
            });
            if (context != null) {
                // 判断是否符合重试条件
                if (shouldRetry(backendResponse, context.getOriginalRequest())) {
                    logger.info("响应符合重试条件 正在尝试重试");
                    handleRetry(context.getFrontendCtx(), context.originalRequest, context.remainingRetries);
                } else {
                    logger.info("响应成功 准备返回");
                    forwardResponseToClient(context.getFrontendCtx(), backendResponse, backendCtx);// 正常响应转发
                }
            }
            backendCtx.close();// 关闭后端连接
        }

        private boolean shouldRetry(FullHttpResponse response, FullHttpRequest request) {
            // 5xx错误且非POST请求时触发重试
            return response.status().code() >= 500 &&
                    response.status().code() < 600 &&
                    request != null &&
                    !request.method().equals(HttpMethod.POST);
        }

        private void forwardResponseToClient(ChannelHandlerContext ctx, FullHttpResponse response, ChannelHandlerContext backendCtx) {
            FullHttpResponse clientResponse = new DefaultFullHttpResponse(
                    HTTP_1_1,
                    response.status(),
                    // 使用Unpooled.copiedBuffer减少内存拷贝
                    Unpooled.copiedBuffer(response.content())
            ); // 复制响应内容

            // 添加 CORS 响应头 保证能跨域
            clientResponse.headers()
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE")
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization")
                    .set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "3600")
                    .set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8"); // 必须包含charset;

            // 添加Keep-Alive头 保持长连接
            String requestId = backendCtx.channel().attr(REQUEST_ID_KEY).get();
            HttpUtil.setKeepAlive(clientResponse, requestContextMap.get(requestId).keepAlive);

            CompletableFuture.runAsync(() ->
                    requestContextMap.remove(requestId)
            );
            // 处理分块传输
            if (HttpUtil.isTransferEncodingChunked(response)) {
                HttpUtil.setTransferEncodingChunked(clientResponse, true);
            } else {
                clientResponse.headers()
                        .set(HttpHeaderNames.CONTENT_LENGTH, clientResponse.content().readableBytes());
            }
            ctx.writeAndFlush(clientResponse);
//                    .addListener(ChannelFutureListener.CLOSE); // 发送并关闭
            logger.info("正在返回响应");
        }
    }

    private void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status) {
        // 构建JSON错误信息对象
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("code", status.code());
        errorData.put("message", status.reasonPhrase());
        errorData.put("timestamp", System.currentTimeMillis());
        logger.warn("正在发送失败响应");
        String jsonResponse;
        try {
            jsonResponse = JSONUtil.toJsonStr(errorData); // 使用JSON库（如FastJSON/Gson）
        } catch (Exception e) {
            jsonResponse = "{\"error\":\"JSON序列化失败\"}";
            logger.error("JSON序列化异常", e);
        }
        // 创建响应对象
        ByteBuf content = Unpooled.copiedBuffer(jsonResponse, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1,
                status,
                content  // 空内容，可根据需要填充错误信息
        );

        // 设置必要的响应头
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8") // 必须包含charset
                .set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())// 明确内容长度
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);// Keep-Alive复用连接
                /*
                     关闭持久连接
                    HTTP/1.1默认启用Keep-Alive长连接，允许复用同一TCP连接处理多个请求。
                   设置Connection: close会覆盖默认行为，强制在响应结束后断开连接
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                 */

        // 发送响应并关闭连接
        ctx.writeAndFlush(response);
                //.addListener(ChannelFutureListener.CLOSE);频繁建立连接会增加TCP握手开销
    }

    private String extractUserId(FullHttpRequest request) {
        return "1";
    }
    // 辅助方法：判断是否可重试错误
    private boolean isRetriableError(Throwable cause) {
        return cause instanceof ConnectException ||
                cause instanceof TimeoutException;
    }

    // 辅助方法：计算退避时间
    private int calculateBackoffDelay(int retries) {
        int base = 1000; // 1秒基数
        int maxDelay = 30000; // 30秒上限
        int exp = (int) Math.pow(2, nettyConfig.getTimes() - retries);
        int delay = Math.min(exp * base, maxDelay);
        return delay + ThreadLocalRandom.current().nextInt(500); // +0~500ms抖动
    }

}