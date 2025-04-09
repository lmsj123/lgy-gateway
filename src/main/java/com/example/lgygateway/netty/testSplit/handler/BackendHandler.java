package com.example.lgygateway.netty.testSplit.handler;

import com.example.lgygateway.config.NettyConfig;
import com.example.lgygateway.netty.testSplit.manager.ChannelPoolManager;
import com.example.lgygateway.netty.testSplit.manager.CircuitBreakerManager;
import com.example.lgygateway.netty.testSplit.manager.LimitManager;
import com.example.lgygateway.netty.testSplit.manager.RequestContextMapManager;
import com.example.lgygateway.netty.testSplit.model.RequestContext;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.pool.*;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.*;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_GATEWAY;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

// 回复端
@Component
@Scope("prototype")
public class BackendHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    @Autowired
    private ErrorHandler errorHandler;
    @Autowired
    private CircuitBreakerManager circuitBreakerManager;
    @Autowired
    private NettyConfig nettyConfig;
    @Autowired
    private GatewayHandler gatewayHandler;
    Logger logger = LoggerFactory.getLogger(BackendHandler.class);
    @Autowired
    private RequestContextMapManager requestContextMapManager;
    @Autowired
    private LimitManager limitManager;
    private AttributeKey<String> REQUEST_ID_KEY;
    private ConcurrentHashMap<String, RequestContext> requestContextMap;

    @PostConstruct
    public void init() {
        this.REQUEST_ID_KEY = requestContextMapManager.getRequestIdKey();
        this.requestContextMap = requestContextMapManager.getRequestContextMap();
    }
    @Autowired
    private ApplicationContext applicationContext;
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
                circuitBreakerManager.circuitBreakerRecordMes(context, backendResponse);
                // 判断是否符合重试条件
                if (shouldRetry(backendResponse, context.getOriginalRequest())) {
                    logger.info("响应符合重试条件 正在尝试重试");
                    handleRetry(context.getFrontendCtx(), context.getOriginalRequest(), context.getRemainingRetries());
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
                ChannelPoolManager channelPoolManager = applicationContext.getBean(ChannelPoolManager.class);
                FixedChannelPool pool = channelPoolManager.getPoolMap().get(inetSocketAddress);
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

    private void handleRetry(ChannelHandlerContext ctx, FullHttpRequest request, Integer retries) throws URISyntaxException {
        logger.warn("Retrying request to {} (remaining {} retries)", request.uri(), retries);
        if (retries != null && retries > 0) {
            // 这里的request已经因为channel.writeAndFlush(request)而回收了 所以不能以该请求作为重试请求
            // 创建请求副本并增加引用计数
            FullHttpRequest copiedRequest = copyAndRetainRequest(request);
            if (copiedRequest == null) {
                errorHandler.sendErrorResponse(ctx, BAD_GATEWAY);
                return;
            }
            if (limitManager.limitByGlobalAndUser(ctx, copiedRequest)) {
                logger.info("该请求被限流 释放副本请求");
                ReferenceCountUtil.safeRelease(copiedRequest);
                return;
            }
            if (!limitManager.limitByService(new URI(request.uri()),copiedRequest,ctx)){
                return;
            }
            logger.info("正在重试 重试次数还剩余 {}", retries);
            int totalDelayMs = calculateBackoffDelay(retries);

            ctx.channel().eventLoop().schedule(
                    () -> gatewayHandler.forwardRequestWithRetry(ctx, copiedRequest, retries - 1),
                    totalDelayMs, TimeUnit.MILLISECONDS
            );

        } else {
            logger.info("重试次数耗尽 发送失败响应");
            errorHandler.sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY);
        }
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
        HttpUtil.setKeepAlive(clientResponse, requestContextMap.get(requestId).isKeepAlive());

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

    // 复制请求并保留引用计数
    private FullHttpRequest copyAndRetainRequest(FullHttpRequest original) {
        try {
            FullHttpRequest copy = original.copy(); // 创建副本
            copy.retain(); // 增加引用计数
            return copy;
        } catch (Exception e) {
            logger.error("复制请求失败", e);
            return null;
        }
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