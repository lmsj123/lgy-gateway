package com.example.lgygateway.netty;

import cn.hutool.json.JSONUtil;
import com.example.lgygateway.circuitBreaker.CircuitBreaker;
import com.example.lgygateway.config.*;
import com.example.lgygateway.limit.SlidingWindowCounter;
import com.example.lgygateway.limit.TokenBucket;
import com.example.lgygateway.registryStrategy.Registry;
import com.example.lgygateway.registryStrategy.factory.RegistryFactory;
import com.example.lgygateway.route.RouteTableByTrie;
import com.example.lgygateway.utils.Log;
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

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

//TODO 1)后续可改为异步日志 提高相对应性能
//     2)后续可以添加熔断机制，快速失败（已完成）
//     3)后续将相关配置优化为可动态调整
//     4)后续优化成可以适配https等
@Component
public class AsyncNettyHttpServer {
    private static final Logger logger = LoggerFactory.getLogger(AsyncNettyHttpServer.class);
    @Autowired
    private NettyConfig nettyConfig;
    @Autowired
    private NormalCircuitBreakerConfig circuitBreakerConfig;
    @Autowired
    private GrayCircuitBreakerConfig grayCircuitBreakerConfig;
    @Autowired
    private RouteTableByTrie routeTable; // 动态路由表
    @Autowired
    private ChannelPoolConfig channelPoolConfig;
    @Autowired
    private LimitConfig limitConfig;
    // 定义全局的请求上下文缓存（线程安全）
    private static final ConcurrentHashMap<String, RequestContext> requestContextMap = new ConcurrentHashMap<>();
    // 初始化连接池
    private final ChannelPoolMap<InetSocketAddress, FixedChannelPool> poolMap;
    // 客户端连接池，复用长连接与线程资源，避免每次转发都新建连接。
    private final Bootstrap clientBootstrap; // 客户端连接池启动器
    private final NioEventLoopGroup clientGroup; // 客户端io线程组 io多路复用
    // 唯一标识符的AttributeKey
    private static final AttributeKey<String> REQUEST_ID_KEY = AttributeKey.valueOf("requestId");
    // 滑动窗口限流(全局限流体系)
    private SlidingWindowCounter counter;
    // 服务令牌桶相关(服务限流体系)
    private final ConcurrentHashMap<InetSocketAddress, TokenBucket> serviceBucketMap = new ConcurrentHashMap<>();
    // 用户令牌桶相关(用户限流体系)
    private final ConcurrentHashMap<String, TokenBucket> userBucketMap = new ConcurrentHashMap<>();
    // 用户令牌桶相关(游客限流体系)
    private final ConcurrentHashMap<String, TokenBucket> noUserBucketMap = new ConcurrentHashMap<>();
    // 熔断器存储
    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    // 自定义ChannelPoolHandler
    class CustomChannelPoolHandler extends AbstractChannelPoolHandler {
        @Override
        public void channelCreated(Channel ch) {
            // 初始化Channel的Pipeline（与客户端Bootstrap配置一致）
            ch.pipeline()
                    .addLast(new HttpClientCodec()) // 客户端应使用HttpClientCodec
                    .addLast(new HttpObjectAggregator(10 * 1024 * 1024))
                    .addLast(new BackendHandler());
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
        // 是否为灰度发布
        private boolean isGray;
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
                logger.info("正在初始化 {}:{} 的连接池", key.getAddress(), key.getPort());
                FixedChannelPool pool = new FixedChannelPool(
                        clientBootstrap.remoteAddress(key),
                        new CustomChannelPoolHandler(),
                        ChannelHealthChecker.ACTIVE, //使用默认健康检查
                        FixedChannelPool.AcquireTimeoutAction.FAIL,//获取超时后抛出异常
                        channelPoolConfig.getAcquireTimeoutMillis(),
                        channelPoolConfig.getMaxConnections(),
                        channelPoolConfig.getMaxPendingRequests()
                );
                logger.info("正在预热连接");
                // 异步预热10%连接
                new Thread(() -> {
                    int warmupConnections = (int) (channelPoolConfig.getMaxConnections() * 0.1);
                    for (int i = 0; i < warmupConnections; i++) {
                        pool.acquire().addListener(future -> {
                            if (future.isSuccess()) {
                                Channel ch = (Channel) future.getNow();
                                pool.release(ch); // 立即释放连接至池中
                                //释放操作并非关闭连接，而是将其标记为空闲状态，供后续请求复用。
                                logger.info("预热连接 {} 已释放至连接池 为空闲状态", ch.id());
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

    //定时任务删除缓存
    private void deleteOutTime() {
        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(() -> {
                    Iterator<Map.Entry<String, RequestContext>> it = requestContextMap.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, RequestContext> entry = it.next();
                        if (System.currentTimeMillis() - entry.getValue().getLastAccessTime() > 300_000) { // 5分钟
                            it.remove();
                            if (entry.getValue().getOriginalRequest().refCnt() > 0) {
                                entry.getValue().getOriginalRequest().release();
                            }
                        }
                    }
                }, 5, 5, TimeUnit.MINUTES);
    }

    //初始化响应端
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
                                .addLast(new HttpObjectAggregator(10 * 1024 * 1024))
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
    public void start() {
        counter = new SlidingWindowCounter(limitConfig.getWindowSizeSec(), limitConfig.getSliceCount());
        // 如果不开启一个新线程 阻塞主️线程
        /*
            这会延迟 Spring 容器的完整初始化，导致：
            其他 Bean 的初始化被阻塞
            Actuator 端点无法及时就绪
            健康检查状态延迟更新
            条件评估报告出现未完成初始化的提示
         */
        CompletableFuture.runAsync(() -> {
            try {
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
                                logger.info("正在初始化GatewayHandler（请求阶段）");
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
                //这行代码通过阻塞当前线程，防止 Netty 服务在启动后立即退出，确保服务端持续监听端口并处理请求。
                f.channel().closeFuture().sync(); // 阻塞主线程（需结合优雅关机逻辑）
            } catch (Exception e) {
                e.fillInStackTrace();
            }
        });
    }

    // 发送端
    private class GatewayHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        //SimpleChannelInboundHandler 在 channelRead0 方法执行完毕后会自动调用 ReferenceCountUtil.release(msg)，因此无需手动释放请求对象。
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
                logger.info("路由匹配失败 释放相关请求");
                // ReferenceCountUtil.safeRelease(request);
                // [6] 返回404错误
                sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND);
            }
        }
    }

    // 使用了连接池长连接 避免每次都进行tcp连接
    private void forwardRequestWithRetry(ChannelHandlerContext ctx, FullHttpRequest request, int retries) {
        URI uri = null;
        try {
            uri = new URI(request.uri());
        } catch (URISyntaxException e) {
            sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST);
            ReferenceCountUtil.safeRelease(request); // 无论成功或失败，最终释放请求资源
        }
        // 灰度服务和普通服务之间的熔断器应该分离
        String isGray = request.headers().get("X-Gray-Hit");
        String circuitBreakerKey = isGray.equals("true") ? uri.getHost() + ":" + uri.getPort() + "-gray" :  uri.getHost() + ":" + uri.getPort() + "-normal";
        InetSocketAddress inetSocketAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
        logger.info("正在获取 {}:{} 的熔断器", uri.getHost(), uri.getPort());
        //高并发下多个线程可能同时执行 new CircuitBreaker，导致资源浪费或状态不一致。
        //若 TokenBucket 构造函数无副作用，可以接受短暂重复创建，但需确保 TokenBucket 自身线程安全
        //可考虑优化成双重锁
        CircuitBreaker circuitBreaker = isGray.equals("true") ? circuitBreakers.computeIfAbsent(
                circuitBreakerKey,
                k -> new CircuitBreaker(grayCircuitBreakerConfig)
        ) : circuitBreakers.computeIfAbsent(
                circuitBreakerKey,
                k -> new CircuitBreaker(circuitBreakerConfig)
        );
        // 检查是否允许请求
        if (!circuitBreaker.allowRequest()) {
            logger.info("熔断器发挥降级作用，释放相关请求");
            ReferenceCountUtil.safeRelease(request);
            sendErrorResponse(ctx, SERVICE_UNAVAILABLE);
            return;
        }
        TokenBucket tokenBucket = serviceBucketMap.computeIfAbsent(inetSocketAddress, k -> new TokenBucket(limitConfig.getServiceMaxBurst(), limitConfig.getServiceTokenRefillRate()));
        if (!tokenBucket.tryAcquire(1)) {
            logger.info("该 {}:{} 服务实例被限流 释放相关请求", uri.getHost(), uri.getPort());
            ReferenceCountUtil.safeRelease(request);
            sendErrorResponse(ctx, TOO_MANY_REQUESTS);
            return;
        }

        String requestId = UUID.randomUUID().toString(); // 生成唯一ID
        ctx.channel().attr(REQUEST_ID_KEY).set(requestId); // 绑定到Channel属性（仅存储ID）

        // 存储到全局缓存（包含完整上下文）
        requestContextMap.put(requestId, new RequestContext(ctx, request, retries, HttpUtil.isKeepAlive(request), System.currentTimeMillis(), isGray.equals("true")));


        //获取或创建连接池
        FixedChannelPool pool = poolMap.get(inetSocketAddress);
        request.headers().set(HttpHeaderNames.HOST, uri.getHost() + ':' + uri.getPort());
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        //从池中获取channel
        logger.info("正在从 {}:{} 的连接池中获取连接", uri.getHost(), uri.getPort());
        pool.acquire().addListener((Future<Channel> future) -> {
            //当异步获取pool中的channel成功时会进入下面的分支
            if (future.isSuccess()) {
                logger.info("获取连接成功");
                Channel channel = future.getNow();
                try {
                    //绑定元数据到channel
                    channel.attr(REQUEST_ID_KEY).set(requestId);
                    //发送请求
                    //writeAndFlush(request)发送成功时会自动回收request
                    channel.writeAndFlush(request).addListener((ChannelFuture writeFuture) -> {
                        if (writeFuture.isSuccess()) {
                            logger.info("成功发送请求");
                            // 请求成功时记录成功
                            circuitBreaker.recordSuccess();
                        } else {
                            // 处理失败：记录异常、重试或响应错误
                            logger.info("发送请求失败");
                            // 请求失败时记录失败
                            circuitBreaker.recordFailure();
                            sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY);
                            ReferenceCountUtil.safeRelease(request);//成功发送情况下会自动释放request
                            pool.release(channel); // 确保异常后释放连接
                        }
                    });
                } catch (Exception e) {
                    if (channel != null) {
                        pool.release(channel); // 强制释放连接
                    }
                    requestContextMap.remove(requestId);
                    channel.attr(REQUEST_ID_KEY).set(null);
                    sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY);
                    ReferenceCountUtil.safeRelease(request);
                }
            } else {
                logger.info("获取连接失败");
                // 获取连接失败处理
                handleAcquireFailure(ctx, request, retries, future.cause());
            }
        });
    }

    // 处理连接获取失败
    private void handleAcquireFailure(ChannelHandlerContext ctx, FullHttpRequest request, int retries, Throwable cause) throws URISyntaxException {
        if (retries > 0) {
            // 错误类型判断
            if (!isRetriableError(cause)) {
                logger.info("错误的类型 释放请求连接");
                ReferenceCountUtil.safeRelease(request);
                sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY);
                return;
            }
            if (limit(ctx, request)) {
                logger.info("该请求被限流 释放请求连接");
                ReferenceCountUtil.safeRelease(request);
                return;
            }
            URI uri = new URI(request.uri());
            InetSocketAddress inetSocketAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
            TokenBucket tokenBucket = serviceBucketMap.computeIfAbsent(inetSocketAddress, k -> new TokenBucket(limitConfig.getServiceMaxBurst(), limitConfig.getServiceTokenRefillRate()));
            if (!tokenBucket.tryAcquire(1)) {
                logger.info("该 {}:{} 服务实例被限流 释放请求连接", uri.getHost(), uri.getPort());
                ReferenceCountUtil.safeRelease(request);
                sendErrorResponse(ctx, TOO_MANY_REQUESTS);
                return;
            }
            logger.info("正在重试 重试次数还剩余 {}", retries);
            // 指数退避+随机抖动（替换固定1秒）
            int totalDelayMs = calculateBackoffDelay(retries);
            ctx.channel().eventLoop().schedule(() ->
                            forwardRequestWithRetry(ctx, request, retries - 1),
                    totalDelayMs, TimeUnit.MILLISECONDS
            );
        } else {
            logger.info("重试次数为0 发送响应失败请求");
            ReferenceCountUtil.safeRelease(request);
            sendErrorResponse(ctx, SERVICE_UNAVAILABLE);
        }
    }

    // 这里重试请求是需要拷贝副本的
    private void handleRetry(ChannelHandlerContext ctx, FullHttpRequest request, Integer retries) throws URISyntaxException {
        logger.warn("Retrying request to {} (remaining {} retries)", request.uri(), retries);
        if (retries != null && retries > 0) {
            // 这里的request已经因为channel.writeAndFlush(request)而回收了 所以不能以该请求作为重试请求
            // 创建请求副本并增加引用计数
            FullHttpRequest copiedRequest = copyAndRetainRequest(request);
            if (copiedRequest == null) {
                sendErrorResponse(ctx, BAD_GATEWAY);
                return;
            }
            if (limit(ctx, copiedRequest)) {
                logger.info("该请求被限流 释放副本请求");
                ReferenceCountUtil.safeRelease(copiedRequest);
                return;
            }
            URI uri = new URI(request.uri());
            InetSocketAddress inetSocketAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
            TokenBucket tokenBucket = serviceBucketMap.computeIfAbsent(inetSocketAddress, k -> new TokenBucket(limitConfig.getServiceMaxBurst(), limitConfig.getServiceTokenRefillRate()));
            if (!tokenBucket.tryAcquire(1)) {
                logger.info("该 {}:{} 服务实例被限流 释放副本请求", uri.getHost(), uri.getPort());
                sendErrorResponse(ctx, TOO_MANY_REQUESTS);
                ReferenceCountUtil.safeRelease(copiedRequest);
                return;
            }
            logger.info("正在重试 重试次数还剩余 {}", retries);
            int totalDelayMs = calculateBackoffDelay(retries);

            ctx.channel().eventLoop().schedule(
                    () -> forwardRequestWithRetry(ctx, copiedRequest, retries - 1),
                    totalDelayMs, TimeUnit.MILLISECONDS
            );

        } else {
            logger.info("重试次数耗尽 发送失败响应");
            sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY);
        }
    }

    // 全局限流和用户级限流的校验
    private boolean limit(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!counter.allowRequest(limitConfig.getMaxQps())) {
            logger.info("全局限流体系（滑动窗口）提示请求被限流");
            sendErrorResponse(ctx, TOO_MANY_REQUESTS);
            return true;
        }
        String userId = extractUserId(request);
        if (!userId.isEmpty()) {
            TokenBucket userTokenBucket = userBucketMap.computeIfAbsent(userId, k -> new TokenBucket(limitConfig.getUserMaxBurst(), limitConfig.getUserTokenRefillRate()));
            if (!userTokenBucket.tryAcquire(1)) {
                logger.info("该用户{}被限流", userId);
                sendErrorResponse(ctx, TOO_MANY_REQUESTS);
                return true;
            }
        } else {
            TokenBucket noUserTokenBucket = noUserBucketMap.computeIfAbsent("noUser", k -> new TokenBucket(limitConfig.getNoUserMaxBurst(), limitConfig.getNoUserTokenRefillRate()));
            if (!noUserTokenBucket.tryAcquire(1)) {
                logger.info("该游客被限流");
                sendErrorResponse(ctx, TOO_MANY_REQUESTS);
                return true;
            }
        }
        return false;
    }

    // 回复端
    private class BackendHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        @Override
        protected void channelRead0(ChannelHandlerContext backendCtx, FullHttpResponse backendResponse) throws URISyntaxException {
            // 获取关联的原始请求和剩余重试次数
            String requestId = backendCtx.channel().attr(REQUEST_ID_KEY).get();
            logger.info("正在解析响应 Received backend response for ID: {}  status : {}", requestId, backendResponse.status());
            RequestContext context = requestContextMap.computeIfPresent(requestId, (k, v) -> {
                v.lastAccessTime = System.currentTimeMillis();
                return v;
            });
            // 这里的request已经被释放请求 无需在显式调用
            if (context != null) {
                try {
                    circuitBreakerRecordMes(context, backendResponse);
                    // 判断是否符合重试条件
                    if (shouldRetry(backendResponse, context.originalRequest)) {
                        logger.info("响应符合重试条件 正在尝试重试");
                        handleRetry(context.getFrontendCtx(), context.originalRequest, context.remainingRetries);
                    } else {
                        logger.info("响应成功 准备返回");
                        forwardResponseToClient(context, backendResponse, backendCtx);// 正常响应转发
                    }
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                } finally {
                    // 响应处理完成后释放连接
                    URI uri = new URI(context.getOriginalRequest().uri());
                    InetSocketAddress inetSocketAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
                    FixedChannelPool pool = poolMap.get(inetSocketAddress);
                    pool.release(backendCtx.channel());
                }
            }
        }

        private boolean shouldRetry(FullHttpResponse response, FullHttpRequest request) {
            // 5xx错误且非POST请求时触发重试
            return response.status().code() >= 500 &&
                    response.status().code() < 600 &&
                    request != null &&
                    !request.method().equals(HttpMethod.POST);
        }

        private void forwardResponseToClient(RequestContext context, FullHttpResponse response, ChannelHandlerContext backendCtx) {
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

            // BackendHandler 响应处理
            boolean isGray = context.isGray();
            ChannelHandlerContext ctx = context.getFrontendCtx();
            clientResponse.headers().set("X-Gray-Hit", isGray ? "true" : "false");
            // 添加Keep-Alive头 保持长连接
            String requestId = backendCtx.channel().attr(REQUEST_ID_KEY).get();
            HttpUtil.setKeepAlive(clientResponse, requestContextMap.get(requestId).keepAlive);

            // 异步清理请求上下文和Channel属性
            // 使用 Netty 的 EventLoop 执行清理
            ctx.channel().eventLoop().execute(() -> {
                requestContextMap.remove(requestId); // 移除全局缓存
                ctx.channel().attr(REQUEST_ID_KEY).set(null);// 清理Channel属性
            });

            // 处理分块传输
            if (HttpUtil.isTransferEncodingChunked(response)) {
                HttpUtil.setTransferEncodingChunked(clientResponse, true);
            } else {
                clientResponse.headers()
                        .set(HttpHeaderNames.CONTENT_LENGTH, clientResponse.content().readableBytes());
            }
            // 发送响应并添加释放监听器
            ctx.writeAndFlush(clientResponse).addListener(future -> {
                if (!future.isSuccess()) {
                    if (clientResponse.content().refCnt() > 0) {
                        ReferenceCountUtil.safeRelease(clientResponse.content());
                    }
                }
            });
            logger.info("正在返回响应");
        }
    }

    // 发送失败响应给客户端
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
        String requestId = ctx.channel().attr(REQUEST_ID_KEY).get();
        // 创建响应对象
        ByteBuf content = Unpooled.copiedBuffer(jsonResponse, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1,
                status,
                content  // 空内容，可根据需要填充错误信息
        );

        // 异步清理请求上下文和Channel属性
        // 使用 Netty 的 EventLoop 执行清理
        ctx.channel().eventLoop().execute(() -> {
            if (requestId != null) {
                requestContextMap.remove(requestId); // 移除全局缓存
                ctx.channel().attr(REQUEST_ID_KEY).set(null);// 清理Channel属性
            }
        });

        // 设置必要的响应头
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8") // 必须包含charset
                .set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())// 明确内容长度
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);// Keep-Alive复用连接


        // 发送响应并关闭连接
        ctx.writeAndFlush(response).addListener(future -> {
            if (!future.isSuccess()) {
                if (content.refCnt() > 0) {
                    ReferenceCountUtil.safeRelease(content);
                }
            }
        });
    }

    // 从请求中获取userId
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

    // 熔断器记录成功与失败的响应
    private void circuitBreakerRecordMes(RequestContext context, FullHttpResponse backendResponse) throws URISyntaxException {
        URI uri = new URI(context.originalRequest.uri());
        String circuitBreakerKey = context.isGray() ? uri.getHost() + ":" + uri.getPort() + "-gray" : uri.getHost() + uri.getPort() + "-normal";
        CircuitBreaker circuitBreaker = circuitBreakers.get(circuitBreakerKey);
        if (circuitBreaker == null) {
            circuitBreaker = context.isGray() ? new CircuitBreaker(grayCircuitBreakerConfig) : new CircuitBreaker(circuitBreakerConfig);
            CircuitBreaker existing = circuitBreakers.putIfAbsent(circuitBreakerKey, circuitBreaker);
            if (existing != null) {
                circuitBreaker = existing;
            }
        }
        if (backendResponse.status().code() >= 500) {
            Log.logger.info("熔断器记录失败次数");
            circuitBreaker.recordFailure();
        } else {
            Log.logger.info("熔断器记录成功次数");
            circuitBreaker.recordSuccess();
        }
    }

    // 复制请求并保留引用计数
    private FullHttpRequest copyAndRetainRequest(FullHttpRequest original) {
        ByteBuf contentCopy = null;
        try {
            // 尝试拷贝内容
            contentCopy = Unpooled.buffer(0);                          // 创建空缓冲区

            // 构建请求副本（保留元数据）
            DefaultFullHttpRequest copy = new DefaultFullHttpRequest(
                    original.protocolVersion(),
                    original.method(),
                    original.uri(),
                    contentCopy,
                    original.headers().copy(),          // 头信息可独立拷贝
                    original.trailingHeaders().copy()   // 尾部头同样安全
            );
            copy.setDecoderResult(original.decoderResult());

            return copy;

        } catch (Exception e) {
            // 异常时清理资源
            if (contentCopy != null) contentCopy.release();
            logger.error("复制失败", e);
            return null;
        }
    }

}