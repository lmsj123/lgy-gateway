package com.example.lgygateway.netty.testSplit.handler;

import cn.hutool.json.JSONUtil;
import com.example.lgygateway.circuitBreaker.CircuitBreaker;
import com.example.lgygateway.config.NettyConfig;
import com.example.lgygateway.netty.testSplit.manager.ChannelPoolManager;
import com.example.lgygateway.netty.testSplit.manager.CircuitBreakerManager;
import com.example.lgygateway.netty.testSplit.manager.LimitManager;
import com.example.lgygateway.netty.testSplit.manager.RequestContextMapManager;
import com.example.lgygateway.netty.testSplit.model.RequestContext;
import com.example.lgygateway.route.RouteTableByTrie;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.pool.*;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.*;

import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Component
@Scope("prototype")
public class GatewayHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    @Autowired
    private CircuitBreakerManager circuitBreakerManager;
    @Autowired
    private NettyConfig nettyConfig;
    @Autowired
    private RouteTableByTrie routeTable; // 动态路由表
    @Autowired
    private LimitManager limitManager;
    @Autowired
    private RequestContextMapManager requestContextMapManager;
    @Autowired
    private ErrorHandler errorHandler;
    private AttributeKey<String> REQUEST_ID_KEY;
    private ConcurrentHashMap<String, RequestContext> requestContextMap;
    Logger logger = LoggerFactory.getLogger(GatewayHandler.class);
    @PostConstruct
    public void init() {
        this.REQUEST_ID_KEY = requestContextMapManager.getRequestIdKey();
        this.requestContextMap = requestContextMapManager.getRequestContextMap();
    }
    @Autowired
    private ApplicationContext applicationContext;
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
        if (limitManager.limitByGlobalAndUser(ctx, request)) { // 触发限流则阻断请求
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
            errorHandler.sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND);
        }
    }
    // 使用了连接池长连接 避免每次都进行tcp连接
    public void forwardRequestWithRetry(ChannelHandlerContext ctx, FullHttpRequest request, int retries) {
        URI uri = null;
        try {
            uri = new URI(request.uri());
        } catch (URISyntaxException e) {
            errorHandler.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST);
            ReferenceCountUtil.safeRelease(request); // 无论成功或失败，最终释放请求资源
        }
        CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker(uri, request, ctx);
        if (circuitBreaker == null) {
            return;
        }
        if (!limitManager.limitByService(uri, request, ctx)) {
            return;
        }
        String requestId = UUID.randomUUID().toString(); // 生成唯一ID
        ctx.channel().attr(REQUEST_ID_KEY).set(requestId); // 绑定到Channel属性（仅存储ID）

        // 存储到全局缓存（包含完整上下文）
        requestContextMap.put(requestId, new RequestContext(ctx, request, retries, HttpUtil.isKeepAlive(request), System.currentTimeMillis()));


        //获取或创建连接池
        ChannelPoolManager channelPoolManager = applicationContext.getBean(ChannelPoolManager.class);
        InetSocketAddress inetSocketAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
        FixedChannelPool pool = channelPoolManager.getPoolMap().get(inetSocketAddress);
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
                            errorHandler.sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY);
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
                    errorHandler.sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY);
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
                errorHandler.sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY);
                return;
            }
            if (limitManager.limitByGlobalAndUser(ctx, request)) {
                logger.info("该请求被限流 释放请求连接");
                ReferenceCountUtil.safeRelease(request);
                return;
            }

            if (!limitManager.limitByService(new URI(request.uri()), request, ctx)) {
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
            errorHandler.sendErrorResponse(ctx, SERVICE_UNAVAILABLE);
        }
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