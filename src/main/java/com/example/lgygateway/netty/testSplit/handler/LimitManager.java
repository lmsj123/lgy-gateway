package com.example.lgygateway.netty.testSplit.handler;

import cn.hutool.json.JSONUtil;
import com.example.lgygateway.config.NettyConfig;
import com.example.lgygateway.limit.SlidingWindowCounter;
import com.example.lgygateway.limit.TokenBucket;
import com.example.lgygateway.netty.testSplit.manager.RequestContextMapManager;
import com.example.lgygateway.netty.testSplit.model.RequestContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.http.HttpResponseStatus.TOO_MANY_REQUESTS;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Component
public class LimitManager {
    // 用户令牌桶相关(用户限流体系)
    private final ConcurrentHashMap<String, TokenBucket> userBucketMap = new ConcurrentHashMap<>();
    // 用户令牌桶相关(游客限流体系)
    private final ConcurrentHashMap<String, TokenBucket> noUserBucketMap = new ConcurrentHashMap<>();
    // 服务令牌桶相关(服务限流体系)
    private final ConcurrentHashMap<InetSocketAddress, TokenBucket> serviceBucketMap = new ConcurrentHashMap<>();
    @Autowired
    private RequestContextMapManager requestContextMapManager;
    private AttributeKey<String> REQUEST_ID_KEY;
    private ConcurrentHashMap<String, RequestContext> requestContextMap;

    @PostConstruct
    public void init() {
        this.REQUEST_ID_KEY = requestContextMapManager.getRequestIdKey();
        this.requestContextMap = requestContextMapManager.getRequestContextMap();
    }

    Logger logger = LoggerFactory.getLogger(LimitManager.class);
    @Autowired
    private NettyConfig nettyConfig;
    // 滑动窗口限流(全局限流体系)
    private final SlidingWindowCounter counter = new SlidingWindowCounter(60, 6000);

    // 全局限流和用户级限流的校验
    public boolean limitByGlobalAndUser(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!counter.allowRequest(nettyConfig.getMaxQps())) {
            logger.info("全局限流体系（滑动窗口）提示请求被限流");
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
                logger.info("该游客被限流");
                sendErrorResponse(ctx, TOO_MANY_REQUESTS);
                return true;
            }
        }
        return false;
    }

    // 从请求中获取userId
    private String extractUserId(FullHttpRequest request) {
        return "1";
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
                ReferenceCountUtil.safeRelease(content);
            }
        });
    }

    // 服务限流
    public boolean limitByService(URI uri, FullHttpRequest request, ChannelHandlerContext ctx) {
        InetSocketAddress inetSocketAddress = new InetSocketAddress(uri.getHost(), uri.getPort());
        TokenBucket tokenBucket = serviceBucketMap.computeIfAbsent(inetSocketAddress, k -> new TokenBucket(nettyConfig.getServiceMaxBurst(), nettyConfig.getServiceTokenRefillRate()));
        if (!tokenBucket.tryAcquire(1)) {
            logger.info("该 {}:{} 服务实例被限流 释放请求连接", uri.getHost(), uri.getPort());
            ReferenceCountUtil.safeRelease(request);
            sendErrorResponse(ctx, TOO_MANY_REQUESTS);
            return false;
        }
        return true;
    }
}
